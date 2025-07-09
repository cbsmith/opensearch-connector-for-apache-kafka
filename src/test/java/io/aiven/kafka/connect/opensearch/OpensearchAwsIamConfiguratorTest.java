/*
 * Copyright 2024 Aiven Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aiven.kafka.connect.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import org.apache.http.HttpRequestInterceptor;
import static org.mockito.Mockito.times;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Width;

import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OpensearchAwsIamConfiguratorTest {

    private OpensearchAwsIamConfigurator configurator;
    
    @Mock
    private HttpAsyncClientBuilder httpClientBuilder;
    
    private Map<String, String> props;

    @BeforeEach
    public void setup() {
        configurator = new OpensearchAwsIamConfigurator();
        props = new HashMap<>();
        props.put(OpensearchSinkConnectorConfig.CONNECTION_URL_CONFIG, "https://search-test.us-east-1.es.amazonaws.com");
        props.put(OpensearchSinkConnectorConfig.KEY_IGNORE_CONFIG, "true");
    }

    @Test
    public void testConfigDefContribution() {
        final ConfigDef configDef = new ConfigDef();
        configurator.addConfig(configDef);
        
        // Verify that AWS configuration options are added
        assertTrue(configDef.names().contains(OpensearchAwsIamConfigurator.AWS_REGION_CONFIG));
        assertTrue(configDef.names().contains(OpensearchAwsIamConfigurator.AWS_ACCESS_KEY_ID_CONFIG));
        assertTrue(configDef.names().contains(OpensearchAwsIamConfigurator.AWS_SECRET_ACCESS_KEY_CONFIG));
        assertTrue(configDef.names().contains(OpensearchAwsIamConfigurator.AWS_SESSION_TOKEN_CONFIG));
        assertTrue(configDef.names().contains(OpensearchAwsIamConfigurator.AWS_CREDENTIALS_PROVIDER_CONFIG));
        assertTrue(configDef.names().contains(OpensearchAwsIamConfigurator.AWS_SERVICE_NAME_CONFIG));
        
        // Verify configuration defaults
        assertEquals("es", configDef.defaultValues().get(OpensearchAwsIamConfigurator.AWS_SERVICE_NAME_CONFIG));
        assertEquals("default", configDef.defaultValues().get(OpensearchAwsIamConfigurator.AWS_CREDENTIALS_PROVIDER_CONFIG));
    }

    @Test
    public void testAwsAuthNotEnabledByDefault() {
        final OpensearchSinkConnectorConfig config = new OpensearchSinkConnectorConfig(props);
        assertFalse(configurator.apply(config, httpClientBuilder));
    }

    @Test
    public void testAwsAuthEnabledWithRegion() {
        props.put(OpensearchAwsIamConfigurator.AWS_REGION_CONFIG, "us-east-1");
        final OpensearchSinkConnectorConfig config = new OpensearchSinkConnectorConfig(props);
        assertTrue(configurator.apply(config, httpClientBuilder));
        verify(httpClientBuilder, times(1)).addInterceptorFirst(any(HttpRequestInterceptor.class));
    }

    @Test
    public void testAwsAuthEnabledWithStaticCredentials() {
        props.put(OpensearchAwsIamConfigurator.AWS_ACCESS_KEY_ID_CONFIG, "test-access-key");
        props.put(OpensearchAwsIamConfigurator.AWS_SECRET_ACCESS_KEY_CONFIG, "test-secret-key");
        props.put(OpensearchAwsIamConfigurator.AWS_CREDENTIALS_PROVIDER_CONFIG, "static");
        
        final OpensearchSinkConnectorConfig config = new OpensearchSinkConnectorConfig(props);
        assertTrue(configurator.apply(config, httpClientBuilder));
        verify(httpClientBuilder, times(1)).addInterceptorFirst(any(HttpRequestInterceptor.class));
    }

    @Test
    public void testAwsAuthEnabledWithInstanceProfile() {
        props.put(OpensearchAwsIamConfigurator.AWS_CREDENTIALS_PROVIDER_CONFIG, "instance_profile");
        
        final OpensearchSinkConnectorConfig config = new OpensearchSinkConnectorConfig(props);
        assertTrue(configurator.apply(config, httpClientBuilder));
        verify(httpClientBuilder, times(1)).addInterceptorFirst(any(HttpRequestInterceptor.class));
    }

    @Test
    public void testAwsAuthWithCustomServiceName() {
        props.put(OpensearchAwsIamConfigurator.AWS_REGION_CONFIG, "us-east-1");
        props.put(OpensearchAwsIamConfigurator.AWS_SERVICE_NAME_CONFIG, "opensearch");
        
        final OpensearchSinkConnectorConfig config = new OpensearchSinkConnectorConfig(props);
        assertTrue(configurator.apply(config, httpClientBuilder));
        verify(httpClientBuilder, times(1)).addInterceptorFirst(any(HttpRequestInterceptor.class));
    }

    @Test
    public void testAwsAuthWithSessionToken() {
        props.put(OpensearchAwsIamConfigurator.AWS_ACCESS_KEY_ID_CONFIG, "test-access-key");
        props.put(OpensearchAwsIamConfigurator.AWS_SECRET_ACCESS_KEY_CONFIG, "test-secret-key");
        props.put(OpensearchAwsIamConfigurator.AWS_SESSION_TOKEN_CONFIG, "test-session-token");
        props.put(OpensearchAwsIamConfigurator.AWS_CREDENTIALS_PROVIDER_CONFIG, "static");
        
        final OpensearchSinkConnectorConfig config = new OpensearchSinkConnectorConfig(props);
        assertTrue(configurator.apply(config, httpClientBuilder));
        verify(httpClientBuilder, times(1)).addInterceptorFirst(any(HttpRequestInterceptor.class));
    }

    @Test
    public void testStaticCredentialsValidation() {
        props.put(OpensearchAwsIamConfigurator.AWS_CREDENTIALS_PROVIDER_CONFIG, "static");
        // Missing access key and secret key should cause failure
        
        final OpensearchSinkConnectorConfig config = new OpensearchSinkConnectorConfig(props);
        assertFalse(configurator.apply(config, httpClientBuilder));
    }

    @Test
    public void testValidCredentialsProviderValues() {
        // Test valid credentials provider values
        final String[] validProviders = {"default", "static", "instance_profile"};
        
        for (String provider : validProviders) {
            props.put(OpensearchAwsIamConfigurator.AWS_CREDENTIALS_PROVIDER_CONFIG, provider);
            props.put(OpensearchAwsIamConfigurator.AWS_REGION_CONFIG, "us-east-1");
            
            if ("static".equals(provider)) {
                props.put(OpensearchAwsIamConfigurator.AWS_ACCESS_KEY_ID_CONFIG, "test-access-key");
                props.put(OpensearchAwsIamConfigurator.AWS_SECRET_ACCESS_KEY_CONFIG, "test-secret-key");
            }
            
            final OpensearchSinkConnectorConfig config = new OpensearchSinkConnectorConfig(props);
            assertTrue(configurator.apply(config, httpClientBuilder), 
                "Provider " + provider + " should be valid");
        }
    }

    @Test
    public void testConfigurationImportance() {
        final ConfigDef configDef = new ConfigDef();
        configurator.addConfig(configDef);
        
        // Check that critical configurations have appropriate importance
        assertEquals(Importance.MEDIUM, configDef.configKeys().get(OpensearchAwsIamConfigurator.AWS_REGION_CONFIG).importance);
        assertEquals(Importance.MEDIUM, configDef.configKeys().get(OpensearchAwsIamConfigurator.AWS_ACCESS_KEY_ID_CONFIG).importance);
        assertEquals(Importance.MEDIUM, configDef.configKeys().get(OpensearchAwsIamConfigurator.AWS_SECRET_ACCESS_KEY_CONFIG).importance);
        assertEquals(Importance.LOW, configDef.configKeys().get(OpensearchAwsIamConfigurator.AWS_SESSION_TOKEN_CONFIG).importance);
        assertEquals(Importance.LOW, configDef.configKeys().get(OpensearchAwsIamConfigurator.AWS_CREDENTIALS_PROVIDER_CONFIG).importance);
        assertEquals(Importance.LOW, configDef.configKeys().get(OpensearchAwsIamConfigurator.AWS_SERVICE_NAME_CONFIG).importance);
    }

    @Test
    public void testConfigurationTypes() {
        final ConfigDef configDef = new ConfigDef();
        configurator.addConfig(configDef);
        
        // Check that configurations have correct types
        assertEquals(Type.STRING, configDef.configKeys().get(OpensearchAwsIamConfigurator.AWS_REGION_CONFIG).type);
        assertEquals(Type.STRING, configDef.configKeys().get(OpensearchAwsIamConfigurator.AWS_ACCESS_KEY_ID_CONFIG).type);
        assertEquals(Type.PASSWORD, configDef.configKeys().get(OpensearchAwsIamConfigurator.AWS_SECRET_ACCESS_KEY_CONFIG).type);
        assertEquals(Type.PASSWORD, configDef.configKeys().get(OpensearchAwsIamConfigurator.AWS_SESSION_TOKEN_CONFIG).type);
        assertEquals(Type.STRING, configDef.configKeys().get(OpensearchAwsIamConfigurator.AWS_CREDENTIALS_PROVIDER_CONFIG).type);
        assertEquals(Type.STRING, configDef.configKeys().get(OpensearchAwsIamConfigurator.AWS_SERVICE_NAME_CONFIG).type);
    }
}