package com.febrie.backend.media;

import com.febrie.backend.media.application.*;
import com.febrie.backend.user.application.UserService;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真HTTP、Session、CSRF、MySQL和Redis集成，存储网络由独立服务商契约测试覆盖。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "IT_DB_PASSWORD", matches = ".+")
class MediaHttpTests {
    private static final String PREFIX = "ec:test:media-http:" + UUID.randomUUID();
    private static final String PASSWORD = "http-test-password-123";
    @LocalServerPort int port;
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;
    @MockitoBean ObjectStorageProvider storage;
    private long owner;
    private String username;
    private HttpClient http;
    private String csrf;

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        registry.add("app.auth.redis-prefix", () -> PREFIX);
        String url = System.getenv().getOrDefault("IT_MEDIA_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/easy_community_media_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        // 在Spring初始化和Flyway迁移之前拒绝业务库地址。
        if (!url.matches("jdbc:mysql://[^/]+/easy_community_media_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("文件HTTP测试只能连接专用数据库");
        }
        registry.add("spring.datasource.url", () -> url);
    }

    @BeforeEach
    void fixture() {
        when(storage.name()).thenReturn("minio");
        when(storage.authorizeUpload(anyString(), anyString(), anyString(), any())).thenAnswer(call ->
                new ObjectStorageProvider.UploadAuthorization("PUT", "https://upload.example.com/" + call.getArgument(1),
                        Map.of("If-None-Match", "*", "Content-Type", call.getArgument(2)), call.getArgument(3, Instant.class)));
        when(storage.head(anyString(), anyString())).thenReturn(Optional.of(new ObjectStorageProvider.ObjectMetadata(5, "image/png", "opaque")));
        username = "http_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        owner = Long.parseLong(users.bootstrapAdmin(username, PASSWORD).userId());
        http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    @AfterEach
    void cleanup() {
        http.close();
        jdbc.update("UPDATE users SET avatar_media_id=NULL WHERE id=?", owner);
        jdbc.update("DELETE r FROM media_reference r JOIN media_file f ON f.id=r.media_id WHERE f.owner_user_id=?", owner);
        jdbc.update("DELETE FROM media_file WHERE owner_user_id=?", owner);
        jdbc.update("DELETE FROM user_auth_identity WHERE user_id=?", owner);
        jdbc.update("DELETE FROM users WHERE id=?", owner);
        try (var keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + ":*").count(100).build())) {
            while (keys.hasNext()) { redis.delete(keys.next()); }
        }
    }

    @Test
    void uploadToAvatarRoundTripUsesFreshProfileAndKeepsSessionVersion() throws Exception {
        login();
        var grant = request("POST", "/api/media/uploads", Map.of("purpose", "AVATAR", "filename", "a.png", "contentType", "image/png", "size", 5), true);
        assertEquals(201, grant.statusCode());
        var upload = mapper.readTree(grant.body()); String id = upload.path("mediaId").asText();
        assertEquals("PUT", upload.path("upload").path("method").asText());
        assertEquals(200, request("POST", "/api/media/" + id + "/complete", null, true).statusCode());
        var avatar = request("PUT", "/api/users/me/avatar", Map.of("mediaId", id), true);
        assertEquals(200, avatar.statusCode());
        assertEquals(id, mapper.readTree(request("GET", "/api/users/me", null, true).body()).path("avatarMediaId").asText());
        assertEquals(1L, jdbc.queryForObject("SELECT auth_version FROM users WHERE id=?", Long.class, owner));
        assertEquals(204, request("DELETE", "/api/users/me/avatar", null, true).statusCode());
        assertEquals(204, request("DELETE", "/api/users/me/avatar", null, true).statusCode());
        assertTrue(mapper.readTree(request("GET", "/api/users/me", null, true).body()).path("avatarMediaId").isNull());
    }

    @Test
    void anonymousCsrfAndMissingMediaCannotBypassSecurity() throws Exception {
        assertEquals(401, request("GET", "/api/media/" + UUID.randomUUID(), null, false).statusCode());
        login();
        assertEquals(403, request("POST", "/api/media/uploads", Map.of(), false).statusCode());
        assertEquals(403, request("PUT", "/api/users/me/avatar", Map.of("mediaId", UUID.randomUUID().toString()), false).statusCode());
        assertEquals(404, request("GET", "/api/media/" + UUID.randomUUID(), null, true).statusCode());
        assertEquals(400, request("POST", "/api/media/uploads", Map.of("purpose", "AVATAR", "filename", "a.svg", "contentType", "image/svg+xml", "size", 5), true).statusCode());
    }

    private void login() throws Exception {
        csrf = mapper.readTree(request("GET", "/api/auth/csrf", null, false).body()).path("token").asText();
        var response = request("POST", "/api/auth/login", Map.of("username", username, "password", PASSWORD), true);
        assertEquals(200, response.statusCode(), response.body());
        csrf = mapper.readTree(request("GET", "/api/auth/csrf", null, false).body()).path("token").asText();
    }

    private HttpResponse<String> request(String method, String path, Object body, boolean withCsrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (withCsrf && csrf != null) { request.header("X-CSRF-TOKEN", csrf); }
        if (body != null) { request.header("Content-Type", "application/json"); }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
