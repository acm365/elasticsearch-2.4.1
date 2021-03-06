/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cloud.azure.management;

import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.core.Builder;
import com.microsoft.windowsazure.core.DefaultBuilder;
import com.microsoft.windowsazure.core.utils.KeyStoreType;
import com.microsoft.windowsazure.management.compute.ComputeManagementClient;
import com.microsoft.windowsazure.management.compute.ComputeManagementService;
import com.microsoft.windowsazure.management.compute.models.HostedServiceGetDetailedResponse;
import com.microsoft.windowsazure.management.configuration.ManagementConfiguration;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.cloud.azure.AzureServiceRemoteException;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ServiceLoader;

import static org.elasticsearch.cloud.azure.management.AzureComputeService.Management.KEYSTORE_PASSWORD;
import static org.elasticsearch.cloud.azure.management.AzureComputeService.Management.KEYSTORE_PATH;
import static org.elasticsearch.cloud.azure.management.AzureComputeService.Management.KEYSTORE_TYPE;
import static org.elasticsearch.cloud.azure.management.AzureComputeService.Management.SUBSCRIPTION_ID;

/**
 *
 */
public class AzureComputeServiceImpl extends AbstractLifecycleComponent<AzureComputeServiceImpl>
    implements AzureComputeService {

    static final class Azure {
        private static final String ENDPOINT = "https://management.core.windows.net/";
    }

    private final ComputeManagementClient client;
    private final String serviceName;

    @Inject
    public AzureComputeServiceImpl(Settings settings) {
        super(settings);
        String subscriptionId = settings.get(SUBSCRIPTION_ID);

        serviceName = settings.get(Management.SERVICE_NAME);
        String keystorePath = settings.get(KEYSTORE_PATH);
        String keystorePassword = settings.get(KEYSTORE_PASSWORD);
        String strKeyStoreType = settings.get(KEYSTORE_TYPE, KeyStoreType.pkcs12.name());
        KeyStoreType tmpKeyStoreType = KeyStoreType.pkcs12;
        try {
            tmpKeyStoreType = KeyStoreType.fromString(strKeyStoreType);
        } catch (Exception e) {
            logger.warn("wrong value for [{}]: [{}]. falling back to [{}]...", KEYSTORE_TYPE,
                    strKeyStoreType, KeyStoreType.pkcs12.name());
        }
        KeyStoreType keystoreType = tmpKeyStoreType;

        try {
            // Azure SDK configuration uses DefaultBuilder which uses java.util.ServiceLoader to load the
            // various Azure services. By default, this will use the current thread's context classloader
            // to load services. Since the current thread refers to the main application classloader it
            // won't find any Azure service implementation.

            // Here we basically create a new DefaultBuilder that uses the current class classloader to load services.
            DefaultBuilder builder = new DefaultBuilder();
            for (Builder.Exports exports : ServiceLoader.load(Builder.Exports.class, getClass().getClassLoader())) {
                exports.register(builder);
            }

            // And create a new blank configuration based on the previous DefaultBuilder
            Configuration configuration = new Configuration(builder);
            configuration.setProperty(Configuration.PROPERTY_LOG_HTTP_REQUESTS, logger.isTraceEnabled());

            Configuration managementConfig = ManagementConfiguration.configure(null, configuration, new URI(Azure.ENDPOINT),
                    subscriptionId, keystorePath, keystorePassword, keystoreType);

            logger.debug("creating new Azure client for [{}], [{}]", subscriptionId, serviceName);
            client = ComputeManagementService.create(managementConfig);
        } catch (IOException|URISyntaxException e) {
            throw new ElasticsearchException("Unable to configure Azure compute service", e);
        }
    }

    @Override
    public HostedServiceGetDetailedResponse getServiceDetails() {
        try {
            return client.getHostedServicesOperations().getDetailed(serviceName);
        } catch (Exception e) {
            throw new AzureServiceRemoteException("can not get list of azure nodes", e);
        }
    }

    @Override
    protected void doStart() throws ElasticsearchException {
    }

    @Override
    protected void doStop() throws ElasticsearchException {
    }

    @Override
    protected void doClose() throws ElasticsearchException {
        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                logger.error("error while closing Azure client", e);
            }
        }
    }
}
