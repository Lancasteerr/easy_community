package com.febrie.backend.media;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.config.MediaProperties;
import com.febrie.backend.media.infrastructure.persistence.MediaRepository;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL事务配合可控存储替身，精确覆盖异常边界，不连接业务库。 */
@EnabledIfEnvironmentVariable(named = "IT_DB_PASSWORD", matches = ".+")
abstract class MediaTestSupport {
    protected JdbcTemplate jdbc;
    protected MediaRepository files;
    protected MediaService media;
    protected TransactionTemplate transaction;
    protected DataSourceTransactionManager manager;
    protected final MutableClock clock = new MutableClock();
    protected final FakeStorage storage = new FakeStorage();
    protected long owner;
    protected MediaProperties properties;

    @BeforeAll
    static void migrateMedia() {
        String url = databaseUrl();
        assertTrue(url.matches("jdbc:mysql://[^/]+/easy_community_media_test(?:\\?.*)?"));
        Flyway.configure().dataSource(url, System.getenv().getOrDefault("IT_DB_USERNAME", "root"), System.getenv("IT_DB_PASSWORD")).load().migrate();
    }

    @BeforeEach
    void initialize() {
        var datasource = new DriverManagerDataSource(databaseUrl(), System.getenv().getOrDefault("IT_DB_USERNAME", "root"), System.getenv("IT_DB_PASSWORD"));
        jdbc = new JdbcTemplate(datasource);
        manager = new DataSourceTransactionManager(datasource);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        files = new MediaRepository(jdbc);
        properties = new MediaProperties("minio", URI.create("http://127.0.0.1:9000"), URI.create("http://127.0.0.1:9000"),
                "us-east-1", "test-bucket", "test-key", "test-secret", "ec/test", URI.create("https://images.example.com"));
        media = new MediaService(files, storage, properties, clock, manager);
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO users(username,role) VALUES (?,'USER')", java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "media_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            return statement;
        }, keys);
        owner = keys.getKey().longValue();
        jdbc.update("INSERT INTO user_auth_identity(user_id,provider,provider_uid,password_hash) VALUES (?,'LOCAL',?,'unused')", owner, UUID.randomUUID().toString());
    }

    @AfterEach
    void cleanup() {
        // 只清理当前用例上传者的文件和关联，先解开用户头像外键，再删除文件和用户。
        jdbc.update("UPDATE users SET avatar_media_id=NULL WHERE id=?", owner);
        jdbc.update("DELETE r FROM media_reference r JOIN media_file f ON f.id=r.media_id WHERE f.owner_user_id=?", owner);
        jdbc.update("DELETE FROM media_file WHERE owner_user_id=?", owner);
        jdbc.update("DELETE FROM user_auth_identity WHERE user_id=?", owner);
        jdbc.update("DELETE FROM users WHERE id=?", owner);
    }

    protected String uploaded(MediaPurpose purpose) {
        var grant = media.create(owner, purpose, "test.png", "image/png", 5);
        var file = files.find(grant.mediaId()).orElseThrow();
        storage.objects.put(file.tempKey(), new ObjectStorageProvider.ObjectMetadata(5, "image/png", "opaque-etag"));
        media.complete(owner, file.id());
        return file.id();
    }

    protected String ready(MediaPurpose purpose) {
        String id = uploaded(purpose); media.prepare(owner, id); return id;
    }

    private static String databaseUrl() {
        return System.getenv().getOrDefault("IT_MEDIA_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/easy_community_media_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    }

    /** 可推进业务时间，不依靠等待24小时或7天来测试到期。 */
    protected static class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** 只模拟对象元数据与网络故障，不伪装真实服务商防覆盖验证。 */
    protected static class FakeStorage implements ObjectStorageProvider {
        final Map<String, ObjectMetadata> objects = new ConcurrentHashMap<>();
        final AtomicInteger copies = new AtomicInteger();
        final AtomicInteger deletions = new AtomicInteger();
        boolean copyThenFail;
        boolean deleteFailure;
        Runnable beforeDelete = () -> { };
        Runnable beforeCopy = () -> { };
        @Override public String name() { return "minio"; }
        @Override public UploadAuthorization authorizeUpload(String bucket, String key, String type, Instant expires) {
            return new UploadAuthorization("PUT", "https://upload.example.com/" + key,
                    Map.of("Content-Type", type, "If-None-Match", "*"), expires);
        }
        @Override public Optional<ObjectMetadata> head(String bucket, String key) { return Optional.ofNullable(objects.get(key)); }
        @Override public void copyToPublic(String bucket, String source, String target, String type, String etag) {
            beforeCopy.run(); copies.incrementAndGet();
            var original = objects.get(source);
            if (original == null || !Objects.equals(original.etag(), etag)) { throw MediaErrors.unavailable(); }
            objects.put(target, new ObjectMetadata(original.size(), type, "copied-etag"));
            if (copyThenFail) { copyThenFail = false; throw MediaErrors.unavailable(); }
        }
        @Override public void delete(String bucket, String key) {
            beforeDelete.run(); deletions.incrementAndGet();
            if (deleteFailure) { throw MediaErrors.unavailable(); }
            objects.remove(key);
        }
    }
}
