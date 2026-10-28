package com.febrie.backend.media;

import com.febrie.backend.media.web.CosCallbackAuth;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 固定外部计算的验签向量，覆盖query和原始正文篡改。 */
class CosCallbackTests {
    @Test void signatureBindsRawQueryPathAndBody() {
        byte[] body = "{\"size\":123}".getBytes(StandardCharsets.UTF_8);
        String signature = "L2gQb0UPm1Vk7pNMmx6WGaR1XW4=";
        assertTrue(CosCallbackAuth.verify("/api/storage/cos/upload-callback", "a=%2F", body, signature, "test-callback-key"));
        assertFalse(CosCallbackAuth.verify("/api/storage/cos/upload-callback", "a=/", body, signature, "test-callback-key"));
        assertFalse(CosCallbackAuth.verify("/api/storage/cos/upload-callback", "a=%2F", "{}".getBytes(StandardCharsets.UTF_8), signature, "test-callback-key"));
        assertFalse(CosCallbackAuth.verify("/api/storage/cos/upload-callback", "a=%2F", body, "invalid", "test-callback-key"));
    }
}
