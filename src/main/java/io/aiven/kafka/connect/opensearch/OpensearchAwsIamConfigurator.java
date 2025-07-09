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

import java.util.Objects;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Width;
import org.apache.kafka.common.config.types.Password;

import io.aiven.kafka.connect.opensearch.spi.ConfigDefContributor;
import io.aiven.kafka.connect.opensearch.spi.OpensearchClientConfigurator;

import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.signer.Signer;
import software.amazon.awssdk.auth.signer.AwsSignerExecutionAttribute;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.imds.Ec2MetadataClient;
import software.amazon.awssdk.imds.Ec2MetadataResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds AWS IAM authentication to the HttpAsyncClientBuilder for OpenSearch client if configured.
 * Supports IMDSv2 for credential retrieval from EC2 instances.
 */
public class OpensearchAwsIamConfigurator implements OpensearchClientConfigurator, ConfigDefContributor {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(OpensearchAwsIamConfigurator.class);
    
    public static final String AWS_REGION_CONFIG = "aws.region";
    private static final String AWS_REGION_DOC = "The AWS region for the OpenSearch service. "
            + "If not specified, the region will be determined from the environment or EC2 metadata service.";
    
    public static final String AWS_ACCESS_KEY_ID_CONFIG = "aws.access.key.id";
    private static final String AWS_ACCESS_KEY_ID_DOC = "The AWS access key ID. "
            + "If not specified, credentials will be obtained from the default credential provider chain.";
    
    public static final String AWS_SECRET_ACCESS_KEY_CONFIG = "aws.secret.access.key";
    private static final String AWS_SECRET_ACCESS_KEY_DOC = "The AWS secret access key. "
            + "If not specified, credentials will be obtained from the default credential provider chain.";
    
    public static final String AWS_SESSION_TOKEN_CONFIG = "aws.session.token";
    private static final String AWS_SESSION_TOKEN_DOC = "The AWS session token for temporary credentials. "
            + "Optional, only needed when using temporary credentials.";
    
    public static final String AWS_CREDENTIALS_PROVIDER_CONFIG = "aws.credentials.provider";
    private static final String AWS_CREDENTIALS_PROVIDER_DOC = "The AWS credentials provider to use. "
            + "Valid values are: default, static, instance_profile. Default is 'default'.";
    
    public static final String AWS_SERVICE_NAME_CONFIG = "aws.service.name";
    private static final String AWS_SERVICE_NAME_DOC = "The AWS service name for request signing. Default is 'es'.";
    
    private static final String SERVICE_NAME_DEFAULT = "es";
    private static final String CREDENTIALS_PROVIDER_DEFAULT = "default";

    @Override
    public boolean apply(final OpensearchSinkConnectorConfig config, final HttpAsyncClientBuilder builder) {
        if (!isAwsAuthEnabled(config)) {
            return false;
        }

        try {
            final Region region = getRegion(config);
            final AwsCredentialsProvider credentialsProvider = getCredentialsProvider(config);
            final String serviceName = getServiceName(config);
            
            LOGGER.info("Configuring AWS IAM authentication for region: {}, service: {}", region, serviceName);
            
            // Create AWS SigV4 signer
            final Aws4Signer signer = Aws4Signer.create();
            
            // Create request interceptor for SigV4 signing
            final HttpRequestInterceptor signingInterceptor = new AwsSigV4HttpRequestInterceptor(
                    signer, credentialsProvider, region, serviceName);
            
            builder.addInterceptorFirst(signingInterceptor);
            
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to configure AWS IAM authentication", e);
            return false;
        }
    }

    @Override
    public void addConfig(final ConfigDef config) {
        config.define(AWS_REGION_CONFIG, Type.STRING, null, Importance.MEDIUM, AWS_REGION_DOC,
                "AWS Authentication", 0, Width.SHORT, "AWS Region")
                .define(AWS_ACCESS_KEY_ID_CONFIG, Type.STRING, null, Importance.MEDIUM, AWS_ACCESS_KEY_ID_DOC,
                        "AWS Authentication", 1, Width.SHORT, "AWS Access Key ID")
                .define(AWS_SECRET_ACCESS_KEY_CONFIG, Type.PASSWORD, null, Importance.MEDIUM, AWS_SECRET_ACCESS_KEY_DOC,
                        "AWS Authentication", 2, Width.SHORT, "AWS Secret Access Key")
                .define(AWS_SESSION_TOKEN_CONFIG, Type.PASSWORD, null, Importance.LOW, AWS_SESSION_TOKEN_DOC,
                        "AWS Authentication", 3, Width.SHORT, "AWS Session Token")
                .define(AWS_CREDENTIALS_PROVIDER_CONFIG, Type.STRING, CREDENTIALS_PROVIDER_DEFAULT, 
                        ConfigDef.ValidString.in("default", "static", "instance_profile"), Importance.LOW, 
                        AWS_CREDENTIALS_PROVIDER_DOC, "AWS Authentication", 4, Width.SHORT, "AWS Credentials Provider")
                .define(AWS_SERVICE_NAME_CONFIG, Type.STRING, SERVICE_NAME_DEFAULT, Importance.LOW, 
                        AWS_SERVICE_NAME_DOC, "AWS Authentication", 5, Width.SHORT, "AWS Service Name");
    }

    private boolean isAwsAuthEnabled(final OpensearchSinkConnectorConfig config) {
        // AWS auth is enabled if either:
        // 1. AWS region is explicitly configured
        // 2. AWS credentials are explicitly configured
        // 3. Credentials provider is set to instance_profile
        return Objects.nonNull(config.getString(AWS_REGION_CONFIG)) ||
               Objects.nonNull(config.getString(AWS_ACCESS_KEY_ID_CONFIG)) ||
               "instance_profile".equals(config.getString(AWS_CREDENTIALS_PROVIDER_CONFIG));
    }

    private Region getRegion(final OpensearchSinkConnectorConfig config) {
        String regionStr = config.getString(AWS_REGION_CONFIG);
        
        if (regionStr != null) {
            return Region.of(regionStr);
        }
        
        // Try to get region from environment or EC2 metadata
        try {
            // First try environment variable
            regionStr = System.getenv("AWS_REGION");
            if (regionStr == null) {
                regionStr = System.getenv("AWS_DEFAULT_REGION");
            }
            
            if (regionStr != null) {
                LOGGER.info("Using AWS region from environment: {}", regionStr);
                return Region.of(regionStr);
            }
            
            // Try EC2 metadata service (IMDSv2)
            try (Ec2MetadataClient metadataClient = Ec2MetadataClient.builder().build()) {
                Ec2MetadataResponse response = metadataClient.get("/latest/meta-data/placement/region");
                if (response != null) {
                    regionStr = response.asString();
                    LOGGER.info("Using AWS region from EC2 metadata: {}", regionStr);
                    return Region.of(regionStr);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to determine AWS region from environment or metadata", e);
        }
        
        // Default to us-east-1 if no region could be determined
        LOGGER.info("Using default AWS region: us-east-1");
        return Region.US_EAST_1;
    }

    private AwsCredentialsProvider getCredentialsProvider(final OpensearchSinkConnectorConfig config) {
        final String providerType = config.getString(AWS_CREDENTIALS_PROVIDER_CONFIG);
        
        switch (providerType) {
            case "static":
                return createStaticCredentialsProvider(config);
            case "instance_profile":
                LOGGER.info("Using instance profile credentials provider with IMDSv2");
                return InstanceProfileCredentialsProvider.builder()
                        .asyncCredentialUpdateEnabled(true)
                        .build();
            case "default":
            default:
                LOGGER.info("Using default AWS credentials provider chain");
                return DefaultCredentialsProvider.builder()
                        .asyncCredentialUpdateEnabled(true)
                        .build();
        }
    }

    private AwsCredentialsProvider createStaticCredentialsProvider(final OpensearchSinkConnectorConfig config) {
        final String accessKeyId = config.getString(AWS_ACCESS_KEY_ID_CONFIG);
        final Password secretAccessKey = config.getPassword(AWS_SECRET_ACCESS_KEY_CONFIG);
        final Password sessionToken = config.getPassword(AWS_SESSION_TOKEN_CONFIG);
        
        if (accessKeyId == null || secretAccessKey == null) {
            throw new IllegalArgumentException("AWS access key ID and secret access key must be provided when using static credentials");
        }
        
        LOGGER.info("Using static AWS credentials");
        
        if (sessionToken != null) {
            return StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsSessionCredentials.create(
                            accessKeyId, secretAccessKey.value(), sessionToken.value()));
        } else {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey.value()));
        }
    }

    private String getServiceName(final OpensearchSinkConnectorConfig config) {
        return config.getString(AWS_SERVICE_NAME_CONFIG);
    }
}