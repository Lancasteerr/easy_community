package com.febrie.backend.media;

import com.febrie.backend.media.application.ObjectStorageProvider;
import com.febrie.backend.media.infrastructure.config.MediaProperties;
import com.febrie.backend.media.infrastructure.storage.CosStorageProvider;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/** 真实COS验收须显式提供专用测试桶；不自动修改已有桶策略，只删除本次已知Key。 */
@EnabledIfEnvironmentVariable(named = "IT_COS_ENDPOINT", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CosStorageTests {
    private final Set<String> createdKeys = ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newHttpClient();
    private CosStorageProvider provider;
    private MediaProperties properties;

    @BeforeAll
    void configure() {
        String bucket = System.getenv("IT_COS_BUCKET");
        assertTrue(bucket != null && bucket.startsWith("ec-media-it-"), "真实COS测试必须使用ec-media-it-开头的专用桶");
        URI endpoint = URI.create(System.getenv("IT_COS_ENDPOINT"));
        properties = new MediaProperties("cos", endpoint, endpoint, System.getenv("IT_COS_REGION"), bucket,
                System.getenv("IT_COS_ACCESS_KEY"), System.getenv("IT_COS_SECRET_KEY"), "ec/test",
                URI.create(System.getenv("IT_COS_PUBLIC_BASE_URL")));
        provider = new CosStorageProvider(properties);
    }

    @AfterAll
    void cleanup() {
        try { if (provider != null) { for (String key : createdKeys) { provider.delete(properties.bucket(), key); } } }
        finally { if (provider != null) { provider.close(); } http.close(); }
    }

    @Test
    void conditionCannotBeRemovedOrOverwrittenAndConcurrentPutHasOneWinner() throws Exception {
        var grant = grant(300);
        var missing = new HashMap<>(grant.headers()); missing.remove("x-cos-forbid-overwrite");
        assertEquals(403, put(grant, missing).statusCode());
        var changed = new HashMap<>(grant.headers()); changed.put("x-cos-forbid-overwrite", "false");
        assertEquals(403, put(grant, changed).statusCode());
        assertEquals(200, put(grant, grant.headers()).statusCode());
        assertEquals(409, put(grant, grant.headers()).statusCode());
        var concurrent = grant(300);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> upload = () -> { start.await(); return put(concurrent, concurrent.headers()).statusCode(); };
            var first = executor.submit(upload); var second = executor.submit(upload); start.countDown();
            assertEquals(List.of(200, 409), java.util.stream.Stream.of(first.get(), second.get()).sorted().toList());
        }
    }

    @Test
    void expirationMetadataCopyAndAnonymousPermissionsAreEffective() throws Exception {
        String key = "ec/test/temp/" + UUID.randomUUID(); createdKeys.add(key);
        var grant = provider.authorizeUpload(properties.bucket(), key, "image/png", Instant.now().plusSeconds(300));
        assertEquals(200, put(grant, grant.headers()).statusCode());
        var metadata = provider.head(properties.bucket(), key).orElseThrow(); assertEquals(5, metadata.size());
        String target = key.replace("/temp/", "/public/") + ".png"; createdKeys.add(target);
        provider.copyToPublic(properties.bucket(), key, target, "image/png", metadata.etag());
        assertEquals(5, provider.head(properties.bucket(), target).orElseThrow().size());
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(properties.publicUrl(key))).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(properties.publicUrl(target))).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        var expired = grant(1); Thread.sleep(2100);
        assertEquals(403, put(expired, expired.headers()).statusCode());
        provider.delete(properties.bucket(), target); provider.delete(properties.bucket(), target);
        assertTrue(provider.head(properties.bucket(), target).isEmpty());
    }

    @Test
    void configuredBrowserOriginAllowsConditionalPut() throws Exception {
        String origin = System.getenv().getOrDefault("IT_MEDIA_ORIGIN", "http://127.0.0.1:5173");
        var request = HttpRequest.newBuilder(properties.uploadEndpoint()).header("Origin", origin)
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type,x-cos-forbid-overwrite")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = http.send(request, HttpResponse.BodyHandlers.discarding());
        assertTrue(Set.of(200, 204).contains(response.statusCode()));
        assertEquals(origin, response.headers().firstValue("access-control-allow-origin").orElseThrow());
    }

    private ObjectStorageProvider.UploadAuthorization grant(long seconds) {
        String key = "ec/test/temp/" + UUID.randomUUID(); createdKeys.add(key);
        return provider.authorizeUpload(properties.bucket(), key, "image/png", Instant.now().plusSeconds(seconds));
    }

    private HttpResponse<Void> put(ObjectStorageProvider.UploadAuthorization grant, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(grant.url())).expectContinue(true).PUT(HttpRequest.BodyPublishers.ofString("image"));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }
}
