package com.febrie.backend.article.comment;

import static org.junit.jupiter.api.Assertions.*;

import com.febrie.backend.media.application.UploadStorage;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 在专用库验证真实 Flyway、Redis 快照脚本与同进程 RabbitMQ 消费。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "IT_COMMENT", matches = "true")
class CommentHttpIntegrationTests {
    private static final String PREFIX = "ec:test:comment:" + UUID.randomUUID();
    @LocalServerPort int port;
    @Autowired JdbcTemplate sql;
    @Autowired StringRedisTemplate redis;
    @Autowired CommentWrites writes;
    @Autowired CommentSnapshotCache cache;
    @Autowired CommentCommandTransactions commands;
    @Autowired CommentCommandResults results;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @MockitoBean UploadStorage storage;

    @DynamicPropertySource static void dedicated(DynamicPropertyRegistry properties) {
        String url = System.getenv("IT_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/easy_community_comment_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("仅允许专用评论测试库");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("app.auth.redis-prefix", () -> PREFIX);
        properties.add("app.search.query-enabled", () -> false);
        properties.add("app.search.rank-scheduling-enabled", () -> false);
        properties.add("app.comment.index-sync", () -> false);
    }

    @Test
    void replyRefreshAndQueuedRootDeletion() throws Exception {
        String authorName = "comment_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sql.update("INSERT INTO users(username, role) VALUES (?, 'USER')", authorName);
        long owner = sql.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, authorName);
        String password = "comment-test-password";
        sql.update("INSERT INTO user_auth_identity(user_id, provider, provider_uid, password_hash) VALUES (?, 'LOCAL', ?, ?)",
                owner, authorName, passwords.encode(password));
        sql.update("""
                INSERT INTO article(author_id,title,summary,content_json,content_html,tag_ids,created_at,updated_at,published_at)
                VALUES (?, '评论测试', '摘要', JSON_OBJECT(), '', JSON_ARRAY(), NOW(3), NOW(3), NOW(3))
                """, owner);
        long article = sql.queryForObject("SELECT MAX(id) FROM article WHERE author_id = ?", Long.class, owner);
        sql.update("INSERT INTO article_like_count(article_id, like_count) VALUES (?, 0)", article);
        var actor = new UserActor(owner, Role.USER, 1);
        var create = new CommentCommand(article, actor, UUID.randomUUID().toString(), null, "楼层");
        commands.apply(article, List.of(create));
        long root = results.find(article, owner, create.operationId()).commentId();
        var first = writes.createReply(article, actor, root, "旧回复");
        var key = new CommentListKey(article, root, CommentListKey.Sort.REPLIES);
        String route = "/api/articles/" + article + "/comments";
        String replyRoute = route + "/" + root + "/replies";

        try (var http = HttpClient.newHttpClient()) {
            var old = get(http, replyRoute, 200);
            assertEquals(Long.toString(first.id()), old.at("/items/0/id").asString());
            assertFalse(old.path("hasMore").asBoolean());
            var oldSnapshot = cache.current(key);
            try (var ownerBrowser = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
                var csrf = get(ownerBrowser, "/api/auth/csrf", 200).path("token").asString();
                send(ownerBrowser, "POST", "/api/auth/login", Map.of("username", authorName, "password", password), csrf, 200);
                csrf = get(ownerBrowser, "/api/auth/csrf", 200).path("token").asString();
                send(ownerBrowser, "POST", route, Map.of("content", "新回复", "replyToId", Long.toString(root)), null, 403);
                var created = json.readTree(send(ownerBrowser, "POST", route,
                        Map.of("content", "新回复", "replyToId", Long.toString(root)), csrf, 201));
                assertEquals(Long.toString(root), created.path("rootId").asString());

                // 人工推进逻辑到期，通过真实刷新监听器生成下一版楼层索引。
                var stale = new CommentSnapshot(oldSnapshot.id(), 1, oldSnapshot.hardAt(), oldSnapshot.count());
                String base = PREFIX + ":comment:v1:list:{" + article + ":" + root + ":REPLIES}";
                redis.opsForValue().set(base + ":meta:" + stale.id(), stale.payload(), Duration.ofSeconds(60));
                redis.opsForValue().set(base + ":current", stale.payload(), Duration.ofSeconds(60));
                get(http, replyRoute, 200);
                awaitRefresh(key, oldSnapshot);
                assertEquals(created.path("id").asString(), get(http,
                        replyRoute + "?cursor=" + old.path("nextCursor").asString(), 200).at("/items/0/id").asString());

                String operation = UUID.randomUUID().toString();
                send(ownerBrowser, "DELETE", route + "/" + root, null, csrf, operation, 202);
                awaitNotVisible(http, replyRoute);
                send(ownerBrowser, "DELETE", route + "/" + root, null, csrf, operation, 202);
            }
            assertEquals(0L, get(http, "/api/articles/" + article, 200).path("commentCount").asLong());
            get(http, route + "/" + root, 401);
        } finally {
            sql.update("DELETE o FROM comment_index_outbox o JOIN article_comment c ON c.id=o.comment_id WHERE c.article_id=?", article);
            sql.update("DELETE o FROM comment_like_index_outbox o JOIN article_comment c ON c.id=o.comment_id WHERE c.article_id=?", article);
            sql.update("DELETE l FROM comment_like_state l JOIN article_comment c ON c.id=l.comment_id WHERE c.article_id=?", article);
            sql.update("DELETE l FROM comment_like_count l JOIN article_comment c ON c.id=l.comment_id WHERE c.article_id=?", article);
            sql.update("DELETE FROM comment_write_result WHERE article_id=?", article);
            sql.update("DELETE FROM article_comment WHERE article_id=?", article);
            sql.update("DELETE FROM article_like_count WHERE article_id=?", article);
            sql.update("DELETE FROM article WHERE id=?", article);
            sql.update("DELETE FROM user_auth_identity WHERE user_id=?", owner);
            sql.update("DELETE FROM users WHERE id=?", owner);
        }
    }

    @Test
    void expiredBuildLeaseCannotPublishAnOlderSnapshot() {
        var key = new CommentListKey(9_999_999, 1, CommentListKey.Sort.REPLIES);
        String one = UUID.randomUUID().toString();
        String two = UUID.randomUUID().toString();
        String token = cache.lock(key);
        cache.stage(key, one, token, List.of(CommentSnapshot.member(0, 5)));
        cache.release(key, token);
        String current = cache.lock(key);
        cache.stage(key, two, current, List.of(CommentSnapshot.member(0, 6)));
        var published = cache.publish(key, null, two, current, 1, System.currentTimeMillis(), false);
        assertNotNull(published);
        assertNull(cache.publish(key, null, one, token, 1, System.currentTimeMillis(), false));
        assertEquals(two, cache.current(key).id());
        String three = UUID.randomUUID().toString();
        cache.stage(key, three, current, List.of(CommentSnapshot.member(0, 7)));
        assertNull(cache.publish(key, null, three, current, 1, System.currentTimeMillis(), true));
        assertEquals(published, cache.current(key));
        cache.release(key, current);
    }

    private void awaitRefresh(CommentListKey key, CommentSnapshot old) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (System.nanoTime() < deadline) {
            var active = cache.current(key);
            if (active != null && !old.id().equals(active.id())) return;
            Thread.sleep(25);
        }
        throw new AssertionError("评论刷新监听器未发布新版本");
    }

    private void awaitNotVisible(HttpClient http, String path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        while (System.nanoTime() < deadline) {
            if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 404) return;
            Thread.sleep(25);
        }
        throw new AssertionError("一级删除命令未完成");
    }

    private JsonNode get(HttpClient http, String path, int status) throws Exception {
        return json.readTree(send(http, "GET", path, null, null, status));
    }

    private String send(HttpClient http, String method, String path, Object body, String csrf, int status) throws Exception {
        return send(http, method, path, body, csrf, null, status);
    }

    private String send(HttpClient http, String method, String path, Object body, String csrf, String operation,
                        int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(8));
        if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
        if (operation != null) request.header("X-Operation-Id", operation);
        if (body != null) request.header("Content-Type", "application/json");
        var data = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        var response = http.send(request.method(method, data).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        return response.body();
    }
}
