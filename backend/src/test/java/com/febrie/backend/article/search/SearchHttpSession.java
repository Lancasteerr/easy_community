package com.febrie.backend.article.search;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 检索HTTP测试的独立Cookie会话，避免不同账号共享登录身份。 */
final class SearchHttpSession implements AutoCloseable {
    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    private final ObjectMapper json;
    private final String base;

    SearchHttpSession(int port, ObjectMapper json) {
        this.json = json;
        this.base = "http://127.0.0.1:" + port;
    }

    void login(String username, String password) throws Exception {
        String csrf = json.readTree(get("/api/auth/csrf").body()).path("token").asString();
        var request = HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .timeout(Duration.ofSeconds(10)).header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", username, "password", password))));
        assertEquals(200, http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Override public void close() { http.close(); }
}
