package com.febrie.backend.article.comment;

import static org.junit.jupiter.api.Assertions.*;

import com.febrie.backend.article.like.application.LikeSqlService;
import com.febrie.backend.article.like.domain.LikeCommand;
import com.febrie.backend.article.like.domain.LikeTarget;
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
import java.time.Instant;
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
    @Autowired LikeSqlService likes;
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
    }

    @Test
    void guestRankingSurvivesRealRefreshAndTombstone() throws Exception {
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
        var first = writes.create(article, actor, null, "较早");
        var second = writes.create(article, actor, null, "较晚");
        var reply = writes.create(article, actor, first.id(), "回复");
        var key = new CommentListKey(article, 0, CommentListKey.Sort.HOT);
        String route = "/api/articles/" + article + "/comments";
        String hotRoute = route + "?sort=hot&size=1";

        try (var http = HttpClient.newHttpClient()) {
            var old = get(http, hotRoute, 200);
            assertEquals(Long.toString(second.id()), old.at("/items/0/id").asString());
            String cursor = old.path("nextCursor").asString();
            var oldSnapshot = cache.current(key);
            var command = new LikeCommand(1, UUID.randomUUID().toString(), owner,
                    new LikeTarget(LikeTarget.Type.COMMENT, first.id()), true, "1", Instant.now());
            likes.apply(command);
            likes.apply(command);
            assertEquals(1L, sql.queryForObject("SELECT like_count FROM comment_like_count WHERE comment_id = ?", Long.class, first.id()));
            assertEquals(0L, sql.queryForObject("SELECT like_count FROM article_like_count WHERE article_id = ?", Long.class, article));

            // 人工推进逻辑到期，仍通过 HTTP 投递与真实监听器发布下一版。
            var stale = new CommentSnapshot(oldSnapshot.id(), oldSnapshot.version(), 1,
                    oldSnapshot.hardAt(), oldSnapshot.count());
            String base = PREFIX + ":comment:v1:list:{" + article + ":0:HOT}";
            redis.opsForValue().set(base + ":meta:" + stale.id(), stale.payload(), Duration.ofMinutes(5));
            redis.opsForValue().set(base + ":current", stale.payload(), Duration.ofMinutes(5));
            assertEquals(oldSnapshot.version(), get(http, hotRoute, 200).path("rankVersion").asString());
            var refreshed = waitForRefresh(key, oldSnapshot);
            assertEquals(Long.toString(first.id()), get(http, hotRoute, 200).at("/items/0/id").asString());
            assertEquals(oldSnapshot.version(), get(http, hotRoute + "&cursor=" + cursor, 200).path("rankVersion").asString());
            assertEquals(Long.toString(first.id()), get(http, hotRoute + "&cursor=" + cursor, 200).at("/items/0/id").asString());
            assertNotEquals(oldSnapshot.version(), refreshed.version());

            try (var ownerBrowser = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
                var csrf = get(ownerBrowser, "/api/auth/csrf", 200).path("token").asString();
                send(ownerBrowser, "POST", "/api/auth/login", Map.of("username", authorName, "password", password), csrf, 200);
                csrf = get(ownerBrowser, "/api/auth/csrf", 200).path("token").asString();
                send(ownerBrowser, "POST", route, Map.of("content", "新回复", "replyToId", Long.toString(first.id())), null, 403);
                var created = json.readTree(send(ownerBrowser, "POST", route,
                        Map.of("content", "新回复", "replyToId", Long.toString(first.id())), csrf, 201));
                assertEquals(Long.toString(first.id()), created.path("rootId").asString());
                send(ownerBrowser, "DELETE", route + "/" + first.id(), null, csrf, 204);
                send(ownerBrowser, "DELETE", route + "/" + first.id(), null, csrf, 204);
            }
            var tombstone = get(http, hotRoute, 200).at("/items/0");
            assertEquals("DELETED", tombstone.path("status").asString());
            assertFalse(tombstone.has("content"));
            assertEquals(Long.toString(reply.id()), get(http, route + "/" + first.id() + "/replies", 200).at("/items/0/id").asString());
            assertEquals(1L, get(http, "/api/articles/" + article, 200).path("commentCount").asLong());
            get(http, route + "/" + first.id(), 401);
        } finally {
            sql.update("DELETE r FROM comment_visibility_repair r JOIN article_comment c ON c.id=r.comment_id WHERE c.article_id=?", article);
            sql.update("DELETE l FROM comment_like_state l JOIN article_comment c ON c.id=l.comment_id WHERE c.article_id=?", article);
            sql.update("DELETE l FROM comment_like_count l JOIN article_comment c ON c.id=l.comment_id WHERE c.article_id=?", article);
            sql.update("DELETE FROM article_comment WHERE article_id=?", article);
            sql.update("DELETE FROM article_like_count WHERE article_id=?", article);
            sql.update("DELETE FROM article WHERE id=?", article);
            sql.update("DELETE FROM user_auth_identity WHERE user_id=?", owner);
            sql.update("DELETE FROM users WHERE id=?", owner);
        }
    }

    @Test
    void expiredBuildLeaseCannotPublishAnOlderSnapshot() {
        var key = new CommentListKey(9_999_999, 0, CommentListKey.Sort.HOT);
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

    private CommentSnapshot waitForRefresh(CommentListKey key, CommentSnapshot old) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (System.nanoTime() < deadline) {
            var active = cache.current(key);
            if (!old.version().equals(active.version())) return active;
            Thread.sleep(25);
        }
        throw new AssertionError("评论刷新监听器未发布新版本");
    }

    private JsonNode get(HttpClient http, String path, int status) throws Exception {
        return json.readTree(send(http, "GET", path, null, null, status));
    }

    private String send(HttpClient http, String method, String path, Object body, String csrf, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(8));
        if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        var data = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        var response = http.send(request.method(method, data).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        return response.body();
    }
}
