package com.febrie.backend.article.like;

import com.febrie.backend.BackendApplication;
import com.febrie.backend.article.content.application.dto.PreparedArticle;
import com.febrie.backend.article.content.infrastructure.persistence.ArticleRepository;
import com.febrie.backend.article.like.application.maintenance.*;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.messaging.*;
import com.febrie.backend.article.like.infrastructure.redis.LikeVersionAllocator;
import com.febrie.backend.performance.PerfSqlCounter;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 历史对比专用生命周期与16用户/32文章数据集，不修改开发环境。 */
final class LikePerfFixture implements AutoCloseable {
    final List<ConfigurableApplicationContext> apps = new ArrayList<>();
    final List<Client> clients = new ArrayList<>();
    final List<Long> users = new ArrayList<>(), articles = new ArrayList<>();
    final String prefix = "ec:test:history:" + UUID.randomUUID();
    JdbcTemplate jdbc;
    ObjectMapper mapper;
    LikeProperties properties;
    boolean successful;

    LikePerfFixture() throws Exception {
        String url = System.getenv("IT_DB_URL"), vhost = System.getenv("IT_RABBITMQ_VHOST");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")
                || vhost == null || !vhost.contains("test")) { throw new IllegalArgumentException("仅允许专用性能数据库与vhost"); }
        try {
            int epoch;
            // 所有Web实例启动前初始化本轮随机命名空间，遵守维护动作前提。
            try (var maintenance = start(false)) {
                var sql = maintenance.getBean(JdbcTemplate.class);
                int old = sql.queryForObject("SELECT epoch FROM like_version_epoch WHERE id=1", Integer.class);
                epoch = maintenance.getBean(LikeRecoveryService.class).recover(old);
            }
            for (int i = 0; i < 2; i++) { apps.add(start(true)); }
            var first = apps.getFirst(); jdbc = first.getBean(JdbcTemplate.class); mapper = first.getBean(ObjectMapper.class);
            properties = first.getBean(LikeProperties.class);
            assertEquals(epoch, jdbc.queryForObject("SELECT epoch FROM like_version_epoch WHERE id=1", Integer.class));
            var accounts = first.getBean(AccountRepository.class);
            var transaction = first.getBean(TransactionTemplate.class);
            String hash = first.getBean(PasswordEncoder.class).encode("load-test-password");
            for (int i = 0; i < 16; i++) {
                String name = "ll_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
                long user = transaction.execute(s -> accounts.insert(name, Role.USER, hash)); users.add(user);
                var client = new Client(i); clients.add(client); client.login(name);
            }
            var repository = first.getBean(ArticleRepository.class);
            for (int i = 0; i < 32; i++) {
                articles.add(transaction.execute(s -> repository.insert(users.getFirst(),
                        new PreparedArticle("load", "load", "{}", "<p>load</p>", null, Set.of(), List.of()), Instant.now())));
            }
        } catch (Exception | Error failure) { close(); throw failure; }
    }

    private ConfigurableApplicationContext start(boolean web) {
        return new SpringApplicationBuilder(BackendApplication.class, SqlCounting.class).run(
                "--spring.profiles.active=test", "--spring.main.web-application-type=" + (web ? "servlet" : "none"),
                "--server.port=0", "--management.server.port=0", "--app.auth.redis-prefix=" + prefix,
                "--app.like.per-minute=100000", "--app.auth.user-per-minute=100000", "--app.auth.csrf-per-minute=100000",
                "--app.auth.login-per-minute=100000", "--app.media.cleanup-delay=24h", "--app.auth.cleanup-delay=24h",
                "--app.like.partition-count=4", "--app.like.batch-size=20", "--app.like.batch-wait=100ms", "--app.like.prefetch=20");
    }

    void consumers(boolean running) {
        for (var app : apps) { var group = app.getBean("likeListener", LikeListenerGroup.class); if (running) { group.start(); } else { group.stop(); } }
    }
    void warmReadMatrix() throws Exception {
        for (var client : clients) { for (long article : articles) { assertEquals(200, client.send(0, "GET", "/api/articles/" + article, null, false).statusCode()); } }
        var redis = apps.getFirst().getBean(StringRedisTemplate.class);
        try (var keys = redis.scan(ScanOptions.scanOptions().match(properties.namespace() + ":cache:v1:*").count(100).build())) {
            while (keys.hasNext()) { redis.delete(keys.next()); }
        }
    }
    int port(int instance) { return apps.get(instance).getEnvironment().getRequiredProperty("local.server.port", Integer.class); }

    @Override public void close() {
        clients.forEach(Client::close);
        try {
            consumers(false);
            // 失败保留本轮SQL、队列和缓存，避免删除排查证据；上下文仍必须关闭。
            if (successful && jdbc != null) {
                var first = apps.getFirst(); var admin = first.getBean(org.springframework.amqp.core.AmqpAdmin.class);
                new LikePartitionRouter(properties).queues().forEach(admin::deleteQueue);
                admin.deleteQueue(properties.name("dead.q")); admin.deleteExchange(properties.name("command.x")); admin.deleteExchange(properties.name("dead.x"));
                var redis = first.getBean(StringRedisTemplate.class);
                try (var keys = redis.scan(ScanOptions.scanOptions().match(prefix + ":*").count(100).build())) { while (keys.hasNext()) { redis.delete(keys.next()); } }
                for (long article : articles) {
                    jdbc.update("DELETE FROM article_like_state WHERE article_id=?", article);
                    jdbc.update("DELETE FROM article_like_count WHERE article_id=?", article);
                    jdbc.update("DELETE FROM article WHERE id=?", article);
                }
                for (long user : users) { jdbc.update("DELETE FROM user_auth_identity WHERE user_id=?", user); jdbc.update("DELETE FROM users WHERE id=?", user); }
            }
        } finally { for (int i = apps.size() - 1; i >= 0; i--) { apps.get(i).close(); } }
    }

    /** 显式注册测试拦截器，不加载其他性能HTTP路由。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCounting { @Bean PerfSqlCounter likePerfSqlCounter() { return new PerfSqlCounter(); } }

    final class Client implements AutoCloseable {
        final int index;
        final Semaphore available = new Semaphore(1);
        final Map<Long, Boolean> desired = new HashMap<>();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        Client(int index) { this.index = index; }
        void login(String name) throws Exception {
            csrf = mapper.readTree(send(0, "GET", "/api/auth/csrf", null, false).body()).path("token").asString();
            assertEquals(200, send(0, "POST", "/api/auth/login", Map.of("username", name, "password", "load-test-password"), true).statusCode());
            csrf = mapper.readTree(send(0, "GET", "/api/auth/csrf", null, false).body()).path("token").asString();
        }
        boolean next(long article) { boolean next = !desired.getOrDefault(article, false); desired.put(article, next); return next; }
        HttpResponse<String> send(int server, String method, String path, Object body, boolean token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port(server) + path)).timeout(Duration.ofSeconds(10));
            if (token) { request.header("X-CSRF-TOKEN", csrf); }
            if (path.endsWith("/like")) { request.header("X-Operation-Id", UUID.randomUUID().toString()); }
            if (body != null) { request.header("Content-Type", "application/json"); }
            return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        public void close() { http.close(); }
    }
}
