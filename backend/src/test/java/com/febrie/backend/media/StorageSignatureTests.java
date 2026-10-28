package com.febrie.backend.media;

import com.febrie.backend.media.infrastructure.config.*;
import com.febrie.backend.media.infrastructure.storage.MinioUploadStorage;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** 离线检查统一POST描述与生产装配约束，不将离线签名等同于云端验收。 */
class StorageSignatureTests {
    static MediaProperties properties(String provider) {
        return new MediaProperties(provider, URI.create("http://127.0.0.1:9000"), URI.create("http://127.0.0.1:19002"),
                "us-east-1", "media-test", "test-access", "test-secret", "ec/test", URI.create("https://images.example.com"));
    }
    @Test void minioGrantUsesPostFieldsAndDynamicUrl() {
        var provider = new MinioUploadStorage(properties("minio"));
        try {
            Instant now = Instant.now();
            var grant = provider.authorize("ec/test/temp/id", "image/png", 123, now, now.plusSeconds(300));
            assertEquals("POST", grant.method());
            assertEquals("http://127.0.0.1:19002/media-test", grant.url());
            assertEquals("image/png", grant.fields().get("Content-Type"));
            assertFalse(grant.fields().containsKey("If-None-Match"));
        } finally { provider.close(); }
    }
    @Test void productionRejectsDevelopmentProviderAndMissingRealModeration() {
        var env = new MockEnvironment(); env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new MediaConfiguration(properties("minio"), PostPolicyTests.workflow(), env));
        assertFalse(properties("cos").toString().contains("test-secret"));
        assertFalse(PostPolicyTests.workflow().toString().contains("https://"));
    }
}
