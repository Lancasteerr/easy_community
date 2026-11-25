package com.febrie.backend.article.comment.infrastructure;

import com.febrie.backend.article.comment.application.CommentErrors;
import com.febrie.backend.article.comment.application.query.CommentListKey;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 签名服务共用密钥，回复游标仅保存楼层、时间边界和有效期。 */
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

    public String encode(Cursor cursor) {
        return seal(cursor);
    }

    /** 已签名数据仍由调用方验证用途、归属及有效期。 */
    public String seal(Object payload) {
        String content = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(payload));
        return content + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(content));
    }

    public Cursor decode(CommentListKey key, String encoded) {
        var cursor = open(encoded, Cursor.class, 1024);
        if (!key.equals(cursor.key()) || cursor.position() == null
                || (!cursor.position().isEmpty() && !cursor.position().matches("[0-9]{19}:[0-9]{19}"))) {
            throw CommentErrors.invalid();
        }
        if (cursor.expiresAt() <= clock.millis()) throw CommentErrors.expired();
        return cursor;
    }

    public void rejectSupersededRoot(CommentListKey key, String encoded) {
        var old = open(encoded, Cursor.class, 1024);
        if (!key.equals(old.key())) throw CommentErrors.invalid();
        throw CommentErrors.expired();
    }

    public <T> T open(String encoded, Class<T> type, int maxLength) {
        try {
            if (encoded == null || encoded.length() > maxLength) throw CommentErrors.invalid();
            String[] parts = encoded.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) {
                throw CommentErrors.invalid();
            }
            T result = json.readValue(Base64.getUrlDecoder().decode(parts[0]), type);
            if (result == null) throw CommentErrors.invalid();
            return result;
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

    // 仅在读取切换前签发的游标时忽略旧排名字段。
    @JsonIgnoreProperties({"snapshotId", "rankVersion"})
    public record Cursor(CommentListKey key, String position, long expiresAt) { }
}
