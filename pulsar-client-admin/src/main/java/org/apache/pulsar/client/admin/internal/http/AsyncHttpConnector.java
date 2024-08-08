/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.admin.internal.http;

import static org.asynchttpclient.util.HttpConstants.Methods.GET;
import static org.asynchttpclient.util.HttpConstants.Methods.HEAD;
import static org.asynchttpclient.util.HttpConstants.Methods.OPTIONS;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.FOUND_302;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.MOVED_PERMANENTLY_301;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.PERMANENT_REDIRECT_308;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.SEE_OTHER_303;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.TEMPORARY_REDIRECT_307;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import com.spotify.futures.ConcurrencyReducer;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response.Status;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.apache.pulsar.PulsarVersion;
import org.apache.pulsar.client.admin.internal.PulsarAdminImpl;
import org.apache.pulsar.client.api.AuthenticationDataProvider;
import org.apache.pulsar.client.api.KeyStoreParams;
import org.apache.pulsar.client.impl.PulsarServiceNameResolver;
import org.apache.pulsar.client.impl.ServiceNameResolver;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.client.util.WithSNISslEngineFactory;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.pulsar.common.util.SecurityUtility;
import org.apache.pulsar.common.util.keystoretls.KeyStoreSSLContext;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;
import org.asynchttpclient.Response;
import org.asynchttpclient.SslEngineFactory;
import org.asynchttpclient.channel.DefaultKeepAliveStrategy;
import org.asynchttpclient.netty.ssl.JsseSslEngineFactory;
import org.asynchttpclient.uri.Uri;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.ClientRequest;
import org.glassfish.jersey.client.ClientResponse;
import org.glassfish.jersey.client.spi.AsyncConnectorCallback;
import org.glassfish.jersey.client.spi.Connector;

/**
 * Customized Jersey client connector with multi-host support.
 */
@Slf4j
public class AsyncHttpConnector implements Connector, AsyncHttpRequestExecutor {
    private static final TimeoutException REQUEST_TIMEOUT_EXCEPTION =
            FutureUtil.createTimeoutException("Request timeout", AsyncHttpConnector.class, "retryOrTimeout(...)");
    private static final int DEFAULT_MAX_QUEUE_SIZE_PER_HOST = 10000;
    @Getter
    private final AsyncHttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final ServiceNameResolver serviceNameResolver;
    private final ScheduledExecutorService delayer = Executors.newScheduledThreadPool(1,
            new DefaultThreadFactory("delayer"));
    private final Map<String, ConcurrencyReducer<Response>> concurrencyReducers = new ConcurrentHashMap<>();

    public AsyncHttpConnector(Client client, ClientConfigurationData conf, int autoCertRefreshTimeSeconds) {
        this((int) client.getConfiguration().getProperty(ClientProperties.CONNECT_TIMEOUT),
                (int) client.getConfiguration().getProperty(ClientProperties.READ_TIMEOUT),
                PulsarAdminImpl.DEFAULT_REQUEST_TIMEOUT_SECONDS * 1000,
                autoCertRefreshTimeSeconds,
                conf);
    }

    @SneakyThrows
    public AsyncHttpConnector(int connectTimeoutMs, int readTimeoutMs,
                              int requestTimeoutMs,
                              int autoCertRefreshTimeSeconds, ClientConfigurationData conf) {
        Validate.notEmpty(conf.getServiceUrl(), "Service URL is not provided");
        serviceNameResolver = new PulsarServiceNameResolver();
        String serviceUrl = conf.getServiceUrl();
        serviceNameResolver.updateServiceUrl(serviceUrl);
        AsyncHttpClientConfig asyncHttpClientConfig =
                createAsyncHttpClientConfig(conf, connectTimeoutMs, readTimeoutMs, requestTimeoutMs,
                        autoCertRefreshTimeSeconds);
        httpClient = createAsyncHttpClient(asyncHttpClientConfig);
        this.requestTimeout = requestTimeoutMs > 0 ? Duration.ofMillis(requestTimeoutMs) : null;
        this.maxRetries = httpClient.getConfig().getMaxRequestRetry();
    }

    private AsyncHttpClientConfig createAsyncHttpClientConfig(ClientConfigurationData conf, int connectTimeoutMs,
                                                              int readTimeoutMs,
                                                              int requestTimeoutMs, int autoCertRefreshTimeSeconds)
            throws GeneralSecurityException, IOException {
        DefaultAsyncHttpClientConfig.Builder confBuilder = new DefaultAsyncHttpClientConfig.Builder();
        configureAsyncHttpClientConfig(conf, connectTimeoutMs, readTimeoutMs, requestTimeoutMs, confBuilder);
        if (conf.getServiceUrl().startsWith("https://")) {
            configureAsyncHttpClientSslEngineFactory(conf, autoCertRefreshTimeSeconds, confBuilder);
        }
        AsyncHttpClientConfig asyncHttpClientConfig = confBuilder.build();
        return asyncHttpClientConfig;
    }

    private void configureAsyncHttpClientConfig(ClientConfigurationData conf, int connectTimeoutMs, int readTimeoutMs,
                                                int requestTimeoutMs,
                                                DefaultAsyncHttpClientConfig.Builder confBuilder) {
        if (conf.getConnectionsPerBroker() > 0) {
            confBuilder.setMaxConnectionsPerHost(conf.getConnectionsPerBroker());
            // Use the request timeout value for acquireFreeChannelTimeout so that we don't need to add
            // yet another configuration property. When the ConcurrencyReducer is in use, it shouldn't be necessary to
            // wait for a free channel since the ConcurrencyReducer will queue the requests.
            confBuilder.setAcquireFreeChannelTimeout(conf.getRequestTimeoutMs());
        }
        if (conf.getConnectionMaxIdleSeconds() > 0) {
            confBuilder.setPooledConnectionIdleTimeout(conf.getConnectionMaxIdleSeconds() * 1000);
        }
        confBuilder.setUseProxyProperties(true);
        confBuilder.setFollowRedirect(false);
        confBuilder.setRequestTimeout(conf.getRequestTimeoutMs());
        confBuilder.setConnectTimeout(connectTimeoutMs);
        confBuilder.setReadTimeout(readTimeoutMs);
        confBuilder.setUserAgent(String.format("Pulsar-Java-v%s", PulsarVersion.getVersion()));
        confBuilder.setRequestTimeout(requestTimeoutMs);
        confBuilder.setIoThreadsCount(conf.getNumIoThreads());
        confBuilder.setKeepAliveStrategy(new DefaultKeepAliveStrategy() {
            @Override
            public boolean keepAlive(InetSocketAddress remoteAddress, Request ahcRequest,
                                     HttpRequest request, HttpResponse response) {
                // Close connection upon a server error or per HTTP spec
                return (response.status().code() / 100 != 5)
                       && super.keepAlive(remoteAddress, ahcRequest, request, response);
            }
        });
        confBuilder.setDisableHttpsEndpointIdentificationAlgorithm(!conf.isTlsHostnameVerificationEnable());
    }

    protected AsyncHttpClient createAsyncHttpClient(AsyncHttpClientConfig asyncHttpClientConfig) {
        return new DefaultAsyncHttpClient(asyncHttpClientConfig);
    }

    private void configureAsyncHttpClientSslEngineFactory(ClientConfigurationData conf, int autoCertRefreshTimeSeconds,
                                                          DefaultAsyncHttpClientConfig.Builder confBuilder)
            throws GeneralSecurityException, IOException {
        // Set client key and certificate if available
        AuthenticationDataProvider authData = conf.getAuthentication().getAuthData();

        SslEngineFactory sslEngineFactory = null;
        if (conf.isUseKeyStoreTls()) {
            KeyStoreParams params = authData.hasDataForTls() ? authData.getTlsKeyStoreParams() :
                    new KeyStoreParams(conf.getTlsKeyStoreType(), conf.getTlsKeyStorePath(),
                            conf.getTlsKeyStorePassword());

            final SSLContext sslCtx = KeyStoreSSLContext.createClientSslContext(
                    conf.getSslProvider(),
                    params.getKeyStoreType(),
                    params.getKeyStorePath(),
                    params.getKeyStorePassword(),
                    conf.isTlsAllowInsecureConnection(),
                    conf.getTlsTrustStoreType(),
                    conf.getTlsTrustStorePath(),
                    conf.getTlsTrustStorePassword(),
                    conf.getTlsCiphers(),
                    conf.getTlsProtocols());

            sslEngineFactory = new JsseSslEngineFactory(sslCtx);
            confBuilder.setSslEngineFactory(sslEngineFactory);
        } else {
            SslProvider sslProvider = null;
            if (conf.getSslProvider() != null) {
                sslProvider = SslProvider.valueOf(conf.getSslProvider());
            }
            SslContext sslCtx = null;
            if (authData.hasDataForTls()) {
                sslCtx = authData.getTlsTrustStoreStream() == null
                        ? SecurityUtility.createAutoRefreshSslContextForClient(
                        sslProvider,
                        conf.isTlsAllowInsecureConnection(),
                        conf.getTlsTrustCertsFilePath(), authData.getTlsCerificateFilePath(),
                        authData.getTlsPrivateKeyFilePath(), null, autoCertRefreshTimeSeconds, delayer)
                        : SecurityUtility.createNettySslContextForClient(
                        sslProvider,
                        conf.isTlsAllowInsecureConnection(),
                        authData.getTlsTrustStoreStream(), authData.getTlsCertificates(),
                        authData.getTlsPrivateKey(),
                        conf.getTlsCiphers(),
                        conf.getTlsProtocols());
            } else {
                sslCtx = SecurityUtility.createNettySslContextForClient(
                        sslProvider,
                        conf.isTlsAllowInsecureConnection(),
                        conf.getTlsTrustCertsFilePath(),
                        conf.getTlsCertificateFilePath(),
                        conf.getTlsKeyFilePath(),
                        conf.getTlsCiphers(),
                        conf.getTlsProtocols());
            }
            confBuilder.setSslContext(sslCtx);
            if (!conf.isTlsHostnameVerificationEnable()) {
                confBuilder.setSslEngineFactory(new WithSNISslEngineFactory(serviceNameResolver
                        .resolveHostUri().getHost()));
            }
        }
    }

    @Override
    public ClientResponse apply(ClientRequest jerseyRequest) {
        CompletableFuture<ClientResponse> future = new CompletableFuture<>();
        apply(jerseyRequest, new AsyncConnectorCallback() {
            @Override
            public void response(ClientResponse response) {
                future.complete(response);
            }

            @Override
            public void failure(Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new ProcessingException(e.getCause());
        }
    }

    private URI replaceWithNew(InetSocketAddress address, URI uri) {
        String originalUri = uri.toString();
        String newUri = (originalUri.split(":")[0] + "://")
                + address.getHostString() + ":"
                + address.getPort()
                + uri.getRawPath();
        if (uri.getRawQuery() != null) {
            newUri += "?" + uri.getRawQuery();
        }
        return URI.create(newUri);
    }

    @Override
    public Future<?> apply(ClientRequest jerseyRequest, AsyncConnectorCallback callback) {
        CompletableFuture<Response> responseFuture = retryOrTimeOut(jerseyRequest);
        responseFuture.whenComplete(((response, throwable) -> {
            if (throwable != null) {
                callback.failure(throwable);
            } else {
                ClientResponse jerseyResponse =
                        new ClientResponse(Status.fromStatusCode(response.getStatusCode()), jerseyRequest);
                jerseyResponse.setStatusInfo(new javax.ws.rs.core.Response.StatusType() {
                    @Override
                    public int getStatusCode() {
                        return response.getStatusCode();
                    }

                    @Override
                    public Status.Family getFamily() {
                        return Status.Family.familyOf(response.getStatusCode());
                    }

                    @Override
                    public String getReasonPhrase() {
                        if (response.hasResponseBody()) {
                            return response.getResponseBody();
                        }
                        return response.getStatusText();
                    }
                });
                response.getHeaders().forEach(e -> jerseyResponse.header(e.getKey(), e.getValue()));
                if (response.hasResponseBody()) {
                    jerseyResponse.setEntityStream(response.getResponseBodyAsStream());
                }
                callback.response(jerseyResponse);
            }
        }));
        return responseFuture;
    }

    private CompletableFuture<Response> retryOrTimeOut(ClientRequest request) {
        final CompletableFuture<Response> resultFuture = new CompletableFuture<>();
        retryOperation(resultFuture, () -> oneShot(serviceNameResolver.resolveHost(), request), maxRetries);
        if (requestTimeout != null) {
            FutureUtil.addTimeoutHandling(resultFuture, requestTimeout, delayer, () -> REQUEST_TIMEOUT_EXCEPTION);
        }
        return resultFuture;
    }

    // TODO: There are problems with this solution since AsyncHttpClient already contains logic to retry requests.
    // This solution doesn't contain backoff handling.
    private <T> void retryOperation(
            final CompletableFuture<T> resultFuture,
            final Supplier<CompletableFuture<T>> operation,
            final int retries) {

        if (!resultFuture.isDone()) {
            final CompletableFuture<T> operationFuture = operation.get();

            operationFuture.whenComplete(
                    (t, throwable) -> {
                        if (throwable != null) {
                            throwable = FutureUtil.unwrapCompletionException(throwable);
                            if (throwable instanceof CancellationException) {
                                resultFuture.completeExceptionally(
                                        new RetryException("Operation future was cancelled.", throwable));
                            } else if (throwable instanceof MaxRedirectException) {
                                // don't retry on max redirect
                                resultFuture.completeExceptionally(throwable);
                            } else {
                                if (retries > 0) {
                                    if (log.isDebugEnabled()) {
                                        log.debug("Retrying operation. Remaining retries: {}", retries);
                                    }
                                    retryOperation(
                                            resultFuture,
                                            operation,
                                            retries - 1);
                                } else {
                                    if (log.isDebugEnabled()) {
                                        log.debug("Number of retries has been exhausted. Failing the operation.",
                                                throwable);
                                    }
                                    resultFuture.completeExceptionally(
                                        new RetryException("Could not complete the operation. Number of retries "
                                            + "has been exhausted. Failed reason: " + throwable.getMessage(),
                                            throwable));
                                }
                            }
                        } else {
                            resultFuture.complete(t);
                        }
                    });

            resultFuture.whenComplete(
                    (t, throwable) -> operationFuture.cancel(false));
        }
    }

    /**
     * Retry Exception.
     */
    public static class RetryException extends Exception {
        public RetryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class MaxRedirectException extends Exception {
        public MaxRedirectException(String msg) {
            super(msg, null, true, false);
        }
    }

    protected CompletableFuture<Response> oneShot(InetSocketAddress host, ClientRequest request) {
        Request preparedRequest;
        try {
            preparedRequest = prepareRequest(host, request);
        } catch (IOException e) {
            return FutureUtil.failedFuture(e);
        }
        return executeRequest(preparedRequest);
    }

    public CompletableFuture<Response> executeRequest(Request request) {
        return executeRequest(request, () -> new AsyncCompletionHandlerBase());
    }

    public CompletableFuture<Response> executeRequest(Request request,
                                                       Supplier<AsyncHandler<Response>> handlerSupplier) {
        return executeRequest(request, handlerSupplier, 0);
    }

    private CompletableFuture<Response> executeRequest(Request request,
                                                       Supplier<AsyncHandler<Response>> handlerSupplier,
                                                       int redirectCount) {
        int maxRedirects = httpClient.getConfig().getMaxRedirects();
        if (redirectCount > maxRedirects) {
            return FutureUtil.failedFuture(
                    new MaxRedirectException("Maximum redirect reached: " + maxRedirects + " uri:" + request.getUri()));
        }
        CompletableFuture<Response> responseFuture;
        if (httpClient.getConfig().getMaxConnectionsPerHost() > 0) {
            String hostAndPort = request.getUri().getHost() + ":" + request.getUri().getPort();
            ConcurrencyReducer<Response> responseConcurrencyReducer = concurrencyReducers.computeIfAbsent(hostAndPort,
                    h -> ConcurrencyReducer.create(httpClient.getConfig().getMaxConnectionsPerHost(),
                            DEFAULT_MAX_QUEUE_SIZE_PER_HOST));
            responseFuture = responseConcurrencyReducer.add(() -> doExecuteRequest(request, handlerSupplier));
        } else {
            responseFuture = doExecuteRequest(request, handlerSupplier);
        }
        CompletableFuture<Response> futureWithRedirect = responseFuture.thenCompose(response -> {
            if (isRedirectStatusCode(response.getStatusCode())) {
                return executeRedirect(request, response, handlerSupplier, redirectCount);
            }
            return CompletableFuture.completedFuture(response);
        });
        futureWithRedirect.whenComplete((response, throwable) -> {
            // propagate cancellation or timeout to the original response future
            responseFuture.cancel(false);
        });
        return futureWithRedirect;
    }

    private CompletableFuture<Response> executeRedirect(Request request, Response response,
                                                        Supplier<AsyncHandler<Response>> handlerSupplier,
                                                        int redirectCount) {
        String originalMethod = request.getMethod();
        int statusCode = response.getStatusCode();
        boolean switchToGet = !originalMethod.equals(GET)
                && !originalMethod.equals(OPTIONS) && !originalMethod.equals(HEAD) && (
                statusCode == MOVED_PERMANENTLY_301 || statusCode == SEE_OTHER_303 || statusCode == FOUND_302);
        boolean keepBody = statusCode == TEMPORARY_REDIRECT_307 || statusCode == PERMANENT_REDIRECT_308;
        String location = response.getHeader(HttpHeaders.LOCATION);
        Uri newUri = Uri.create(request.getUri(), location);
        BoundRequestBuilder builder = httpClient.prepareRequest(request);
        if (switchToGet) {
            builder.setMethod(GET);
        }
        builder.setUri(newUri);
        if (keepBody) {
            builder.setCharset(request.getCharset());
            if (isNonEmpty(request.getFormParams())) {
                builder.setFormParams(request.getFormParams());
            } else if (request.getStringData() != null) {
                builder.setBody(request.getStringData());
            } else if (request.getByteData() != null){
                builder.setBody(request.getByteData());
            } else if (request.getByteBufferData() != null) {
                builder.setBody(request.getByteBufferData());
            } else if (request.getBodyGenerator() != null) {
                builder.setBody(request.getBodyGenerator());
            } else if (isNonEmpty(request.getBodyParts())) {
                builder.setBodyParts(request.getBodyParts());
            }
        } else {
            builder.resetFormParams();
            builder.resetNonMultipartData();
            builder.resetMultipartData();
            io.netty.handler.codec.http.HttpHeaders headers = new DefaultHttpHeaders();
            headers.add(request.getHeaders());
            headers.remove(HttpHeaders.CONTENT_LENGTH);
            headers.remove(HttpHeaders.CONTENT_TYPE);
            headers.remove(HttpHeaders.CONTENT_ENCODING);
            builder.setHeaders(headers);
        }
        return executeRequest(builder.build(), handlerSupplier, redirectCount + 1);
    }

    private static boolean isRedirectStatusCode(int statusCode) {
        return statusCode == MOVED_PERMANENTLY_301 || statusCode == FOUND_302 || statusCode == SEE_OTHER_303
                || statusCode == TEMPORARY_REDIRECT_307 || statusCode == PERMANENT_REDIRECT_308;
    }

    private CompletableFuture<Response> doExecuteRequest(Request request,
                                                         Supplier<AsyncHandler<Response>> handlerSupplier) {
        ListenableFuture<Response> responseFuture =
                httpClient.executeRequest(request, handlerSupplier.get());
        CompletableFuture<Response> completableFuture = responseFuture.toCompletableFuture();
        completableFuture.whenComplete((response, throwable) -> {
            throwable = FutureUtil.unwrapCompletionException(throwable);
            if (throwable != null && (throwable instanceof CancellationException
                    || throwable instanceof TimeoutException)) {
                // abort the request if the future is cancelled or timed out
                responseFuture.abort(throwable);
            }
        });
        return completableFuture;
    }

    private Request prepareRequest(InetSocketAddress host, ClientRequest request) throws IOException {
        ClientRequest currentRequest = new ClientRequest(request);
        URI newUri = replaceWithNew(host, currentRequest.getUri());
        currentRequest.setUri(newUri);

        BoundRequestBuilder builder =
                httpClient.prepare(currentRequest.getMethod(), currentRequest.getUri().toString());

        if (currentRequest.hasEntity()) {
            ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            currentRequest.setStreamProvider(contentLength -> outStream);
            currentRequest.writeEntity();
            builder.setBody(outStream.toByteArray());
        }

        currentRequest.getHeaders().forEach((key, headers) -> {
            if (!HttpHeaders.USER_AGENT.equals(key)) {
                builder.addHeader(key, headers);
            }
        });

        return builder.build();
    }

    @Override
    public String getName() {
        return "Pulsar-Admin";
    }

    @Override
    public void close() {
        try {
            httpClient.close();
            delayer.shutdownNow();
        } catch (IOException e) {
            log.warn("Failed to close http client", e);
        }
    }

}
