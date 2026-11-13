package com.febrie.backend.user.web;

import com.febrie.backend.media.application.UploadStorage;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.persistence.FileRepository;
import com.febrie.backend.support.PersistenceTestConfiguration;
import com.febrie.backend.support.persistence.DatabaseFixtureMapper;
import com.febrie.backend.user.application.*;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.cache.UserBasicCacheStore;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实HTTP、Session、MySQL和Redis验证资料边界；仅使用专用测试库及随机缓存前缀。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@com.febrie.backend.support.WithoutRabbitConsumer
@ActiveProfiles("test")
@Import(PersistenceTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "IT_ARTICLE", matches = "true")
class UserProfileHttpIntegrationTests {
    private static final String PREFIX = "ec:test:userprofile:" + UUID.randomUUID();
    private static final String PASSWORD = "profile-test-password";
    @LocalServerPort int port;
    @Autowired PasswordEncoder encoder;
    @Autowired TransactionTemplate transaction;
    @Autowired DatabaseFixtureMapper fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;
    @Autowired UserProfileService profiles;
    @Autowired UserBasicQueryService basics;
    @Autowired FileRepository files;
    @MockitoSpyBean AccountRepository accounts;
    @MockitoSpyBean UserBasicReader reader;
    @MockitoSpyBean UserBasicCacheStore cache;
    @MockitoBean UploadStorage storage;
    private final List<Long> owners = new ArrayList<>();
    private final List<Browser> browsers = new ArrayList<>();
    private Browser owner, viewer;
    private long ownerId;

    @DynamicPropertySource static void dedicated(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault("IT_DB_URL", "");
        if (!url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")) {
            throw new IllegalArgumentException("用户资料集成测试仅允许专用测试库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("app.auth.redis-prefix", () -> PREFIX);
    }

    @BeforeEach void prepare() throws Exception {
        owner = login(Role.USER);
        ownerId = owner.id;
        viewer = login(Role.ADMIN);
        clearInvocations(accounts, reader, cache);
    }

    @AfterEach void clean() {
        reset(accounts, reader, cache);
        browsers.forEach(Browser::close);
        for (long id : owners) {
            fixtures.clearAvatar(id);
            fixtures.deleteOwnedReferences(id);
            fixtures.deleteOwnedFiles(id);
            fixtures.deleteIdentities(id);
            fixtures.deleteUser(id);
        }
        try (var keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + ":*").count(100).build())) {
            while (keys.hasNext()) { redis.delete(keys.next()); }
        }
    }

    @Test void warmMeAndTargetShareOneProjectionWithoutProfileSql() throws Exception {
        var me = json(owner.call("GET", "/api/users/me", null, false), 200);
        assertEquals("", me.path("bio").asString());
        assertEquals("USER", me.path("role").asString());
        assertTrue(me.path("avatarUrl").isNull());
        var target = json(viewer.call("GET", target(), null, false), 200);
        assertEquals(Long.toString(ownerId), target.path("userId").asString());
        assertEquals(5, target.size());
        assertFalse(target.has("role"));
        assertEquals("", basics.get(ownerId).orElseThrow().bio());
        verify(reader, times(1)).load(List.of(ownerId));
        verify(accounts, never()).profile(anyLong(), anyBoolean());
    }

    @Test void editIsStrictSessionScopedAndRequiresCsrf() throws Exception {
        assertEquals(403, owner.call("PATCH", "/api/users/edit", Map.of("bio", "x"), false).statusCode());
        for (Object body : List.of(Map.of(), Map.of("bio", 12), Map.of("bio", true), Map.of("bio", List.of()),
                Map.of("avatarMediaId", "x"), Map.of("bio", "x", "userId", viewer.id),
                Map.of("bio", "x", "role", "ADMIN"), Map.of("bio", "a\nb"), Map.of("bio", "中".repeat(51)))) {
            assertEquals(400, owner.call("PATCH", "/api/users/edit", body, true).statusCode(), body.toString());
        }
        var response = json(owner.call("PATCH", "/api/users/edit", Map.of("bio", " 😀社区 "), true), 200);
        assertEquals("😀社区", response.path("bio").asString());
        assertEquals("😀社区", json(viewer.call("GET", target(), null, false), 200).path("bio").asString());
        assertEquals("", accounts.profile(viewer.id, false).orElseThrow().bio());
        assertEquals(1, fixtures.authVersion(ownerId));
        clearInvocations(cache, accounts);
        json(owner.call("PATCH", "/api/users/edit", Map.of("bio", " 😀社区 "), true), 200);
        verify(accounts, never()).updateBio(anyLong(), anyString());
        verify(cache, never()).invalidate(anyLong(), anyString());
        assertEquals("", json(owner.call("PATCH", "/api/users/edit", Collections.singletonMap("bio", null), true), 200).path("bio").asString());
    }

    @Test void anonymousCannotReadProfilesOrEditAndMissingUsesNegativeCache() throws Exception {
        Browser guest = browser(); guest.csrf();
        assertEquals(401, guest.call("GET", target(), null, false).statusCode());
        assertEquals(401, guest.call("GET", "/api/users/me", null, false).statusCode());
        assertEquals(401, guest.call("PATCH", "/api/users/edit", Map.of("bio", "x"), true).statusCode());
        String missing = "/api/users/9223372036854775807";
        assertEquals("USER_NOT_FOUND", json(viewer.call("GET", missing, null, false), 404).path("code").asString());
        json(viewer.call("GET", missing, null, false), 404);
        verify(reader, times(1)).load(List.of(Long.MAX_VALUE));
        assertEquals(400, viewer.call("GET", "/api/users/0", null, false).statusCode());
        assertEquals(400, viewer.call("GET", "/api/users/9223372036854775808", null, false).statusCode());
    }

    @Test void deletionKeepsBioButDetachesAvatarAndRevokesSession() throws Exception {
        json(owner.call("PATCH", "/api/users/edit", Map.of("bio", "历史简介"), true), 200);
        String avatar = readyAvatar();
        var set = json(owner.call("PUT", "/api/users/me/avatar", Map.of("mediaId", avatar), true), 200);
        assertEquals("历史简介", set.path("bio").asString());
        assertFalse(set.path("avatarUrl").isNull());
        json(viewer.call("GET", target(), null, false), 200);
        assertEquals(204, viewer.call("DELETE", "/api/admin/users/" + ownerId, null, true).statusCode());
        var deleted = json(viewer.call("GET", target(), null, false), 200);
        assertEquals("历史简介", deleted.path("bio").asString());
        assertTrue(deleted.path("avatarMediaId").isNull());
        assertTrue(deleted.path("avatarUrl").isNull());
        assertTrue(files.references(ReferenceType.USER_AVATAR, ownerId).isEmpty());
        assertNotNull(files.find(avatar).orElseThrow().nextActionAt());
        verifyNoInteractions(storage);
        assertEquals(401, owner.call("PATCH", "/api/users/edit", Map.of("bio", "禁止"), true).statusCode());
        assertEquals(401, owner.call("GET", "/api/users/me", null, false).statusCode());
    }

    @Test void disabledTargetStillDisplaysButCannotUseMe() throws Exception {
        json(owner.call("PATCH", "/api/users/edit", Map.of("bio", "停用资料"), true), 200);
        json(viewer.call("GET", target(), null, false), 200);
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", ownerId);
        assertEquals("停用资料", json(viewer.call("GET", target(), null, false), 200).path("bio").asString());
        // 冷缓存同样允许读取停用目标，不能仅靠停用前的旧缓存通过断言。
        redis.delete(cache.key(ownerId, "data"));
        assertEquals("停用资料", json(viewer.call("GET", target(), null, false), 200).path("bio").asString());
        assertEquals(401, owner.call("GET", "/api/users/me", null, false).statusCode());
    }

    @Test void outerRollbackKeepsBioAndWarmCacheUnchanged() {
        basics.get(ownerId);
        String before = redis.opsForValue().get(cache.key(ownerId, "data"));
        clearInvocations(cache);
        var outer = new TransactionTemplate(transaction.getTransactionManager());
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(status -> {
            assertEquals("回滚", profiles.editBio(actor(), "回滚").bio());
            assertEquals("回滚", accounts.profile(ownerId, false).orElseThrow().bio());
            verify(cache, never()).tryLock(anyLong());
            status.setRollbackOnly();
        });
        assertEquals("", accounts.profile(ownerId, false).orElseThrow().bio());
        assertEquals(before, redis.opsForValue().get(cache.key(ownerId, "data")));
        verify(cache, never()).invalidate(anyLong(), anyString());
    }

    @Test void concurrentBioAndAvatarUpdatesPreserveBothFields() throws Exception {
        String avatar = readyAvatar();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var first = executor.submit(() -> { start.await(); return profiles.editBio(actor(), "甲"); });
            var second = executor.submit(() -> { start.await(); return profiles.editBio(actor(), "乙"); });
            var image = executor.submit(() -> { start.await(); return profiles.setAvatar(actor(), avatar); });
            start.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS); image.get(10, TimeUnit.SECONDS);
        }
        var current = accounts.profile(ownerId, false).orElseThrow();
        assertTrue(Set.of("甲", "乙").contains(current.bio()));
        assertEquals(avatar, current.avatarMediaId());
        assertEquals(Set.of(avatar), files.references(ReferenceType.USER_AVATAR, ownerId));
        assertEquals(current.bio(), json(owner.call("GET", "/api/users/me", null, false), 200).path("bio").asString());
        assertEquals(1, current.authVersion());
    }

    @Test void concurrentMeAndTargetUseSameOperationLockAndSingleSource() throws Exception {
        var loaded = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var contended = new CountDownLatch(1);
        doAnswer(call -> {
            var result = call.callRealMethod(); loaded.countDown();
            if (!proceed.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("回源屏障超时"); }
            return result;
        }).when(reader).load(List.of(ownerId));
        doAnswer(call -> {
            var result = call.callRealMethod();
            if (result == null) { contended.countDown(); }
            return result;
        }).when(cache).tryLock(ownerId);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> owner.call("GET", "/api/users/me", null, false));
            try {
                assertTrue(loaded.await(5, TimeUnit.SECONDS));
                var second = executor.submit(() -> viewer.call("GET", target(), null, false));
                assertTrue(contended.await(5, TimeUnit.SECONDS));
                proceed.countDown();
                json(first.get(5, TimeUnit.SECONDS), 200);
                json(second.get(5, TimeUnit.SECONDS), 200);
            } finally { proceed.countDown(); }
        }
        verify(reader, times(1)).load(List.of(ownerId));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(cache.key(ownerId, "cache-lock"))));
    }

    @Test void failedInvalidationKeepsWriteSuccessAndEventuallyRefreshes() throws Exception {
        json(owner.call("GET", "/api/users/me", null, false), 200);
        doThrow(new org.springframework.data.redis.RedisSystemException("模拟缓存删除故障", null))
                .when(cache).invalidate(eq(ownerId), anyString());
        assertEquals("已提交", json(owner.call("PATCH", "/api/users/edit", Map.of("bio", "已提交"), true), 200).path("bio").asString());
        assertEquals("已提交", accounts.profile(ownerId, false).orElseThrow().bio());
        assertEquals("", json(owner.call("GET", "/api/users/me", null, false), 200).path("bio").asString());
        verify(cache, times(3)).invalidate(eq(ownerId), anyString());
        redis.expire(cache.key(ownerId, "data"), java.time.Duration.ofMillis(50));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() ->
                !Boolean.TRUE.equals(redis.hasKey(cache.key(ownerId, "data"))));
        reset(cache);
        assertEquals("已提交", json(viewer.call("GET", target(), null, false), 200).path("bio").asString());
    }

    @Test void cacheAndSqlFailuresReturn503InsteadOf404() throws Exception {
        redis.opsForValue().set(cache.key(ownerId, "data"), "D{broken");
        json(viewer.call("GET", target(), null, false), 503);
        redis.delete(cache.key(ownerId, "data"));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("模拟主库故障"))
                .when(reader).load(List.of(ownerId));
        json(viewer.call("GET", target(), null, false), 503);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(cache.key(ownerId, "data"))));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(cache.key(ownerId, "cache-lock"))));
    }

    private UserActor actor() { return new UserActor(ownerId, Role.USER, 1); }
    private String target() { return "/api/users/" + ownerId; }
    private JsonNode json(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private String readyAvatar() {
        String id = UUID.randomUUID().toString();
        files.insert(new FileRecord(id, ownerId, MediaPurpose.AVATAR, MediaStatus.ISSUED, null, null,
                Instant.now(), Instant.now().plusSeconds(7200)));
        fixtures.makeReady(MediaIds.bytes(id), 100);
        return id;
    }

    private Browser login(Role role) throws Exception {
        String username = "up_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        long id = Objects.requireNonNull(transaction.execute(status -> accounts.insert(username, role, encoder.encode(PASSWORD))));
        owners.add(id);
        Browser browser = browser(); browser.id = id; browser.csrf();
        json(browser.call("POST", "/api/auth/login", Map.of("username", username, "password", PASSWORD), true), 200);
        browser.csrf();
        return browser;
    }

    private Browser browser() { Browser value = new Browser(); browsers.add(value); return value; }

    /** 浏览器客户端保留Cookie并显式选择是否提交CSRF，验证真实安全过滤链。 */
    private final class Browser implements AutoCloseable {
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        private long id;
        private String csrf;
        void csrf() throws Exception { csrf = json(call("GET", "/api/auth/csrf", null, false), 200).path("token").asString(); }
        HttpResponse<String> call(String method, String path, Object body, boolean token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (token) { request.header("X-CSRF-TOKEN", csrf); }
            if (body != null) { request.header("Content-Type", "application/json"); }
            return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() { http.close(); }
    }
}
