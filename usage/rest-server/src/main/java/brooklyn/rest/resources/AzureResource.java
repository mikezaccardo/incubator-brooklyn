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

import brooklyn.location.LocationDefinition;
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
    
    private static final String AZURE_ENDPOINT_BASE_URL = "https://management.core.windows.net/";

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
        LocationDefinition azureLocationDefinition = mgmt().getLocationRegistry().getDefinedLocationByName("azure");

        if (azureLocationDefinition == null) {
            log.debug("Azure location not found.");
            return false;
        }

        if (azureLocationDefinition.getConfig().get(JcloudsLocation.ACCESS_IDENTITY.getName()) == null) {
            log.debug("Azure identity not set.");
            return false;
        }

        return true;
    }

    private void setupThrowing(FormDataMultiPart input) throws IOException, FileNotFoundException {
        String endpoint = AZURE_ENDPOINT_BASE_URL + get(input, "subscriptionId");
        File certificate = get(input, "certificate", File.class);
        String certificatePassword = get(input, "certificatePassword");
        String consolePassword = get(input, "consolePassword");
        
        checkCredentials(endpoint, certificate.getAbsolutePath(), certificatePassword);
        
        File home = new File(System.getProperty("user.home"), ".brooklyn");
        home.mkdirs();

        File cert = new File(home, "certificate.p12");
        Files.move(certificate, cert);

        File propsFile = new File(home, "brooklyn.properties");
        Properties props = new Properties();
        
        props.setProperty("brooklyn.location.named.azure", "jclouds:azurecompute:East US");
        props.setProperty("brooklyn.location.named.azure.identity", cert.getAbsolutePath());
        props.setProperty("brooklyn.location.named.azure.credential", Strings.nullToEmpty(certificatePassword));
        props.setProperty("brooklyn.location.named.azure.endpoint", endpoint);
        props.setProperty("brooklyn.location.named.azure.imageId", "b39f27a8b8c64d52b05eac6a62ebad85__Ubuntu-14_04_1-LTS-amd64-server-20150123-en-us-30GB");
        props.setProperty("brooklyn.location.named.azure.hardwareId", "BASIC_A2");
        props.setProperty("brooklyn.location.named.azure.displayName", "Azure");
        props.setProperty("brooklyn.location.named.azure.vmNameMaxLength", "45");
        props.setProperty("brooklyn.location.named.azure.jclouds.azurecompute.operation.timeout", "120000");
        
        boolean hasConsolePassword = !Strings.isNullOrEmpty(consolePassword);
        
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
    
    private void checkCredentials(String endpoint, String certificate, String certificatePassword) {
        ComputeService compute = createComputeService(endpoint, certificate, certificatePassword);
        try {
            if (compute.listAssignableLocations().size() == 0) {
                throw new IllegalStateException("Invalid credentials");
            }
        } finally {
            compute.getContext().close();
        }
    }

    private ComputeService createComputeService(String endpoint, String certificate, String certificatePassword) {
        Properties properties = new Properties();
        properties.setProperty(ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE, Long.toString(TimeUnit.MINUTES.toMillis(1)));

        Iterable<Module> modules = ImmutableSet.<Module> of(
              new SshjSshClientModule(),
              new SLF4JLoggingModule());

        ContextBuilder builder = ContextBuilder.newBuilder("azurecompute")
                                               .credentials(certificate, certificatePassword)
                                               .endpoint(endpoint)
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