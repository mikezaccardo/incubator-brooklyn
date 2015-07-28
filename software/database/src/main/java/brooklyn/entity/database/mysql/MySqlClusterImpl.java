/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.entity.database.mysql;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;

import brooklyn.enricher.Enrichers;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.group.DynamicClusterImpl;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.trait.Startable;
import brooklyn.event.AttributeSensor;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.event.basic.DependentConfiguration;
import brooklyn.event.basic.Sensors;
import brooklyn.util.task.DeferredSupplier;
import brooklyn.util.task.DynamicTasks;
import brooklyn.util.task.TaskBuilder;

public class MySqlClusterImpl extends DynamicClusterImpl implements MySqlCluster {
    // TODO Would be nice to do it before SERVICE_UP, should't have clients connected yet.
    private static final class NodeUpListener implements SensorEventListener<Boolean> {
        private MySqlCluster cluster;
        public NodeUpListener(MySqlCluster cluster) {
            this.cluster = cluster;
        }

        @Override
        public void onEvent(SensorEvent<Boolean> event) {
            final MySqlNode node = (MySqlNode) event.getSource();
            if (event.getValue()) {
                DynamicTasks.queueIfPossible(TaskBuilder.builder()
                        .name("Configure master-slave replication on node")
                        .body(new Runnable() {
                            @Override
                            public void run() {
                                Integer serverId = node.getConfig(MySqlNode.MYSQL_SERVER_ID);
                                if (serverId == 1) {
                                    initMaster(node);
                                } else if (serverId > 1) {
                                    initSlave(node);
                                }
                            }
                        }).build())
                .orSubmitAsync(node);
            }
        }

        private void initMaster(MySqlNode master) {
            if (master.getAttribute(MASTER_LOG_FILE) == null ||
                    master.getAttribute(MASTER_LOG_POSITION) == null) {
                // TODO syncrhonize better
    
                // Create replication slaves user
                // TODO how to check for success?
                master.executeScript("CREATE USER 'slave'@'%' IDENTIFIED BY 'slavepass';\n" +
                        "GRANT REPLICATION SLAVE ON *.* TO 'slave'@'%';\n");
    
                String binLogInfo = master.executeScript("FLUSH TABLES WITH READ LOCK;SHOW MASTER STATUS \\G UNLOCK TABLES;");
                Iterator<String> splitIter = Splitter.on(Pattern.compile("\\n|:"))
                        .omitEmptyStrings()
                        .trimResults()
                        .split(binLogInfo)
                        .iterator();
                while (splitIter.hasNext()) {
                    String part = splitIter.next();
                    if (part.equals("File")) {
                        String file = splitIter.next();
                        ((EntityInternal)master).setAttribute(MASTER_LOG_FILE, file);
                    } else if (part.equals("Position")) {
                        Integer position = new Integer(splitIter.next());
                        ((EntityInternal)master).setAttribute(MASTER_LOG_POSITION, position);
                    }
                }
            }
        }

        private static final AttributeSensor<Boolean> SLAVE_INITIALIZED = Sensors.newBooleanSensor("mysql.slave.initialized");
        private void initSlave(MySqlNode node) {
            // TODO syncrhonize better, check server state instead of using a marker
            if (!Boolean.TRUE.equals(node.getAttribute(SLAVE_INITIALIZED))) {
                ((EntityInternal)node).setAttribute(SLAVE_INITIALIZED, Boolean.TRUE);
                // TODO is master guaranteed to exist?
                Entity masterNode = cluster.getAttribute(MySqlCluster.FIRST);
                String masterLogFile = DynamicTasks.queue(DependentConfiguration.attributeWhenReady(masterNode, MASTER_LOG_FILE)).getUnchecked();
                Integer masterLogPos = DynamicTasks.queue(DependentConfiguration.attributeWhenReady(masterNode, MASTER_LOG_POSITION)).getUnchecked();
                String host = masterNode.getAttribute(MySqlNode.SUBNET_ADDRESS);
                String slaveCmd =
                        "CHANGE MASTER TO " +
                        "MASTER_HOST='" + host + "', " +
                        "MASTER_USER='slave', " +
                        "MASTER_PASSWORD='slavepass', " +
                        "MASTER_LOG_FILE='" + masterLogFile + "', " +
                        "MASTER_LOG_POS=" + masterLogPos;
                // TODO check success
                node.executeScript(slaveCmd);
            }
        }

    }

    // TODO Incremented on config views from UI as well, so slaves don't increment linearly
    private static class SlaveServerIdSupplier implements DeferredSupplier<Integer> {
        private AtomicInteger serverId = new AtomicInteger(10);
        @Override
        public Integer get() {
            return serverId.getAndIncrement();
        }
    }

    private static class MasterAttributePropagator implements SensorEventListener<Object> {
        private MySqlCluster cluster;
        public MasterAttributePropagator(MySqlClusterImpl cluster) {
            this.cluster = cluster;
        }

        @Override
        public void onEvent(SensorEvent<Object> event) {
            Entity node = event.getSource();
            if (node.getConfig(MySqlNode.MYSQL_SERVER_ID) == 1) {
                ((EntityInternal)cluster).setAttribute((AttributeSensor<Object>)event.getSensor(), event.getValue());
            }
        }
        
    }
    @Override
    public void init() {
        super.init();
        // TODO gen password
        // TODO dynamically update slave IPs
        // TODO why .configure has Supplier but requires Future/DeferredSupplier?
        // TODO [Warning] Storing MySQL user name or password information in the master info repository is not secure and is therefore not recommended. Please consider using the USER and PASSWORD connection options for START SLAVE; see the 'START SLAVE Syntax' in the MySQL Manual for more information.
        //      http://dev.mysql.com/doc/refman/5.6/en/slave-logs.html#replication-implementation-crash-safe
        // TODO Storing MySQL user name or password information in the master info repository is not secure and is therefore not recommended. Please consider using the USER and PASSWORD connection options for START SLAVE; see the 'START SLAVE Syntax' in the MySQL Manual for more information.
        // TODO CREATION_SCRIPT_CONTENTS failing doesn't abort installation
        // TODO Neither --relay-log nor --relay-log-index were used; so replication may break when this MySQL server acts as a slave and has his hostname changed!! Please use '--relay-log=brooklyn-sinj-svet-appli-vjly-machineentity-r-rchh-e06-relay-bin' to avoid this problem.
        // TODO White/black list of tables to replicate
        // TODO Investigate what happens if old binary logs removed, should restore slaves from a dump
        if (getConfig(FIRST_MEMBER_SPEC) == null) {
            config().set(FIRST_MEMBER_SPEC, EntitySpec.create(MySqlNode.class)
                    .configure(MySqlNode.MYSQL_SERVER_ID, 1)
                    .configure(MySqlNode.TEMPLATE_CONFIGURATION_URL, "classpath:///brooklyn/entity/database/mysql/mysql_master.conf"));
        }
        if (getConfig(MEMBER_SPEC) == null) {
            config().set(MEMBER_SPEC, EntitySpec.create(MySqlNode.class)
                    .configure(MySqlNode.MYSQL_SERVER_ID, new SlaveServerIdSupplier()));
        }

        subscribeToMembers(this, MySqlNode.SERVICE_UP, new NodeUpListener(this));
        // TODO Properly propagate master, create slave ip attribute list
        subscribeToMembers(this, MySqlNode.HOSTNAME, new MasterAttributePropagator(this));
        subscribeToMembers(this, MySqlNode.ADDRESS, new MasterAttributePropagator(this));
        subscribeToMembers(this, MySqlNode.MYSQL_PORT, new MasterAttributePropagator(this));
    }

    // https://dev.mysql.com/doc/refman/5.1/en/replication-howto.html

    /*
     Replication steps:

     1. Master config:
        [mysqld]
        log-bin=mysql-bin
        server-id=1
        innodb_flush_log_at_trx_commit=1
        sync_binlog=1
        
      2. Slave config:
        [mysqld]
        server-id=2
        
      3. Create replication user:
        // see also https://dev.mysql.com/doc/refman/5.1/en/account-management-sql.html
        // TODO grant access to slave IPs only
        CREATE USER 'replication_slave'@'%' IDENTIFIED BY 'slavepass';
        GRANT REPLICATION SLAVE ON *.* TO 'replication_slave'@'%';
        
      4.
        FLUSH TABLES WITH READ LOCK;
        * SHOW MASTER STATUS;
        UNLOCK TABLES;
      
      5. // sel also https://dev.mysql.com/doc/refman/5.1/en/change-master-to.html
         // TODO setup SSL conn
        CHANGE MASTER TO
    ->     MASTER_HOST='master_host_name',
    ->     MASTER_USER='replication_user_name',
    ->     MASTER_PASSWORD='replication_password',
    ->     MASTER_LOG_FILE='recorded_log_file_name',
    ->     MASTER_LOG_POS=recorded_log_position; //4 for beginning so no dump is needed
     */
    
    
}
