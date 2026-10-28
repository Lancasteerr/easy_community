package com.febrie.backend.media;

import com.febrie.backend.media.infrastructure.config.*;
import com.febrie.backend.media.infrastructure.storage.PostPolicies;
import com.qcloud.cos.auth.COSSigner;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 解码真实Policy并与官方COS签名器交叉检查，避免仅断言实现内部结果。 */
class PostPolicyTests {
    static MediaWorkflowProperties workflow() {
        return new Binder(new MapConfigurationPropertySource(Map.of("app.media.workflow.callback-url", "https://api.example.com/api/storage/cos/upload-callback")))
                .bindOrCreate("app.media.workflow", Bindable.of(MediaWorkflowProperties.class));
    }
    @Test void policiesBindSizeIdentityTypeAndProviderFields() {
        Instant created = Instant.parse("2026-10-29T00:00:00Z");
        var p = StorageSignatureTests.properties("cos");
        var fields = PostPolicies.cos(p, workflow(), "ec/test/temp/id", "image/png", 123, created, created.plusSeconds(300));
        String json = new String(Base64.getDecoder().decode(fields.get("policy")), java.nio.charset.StandardCharsets.UTF_8);
        var tree = new ObjectMapper().readTree(json);
        assertTrue(tree.get("conditions").toString().contains("[\"content-length-range\",123,123]"));
        assertTrue(json.contains("q-sign-time"));
        assertTrue(json.contains("x-cos-callback"));
        assertEquals("true", fields.get("x-cos-forbid-overwrite"));
        assertEquals(new COSSigner().buildPostObjectSignature(p.secretKey(), fields.get("q-key-time"), json), fields.get("q-signature"));
        var minio = PostPolicies.minio(p, "ec/test/temp/id", "image/png", 123, created, created.plusSeconds(300));
        assertEquals("AWS4-HMAC-SHA256", minio.get("x-amz-algorithm"));
        assertFalse(minio.keySet().stream().anyMatch(name -> name.startsWith("x-cos") || name.equalsIgnoreCase("if-none-match")));
        assertEquals(64, minio.get("x-amz-signature").length());
    }
}
