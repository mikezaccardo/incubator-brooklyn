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
package brooklyn.rest.resources;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.config.ComputeServiceProperties;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.python.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.util.stream.Streams;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.inject.Module;
import com.sun.jersey.multipart.FormDataBodyPart;
import com.sun.jersey.multipart.FormDataMultiPart;
import com.wordnik.swagger.core.ApiOperation;


@Path("/v1/azure")
@Produces(MediaType.APPLICATION_JSON)
public class AzureResource extends AbstractBrooklynRestResource  {
    private static final Logger log = LoggerFactory.getLogger(ApplicationResource.class);

    @GET
    public Map<String, Boolean> info() {
        return ImmutableMap.of(
                "hasApps", Boolean.valueOf(hasApps()),
                "azureLocationConfigured", Boolean.valueOf(hasAzureLocationConfigured()));
    }

    @POST
    @ApiOperation(value = "Setup Azure")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_HTML)
    public String setup(FormDataMultiPart input) {
        try {
            setupThrowing(input);
            return "done";
        } catch (Exception e) {
            log.error("Couldn't setup", e);
            return "fail";
        }
    }

    private boolean hasApps() {
        return mgmt().getApplications().size() > 0;
    }

    private boolean hasAzureLocationConfigured() {
        try {
            return mgmt().getLocationRegistry().resolve("jclouds:azurecompute").getConfig(JcloudsLocation.ACCESS_IDENTITY) != null;
        } catch (Exception e) {
            log.debug("Unable to resolve azure location", e);
            return false;
        }
    }

    private void setupThrowing(FormDataMultiPart input) throws IOException, FileNotFoundException {
        String subscriptionId = get(input, "subscriptionId");
        File certificate = get(input, "certificate", File.class);
        String certificatePassword = get(input, "certificatePassword");
        String consolePassword = get(input, "consolePassword");
        
        checkCredentials(subscriptionId, certificate.getAbsolutePath(), certificatePassword);
        
        File home = new File(System.getProperty("user.home"), ".brooklyn");
        home.mkdirs();

        File cert = new File(home, "certificate.p12");
        Files.move(certificate, cert);

        boolean hasConsolePassword = !Strings.isNullOrEmpty(consolePassword);

        File propsFile = new File(home, "azure-brooklyn.properties");
        Properties props = new Properties();
        props.setProperty("brooklyn.location.jclouds.azurecompute.identity", cert.getAbsolutePath());
        props.setProperty("brooklyn.location.jclouds.azurecompute.credential", Strings.nullToEmpty(certificatePassword));
        props.setProperty("brooklyn.location.jclouds.azurecompute.endpoint", "https://management.core.windows.net/" + subscriptionId);
        if (hasConsolePassword) {
            props.setProperty("brooklyn.webconsole.security.users", "amp");
            props.setProperty("brooklyn.webconsole.security.user.amp.password", consolePassword);
        }

        Writer out = new FileWriter(propsFile);
        try {
            props.store(out, "Azure minimal config");
        } finally {
            Streams.closeQuietly(out);
        }
        
        java.nio.file.Files.setPosixFilePermissions(propsFile.toPath(), ImmutableSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        
        mgmt().reloadBrooklynProperties();
        
        if (hasConsolePassword) {
        //reload console credentials
//        Runtime.getRuntime().exec("sudo /etc/init.d/amp restart");
        }
    }
    
    private void checkCredentials(String subscriptionId, String certificate, String certificatePassword) {
        ComputeService compute = createComputeService(certificate, certificatePassword);
        try {
            if (compute.listAssignableLocations().size() == 0) {
                throw new IllegalStateException("Invalid credentials");
            }
        } finally {
            compute.getContext().close();
        }
    }

    private ComputeService createComputeService(String certificate, String certificatePassword) {
        Properties properties = new Properties();
        properties.setProperty(ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE, Long.toString(TimeUnit.MINUTES.toMillis(1)));

        Iterable<Module> modules = ImmutableSet.<Module> of(
              new SshjSshClientModule(),
              new SLF4JLoggingModule());

        ContextBuilder builder = ContextBuilder.newBuilder("azurecompute")
                                               .credentials(certificate, certificatePassword)
                                               .modules(modules)
                                               .overrides(properties);

        ComputeService compute = builder.buildView(ComputeServiceContext.class).getComputeService();
        return compute;
    }

    private <T> T get(FormDataMultiPart input, String fieldName, Class<T> type) {
        FormDataBodyPart part = input.getField(fieldName);
        if (part != null) {
            return part.getValueAs(type);
        } else {
            return null;
        }
    }
    
    private String get(FormDataMultiPart input, String fieldName) {
        return get(input, fieldName, String.class);
    }

}