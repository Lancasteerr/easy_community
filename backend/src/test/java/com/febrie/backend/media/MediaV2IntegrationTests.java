package com.febrie.backend.media;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.persistence.FileRepository;
import com.febrie.backend.user.application.*;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

/** 显式启用后连接全新专用库、Redis、真实MinIO及Caddy；不会清空业务库或共享桶。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {"server.port=18080", "server.address=0.0.0.0"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "IT_MEDIA_V2", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaV2IntegrationTests {
    private static final String PREFIX = "ec:test:media-v2:" + UUID.randomUUID();
    private static final String PASSWORD = "media-v2-test-password";
    private static String bucket() { return required("IT_MEDIA_BUCKET"); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileRepository files;
    @Autowired MediaService media;
    @Autowired MediaReferenceService references;
    @Autowired MediaLifecycleService lifecycle;
    @Autowired UserService users;
    @Autowired UserProfileService profiles;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.febrie.backend.shared.ratelimit.FixedWindowCounter counter;
    @Autowired org.springframework.scheduling.TaskScheduler scheduledMaintenance;
    private S3Client s3;
    private boolean createdBucket;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> owners = new ArrayList<>();
    private HttpClient http;
    private long owner;
    private String csrf;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        String url = required("IT_MEDIA_DB_URL");
        if (!url.matches("jdbc:mysql://[^/]+/easy_community_media_v2_test(?:_[a-z0-9]+)?(?:\\?.*)?")) {
            throw new IllegalArgumentException("V2测试只允许新建专用库");
        }
        required("IT_DB_PASSWORD");
        if (!bucket().matches("ec-v2-it-[a-z0-9-]+")) { throw new IllegalArgumentException("必须使用本次测试专属桶"); }
        registry.add("spring.datasource.url", () -> url);
        registry.add("app.auth.redis-prefix", () -> PREFIX);
        registry.add("app.auth.user-per-minute", () -> "1000");
        registry.add("app.media.workflow.redis-prefix", () -> PREFIX);
        registry.add("app.media.workflow.uploads-per-minute", () -> "1000");
        registry.add("app.media.workflow.webhook-token", () -> "media-v2-test-token");
        registry.add("app.media.endpoint", () -> required("IT_MINIO_ENDPOINT"));
        registry.add("app.media.upload-endpoint", () -> required("IT_MINIO_UPLOAD_ENDPOINT"));
        registry.add("app.media.access-key", () -> required("IT_MINIO_ACCESS_KEY"));
        registry.add("app.media.secret-key", () -> required("IT_MINIO_SECRET_KEY"));
        registry.add("app.media.bucket", MediaV2IntegrationTests::bucket);
        registry.add("app.media.public-base-url", () -> required("IT_MINIO_ENDPOINT") + "/" + bucket());
    }
    @BeforeAll void prepareStorage() {
        assertEquals(1, counter.increment(PREFIX + ":probe", Duration.ofSeconds(60)));
        s3 = S3Client.builder().endpointOverride(URI.create(required("IT_MINIO_ENDPOINT"))).region(Region.US_EAST_1)
                .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        required("IT_MINIO_ACCESS_KEY"), required("IT_MINIO_SECRET_KEY")))).build();
        s3.createBucket(request -> request.bucket(bucket())); createdBucket = true;
        s3.putBucketPolicy(request -> request.bucket(bucket()).policy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":["s3:GetObject"],
                "Resource":["arn:aws:s3:::%s/easy-community/test/public/*"]}]}
                """.formatted(bucket())));
        s3.putBucketLifecycleConfiguration(request -> request.bucket(bucket()).lifecycleConfiguration(config -> config.rules(
                rule -> rule.id("temp-one-day").status(ExpirationStatus.ENABLED).filter(f -> f.prefix("easy-community/test/temp/"))
                        .expiration(e -> e.days(1)))));
        s3.putBucketNotificationConfiguration(request -> request.bucket(bucket()).notificationConfiguration(config -> config.queueConfigurations(
                queue -> queue.id("temp-post").queueArn("arn:minio:sqs::MEDIA:webhook").events(Event.S3_OBJECT_CREATED_POST)
                        .filter(filter -> filter.key(key -> key.filterRules(rule -> rule.name("prefix").value("easy-community/test/temp/")))))));
    }
    @BeforeEach void login() throws Exception {
        String username = "v2_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        // 直接用用户服务创建测试主体，不依赖数据库中不存在其他管理员。
        var user = users.createManaged(new UserActor(1, Role.ADMIN, 1), username, PASSWORD, Role.USER);
        owner = Long.parseLong(user.userId()); owners.add(owner);
        http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        refreshCsrf();
        assertEquals(200, api("POST", "/api/auth/login", Map.of("username", username, "password", PASSWORD), true).statusCode());
        refreshCsrf();
    }
    @AfterEach void closeClient() { if (http != null) { http.close(); } }
    @AfterAll void cleanup() {
        for (long id : owners) {
            jdbc.update("UPDATE users SET avatar_media_id=NULL WHERE id=?", id);
            jdbc.update("DELETE r FROM media_reference r JOIN media_file f ON r.file_id=f.id WHERE f.uploader_id=?", id);
            jdbc.update("DELETE FROM media_file WHERE uploader_id=?", id);
            jdbc.update("DELETE FROM user_auth_identity WHERE user_id=?", id);
            jdbc.update("DELETE FROM users WHERE id=?", id);
        }
        if (s3 != null) {
            if (createdBucket) {
                for (var page : s3.listObjectsV2Paginator(request -> request.bucket(bucket()))) {
                    for (var object : page.contents()) { s3.deleteObject(request -> request.bucket(bucket()).key(object.key())); }
                }
                s3.deleteBucket(request -> request.bucket(bucket()));
            }
            s3.close();
        }
    }
    @Test void postProxyWebhookAndAvatarAreOneRealFlow() throws Exception {
        var grant = grant("AVATAR", 5);
        var result = upload(grant, "image".getBytes(StandardCharsets.UTF_8), Map.of());
        assertEquals(200, result.statusCode(), result.body());
        assertEquals("http://127.0.0.1:5173", result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertTrue(json.readTree(result.body()).get("success").asBoolean());
        String id = grant.get("mediaId").asString();
        // 测试等待用来观察Webhook，产品前端不执行这段轮询。
        long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (files.find(id).orElseThrow().status() != MediaStatus.READY && System.nanoTime() < end) { Thread.sleep(30); }
        assertEquals(MediaStatus.READY, files.find(id).orElseThrow().status());
        assertNull(files.find(id).orElseThrow().mimeType());
        try (var anonymous = HttpClient.newHttpClient()) {
            assertEquals(200, anonymous.send(HttpRequest.newBuilder(URI.create(grant.get("publicUrl").asString())).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(403, anonymous.send(HttpRequest.newBuilder(URI.create(required("IT_MINIO_ENDPOINT") + "/" + bucket() + "/easy-community/test/temp/" + id)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        assertEquals(200, api("PUT", "/api/users/me/avatar", Map.of("mediaId", id), true).statusCode());
        assertNull(files.find(id).orElseThrow().nextActionAt());
        assertEquals(204, api("DELETE", "/api/users/me/avatar", null, true).statusCode());
        assertNotNull(files.find(id).orElseThrow().nextActionAt());
        assertEquals(404, api("POST", "/api/media/" + id + "/complete", Map.of(), true).statusCode());
        assertEquals(404, api("GET", "/api/media/" + id, null, false).statusCode());
    }
    @Test void policyRejectsTamperedKeyTypeAndLengthAndCsrfStillApplies() throws Exception {
        assertEquals(403, api("POST", "/api/media/uploads", Map.of("purpose", "AVATAR", "filename", "a.png", "contentType", "image/png", "size", 5), false).statusCode());
        var grant = grant("AVATAR", 5);
        for (Map<String,String> changes : List.of(Map.of("key", "easy-community/test/public/illegal"), Map.of("Content-Type", "image/jpeg"))) {
            var response = upload(grant, "image".getBytes(StandardCharsets.UTF_8), changes);
            assertTrue(response.statusCode() >= 400, response.body());
            assertFalse(json.readTree(response.body()).get("success").asBoolean());
        }
        assertTrue(upload(grant, "too-long".getBytes(StandardCharsets.UTF_8), Map.of()).statusCode() >= 400);
    }
    @Test void notifyIsStatelessAndRejectsBadTokenEvenWithBadCookie() throws Exception {
        try (var anonymous = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(uri("/api/storage/minio/upload-webhook")).header("Content-Type", "application/json")
                    .header("Cookie", "SESSION=invalid-session-cookie").POST(HttpRequest.BodyPublishers.ofString("{\"Records\":[]}"));
            assertEquals(403, anonymous.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400, anonymous.send(request.header("Authorization", "Bearer media-v2-test-token").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, anonymous.send(HttpRequest.newBuilder(uri("/api/storage/cos/upload-callback")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void schemaIsMinimalAndReferencesProtectFiles() {
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='media_file'", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='media_reference'", Integer.class));
        String id = ready(MediaPurpose.ARTICLE_IMAGE, 100);
        TransactionTemplate tx = transaction();
        tx.executeWithoutResult(status -> references.syncArticle(11, owner, List.of(id, id), id));
        tx.executeWithoutResult(status -> references.syncArticle(12, owner, List.of(id), null));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM media_reference WHERE file_id=?", Integer.class, MediaIds.bytes(id)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM media_file WHERE id=?", MediaIds.bytes(id)));
        tx.executeWithoutResult(status -> references.syncArticle(11, owner, List.of(), null));
        assertNull(files.find(id).orElseThrow().nextActionAt());
        tx.executeWithoutResult(status -> references.syncArticle(12, owner, List.of(), null));
        assertTrue(files.find(id).orElseThrow().nextActionAt().isAfter(Instant.now().plusSeconds(86000)));
        // 旧候选在绑定、解绑刷新宽限期后不能直接删除。
        lifecycle.maintainOne(id);
        assertTrue(files.find(id).isPresent());
    }
    @Test void gcAndBindSerializeOnTheSameRow() throws Exception {
        String id = ready(MediaPurpose.ARTICLE_IMAGE, 100);
        jdbc.update("UPDATE media_file SET next_action_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?", MediaIds.bytes(id));
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var bind = executor.submit(() -> transaction().executeWithoutResult(status -> {
                files.lock(id).orElseThrow(); locked.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new RuntimeException(error); }
                references.syncArticle(21, owner, List.of(id), null);
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var gc = executor.submit(() -> lifecycle.maintainOne(id));
            release.countDown(); bind.get(5, TimeUnit.SECONDS); gc.get(5, TimeUnit.SECONDS);
        }
        assertEquals(MediaStatus.READY, files.find(id).orElseThrow().status());
        assertTrue(files.hasReferences(id));
    }
    @Test void unreadyForeignAndOversizeCoverCannotBindAndTransactionsRollback() {
        String id = ready(MediaPurpose.ARTICLE_IMAGE, 6L * 1024 * 1024);
        String issued = media.create(owner, MediaPurpose.ARTICLE_IMAGE, "a.png", "image/png", 5).mediaId();
        assertThrows(RuntimeException.class, () -> transaction().executeWithoutResult(status -> references.syncArticle(31, owner, List.of(issued), null)));
        assertThrows(RuntimeException.class, () -> transaction().executeWithoutResult(status -> references.syncArticle(31, owner + 1, List.of(id), null)));
        assertThrows(RuntimeException.class, () -> transaction().executeWithoutResult(status -> references.syncArticle(31, owner, List.of(), id)));
        assertFalse(files.hasReferences(id));
        assertThrows(RuntimeException.class, () -> transaction().executeWithoutResult(status -> {
            references.syncArticle(31, owner, List.of(id), null); throw new IllegalStateException("rollback");
        }));
        assertFalse(files.hasReferences(id));
        assertNotNull(files.find(id).orElseThrow().nextActionAt());
    }
    @Test void concurrentAvatarReplacementKeepsExactlyTheCurrentReference() throws Exception {
        String first = ready(MediaPurpose.AVATAR, 100), second = ready(MediaPurpose.AVATAR, 100);
        var actor = new UserActor(owner, Role.USER, 1);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return profiles.setAvatar(actor, first); });
            var b = executor.submit(() -> { start.await(); return profiles.setAvatar(actor, second); });
            start.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
        }
        String current = profiles.profile(actor).avatarMediaId();
        assertEquals(Set.of(current), files.references(ReferenceType.USER_AVATAR, owner));
        assertNull(files.find(current).orElseThrow().nextActionAt());
        assertNotNull(files.find(current.equals(first) ? second : first).orElseThrow().nextActionAt());
        assertEquals(1L, jdbc.queryForObject("SELECT auth_version FROM users WHERE id=?", Long.class, owner));
    }
    @Test void concurrentRedisAdmissionCannotLoseCounts() throws Exception {
        var flow = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
                        "app.media.workflow.redis-prefix", PREFIX + ":limit:" + UUID.randomUUID(), "app.media.workflow.uploads-per-minute", "2")))
                .bindOrCreate("app.media.workflow", org.springframework.boot.context.properties.bind.Bindable.of(com.febrie.backend.media.infrastructure.config.MediaWorkflowProperties.class));
        var limits = new com.febrie.backend.media.infrastructure.ratelimit.UploadRateLimit(counter, flow, Clock.systemUTC());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) { attempts.add(executor.submit(() -> {
                start.await();
                try { limits.consume(owner); return true; }
                catch (com.febrie.backend.shared.error.ApiException exceeded) { assertEquals(429, exceeded.status().value()); return false; }
            })); }
            start.countDown(); int admitted = 0;
            for (var attempt : attempts) { if (attempt.get(5, TimeUnit.SECONDS)) { admitted++; } }
            assertEquals(2, admitted);
        }
    }
    @Test void slowNotificationBodyTimesOutWithoutWaitingForClientToFinish() throws Exception {
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
        var maintenance = scheduledMaintenance.schedule(() -> {
            busy.countDown();
            try { release.await(8, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, Instant.now());
        assertTrue(busy.await(2, TimeUnit.SECONDS));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5500);
            String headers = "POST /api/storage/minio/upload-webhook HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nAuthorization: Bearer media-v2-test-token\r\nContent-Length: 10000\r\nConnection: close\r\n\r\n{";
            long start = System.nanoTime();
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            assertTrue(reader.readLine().contains("200"));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 5000);
            String line; boolean timeout = false;
            while ((line = reader.readLine()) != null && !line.isEmpty()) { if (line.contains("PROCESS_TIMEOUT")) { timeout = true; } }
            assertTrue(timeout);
        } finally { release.countDown(); maintenance.cancel(true); }
    }
    @Test void blockedReadyUpdateFailsWithinBudgetAndGcRemovesItsPublishedOrphan() throws Exception {
        var grant = media.create(owner, MediaPurpose.AVATAR, "a.png", "image/png", 5);
        String id = grant.mediaId(), key = "easy-community/test/temp/" + id;
        String etag = s3.putObject(request -> request.bucket(bucket()).key(key).contentType("image/png"),
                software.amazon.awssdk.core.sync.RequestBody.fromString("image")).eTag();
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var blocker = executor.submit(() -> transaction().executeWithoutResult(status -> {
                files.lock(id).orElseThrow(); locked.countDown();
                try { release.await(8, TimeUnit.SECONDS); } catch (InterruptedException error) { throw new RuntimeException(error); }
            }));
            assertTrue(locked.await(3, TimeUnit.SECONDS));
            try (var anonymous = HttpClient.newHttpClient()) {
                String body = json.writeValueAsString(Map.of("Records", List.of(Map.of("eventName", "s3:ObjectCreated:Post",
                        "s3", Map.of("bucket", Map.of("name", bucket()), "object", Map.of("key", key, "size", 5, "eTag", etag,
                                "userMetadata", Map.of("content-type", "image/png")))))));
                long start = System.nanoTime();
                var response = anonymous.send(HttpRequest.newBuilder(uri("/api/storage/minio/upload-webhook"))
                        .header("Content-Type", "application/json").header("Authorization", "Bearer media-v2-test-token")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 4500);
            } finally { release.countDown(); blocker.get(5, TimeUnit.SECONDS); }
        }
        assertEquals(MediaStatus.ISSUED, files.find(id).orElseThrow().status());
        assertEquals(5L, s3.headObject(request -> request.bucket(bucket()).key("easy-community/test/public/" + id)).contentLength());
        jdbc.update("UPDATE media_file SET next_action_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?", MediaIds.bytes(id));
        lifecycle.maintainOne(id);
        assertTrue(files.find(id).isEmpty());
        assertThrows(NoSuchKeyException.class, () -> s3.headObject(request -> request.bucket(bucket()).key("easy-community/test/public/" + id)));
        // 临时源仍存在，交给桶生命周期；GC不会主动删除它。
        assertEquals(5L, s3.headObject(request -> request.bucket(bucket()).key(key)).contentLength());
    }
    private String ready(MediaPurpose purpose, long size) {
        String id = UUID.randomUUID().toString();
        files.insert(new FileRecord(id, owner, purpose, MediaStatus.ISSUED, null, null, Instant.now(), Instant.now().plusSeconds(7200)));
        jdbc.update("UPDATE media_file SET status='READY',size_bytes=?,next_action_at=UTC_TIMESTAMP(3)+INTERVAL 1 DAY WHERE id=?", size, MediaIds.bytes(id));
        return id;
    }
    private TransactionTemplate transaction() { var tx = new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); return tx; }
    private JsonNode grant(String purpose, long size) throws Exception {
        var response = api("POST", "/api/media/uploads", Map.of("purpose", purpose, "filename", "a.png", "contentType", "image/png", "size", size), true);
        assertEquals(201, response.statusCode(), response.body()); return json.readTree(response.body());
    }
    private HttpResponse<String> upload(JsonNode grant, byte[] file, Map<String,String> changes) throws Exception {
        String boundary = "media-v2-" + UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        for (var field : grant.get("upload").get("fields").properties()) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + changes.getOrDefault(field.getKey(), field.getValue().asString()) + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        try (var storageHttp = HttpClient.newHttpClient()) {
            return storageHttp.send(HttpRequest.newBuilder(URI.create(grant.get("upload").get("url").asString()))
                    .header("Origin", "http://127.0.0.1:5173")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private void refreshCsrf() throws Exception {
        var response = api("GET", "/api/auth/csrf", null, false);
        assertEquals(200, response.statusCode(), response.body());
        csrf = json.readTree(response.body()).get("token").asString();
    }
    private HttpResponse<String> api(String method, String path, Object body, boolean withCsrf) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        if (withCsrf) { request.header("X-CSRF-TOKEN", csrf); }
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) { throw new IllegalArgumentException("启用V2集成测试后必须配置 " + name); } return value;
    }
}
