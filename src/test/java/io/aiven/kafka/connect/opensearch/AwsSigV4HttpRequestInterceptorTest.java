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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import java.io.IOException;

import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.RequestLine;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.regions.Region;

@ExtendWith(MockitoExtension.class)
public class AwsSigV4HttpRequestInterceptorTest {

    @Mock
    private AwsCredentialsProvider credentialsProvider;
    
    private AwsSigV4HttpRequestInterceptor interceptor;
    private HttpContext context;
    private AwsCredentials testCredentials;

    @BeforeEach
    public void setup() {
        testCredentials = AwsBasicCredentials.create("test-access-key", "test-secret-key");
        when(credentialsProvider.resolveCredentials()).thenReturn(testCredentials);
        
        interceptor = new AwsSigV4HttpRequestInterceptor(
                Aws4Signer.create(),
                credentialsProvider,
                Region.US_EAST_1,
                "es"
        );
        
        context = new BasicHttpContext();
    }

    @Test
    public void testProcessGetRequest() throws HttpException, IOException {
        final HttpRequest request = new BasicHttpRequest("GET", "https://search-test.us-east-1.es.amazonaws.com/_search");
        request.addHeader("Host", "search-test.us-east-1.es.amazonaws.com");
        request.addHeader("Content-Type", "application/json");
        
        interceptor.process(request, context);
        
        // Verify that AWS signature headers are added
        assertNotNull(request.getFirstHeader("Authorization"));
        assertNotNull(request.getFirstHeader("X-Amz-Date"));
        
        // Verify the Authorization header contains AWS4-HMAC-SHA256
        assertTrue(request.getFirstHeader("Authorization").getValue().contains("AWS4-HMAC-SHA256"));
        
        // Verify credentials were resolved
        verify(credentialsProvider, times(1)).resolveCredentials();
    }

    @Test
    public void testProcessPostRequest() throws HttpException, IOException {
        final HttpRequest request = new BasicHttpRequest("POST", "https://search-test.us-east-1.es.amazonaws.com/test-index/_doc");
        request.addHeader("Host", "search-test.us-east-1.es.amazonaws.com");
        request.addHeader("Content-Type", "application/json");
        
        interceptor.process(request, context);
        
        // Verify that AWS signature headers are added
        assertNotNull(request.getFirstHeader("Authorization"));
        assertNotNull(request.getFirstHeader("X-Amz-Date"));
        
        // Verify the Authorization header contains AWS4-HMAC-SHA256
        assertTrue(request.getFirstHeader("Authorization").getValue().contains("AWS4-HMAC-SHA256"));
        
        // Verify credentials were resolved
        verify(credentialsProvider, times(1)).resolveCredentials();
    }

    @Test
    public void testProcessRequestWithQueryParameters() throws HttpException, IOException {
        final HttpRequest request = new BasicHttpRequest("GET", "https://search-test.us-east-1.es.amazonaws.com/_search?q=test&size=10");
        request.addHeader("Host", "search-test.us-east-1.es.amazonaws.com");
        
        interceptor.process(request, context);
        
        // Verify that AWS signature headers are added
        assertNotNull(request.getFirstHeader("Authorization"));
        assertNotNull(request.getFirstHeader("X-Amz-Date"));
        
        // Verify the Authorization header contains AWS4-HMAC-SHA256
        assertTrue(request.getFirstHeader("Authorization").getValue().contains("AWS4-HMAC-SHA256"));
    }

    @Test
    public void testProcessRequestWithExistingAuthorizationHeader() throws HttpException, IOException {
        final HttpRequest request = new BasicHttpRequest("GET", "https://search-test.us-east-1.es.amazonaws.com/_search");
        request.addHeader("Host", "search-test.us-east-1.es.amazonaws.com");
        request.addHeader("Authorization", "Bearer token123");
        
        interceptor.process(request, context);
        
        // Verify that the AWS authorization header replaces the existing one
        assertNotNull(request.getFirstHeader("Authorization"));
        assertTrue(request.getFirstHeader("Authorization").getValue().contains("AWS4-HMAC-SHA256"));
    }

    @Test
    public void testProcessRequestFailsWithCredentialsException() {
        when(credentialsProvider.resolveCredentials()).thenThrow(new RuntimeException("Credentials not found"));
        
        final HttpRequest request = new BasicHttpRequest("GET", "https://search-test.us-east-1.es.amazonaws.com/_search");
        request.addHeader("Host", "search-test.us-east-1.es.amazonaws.com");
        
        assertThrows(HttpException.class, () -> interceptor.process(request, context));
    }

    @Test
    public void testProcessRequestWithDifferentRegion() throws HttpException, IOException {
        final AwsSigV4HttpRequestInterceptor usWestInterceptor = new AwsSigV4HttpRequestInterceptor(
                Aws4Signer.create(),
                credentialsProvider,
                Region.US_WEST_2,
                "es"
        );
        
        final HttpRequest request = new BasicHttpRequest("GET", "https://search-test.us-west-2.es.amazonaws.com/_search");
        request.addHeader("Host", "search-test.us-west-2.es.amazonaws.com");
        
        usWestInterceptor.process(request, context);
        
        // Verify that AWS signature headers are added
        assertNotNull(request.getFirstHeader("Authorization"));
        assertNotNull(request.getFirstHeader("X-Amz-Date"));
        
        // Verify the Authorization header contains AWS4-HMAC-SHA256
        assertTrue(request.getFirstHeader("Authorization").getValue().contains("AWS4-HMAC-SHA256"));
    }

    @Test
    public void testProcessRequestWithOpenSearchService() throws HttpException, IOException {
        final AwsSigV4HttpRequestInterceptor opensearchInterceptor = new AwsSigV4HttpRequestInterceptor(
                Aws4Signer.create(),
                credentialsProvider,
                Region.US_EAST_1,
                "opensearch"
        );
        
        final HttpRequest request = new BasicHttpRequest("GET", "https://search-test.us-east-1.opensearch.amazonaws.com/_search");
        request.addHeader("Host", "search-test.us-east-1.opensearch.amazonaws.com");
        
        opensearchInterceptor.process(request, context);
        
        // Verify that AWS signature headers are added
        assertNotNull(request.getFirstHeader("Authorization"));
        assertNotNull(request.getFirstHeader("X-Amz-Date"));
        
        // Verify the Authorization header contains AWS4-HMAC-SHA256
        assertTrue(request.getFirstHeader("Authorization").getValue().contains("AWS4-HMAC-SHA256"));
    }
}