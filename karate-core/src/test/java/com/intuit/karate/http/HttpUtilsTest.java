package com.intuit.karate.http;

import com.intuit.karate.FileUtils;
import com.intuit.karate.Match;
import java.util.Arrays;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 *
 * @author pthomas3
 */
class HttpUtilsTest {

    @Test
    void testParseContentTypeCharset() {
        assertEquals(FileUtils.UTF8, HttpUtils.parseContentTypeCharset("application/json; charset=UTF-8"));
        assertEquals(FileUtils.UTF8, HttpUtils.parseContentTypeCharset("application/json; charset = UTF-8 "));
        assertEquals(FileUtils.UTF8, HttpUtils.parseContentTypeCharset("application/json; charset=UTF-8; version=1.2.3"));
        assertEquals(FileUtils.UTF8, HttpUtils.parseContentTypeCharset("application/json; charset = UTF-8 ; version=1.2.3"));
    }

    @Test
    void testParseContentTypeParams() {
        Map<String, String> map = HttpUtils.parseContentTypeParams("application/json");
        assertNull(map);
        map = HttpUtils.parseContentTypeParams("application/json; charset=UTF-8");
        Match.equals(map, "{ charset: 'UTF-8' }");
        map = HttpUtils.parseContentTypeParams("application/json; charset = UTF-8 ");
        Match.equals(map, "{ charset: 'UTF-8' }");
        map = HttpUtils.parseContentTypeParams("application/json; charset=UTF-8; version=1.2.3");
        Match.equals(map, "{ charset: 'UTF-8', version: '1.2.3' }");
        map = HttpUtils.parseContentTypeParams("application/json; charset = UTF-8 ; version=1.2.3");
        Match.equals(map, "{ charset: 'UTF-8', version: '1.2.3' }");
        map = HttpUtils.parseContentTypeParams("application/vnd.app.test+json;ton-version=1");
        Match.equals(map, "{ 'ton-version': '1' }");
    }

    @Test
    void testParseUriPathPatterns() {
        Map<String, String> map = HttpUtils.parseUriPattern("/cats/{id}", "/cats/1");
        Match.equals(map, "{ id: '1' }");
        map = HttpUtils.parseUriPattern("/cats/{id}/", "/cats/1"); // trailing slash
        Match.equals(map, "{ id: '1' }");
        map = HttpUtils.parseUriPattern("/cats/{id}", "/cats/1/"); // trailing slash
        Match.equals(map, "{ id: '1' }");
        map = HttpUtils.parseUriPattern("/cats/{id}", "/foo/bar");
        Match.equals(map, null);
        map = HttpUtils.parseUriPattern("/cats", "/cats/1"); // exact match
        Match.equals(map, null);
        map = HttpUtils.parseUriPattern("/{path}/{id}", "/cats/1");
        Match.equals(map, "{ path: 'cats', id: '1' }");
        map = HttpUtils.parseUriPattern("/cats/{id}/foo", "/cats/1/foo");
        Match.equals(map, "{ id: '1' }");
        map = HttpUtils.parseUriPattern("/api/{img}", "/api/billie.jpg");
        Match.equals(map, "{ img: 'billie.jpg' }");
        map = HttpUtils.parseUriPattern("/hello/{raw}", "/hello/�Ill~Formed@RequiredString!");
        Match.equals(map, "{ raw: '�Ill~Formed@RequiredString!' }");
    }

    @Test
    void testParseCookieString() {
        String header = "Set-Cookie: foo=\"bar\";Version=1";
        Map<String, Cookie> map = HttpUtils.parseCookieHeaderString(header);
        Match.equals(map, "{ foo: '#object' }"); // only one entry
        Match.contains(map.get("foo"), "{ name: 'foo', value: 'bar' }");
    }

    @Test
    void testCreateCookieString() {
        Cookie c1 = new Cookie("foo", "bar");
        Cookie c2 = new Cookie("hello", "world");
        String header = HttpUtils.createCookieHeaderValue(Arrays.asList(c1, c2));
        Match.equalsText(header, "foo=bar; hello=world");
    }

}
