package com.febrie.backend.config;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实Tomcat代理处理器，验证只接受可信连接的IP和HTTPS信息。 */
class ForwardedIpTests {
    @Test
    void trustedNginxSuppliesRealAddressAndExternalHttps() throws Exception {
        check("172.31.0.2", "198.51.100.7", "198.51.100.7", true);
    }

    @Test
    void untrustedClientCannotSpoofForwardedAddressOrScheme() throws Exception {
        check("192.0.2.8", "198.51.100.7", "192.0.2.8", false);
    }

    @Test
    void appendedSpoofedLeftmostAddressIsNotChosen() throws Exception {
        check("172.31.0.2", "203.0.113.99, 198.51.100.7", "198.51.100.7", true);
    }

    private void check(String peer, String forwarded, String expected, boolean secure) throws Exception {
        var coyote = new org.apache.coyote.Request();
        var request = new Request(new Connector(), coyote);
        coyote.scheme().setString("http");
        coyote.serverName().setString("backend");
        coyote.setServerPort(8080);
        request.setRemoteAddr(peer);
        request.setRemoteHost(peer);
        coyote.getMimeHeaders().addValue("X-Forwarded-For").setString(forwarded);
        coyote.getMimeHeaders().addValue("X-Forwarded-Proto").setString("https");
        var valve = new RemoteIpValve();
        valve.setInternalProxies("172\\.31\\.0\\.2");
        valve.setTrustedProxies("(?!)");
        valve.setRemoteIpHeader("X-Forwarded-For");
        valve.setProtocolHeader("X-Forwarded-Proto");
        valve.setNext(new ValveBase() {
            @Override
            public void invoke(Request current, Response response) throws IOException, ServletException {
                assertEquals(expected, current.getRemoteAddr());
                assertEquals(secure, current.isSecure());
            }
        });
        valve.invoke(request, new Response(new org.apache.coyote.Response()));
        assertEquals(peer, request.getRemoteAddr());
    }
}
