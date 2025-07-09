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

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.signer.AwsSignerExecutionAttribute;
import software.amazon.awssdk.core.signer.Signer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP request interceptor that signs requests using AWS Signature Version 4.
 * This interceptor is compatible with IMDSv2 for credential retrieval.
 */
public class AwsSigV4HttpRequestInterceptor implements HttpRequestInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AwsSigV4HttpRequestInterceptor.class);

    private final Aws4Signer signer;
    private final AwsCredentialsProvider credentialsProvider;
    private final Region region;
    private final String serviceName;

    public AwsSigV4HttpRequestInterceptor(
            final Aws4Signer signer,
            final AwsCredentialsProvider credentialsProvider,
            final Region region,
            final String serviceName) {
        this.signer = signer;
        this.credentialsProvider = credentialsProvider;
        this.region = region;
        this.serviceName = serviceName;
    }

    @Override
    public void process(final HttpRequest request, final HttpContext context) throws HttpException, IOException {
        try {
            // Get AWS credentials (supports IMDSv2)
            final AwsCredentials credentials = credentialsProvider.resolveCredentials();
            
            // Convert Apache HttpRequest to AWS SDK HttpRequest
            final SdkHttpFullRequest sdkRequest = convertToSdkRequest(request);
            
            // Set up execution attributes for signing
            final ExecutionAttributes executionAttributes = new ExecutionAttributes();
            executionAttributes.putAttribute(AwsSignerExecutionAttribute.AWS_CREDENTIALS, credentials);
            executionAttributes.putAttribute(AwsSignerExecutionAttribute.SERVICE_SIGNING_NAME, serviceName);
            executionAttributes.putAttribute(AwsSignerExecutionAttribute.SIGNING_REGION, region);
            
            // Sign the request
            final SdkHttpFullRequest signedRequest = signer.sign(sdkRequest, executionAttributes);
            
            // Apply signed headers back to the original request
            applySignedHeaders(request, signedRequest);
            
            LOGGER.debug("Successfully signed request for service: {}, region: {}", serviceName, region);
            
        } catch (Exception e) {
            LOGGER.error("Failed to sign request with AWS SigV4", e);
            throw new HttpException("Failed to sign request with AWS SigV4", e);
        }
    }

    private SdkHttpFullRequest convertToSdkRequest(final HttpRequest request) throws IOException {
        final String uri = request.getRequestLine().getUri();
        final String method = request.getRequestLine().getMethod();
        
        // Parse URI to extract host, path, and query parameters
        final URI parsedUri = URI.create(uri);
        final String host = request.getFirstHeader("Host") != null ? 
                request.getFirstHeader("Host").getValue() : parsedUri.getHost();
        
        // Build the SDK request
        final SdkHttpFullRequest.Builder requestBuilder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(method))
                .uri(parsedUri)
                .host(host);
        
        // Add headers
        final Map<String, java.util.List<String>> headers = new HashMap<>();
        for (final Header header : request.getAllHeaders()) {
            // Skip headers that will be added by the signer
            if (!header.getName().equalsIgnoreCase("Authorization") && 
                !header.getName().equalsIgnoreCase("X-Amz-Date")) {
                headers.put(header.getName(), java.util.Collections.singletonList(header.getValue()));
            }
        }
        requestBuilder.headers(headers);
        
        // Add body if present
        if (request instanceof org.apache.http.HttpEntityEnclosingRequest) {
            final org.apache.http.HttpEntityEnclosingRequest entityRequest = 
                    (org.apache.http.HttpEntityEnclosingRequest) request;
            if (entityRequest.getEntity() != null) {
                final byte[] content = EntityUtils.toByteArray(entityRequest.getEntity());
                requestBuilder.contentStreamProvider(() -> new java.io.ByteArrayInputStream(content));
            }
        }
        
        return requestBuilder.build();
    }

    private void applySignedHeaders(final HttpRequest request, final SdkHttpFullRequest signedRequest) {
        // Add signed headers to the original request
        signedRequest.headers().forEach((name, values) -> {
            // Remove existing header if present
            request.removeHeaders(name);
            // Add new header value(s)
            for (final String value : values) {
                request.addHeader(name, value);
            }
        });
    }
}