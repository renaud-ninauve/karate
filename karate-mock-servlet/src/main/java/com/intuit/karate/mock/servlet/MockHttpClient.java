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
package com.intuit.karate.mock.servlet;

import com.intuit.karate.Logger;
import com.intuit.karate.runtime.Config;
import com.intuit.karate.runtime.ScenarioEngine;
import com.intuit.karate.server.HttpClient;
import com.intuit.karate.server.HttpConstants;
import com.intuit.karate.server.HttpLogger;
import com.intuit.karate.server.HttpRequest;
import com.intuit.karate.server.Response;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.CookieDecoder;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 *
 * @author pthomas3
 */
public class MockHttpClient implements HttpClient {

    private final ScenarioEngine engine;
    private final Logger logger;
    private final HttpLogger httpLogger;
    private final Servlet servlet;
    private final ServletContext servletContext;

    public MockHttpClient(ScenarioEngine engine, Servlet servlet, ServletContext servletContext) {
        this.engine = engine;
        logger = engine.logger;
        httpLogger = new HttpLogger(logger);
        this.servlet = servlet;
        this.servletContext = servletContext;
    }

    @Override
    public void setConfig(Config config, String keyThatChanged) {
        // 
    }

    @Override
    public Config getConfig() {
        return engine.getConfig();
    }

    @Override
    public Response invoke(HttpRequest request) {
        URI uri;
        try {
            uri = new URI(request.getUrl());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(request.getMethod(), uri);
        if (request.getHeaders() != null) {
            request.getHeaders().forEach((k, vals) -> builder.header(k, vals.toArray()));
            List<String> cookieValues = request.getHeaderValues(HttpConstants.HDR_COOKIE);
            if (cookieValues != null) {
                for (String cookieValue : cookieValues) {
                    Cookie c = ClientCookieDecoder.STRICT.decode(cookieValue);
                    javax.servlet.http.Cookie cookie = new javax.servlet.http.Cookie(c.name(), c.value());
                    if (c.domain() != null) {
                        cookie.setDomain(c.domain());
                    }
                    if (c.path() != null) {
                        cookie.setPath(c.path());
                    }
                    cookie.setHttpOnly(c.isHttpOnly());
                    cookie.setSecure(c.isSecure());
                    cookie.setMaxAge((int) c.maxAge());
                    builder.cookie(cookie);
                }
            }
        }
        if (request.getBody() != null) {
            builder.content(request.getBody());
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockHttpServletRequest req = builder.buildRequest(servletContext);
        Map<String, List<String>> headers = toHeaders(toCollection(req.getHeaderNames()), name -> toCollection(req.getHeaders(name)));
        request.setHeaders(headers);
        httpLogger.logRequest(engine.getConfig(), request);
        try {
            servlet.service(req, res);
            request.setEndTimeMillis(System.currentTimeMillis());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        headers = toHeaders(res.getHeaderNames(), name -> res.getHeaders(name));
        javax.servlet.http.Cookie[] cookies = res.getCookies();
        List<String> cookieValues = new ArrayList(cookies.length);
        for (javax.servlet.http.Cookie c : cookies) {
            DefaultCookie dc = new DefaultCookie(c.getName(), c.getValue());
            dc.setDomain(c.getDomain());
            dc.setMaxAge(c.getMaxAge());
            dc.setSecure(c.getSecure());
            dc.setPath(c.getPath());
            dc.setHttpOnly(c.isHttpOnly());
            cookieValues.add(ServerCookieEncoder.STRICT.encode(dc));
        }
        if (!cookieValues.isEmpty()) {
            headers.put(HttpConstants.HDR_SET_COOKIE, cookieValues);
        }
        Response response = new Response(res.getStatus(), headers, res.getContentAsByteArray());
        httpLogger.logResponse(getConfig(), request, response);
        return response;
    }

    private static Collection<String> toCollection(Enumeration<String> values) {
        List<String> list = new ArrayList();
        while (values.hasMoreElements()) {
            list.add(values.nextElement());
        }
        return list;
    }

    private static Map<String, List<String>> toHeaders(Collection<String> names, Function<String, Collection<String>> valuesFn) {
        Map<String, List<String>> map = new LinkedHashMap(names.size());
        for (String name : names) {
            Collection<String> values = valuesFn.apply(name);
            List<String> list = new ArrayList(values.size());
            for (String value : values) {
                list.add(value);
            }
            map.put(name, list);
        }
        return map;
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

}
