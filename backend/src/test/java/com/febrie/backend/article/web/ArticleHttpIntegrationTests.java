package com.febrie.backend.article.web;

import com.febrie.backend.article.application.*;
import com.febrie.backend.article.infrastructure.cache.ArticleCache;
import com.febrie.backend.media.application.UploadStorage;
import com.febrie.backend.support.PersistenceTestConfiguration;
import com.febrie.backend.support.persistence.*;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真HTTP/Session/CSRF与MySQL/Redis接入；只清理本次用户、Tag和随机文章前缀。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PersistenceTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "IT_ARTICLE", matches = "true")
class ArticleHttpIntegrationTests {
    private static final String PREFIX = "ec:test:articlehttp:" + UUID.randomUUID();
    private static final String PASSWORD = "article-test-password";
    @LocalServerPort int port;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired TransactionTemplate transaction;
    @Autowired DatabaseFixtureMapper fixtures;
    @Autowired ArticleFixtureMapper articleFixtures;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;
    @Autowired ArticleQueryService queries;
    @MockitoBean UploadStorage storage;
    @MockitoSpyBean ArticleCache cache;
    @MockitoSpyBean ArticleReader reader;
    private final List<Long> owners = new ArrayList<>(), tags = new ArrayList<>();
    private final List<Browser> browsers = new ArrayList<>();

    @DynamicPropertySource static void dedicated(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault("IT_DB_URL", "jdbc:mysql://127.0.0.1:3306/easy_community_article_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        if (!url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")) { throw new IllegalArgumentException("仅允许文章测试专用库"); }
        registry.add("spring.datasource.url", () -> url);
        registry.add("app.auth.redis-prefix", () -> PREFIX);
    }
    @AfterEach void clean() {
        reset(cache, reader);
        browsers.forEach(Browser::close);
        for (long id : owners) {
            articleFixtures.deleteTagsForAuthor(id); articleFixtures.deleteArticlesForAuthor(id);
            fixtures.deleteOwnedReferences(id); fixtures.deleteOwnedFiles(id);
            fixtures.deleteIdentities(id); fixtures.deleteUser(id);
        }
        tags.forEach(articleFixtures::deleteTag);
        try (var keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + ":*").count(100).build())) {
            while (keys.hasNext()) { redis.delete(keys.next()); }
        }
    }
    @Test void visitorReadsAndAuthorCanSaveEditAndIrreversiblyDelete() throws Exception {
        Browser author = login(Role.USER), visitor = browser();
        var created = author.call("POST", "/api/articles", save(null, "first"), true);
        assertEquals(200, created.statusCode(), created.body());
        String id = mapper.readTree(created.body()).get("articleId").asString();
        var detail = visitor.call("GET", "/api/articles/" + id, null, false);
        assertEquals(200, detail.statusCode(), detail.body());
        assertEquals("no-store", detail.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(mapper.readTree(detail.body()).path("contentHtml").asString().contains("&lt;safe&gt;"));
        assertEquals(401, visitor.call("GET", "/api/articles/" + id + "/edit", null, false).statusCode());
        assertEquals(401, visitor.call("GET", "/api/users/me/articles", null, false).statusCode());
        assertEquals(401, visitor.call("GET", "/api/tags", null, false).statusCode());
        assertEquals(403, author.call("POST", "/api/articles", save(id, "second"), false).statusCode());
        var modified = author.call("POST", "/api/articles", save(id, "second"), true);
        assertEquals(id, mapper.readTree(modified.body()).get("articleId").asString());
        var edit = mapper.readTree(author.call("GET", "/api/articles/" + id + "/edit", null, false).body());
        assertEquals("second", edit.get("title").asString());
        assertEquals(1, edit.path("content").path("schemaVersion").asInt());
        var page = mapper.readTree(author.call("GET", "/api/users/me/articles", null, false).body());
        assertEquals(1, page.path("items").size());
        assertFalse(page.path("items").get(0).has("hidden"));
        assertFalse(page.path("items").get(0).has("contentHtml"));
        assertEquals(204, author.call("DELETE", "/api/articles/" + id, null, true).statusCode());
        assertEquals(204, author.call("DELETE", "/api/articles/" + id, null, true).statusCode());
        assertEquals(404, visitor.call("GET", "/api/articles/" + id, null, false).statusCode());
        assertEquals(409, author.call("GET", "/api/articles/" + id + "/edit", null, false).statusCode());
        assertEquals(409, author.call("POST", "/api/articles", save(id, "resurrect"), true).statusCode());
        assertEquals(0, mapper.readTree(author.call("GET", "/api/users/me/articles", null, false).body()).path("total").asInt());
    }
    @Test void rolesDoNotGrantEditingAndUnknownTargetsNeverCreate() throws Exception {
        Browser owner = login(Role.USER), other = login(Role.USER), admin = login(Role.ADMIN), patrol = login(Role.PATROLLER);
        String id = mapper.readTree(owner.call("POST", "/api/articles", save(null, "x"), true).body()).path("articleId").asString();
        assertEquals(403, other.call("DELETE", "/api/articles/" + id, null, true).statusCode());
        assertEquals(403, admin.call("POST", "/api/articles", save(id, "admin edit"), true).statusCode());
        assertEquals(403, patrol.call("POST", "/api/articles", save(id, "patrol edit"), true).statusCode());
        assertEquals(204, patrol.call("DELETE", "/api/articles/" + id, null, true).statusCode());
        assertEquals(403, other.call("DELETE", "/api/articles/" + id, null, true).statusCode());
        assertEquals(204, admin.call("DELETE", "/api/articles/" + id, null, true).statusCode());
        assertEquals(404, owner.call("POST", "/api/articles", save("9223372036854775807", "missing"), true).statusCode());
        var numericId = save(null, "x"); numericId.put("articleId", 12);
        assertEquals(400, owner.call("POST", "/api/articles", numericId, true).statusCode());
        var forged = save(null, "x"); forged.put("authorId", "1");
        assertEquals(400, owner.call("POST", "/api/articles", forged, true).statusCode());
    }
    @Test void tagMetadataAndWarmDetailRequireNoArticleBackfill() throws Exception {
        Browser admin = login(Role.ADMIN), owner = login(Role.USER), visitor = browser();
        var tagResponse = admin.call("POST", "/api/admin/tags", Map.of("name", " Tag_" + UUID.randomUUID().toString().substring(0, 20)), true);
        assertEquals(201, tagResponse.statusCode(), tagResponse.body());
        String tag = mapper.readTree(tagResponse.body()).path("tagId").asString(); tags.add(Long.parseLong(tag));
        var command = save(null, "tagged"); command.put("tagIds", List.of(tag));
        String id = mapper.readTree(owner.call("POST", "/api/articles", command, true).body()).path("articleId").asString();
        assertEquals(200, visitor.call("GET", "/api/articles/" + id, null, false).statusCode());
        clearInvocations(reader);
        var warm = visitor.call("GET", "/api/articles/" + id, null, false);
        assertEquals(tag, mapper.readTree(warm.body()).path("tags").get(0).path("tagId").asString());
        verifyNoInteractions(reader);
    }
    @Test void postCommitRedisFailureKeepsSqlSuccess() throws Exception {
        Browser owner = login(Role.USER);
        doThrow(new org.springframework.data.redis.RedisSystemException("注入缓存故障", null)).when(cache).invalidate(anyLong(), anyString());
        doThrow(new org.springframework.data.redis.RedisSystemException("注入缓存故障", null)).when(cache).hide(anyLong(), anyString());
        var created = owner.call("POST", "/api/articles", save(null, "committed"), true);
        assertEquals(200, created.statusCode());
        String id = mapper.readTree(created.body()).path("articleId").asString();
        assertEquals("committed", mapper.readTree(owner.call("GET", "/api/articles/" + id + "/edit", null, false).body()).path("title").asString());
        verify(cache, times(3)).invalidate(eq(Long.parseLong(id)), anyString());
        assertEquals(204, owner.call("DELETE", "/api/articles/" + id, null, true).statusCode());
        verify(cache, times(3)).hide(eq(Long.parseLong(id)), anyString());
    }
    @Test void oldDetailBackfillIsInvalidatedAfterSqlCommitUnderSharedLock() throws Exception {
        Browser owner = login(Role.USER);
        long id = Long.parseLong(mapper.readTree(owner.call("POST", "/api/articles", save(null, "old"), true).body()).path("articleId").asString());
        CountDownLatch oldRead = new CountDownLatch(1), continueRead = new CountDownLatch(1), writerWaits = new CountDownLatch(1), readDone = new CountDownLatch(1);
        doAnswer(call -> {
            var loaded = call.callRealMethod();
            oldRead.countDown();
            if (!continueRead.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("旧回填屏障超时"); }
            return loaded;
        }).when(reader).detail(id);
        doAnswer(call -> {
            Object token = call.callRealMethod();
            if (token == null) {
                writerWaits.countDown();
                if (!readDone.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("等待旧读释放锁超时"); }
            }
            return token;
        }).when(cache).tryLock(id);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var old = executor.submit(() -> { try { return queries.detail(id); } finally { readDone.countDown(); } });
            assertTrue(oldRead.await(5, TimeUnit.SECONDS));
            var write = executor.submit(() -> owner.call("POST", "/api/articles", save(Long.toString(id), "new"), true));
            try { assertTrue(writerWaits.await(5, TimeUnit.SECONDS)); }
            finally { continueRead.countDown(); }
            assertEquals("old", old.get(10, TimeUnit.SECONDS).title());
            assertEquals(200, write.get(10, TimeUnit.SECONDS).statusCode());
        } finally { continueRead.countDown(); readDone.countDown(); }
        reset(reader, cache);
        assertEquals("new", queries.detail(id).title());
    }
    private Browser login(Role role) throws Exception {
        String name = "ah_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        long id = Objects.requireNonNull(transaction.execute(status -> accounts.insert(name, role, encoder.encode(PASSWORD))));
        owners.add(id);
        Browser browser = browser(); browser.csrf();
        assertEquals(200, browser.call("POST", "/api/auth/login", Map.of("username", name, "password", PASSWORD), true).statusCode());
        browser.csrf(); return browser;
    }
    private Browser browser() { Browser value = new Browser(); browsers.add(value); return value; }
    private Map<String, Object> save(String id, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("articleId", id); body.put("title", title); body.put("coverMediaId", null); body.put("tagIds", List.of());
        body.put("content", mapper.readTree("{\"schemaVersion\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"<safe>\"}]}]}}"));
        return body;
    }
    private final class Browser implements AutoCloseable {
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        private String csrf;
        void csrf() throws Exception { csrf = mapper.readTree(call("GET", "/api/auth/csrf", null, false).body()).path("token").asString(); }
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
