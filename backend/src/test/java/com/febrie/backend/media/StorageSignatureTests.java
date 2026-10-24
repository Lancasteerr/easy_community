package com.febrie.backend.media;

import com.febrie.backend.media.infrastructure.config.MediaProperties;
import com.febrie.backend.media.infrastructure.storage.MinioStorageProvider;
import com.febrie.backend.media.infrastructure.storage.CosStorageProvider;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 离线验证签名覆盖关键条件，不连接真实存储，不使用真实凭证。 */
class StorageSignatureTests {
    static MediaProperties properties(String provider) {
        return new MediaProperties(provider, URI.create("http://127.0.0.1:9000"), URI.create("http://127.0.0.1:9000"),
                "us-east-1", "media-test", "test-access", "test-secret", "ec/test", URI.create("https://images.example.com"));
    }

    @Test
    void minioSignsConditionalHeaderAndContentType() {
        try (var provider = new MinioStorageProvider(properties("minio"), Clock.systemUTC())) {
            var grant = provider.authorizeUpload("media-test", "ec/test/temp/test", "image/png", Instant.now().plusSeconds(300));
            assertEquals("PUT", grant.method());
            assertEquals("*", grant.headers().get("if-none-match"));
            assertEquals("image/png", grant.headers().get("content-type"));
            assertTrue(grant.url().contains("X-Amz-SignedHeaders=content-type%3Bhost%3Bif-none-match"));
            assertFalse(grant.headers().containsKey("host"));
        }
    }

    @Test
    void propertiesRejectUnscopedPrefixesAndNeverPrintCredentials() {
        var properties = properties("minio");
        assertFalse(properties.toString().contains("test-secret"));
        assertFalse(properties.toString().contains("test-access"));
        assertThrows(IllegalArgumentException.class, () -> new MediaProperties("minio", properties.endpoint(),
                properties.uploadEndpoint(), "us-east-1", "test", "key", "secret", "../prod", properties.publicBaseUrl()));
        assertEquals("https://images.example.com/ec/test/public/a.png", properties.publicUrl("ec/test/public/a.png"));
    }

    @Test
    void cosSignsNativeConditionHostAndContentType() {
        try (var provider = new CosStorageProvider(properties("cos"))) {
            var grant = provider.authorizeUpload("media-test", "ec/test/temp/test", "image/png", Instant.now().plusSeconds(300));
            assertEquals("PUT", grant.method());
            assertEquals("true", grant.headers().get("x-cos-forbid-overwrite"));
            String query = URLDecoder.decode(URI.create(grant.url()).getRawQuery(), StandardCharsets.UTF_8);
            assertTrue(query.contains("q-header-list=content-type;host;x-cos-forbid-overwrite"));
            assertEquals("image/png", grant.headers().get("Content-Type"));
        }
    }
}
