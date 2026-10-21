package com.febrie.backend;

import com.febrie.backend.auth.application.SessionValidation;
import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.auth.infrastructure.config.AuthProperties;
import com.febrie.backend.auth.infrastructure.ratelimit.RequestLimits;
import com.febrie.backend.auth.infrastructure.session.ActiveSessions;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.UserService;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.application.dto.UserView;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
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
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 真MySQL、Redis、HTTP测试；只清理本次创建的账号和随机命名空间。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "IT_DB_PASSWORD", matches = ".+")
class BackendApplicationTests {
    static final String PREFIX = "ec:test:" + UUID.randomUUID();
    static final String PASSWORD = "correct-password-123";
    @LocalServerPort int port;
    @Autowired UserService users;
    @Autowired AccountRepository accounts;
    @Autowired StringRedisTemplate redis;
    @Autowired SessionRepository<? extends Session> repository;
    @Autowired SessionValidation validation;
    @Autowired ActiveSessions active;
    @Autowired RequestLimits limits;
    @Autowired AuthenticationManager authentication;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired ObjectMapper mapper;
    @Autowired AuthProperties properties;
    final List<Long> created = new ArrayList<>();
    SessionPrincipal admin;

    @DynamicPropertySource
    static void namespace(DynamicPropertyRegistry registry) { registry.add("app.auth.redis-prefix", () -> PREFIX); }

    @BeforeEach
    void fixture() {
        var view = users.bootstrapAdmin(name(), PASSWORD);
        created.add(Long.parseLong(view.userId()));
        admin = principal(view, 1);
    }

    @AfterEach
    void cleanup() {
        for (long id : created) {
            jdbc.update("DELETE FROM user_auth_identity WHERE user_id=?", id);
            jdbc.update("DELETE FROM users WHERE id=?", id);
        }
        // 不执行FLUSHDB或KEYS，绝不触及其他应用的数据。
        try (var keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + ":*").count(100).build())) {
            while (keys.hasNext()) { redis.delete(keys.next()); }
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginRotatesSessionAndCsrfAndKeepsRoleWithoutCredentials() throws Exception {
        var view = create(Role.PATROLLER);
        var browser = new Browser(); browser.csrf();
        String anonymousId = browser.sessionId();
        String oldCsrf = browser.token;
        assertEquals(200, browser.call("POST", "/api/auth/login", Map.of("username", view.username().toUpperCase(), "password", PASSWORD)).statusCode());
        assertNotEquals(anonymousId, browser.sessionId());
        assertNull(repository.findById(anonymousId));
        Session stored = repository.findById(browser.sessionId());
        assertNotNull(stored);
        var context = (org.springframework.security.core.context.SecurityContext) stored.getAttribute("SPRING_SECURITY_CONTEXT");
        var saved = (SessionPrincipal) context.getAuthentication().getPrincipal();
        assertEquals(Role.PATROLLER, saved.role());
        assertEquals(1, saved.authVersion());
        assertNull(context.getAuthentication().getCredentials());
        assertEquals("set", redis.type(active.key(saved)).code());
        assertTrue(active.contains(saved, browser.sessionId()));
        browser.token = oldCsrf;
        assertEquals(403, browser.call("POST", "/api/auth/logout", null).statusCode());
        browser.csrf();
        assertNotNull(repository.findById(browser.sessionId()).getAttribute("auth.csrfToken"));
        var me = browser.call("GET", "/api/users/me", null);
        assertEquals(200, me.statusCode());
        assertEquals("PATROLLER", mapper.readTree(me.body()).path("role").asText());
        assertFalse(me.body().contains("password"));
    }

    @Test
    void csrfRequiredAndCannotBeUsedAcrossSessions() throws Exception {
        var one = new Browser();
        assertEquals(403, one.call("POST", "/api/auth/login", Map.of("username", admin.username(), "password", PASSWORD)).statusCode());
        one.csrf();
        var two = new Browser(); two.csrf(); two.token = one.token;
        assertEquals(403, two.call("POST", "/api/auth/login", Map.of("username", admin.username(), "password", PASSWORD)).statusCode());
        assertEquals(401, new Browser().call("GET", "/api/users/me", null).statusCode());
    }

    @Test
    void logoutOnlyRevokesCurrentDeviceAndLateHashCannotRestoreIt() throws Exception {
        var view = create(Role.USER);
        var one = new Browser(); one.login(view.username());
        var two = new Browser(); two.login(view.username());
        String oldId = one.sessionId();
        var oldData = redis.opsForHash().entries(PREFIX + ":session:v2:sessions:" + oldId);
        assertEquals(204, one.call("POST", "/api/auth/logout", null).statusCode());
        // 模拟已通过鉴权的旧请求在退出后回写完整Hash，Set仍然拒绝它。
        redis.opsForHash().putAll(PREFIX + ":session:v2:sessions:" + oldId, oldData);
        redis.expire(PREFIX + ":session:v2:sessions:" + oldId, java.time.Duration.ofMinutes(2));
        assertThrows(ApiException.class, () -> validation.validate(principal(view, 1), oldId));
        assertEquals(200, two.call("GET", "/api/users/me", null).statusCode());
    }

    @Test
    void changePasswordRevokesAllDevicesAndOldPassword() throws Exception {
        var view = create(Role.USER);
        var one = new Browser(); one.login(view.username());
        var two = new Browser(); two.login(view.username());
        assertEquals(204, one.call("PUT", "/api/users/me/password", Map.of("oldPassword", PASSWORD, "newPassword", "new-password-456")).statusCode());
        assertEquals(2, accounts.authState(Long.parseLong(view.userId())).orElseThrow().authVersion());
        assertEquals(401, two.call("GET", "/api/users/me", null).statusCode());
        var oldLogin = new Browser(); oldLogin.csrf();
        assertEquals(401, oldLogin.call("POST", "/api/auth/login", Map.of("username", view.username(), "password", PASSWORD)).statusCode());
        var newLogin = new Browser(); newLogin.csrf();
        assertEquals(200, newLogin.call("POST", "/api/auth/login", Map.of("username", view.username(), "password", "new-password-456")).statusCode());
    }

    @Test
    void roleVersionAndDeleteRejectOldSessionsAndProtectAdmins() throws Exception {
        var view = create(Role.USER);
        var browser = new Browser(); browser.login(view.username());
        assertEquals(403, browser.call("POST", "/api/admin/users", Map.of("username", name(), "password", PASSWORD)).statusCode());
        users.changeRole(Long.parseLong(view.userId()), Role.PATROLLER);
        assertEquals(401, browser.call("GET", "/api/users/me", null).statusCode());
        var changed = new Browser(); changed.login(view.username());
        assertEquals("PATROLLER", mapper.readTree(changed.call("GET", "/api/users/me", null).body()).path("role").asText());
        assertEquals(403, changed.call("POST", "/api/admin/users", Map.of("username", name(), "password", PASSWORD)).statusCode());
        var operator = new Browser(); operator.login(admin.username());
        assertEquals(403, operator.call("DELETE", "/api/admin/users/" + admin.userId(), null).statusCode());
        assertEquals(403, operator.call("POST", "/api/admin/users", Map.of("username", name(), "password", PASSWORD, "role", "ADMIN")).statusCode());
        assertEquals(204, operator.call("DELETE", "/api/admin/users/" + view.userId(), null).statusCode());
        assertEquals(204, operator.call("DELETE", "/api/admin/users/" + view.userId(), null).statusCode());
        assertEquals(401, changed.call("GET", "/api/users/me", null).statusCode());
        assertEquals(409, operator.call("POST", "/api/admin/users", Map.of("username", view.username(), "password", PASSWORD)).statusCode());
        assertEquals(404, operator.call("DELETE", "/api/admin/users/9223372036854775807", null).statusCode());
    }

    @Test
    void administratorCreatesDefaultRoleAndRejectsNormalizedDuplicate() throws Exception {
        var operator = new Browser(); operator.login(admin.username());
        String username = name();
        var response = operator.call("POST", "/api/admin/users", Map.of("username", " " + username.toUpperCase() + " ", "password", PASSWORD));
        assertEquals(201, response.statusCode(), response.body());
        long id = mapper.readTree(response.body()).path("userId").asLong(); created.add(id);
        assertEquals("USER", mapper.readTree(response.body()).path("role").asText());
        assertEquals(409, operator.call("POST", "/api/admin/users", Map.of("username", username, "password", PASSWORD)).statusCode());
        assertEquals(400, operator.call("POST", "/api/admin/users", Map.of("username", name(), "password", "中".repeat(25))).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_auth_identity WHERE user_id=?", Integer.class, id));
        assertThrows(ApiException.class, () -> users.bootstrapAdmin(name(), PASSWORD));
    }

    @Test
    void lateOldPasswordAuthenticationCannotUseNewVersionOrRecreatedOldBucket() throws Exception {
        var view = create(Role.USER);
        var snapshotReady = new CountDownLatch(1);
        var changed = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var lateLogin = executor.submit(() -> {
                var verified = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(view.username(), PASSWORD));
                snapshotReady.countDown();
                assertTrue(changed.await(10, TimeUnit.SECONDS));
                var principal = (SessionPrincipal) verified.getPrincipal();
                var session = repository.createSession();
                session.setAttribute("auth.expiresAt", principal.expiresAt().toEpochMilli());
                save(session); active.register(principal, session.getId());
                assertThrows(ApiException.class, () -> validation.validate(principal, session.getId()));
                return principal;
            });
            assertTrue(snapshotReady.await(10, TimeUnit.SECONDS));
            users.changePassword(actor(principal(view, 1)), PASSWORD, "updated-password-456");
            changed.countDown();
            assertEquals(1, lateLogin.get(10, TimeUnit.SECONDS).authVersion());
            assertEquals(2, accounts.authState(Long.parseLong(view.userId())).orElseThrow().authVersion());
        }
    }

    @Test
    void transactionRollbackKeepsHashAndVersionTogether() {
        var view = create(Role.USER);
        String hash = accounts.findByUsername(view.username()).orElseThrow().passwordHash();
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            accounts.updatePassword(Long.parseLong(view.userId()), "not-a-real-hash");
            throw new IllegalStateException("模拟事务失败");
        }));
        var unchanged = accounts.findByUsername(view.username()).orElseThrow();
        assertEquals(hash, unchanged.passwordHash());
        assertEquals(1, unchanged.authVersion());
        String rolledBack = name();
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            accounts.insert(rolledBack, Role.USER, hash);
            throw new IllegalStateException("模拟创建身份之后失败");
        }));
        assertTrue(accounts.findByUsername(rolledBack).isEmpty());
    }

    @Test
    void exactExpiryAndCleanupRemoveMembersWithoutReRegistration() {
        var view = create(Role.USER);
        var expired = new SessionPrincipal(Long.parseLong(view.userId()), view.username(), Role.USER, 1,
                Instant.now().minusSeconds(8000), Instant.now().minusSeconds(1));
        var session = repository.createSession();
        session.setAttribute("auth.expiresAt", expired.expiresAt().toEpochMilli());
        save(session); active.register(expired, session.getId()); active.register(expired, "already-gone");
        assertThrows(ApiException.class, () -> validation.validate(expired, session.getId()));
        active.cleanup();
        assertFalse(active.contains(expired, session.getId()));
        assertFalse(active.contains(expired, "already-gone"));
    }

    @Test
    void countersAreAtomicUnderConcurrencyAndHaveTtl() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++) {
                attempts.add(executor.submit(() -> {
                    try { limits.consume("concurrent", "shared", 3); return true; }
                    catch (ApiException denied) { assertEquals(429, denied.status().value()); return false; }
                }));
            }
            int accepted = 0;
            for (var attempt : attempts) { if (attempt.get()) { accepted++; } }
            assertEquals(3, accepted);
        }
        try (var keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + ":auth:request:concurrent:*").build())) {
            assertTrue(keys.hasNext()); assertTrue(redis.getExpire(keys.next()) > 0);
        }
        for (int i = 0; i < properties.accountFailures(); i++) { limits.failed("missing", "192.0.2.1"); }
        assertThrows(ApiException.class, () -> limits.checkFailures("missing", "192.0.2.2"));
    }

    @Test
    void concurrentNormalizedCreationCommitsOnlyOneUserAndIdentity() throws Exception {
        String username = name();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<UserView>>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try { return users.createManaged(actor(admin), username.toUpperCase(), PASSWORD, Role.USER); }
                    catch (ApiException duplicate) { assertEquals(409, duplicate.status().value()); return null; }
                }));
            }
            start.countDown();
            int successful = 0;
            for (var future : futures) {
                var result = future.get(10, TimeUnit.SECONDS);
                if (result != null) { successful++; created.add(Long.parseLong(result.userId())); }
            }
            assertEquals(1, successful);
        }
        long id = accounts.findByUsername(username).orElseThrow().userId();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_auth_identity WHERE user_id=?", Integer.class, id));
    }

    @Test
    void httpUnknownLoginFailuresReturnRetryAfterAndDoNotExposeAccountExistence() throws Exception {
        var browser = new Browser(); browser.csrf();
        String username = name();
        for (int i = 0; i < properties.accountFailures(); i++) {
            var response = browser.call("POST", "/api/auth/login", Map.of("username", username, "password", "wrong-password"));
            assertEquals(401, response.statusCode());
            assertEquals("INVALID_CREDENTIALS", mapper.readTree(response.body()).path("code").asText());
        }
        var blocked = browser.call("POST", "/api/auth/login", Map.of("username", username, "password", "wrong-password"));
        assertEquals(429, blocked.statusCode());
        assertTrue(Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow()) > 0);
    }

    private UserView create(Role role) {
        var view = users.createManaged(actor(admin), name(), PASSWORD, role);
        created.add(Long.parseLong(view.userId())); return view;
    }

    static String name() { return "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); }

    /** 从测试认证身份提取用户服务所需的信息，不向服务传入会话对象。 */
    static UserActor actor(SessionPrincipal principal) {
        return new UserActor(principal.userId(), principal.role(), principal.authVersion());
    }

    static SessionPrincipal principal(UserView view, long version) {
        var now = Instant.now();
        return new SessionPrincipal(Long.parseLong(view.userId()), view.username(), view.role(), version, now, now.plusSeconds(7200));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void save(Session session) { ((SessionRepository) repository).save(session); }

    /** 模拟浏览器Cookie和CSRF往返，经过真实Servlet容器及Redis序列化。 */
    class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String token;
        String header = "X-CSRF-TOKEN";

        HttpResponse<String> call(String method, String path, Object body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (token != null) { request.header(header, token); }
            if (body != null) { request.header("Content-Type", "application/json"); }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        void csrf() throws Exception {
            var response = call("GET", "/api/auth/csrf", null);
            assertEquals(200, response.statusCode(), response.body());
            var body = mapper.readTree(response.body());
            token = body.path("token").asText(); header = body.path("headerName").asText();
        }

        void login(String username) throws Exception {
            csrf();
            var response = call("POST", "/api/auth/login", Map.of("username", username, "password", PASSWORD));
            assertEquals(200, response.statusCode(), response.body()); csrf();
        }

        String sessionId() {
            String cookie = cookies.getCookieStore().getCookies().stream().filter(value -> value.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
            return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
        }
    }
}
