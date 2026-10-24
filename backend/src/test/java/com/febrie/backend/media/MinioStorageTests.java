package com.febrie.backend.media;

import com.febrie.backend.media.application.ObjectStorageProvider;
import com.febrie.backend.media.infrastructure.config.MediaProperties;
import com.febrie.backend.media.infrastructure.storage.MinioStorageProvider;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import static org.junit.jupiter.api.Assertions.*;

/** 创建本次运行专属桶验证真实MinIO，结束时仅删除自己创建的桶与对象。 */
@EnabledIfEnvironmentVariable(named = "IT_MINIO_ENDPOINT", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioStorageTests {
    private final String bucket = "ec-media-it-" + UUID.randomUUID().toString().substring(0, 12);
    private final HttpClient http = HttpClient.newHttpClient();
    private S3Client client;
    private MinioStorageProvider provider;
    private boolean created;

    @BeforeAll
    void provision() {
        URI endpoint = URI.create(System.getenv("IT_MINIO_ENDPOINT"));
        String access = System.getenv("IT_MINIO_ACCESS_KEY");
        String secret = System.getenv("IT_MINIO_SECRET_KEY");
        client = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))).build();
        client.createBucket(builder -> builder.bucket(bucket));
        created = true;
        client.putBucketPolicy(builder -> builder.bucket(bucket).policy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",
                "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/ec/test/public/*"]}]}
                """.formatted(bucket)));
        client.putBucketLifecycleConfiguration(builder -> builder.bucket(bucket).lifecycleConfiguration(
                config -> config.rules(rule -> rule.id("temp-one-day").status(ExpirationStatus.ENABLED)
                        .filter(filter -> filter.prefix("ec/test/temp/"))
                        .expiration(expiration -> expiration.days(1)))));
        // 本机MinIO不支持桶级PutBucketCors；来源白名单由服务端MINIO_API_CORS_ALLOW_ORIGIN管理。
        provider = new MinioStorageProvider(new MediaProperties("minio", endpoint, endpoint, "us-east-1", bucket,
                access, secret, "ec/test", URI.create(endpoint + "/" + bucket)), Clock.systemUTC());
    }

    @AfterAll
    void cleanup() {
        if (client == null) { return; }
        try {
            if (created) {
                // 桶名由本测试生成，不接受环境指定的业务桶，不使用递归清空命令。
                for (var page : client.listObjectsV2Paginator(builder -> builder.bucket(bucket).prefix("ec/test/"))) {
                    for (var object : page.contents()) { client.deleteObject(builder -> builder.bucket(bucket).key(object.key())); }
                }
                client.deleteBucket(builder -> builder.bucket(bucket));
            }
        } finally {
            if (provider != null) { provider.close(); }
            client.close(); http.close();
        }
    }

    @Test
    void conditionalUploadCannotBeOverwrittenAndSignedHeadersCannotBeRemoved() throws Exception {
        String key = key();
        var grant = grant(key, 300);
        Map<String, String> missing = new HashMap<>(grant.headers()); missing.remove("if-none-match");
        // MinIO对缺失已签名头可返回400，对签名不匹配返回403，两者都不得写入对象。
        assertTrue(Set.of(400, 403).contains(put(grant, missing, "first").statusCode()));
        Map<String, String> changed = new HashMap<>(grant.headers()); changed.put("if-none-match", "other");
        assertEquals(403, put(grant, changed, "first").statusCode());
        assertEquals(200, put(grant, grant.headers(), "first").statusCode());
        assertEquals(412, put(grant, grant.headers(), "replacement").statusCode());
        assertEquals(5, provider.head(bucket, key).orElseThrow().size());
    }

    @Test
    void concurrentCreationHasExactlyOneWinner() throws Exception {
        var grant = grant(key(), 300);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> upload = () -> { start.await(); return put(grant, grant.headers(), "same").statusCode(); };
            var first = executor.submit(upload); var second = executor.submit(upload); start.countDown();
            assertEquals(List.of(200, 412), java.util.stream.Stream.of(first.get(), second.get()).sorted().toList());
        }
    }

    @Test
    void expirationCopyPermissionsAndLifecycleAreEffective() throws Exception {
        String key = key(); var grant = grant(key, 300);
        assertEquals(200, put(grant, grant.headers(), "image").statusCode());
        String target = key.replace("/temp/", "/public/") + ".png";
        provider.copyToPublic(bucket, key, target, "image/png", provider.head(bucket, key).orElseThrow().etag());
        String base = System.getenv("IT_MINIO_ENDPOINT") + "/" + bucket + "/";
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + key)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + target)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        // 临时源仍存在，复制后尚有效的授权也不能重新创建或覆盖它。
        assertEquals(412, put(grant, grant.headers(), "new").statusCode());
        var expiring = grant(key(), 1); Thread.sleep(2100);
        assertEquals(403, put(expiring, expiring.headers(), "late").statusCode());
        var rule = client.getBucketLifecycleConfiguration(builder -> builder.bucket(bucket)).rules().getFirst();
        assertEquals("ec/test/temp/", rule.filter().prefix()); assertEquals(1, rule.expiration().days());
        provider.delete(bucket, target); provider.delete(bucket, target);
        assertTrue(provider.head(bucket, target).isEmpty());
    }

    @Test
    void browserPreflightAllowsConditionalPut() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(System.getenv("IT_MINIO_ENDPOINT") + "/" + bucket + "/" + key()))
                .header("Origin", "http://127.0.0.1:5173").header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type,if-none-match")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = http.send(request, HttpResponse.BodyHandlers.discarding());
        assertTrue(Set.of(200, 204).contains(response.statusCode()));
        String origin = response.headers().firstValue("access-control-allow-origin").orElseThrow();
        assertTrue(Set.of("*", "http://127.0.0.1:5173").contains(origin));
    }

    private String key() { return "ec/test/temp/" + UUID.randomUUID(); }

    private ObjectStorageProvider.UploadAuthorization grant(String key, long seconds) {
        return provider.authorizeUpload(bucket, key, "image/png", Instant.now().plusSeconds(seconds));
    }

    private HttpResponse<Void> put(ObjectStorageProvider.UploadAuthorization grant, Map<String, String> headers, String content) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(grant.url())).expectContinue(true)
                .PUT(HttpRequest.BodyPublishers.ofString(content));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }
}
