package brooklyn.entity.messaging.activemq

import java.util.List
import java.util.Map

import brooklyn.entity.basic.Attributes
import brooklyn.entity.basic.JavaApp;
import brooklyn.location.basic.SshMachineLocation
import brooklyn.util.SshBasedJavaAppSetup;
import brooklyn.util.SshBasedJavaWebAppSetup

/**
 * Start a {@link ActiveMQBroker} in a {@link Location} accessible over ssh.
 */
public class ActiveMQSetup extends SshBasedJavaAppSetup {
    public static final String DEFAULT_VERSION = "5.5.0"
    public static final String DEFAULT_INSTALL_DIR = DEFAULT_INSTALL_BASEDIR+"/"+"activemq"
    public static final int DEFAULT_FIRST_OPEN_WIRE_PORT = 61616

    private int openWirePort
    private int rmiPort

    public static ActiveMQSetup newInstance(ActiveMQBroker entity, SshMachineLocation machine) {
        Integer suggestedVersion = entity.getConfig(ActiveMQBroker.SUGGESTED_VERSION)
        String suggestedInstallDir = entity.getConfig(ActiveMQBroker.SUGGESTED_INSTALL_DIR)
        String suggestedRunDir = entity.getConfig(ActiveMQBroker.SUGGESTED_RUN_DIR)
        Integer suggestedJmxPort = entity.getConfig(ActiveMQBroker.SUGGESTED_JMX_PORT)
        Integer suggestedOpenWirePort = entity.getConfig(ActiveMQBroker.OPEN_WIRE_PORT.configKey)

        String version = suggestedVersion ?: DEFAULT_VERSION
        String installDir = suggestedInstallDir ?: (DEFAULT_INSTALL_DIR+"/"+"${version}"+"/"+"apache-activemq-${version}")
        String runDir = suggestedRunDir ?: (BROOKLYN_HOME_DIR+"/"+"${entity.application.id}"+"/"+"activemq-${entity.id}")
        int jmxPort = machine.obtainPort(toDesiredPortRange(suggestedJmxPort, DEFAULT_FIRST_JMX_PORT))
        int openWirePort = machine.obtainPort(toDesiredPortRange(suggestedOpenWirePort, ActiveMQBroker.OPEN_WIRE_PORT.configKey.defaultValue))

        ActiveMQSetup result = new ActiveMQSetup(entity, machine)
        result.setJmxPort(jmxPort)
        result.setOpenWirePort(openWirePort)
        result.setVersion(version)
        result.setInstallDir(installDir)
        result.setRunDir(runDir)

        return result
    }

    public ActiveMQSetup(ActiveMQBroker entity, SshMachineLocation machine) {
        super(entity, machine)
    }

    public void setOpenWirePort(int val) {
        openWirePort = val
    }

    @Override
    protected void setCustomAttributes() {
        entity.setAttribute(ActiveMQBroker.OPEN_WIRE_PORT, openWirePort)
    }

    @Override
    public List<String> getInstallScript() {
        makeInstallScript([
                "wget http://www.mirrorservice.org/sites/ftp.apache.org/activemq/apache-activemq/${version}/apache-activemq-${version}-bin.tar.gz",
                "tar xvzf apache-activemq-${version}-bin.tar.gz",
            ])
    }

    /**
     * Creates the directories ActiveMQ needs to run in a different location from where it is installed.
     */
    public List<String> getRunScript() {
        List<String> script = [
            "cd ${runDir}",
			"nohup ./bin/activemq start &",
        ]
        return script
    }

    public Map<String, String> getRunEnvironment() {
        Map<String, String> env = [
			"ACTIVEMQ_HOME" : "${runDir}",
			"JAVA_OPTS" : toJavaDefinesString(getJvmStartupProperties()),
        ]
        return env
    }

    /** @see SshBasedJavaAppSetup#getCheckRunningScript() */
    public List<String> getCheckRunningScript() {
       return makeCheckRunningScript("activemq", "data/activemq.pid")
    }

    @Override
    public List<String> getConfigScript() {
        List<String> script = [
            "mkdir -p ${runDir}",
            "cd ${runDir}",
            "cp -R ${installDir}/{bin,conf,data,lib,webapps} .",
            "sed -i.bk 's/broker /broker useJmx=\"true\" /g' conf/activemq.xml",
            "sed -i.bk 's/managementContext createConnector=\"false\"/managementContext connectorPort=\"${jmxPort}\"/g' conf/activemq.xml",
            "sed -i.bk 's/tcp:\\/\\/0.0.0.0:61616\"/tcp:\\/\\/${machine.address.hostName}:${openWirePort}\"/g' conf/activemq.xml",
        ]
        return script
    }

    @Override
    public List<String> getRestartScript() {
       return makeRestartScript("activemq", "data/activemq.pid")
    }

    @Override
    public List<String> getShutdownScript() {
       return makeShutdownScript("activemq", "data/activemq.pid")
    }

    @Override
    protected void postShutdown() {
        machine.releasePort(rmiPort)
        machine.releasePort(jmxPort)
        machine.releasePort(openWirePort);
    }
}
