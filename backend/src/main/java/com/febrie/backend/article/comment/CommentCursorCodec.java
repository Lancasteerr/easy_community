package com.febrie.backend.article.comment;

import com.febrie.backend.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 排名游标绑定不可变快照，时间游标只绑定不可变的时间和 ID 排序键。 */
@Component
public class CommentCursorCodec {
    private final ObjectMapper json;
    private final Clock clock;
    private final byte[] secret;

    public CommentCursorCodec(ObjectMapper json, Clock clock, @Value("${app.comment.cursor-secret:}") String configured) {
        this.json = json;
        this.clock = clock;
        // 单进程可直接运行；多个 Web 实例使用同一个显式密钥。
        if (configured.isBlank()) {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
        } else {
            secret = configured.getBytes(StandardCharsets.UTF_8);
            if (secret.length < 32) throw new IllegalArgumentException("评论游标签名密钥至少需要 32 字节");
        }
    }

    String encode(Cursor cursor) {
        String content = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(cursor));
        return content + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(content));
    }

    Cursor decode(CommentListKey key, String encoded) {
        try {
            if (encoded.length() > 1024) throw CommentErrors.invalid();
            String[] parts = encoded.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) {
                throw CommentErrors.invalid();
            }
            var cursor = json.readValue(Base64.getUrlDecoder().decode(parts[0]), Cursor.class);
            if (!key.equals(cursor.key()) || cursor.position() == null) throw CommentErrors.invalid();
            if (key.sort() == CommentListKey.Sort.HOT) {
                UUID.fromString(cursor.snapshotId());
                if (!cursor.rankVersion().matches("[1-9][0-9]{0,18}") ||
                        !cursor.position().matches("[0-9]{1,18}")) throw CommentErrors.invalid();
            } else if (cursor.snapshotId() != null || cursor.rankVersion() != null ||
                    !cursor.position().matches("[0-9]{19}:[0-9]{19}")) {
                throw CommentErrors.invalid();
            }
            if (cursor.expiresAt() <= clock.millis()) throw CommentErrors.expired();
            return cursor;
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException invalid) {
            throw CommentErrors.invalid();
        }
    }

    private byte[] sign(String content) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("评论游标签名失败", failure);
        }
    }

    record Cursor(CommentListKey key, String snapshotId, String rankVersion, String position, long expiresAt) { }
}
