package com.febrie.backend.performance;

import com.febrie.backend.BackendApplication;
import com.febrie.backend.article.content.application.*;
import com.febrie.backend.article.content.application.dto.PreparedArticle;
import com.febrie.backend.article.content.infrastructure.persistence.ArticleRepository;
import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import com.febrie.backend.article.search.infrastructure.elasticsearch.*;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 单轮真实应用与随机数据的生命周期；只允许专用数据库，不打印连接凭证。 */
final class PerfFixture implements AutoCloseable {
    final ConfigurableApplicationContext app;
    final String prefix = "ec:test:resume:" + UUID.randomUUID();
    final String indexName = "resume_perf_" + UUID.randomUUID().toString().replace("-", "");
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final String base;
    final List<Long> users = new ArrayList<>(), ids = new ArrayList<>();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    String csrf;

    PerfFixture() throws Exception {
        String database = System.getenv("IT_DB_URL");
        if (database == null || !database.contains("/easy_community_resume_test?")) {
            throw new IllegalArgumentException("性能测试只允许 easy_community_resume_test 专用数据库");
        }
        app = new SpringApplication(BackendApplication.class, PerfConfiguration.class).run(
                "--spring.profiles.active=test,resume-perf", "--spring.config.import=", "--server.port=0",
                "--management.server.port=0", "--app.auth.redis-prefix=" + prefix,
                "--app.search.index-name=" + indexName, "--app.search.read-alias=" + indexName + "_read",
                "--app.search.write-alias=" + indexName + "_write", "--app.search.query-enabled=true",
                "--app.search.cursor-secret=resume-test-only-shared-secret-123456789",
                "--app.search.rank-scheduling-enabled=false", "--app.auth.user-per-minute=100000",
                "--app.auth.csrf-per-minute=100000", "--app.auth.login-per-minute=100000",
                "--app.media.cleanup-delay=24h", "--app.auth.cleanup-delay=24h", "--logging.level.root=WARN");
        jdbc = app.getBean(JdbcTemplate.class); json = app.getBean(ObjectMapper.class);
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
        try { seed(); } catch (Exception failure) { close(); throw failure; }
    }
    private void seed() throws Exception {
        var accounts = app.getBean(AccountRepository.class);
        var tx = app.getBean(TransactionTemplate.class);
        String password = "resume-perf-only-password";
        String hash = app.getBean(PasswordEncoder.class).encode(password);
        String username = "rp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        for (int i = 0; i < 4; i++) {
            String name = i == 0 ? username : "rp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            users.add(tx.execute(status -> accounts.insert(name, Role.USER, hash)));
        }
        var repository = app.getBean(ArticleRepository.class);
        for (int i = 0; i < 256; i++) {
            int number = i;
            var content = new PreparedArticle("性能文章" + i, "摘要内容".repeat(30), "{}", "<p>" + "正文".repeat(2048) + "</p>",
                    null, Set.of(), List.of());
            ids.add(tx.execute(status -> repository.insert(users.get(number % users.size()), content, Instant.now())));
        }
        app.getBean(PerfConfiguration.PerfController.class).articleIds = List.copyOf(ids.subList(0, 20));
        app.getBean(ArticleIndexAdmin.class).initialize(indexName, true);
        app.getBean(ArticleIndexStore.class).upsertBatch(indexName,
                new ArrayList<>(app.getBean(ArticleIndexReader.class).load(ids).values()));
        app.getBean(co.elastic.clients.elasticsearch.ElasticsearchClient.class).indices().refresh(r -> r.index(indexName));
        csrf = json.readTree(get("/api/auth/csrf").body()).path("token").asString();
        assertEquals(200, post("/api/auth/login", json.writeValueAsString(Map.of("username", username, "password", password))).statusCode());
        csrf = json.readTree(get("/api/auth/csrf").body()).path("token").asString();
    }
    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Override public void close() throws Exception {
        try {
            app.getBean("articleIndexRelayScheduler", ThreadPoolTaskScheduler.class).shutdown();
            app.getBean("articleRankScheduler", ThreadPoolTaskScheduler.class).shutdown();
            app.getBean("articleIndexListener", SimpleMessageListenerContainer.class).stop();
            app.getBean("likeListener", SimpleMessageListenerContainer.class).stop();
            // 删除顺序遵守外键；文章范围由本轮随机账号限定，绝不清空整个库。
            for (long user : users) {
                for (String table : List.of("article_outbox", "article_view_stat", "article_like_state", "article_like_count")) {
                    jdbc.update("DELETE child FROM " + table + " child JOIN article a ON child.article_id=a.id WHERE a.author_id=?", user);
                }
                jdbc.update("DELETE FROM article WHERE author_id=?", user);
                jdbc.update("DELETE FROM user_auth_identity WHERE user_id=?", user);
                jdbc.update("DELETE FROM users WHERE id=?", user);
            }
            var redis = app.getBean(StringRedisTemplate.class);
            // 统计Key带花括号，使用两个精确前缀分别扫描，不漏清本轮统计Hash。
            for (String pattern : List.of(prefix + ":*", "{" + prefix + ":*")) {
                try (var keys = redis.scan(ScanOptions.scanOptions().match(pattern).count(100).build())) {
                    while (keys.hasNext()) { redis.delete(keys.next()); }
                }
            }
            var admin = app.getBean(AmqpAdmin.class); var search = app.getBean(SearchProperties.class);
            admin.deleteQueue(search.name("work.q")); admin.deleteQueue(search.name("dead.q"));
            admin.deleteExchange(search.name("change.x")); admin.deleteExchange(search.name("dead.x"));
            var like = app.getBean(LikeProperties.class);
            admin.deleteQueue(like.name("command.q")); admin.deleteQueue(like.name("dead.q"));
            admin.deleteExchange(like.name("command.x")); admin.deleteExchange(like.name("dead.x"));
            app.getBean(co.elastic.clients.elasticsearch.ElasticsearchClient.class).indices().delete(d -> d.index(indexName).ignoreUnavailable(true));
        } finally { http.close(); app.close(); }
    }
}
