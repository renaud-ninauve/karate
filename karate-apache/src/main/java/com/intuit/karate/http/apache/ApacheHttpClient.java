/*
 * The MIT License
 *
 * Copyright 2020 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate.http.apache;

import com.intuit.karate.FileUtils;
import com.intuit.karate.Logger;
import com.intuit.karate.runtime.Config;
import com.intuit.karate.runtime.ScenarioEngine;
import com.intuit.karate.server.HttpClient;
import com.intuit.karate.server.HttpConstants;
import com.intuit.karate.server.HttpLogger;
import com.intuit.karate.server.HttpRequest;
import com.intuit.karate.server.Response;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpMessage;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ssl.LenientSslConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;

/**
 *
 * @author pthomas3
 */
public class ApacheHttpClient implements HttpClient, HttpRequestInterceptor {

    private final ScenarioEngine engine;
    private final Logger logger;
    private final HttpLogger httpLogger;

    private HttpClientBuilder clientBuilder;

    public ApacheHttpClient(ScenarioEngine engine) {
        this.engine = engine;
        logger = engine.logger;
        httpLogger = new HttpLogger(logger);
        configure(engine.getConfig());
    }
    
    @Override
    public Logger getLogger() {
        return logger;
    }    

    private void configure(Config config) {
        clientBuilder = HttpClientBuilder.create();
        if (!config.isFollowRedirects()) {
            clientBuilder.disableRedirectHandling();
        } else { // support redirect on POST by default
            clientBuilder.setRedirectStrategy(new LaxRedirectStrategy());
        }
        clientBuilder.useSystemProperties();
        clientBuilder.setDefaultCookieSpecRegistry(LenientCookieSpec.registry());
        if (config.isSslEnabled()) {
            // System.setProperty("jsse.enableSNIExtension", "false");
            String algorithm = config.getSslAlgorithm(); // could be null
            KeyStore trustStore = engine.getKeyStore(config.getSslTrustStore(), config.getSslTrustStorePassword(), config.getSslTrustStoreType());
            KeyStore keyStore = engine.getKeyStore(config.getSslKeyStore(), config.getSslKeyStorePassword(), config.getSslKeyStoreType());
            SSLContext sslContext;
            try {
                SSLContextBuilder builder = SSLContexts.custom()
                        .setProtocol(algorithm); // will default to TLS if null
                if (trustStore == null && config.isSslTrustAll()) {
                    builder = builder.loadTrustMaterial(new TrustAllStrategy());
                } else {
                    if (config.isSslTrustAll()) {
                        builder = builder.loadTrustMaterial(trustStore, new TrustSelfSignedStrategy());
                    } else {
                        builder = builder.loadTrustMaterial(trustStore, null); // will use system / java default
                    }
                }
                if (keyStore != null) {
                    char[] keyPassword = config.getSslKeyStorePassword() == null ? null : config.getSslKeyStorePassword().toCharArray();
                    builder = builder.loadKeyMaterial(keyStore, keyPassword);
                }
                sslContext = builder.build();
                SSLConnectionSocketFactory socketFactory;
                if (keyStore != null) {
                    socketFactory = new SSLConnectionSocketFactory(sslContext, new NoopHostnameVerifier());
                } else {
                    socketFactory = new LenientSslConnectionSocketFactory(sslContext, new NoopHostnameVerifier());
                }
                clientBuilder.setSSLSocketFactory(socketFactory);
            } catch (Exception e) {
                logger.error("ssl context init failed: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        }
        RequestConfig.Builder configBuilder = RequestConfig.custom()
                .setCookieSpec(LenientCookieSpec.KARATE)
                .setConnectTimeout(config.getConnectTimeout())
                .setSocketTimeout(config.getReadTimeout());
        if (config.getLocalAddress() != null) {
            try {
                InetAddress localAddress = InetAddress.getByName(config.getLocalAddress());
                configBuilder.setLocalAddress(localAddress);
            } catch (Exception e) {
                logger.warn("failed to resolve local address: {} - {}", config.getLocalAddress(), e.getMessage());
            }
        }
        clientBuilder.setDefaultRequestConfig(configBuilder.build());
        SocketConfig.Builder socketBuilder = SocketConfig.custom().setSoTimeout(config.getConnectTimeout());
        clientBuilder.setDefaultSocketConfig(socketBuilder.build());
        if (config.getProxyUri() != null) {
            try {
                URI proxyUri = new URIBuilder(config.getProxyUri()).build();
                clientBuilder.setProxy(new HttpHost(proxyUri.getHost(), proxyUri.getPort(), proxyUri.getScheme()));
                if (config.getProxyUsername() != null && config.getProxyPassword() != null) {
                    CredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(
                            new AuthScope(proxyUri.getHost(), proxyUri.getPort()),
                            new UsernamePasswordCredentials(config.getProxyUsername(), config.getProxyPassword()));
                    clientBuilder.setDefaultCredentialsProvider(credsProvider);
                }
                if (config.getNonProxyHosts() != null) {
                    ProxySelector proxySelector = new ProxySelector() {
                        private final List<String> proxyExceptions = config.getNonProxyHosts();

                        @Override
                        public List<Proxy> select(URI uri) {
                            return Collections.singletonList(proxyExceptions.contains(uri.getHost())
                                    ? Proxy.NO_PROXY
                                    : new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyUri.getHost(), proxyUri.getPort())));
                        }

                        @Override
                        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                            logger.info("connect failed to uri: {}", uri, ioe);
                        }
                    };
                    clientBuilder.setRoutePlanner(new SystemDefaultRoutePlanner(proxySelector));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        clientBuilder.addInterceptorLast(this);
    }

    @Override
    public void setConfig(Config config, String keyThatChanged) {
        configure(config);
    }

    @Override
    public Config getConfig() {
        return engine.getConfig();
    }

    private HttpRequest request;

    @Override
    public Response invoke(HttpRequest request) {
        this.request = request;
        RequestBuilder requestBuilder = RequestBuilder.create(request.getMethod()).setUri(request.getUrl());
        if (request.getHeaders() != null) {
            request.getHeaders().forEach((k, vals) -> vals.forEach(v -> requestBuilder.addHeader(k, v)));
        }
        if (request.getBody() != null) {
            requestBuilder.setEntity(new ByteArrayEntity(request.getBody()));
        }
        CloseableHttpClient client = clientBuilder.build();
        CloseableHttpResponse httpResponse;
        byte[] bytes;
        try {
            httpResponse = client.execute(requestBuilder.build());
            HttpEntity responseEntity = httpResponse.getEntity();
            if (responseEntity == null || responseEntity.getContent() == null) {
                bytes = HttpConstants.ZERO_BYTES;
            } else {
                InputStream is = responseEntity.getContent();
                bytes = FileUtils.toBytes(is);
            }
            request.setEndTimeMillis(System.currentTimeMillis());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Map<String, List<String>> headers = toHeaders(httpResponse);
        Response response = new Response(httpResponse.getStatusLine().getStatusCode(), headers, bytes);
        httpLogger.logResponse(getConfig(), request, response);
        return response;
    }

    @Override
    public void process(org.apache.http.HttpRequest hr, HttpContext hc) throws HttpException, IOException {
        request.setHeaders(toHeaders(hr));
        httpLogger.logRequest(getConfig(), request);
        request.setStartTimeMillis(System.currentTimeMillis());
    }

    private static Map<String, List<String>> toHeaders(HttpMessage msg) {
        Header[] headers = msg.getAllHeaders();
        Map<String, List<String>> map = new LinkedHashMap(headers.length);
        for (Header outer : headers) {
            String name = outer.getName();
            Header[] inner = msg.getHeaders(name);
            List<String> list = new ArrayList(inner.length);
            for (Header h : inner) {
                list.add(h.getValue());
            }
            map.put(name, list);
        }
        return map;
    }

}
