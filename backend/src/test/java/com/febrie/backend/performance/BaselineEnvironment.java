package com.febrie.backend.performance;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.febrie.backend.BackendApplication;
import com.febrie.backend.article.like.application.maintenance.LikeRecoveryService;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.messaging.LikeListenerGroup;
import com.febrie.backend.article.like.infrastructure.messaging.LikePartitionRouter;
import com.febrie.backend.article.search.application.ArticleIndexReader;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleIndexAdmin;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleIndexStore;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

/** 生命周期限定在固定回环端口和本次随机创建的库、索引与消息命名空间。 */
final class BaselineEnvironment implements AutoCloseable {
    private static final String ROOT = "jdbc:mysql://127.0.0.1:17906/";
    private static final String SETTINGS = "?allowPublicKeyRetrieval=true&sslMode=DISABLED&rewriteBatchedStatements=true"
            + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    private final String password = System.getenv("IT_DB_PASSWORD");
    private final String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    final String database = "ec_k6_" + token;
    final String index = "ec_k6_" + token;
    private final BaselineData.Size size;
    private int stageId;
    private boolean created;

    BaselineEnvironment(BaselineData.Size size) {
        if (password == null || password.isBlank()) { throw new IllegalStateException("缺少压测专用凭证"); }
        this.size = size;
    }

    void seed() throws Exception {
        schema("CREATE DATABASE");
        created = true;
        try (var app = start("ec:test:baseline:" + token, false, 120, 60)) {
            var jdbc = app.getBean(JdbcTemplate.class);
            BaselineData.insert(jdbc, size, app.getBean(PasswordEncoder.class).encode(BaselineData.PASSWORD));
            app.getBean(ArticleIndexAdmin.class).initialize(index, true);
            var reader = app.getBean(ArticleIndexReader.class);
            var store = app.getBean(ArticleIndexStore.class);
            for (long first = 1; first <= size.articles(); first += 250) {
                var ids = LongStream.range(first, Math.min(first + 250, size.articles() + 1L)).boxed().toList();
                store.upsertBatch(index, new ArrayList<>(reader.load(ids).values()));
            }
            var es = app.getBean(ElasticsearchClient.class);
            es.indices().refresh(r -> r.index(index));
            if (es.count(r -> r.index(index).query(q -> q.term(t -> t.field("visible").value(true)))).count() != size.articles()) {
                throw new IllegalStateException("ES 可见文章数量与 MySQL 种子不一致");
            }
        }
    }

    Site stage(BaselineK6.Stage stage) throws Exception {
        String prefix = "ec:test:baseline:" + token + ":" + (++stageId);
        try (var maintenance = start(prefix, false, stage.userLimit(), stage.likeLimit())) {
            var jdbc = maintenance.getBean(JdbcTemplate.class);
            if (stageId > 1) { BaselineData.restore(jdbc); }
            int epoch = jdbc.queryForObject("SELECT epoch FROM like_version_epoch WHERE id=1", Integer.class);
            maintenance.getBean(LikeRecoveryService.class).recover(epoch);
        }
        return new Site(start(prefix, true, stage.userLimit(), stage.likeLimit()), prefix);
    }

    private ConfigurableApplicationContext start(String prefix, boolean web, int userLimit, int likeLimit) {
        return new SpringApplicationBuilder(BackendApplication.class, BaselineInstrumentation.class)
                .run("--spring.profiles.active=test", "--spring.config.import=",
                        "--spring.main.web-application-type=" + (web ? "servlet" : "none"),
                        "--server.address=127.0.0.1", "--server.port=0", "--management.server.port=0",
                        "--spring.datasource.url=" + ROOT + database + SETTINGS,
                        "--spring.datasource.username=root", "--spring.datasource.password=" + password,
                        "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=17979",
                        "--spring.data.redis.password=" + password,
                        "--spring.rabbitmq.host=127.0.0.1", "--spring.rabbitmq.port=17972",
                        "--spring.rabbitmq.username=resume_test", "--spring.rabbitmq.password=" + password,
                        "--spring.rabbitmq.virtual-host=easy_community_resume_test",
                        "--app.auth.redis-prefix=" + prefix, "--app.search.index-name=" + index,
                        "--app.search.read-alias=" + index + "_read", "--app.search.write-alias=" + index + "_write",
                        "--app.search.uri=http://127.0.0.1:19300",
                        "--app.search.username=elastic", "--app.search.password=" + password,
                        "--app.search.query-enabled=true", "--app.search.rank-scheduling-enabled=false",
                        "--app.search.cursor-secret=baseline-fixture-only-cursor-secret",
                        "--app.auth.login-per-minute=100000", "--app.auth.csrf-per-minute=100000",
                        "--app.auth.user-per-minute=" + userLimit, "--app.like.per-minute=" + likeLimit,
                        "--app.like.partition-count=" + BaselineData.PARTITIONS, "--app.like.batch-size=20",
                        "--app.like.batch-wait=100ms", "--app.like.prefetch=20",
                        "--app.media.cleanup-delay=24h", "--app.auth.cleanup-delay=24h", "--logging.level.root=WARN");
    }

    private void schema(String action) throws Exception {
        try (var connection = DriverManager.getConnection(ROOT + SETTINGS, "root", password);
             var statement = connection.createStatement()) {
            // 名称含固定前缀和本次进程生成的十六进制标识，无法来自配置或外部输入。
            statement.executeUpdate(action + " `" + database + "`");
        }
    }

    @Override public void close() throws Exception {
        if (!created) { return; }
        // 索引在建库后才可能创建。显式删除本次物理索引，别名随之消失。
        try (var app = start("ec:test:baseline:" + token, false, 120, 60)) {
            app.getBean(ElasticsearchClient.class).indices().delete(d -> d.index(index).ignoreUnavailable(true));
        } finally { schema("DROP DATABASE"); }
    }

    record Session(long user, String cookie, String csrfHeader, String csrf) { }

    final class Site implements AutoCloseable {
        final ConfigurableApplicationContext app;
        final String prefix;
        final String base;
        final String management;
        final JdbcTemplate jdbc;
        final ObjectMapper json;

        private Site(ConfigurableApplicationContext app, String prefix) {
            this.app = app; this.prefix = prefix;
            base = "http://127.0.0.1:" + app.getEnvironment().getRequiredProperty("local.server.port");
            management = "http://127.0.0.1:" + app.getEnvironment().getRequiredProperty("local.management.port");
            jdbc = app.getBean(JdbcTemplate.class); json = app.getBean(ObjectMapper.class);
        }

        String password() { return password; }
        String index() { return index; }

        List<Session> sessions(int count) throws Exception {
            try (var workers = Executors.newFixedThreadPool(4)) {
                var jobs = LongStream.rangeClosed(1, count).mapToObj(user -> workers.submit(() -> login(user))).toList();
                var result = new ArrayList<Session>();
                for (var job : jobs) { result.add(job.get()); }
                return result;
            }
        }

        private Session login(long user) throws Exception {
            var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            try (var http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build()) {
                var initial = json.readTree(call(http, request("/api/auth/csrf").GET().build()));
                var body = json.createObjectNode().put("username", BaselineData.username(user)).put("password", BaselineData.PASSWORD);
                call(http, request("/api/auth/login").header("Content-Type", "application/json")
                        .header(initial.path("headerName").asString(), initial.path("token").asString())
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
                var token = json.readTree(call(http, request("/api/auth/csrf").GET().build()));
                String cookie = cookies.getCookieStore().getCookies().stream()
                        .map(value -> value.getName() + "=" + value.getValue()).collect(Collectors.joining("; "));
                return new Session(user, cookie, token.path("headerName").asString(), token.path("token").asString());
            }
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        }

        private String call(HttpClient http, HttpRequest request) throws Exception {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("准备会话失败：" + request.uri().getPath() + " HTTP " + response.statusCode());
            }
            return response.body();
        }

        @Override public void close() {
            try {
                app.getBean("likeListener", LikeListenerGroup.class).stop();
                app.getBean("articleIndexListener", SimpleMessageListenerContainer.class).stop();
                var admin = app.getBean(AmqpAdmin.class);
                var like = app.getBean(LikeProperties.class);
                new LikePartitionRouter(like).queues().forEach(admin::deleteQueue);
                admin.deleteQueue(like.name("dead.q"));
                admin.deleteExchange(like.name("command.x")); admin.deleteExchange(like.name("dead.x"));
                var search = app.getBean(SearchProperties.class);
                admin.deleteQueue(search.name("work.q")); admin.deleteQueue(search.name("dead.q"));
                admin.deleteExchange(search.name("change.x")); admin.deleteExchange(search.name("dead.x"));
                var redis = app.getBean(StringRedisTemplate.class);
                for (String pattern : List.of(prefix + ":*", "{" + prefix + ":*")) {
                    var batch = new ArrayList<String>();
                    try (var keys = redis.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
                        while (keys.hasNext()) {
                            batch.add(keys.next());
                            if (batch.size() == 500) { redis.delete(batch); batch.clear(); }
                        }
                    }
                    if (!batch.isEmpty()) { redis.delete(batch); }
                }
            } finally { app.close(); }
        }
    }
}
