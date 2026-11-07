package com.febrie.backend.article.search.application;

import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import com.febrie.backend.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 无服务端分页会话；签名绑定查询及页大小，密钥由各实例共享。 */
@Component
public class SearchCursorCodec {
    private final ObjectMapper json;
    private final byte[] secret;
    private final Clock clock;
    public SearchCursorCodec(ObjectMapper json, SearchProperties p, Clock clock) {
        this.json = json; this.secret = p.cursorSecret().getBytes(StandardCharsets.UTF_8); this.clock = clock;
    }
    public String encode(ArticleSearchQuery query, String pit, List<SearchCursor.Value> sort) {
        var state = new SearchCursor(1, query.fingerprint(), pit, sort, clock.millis() + 120_000);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(state));
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }
    public SearchCursor decode(String cursor, ArticleSearchQuery query) {
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) { throw SearchErrors.invalid(); }
            var state = json.readValue(Base64.getUrlDecoder().decode(parts[0]), SearchCursor.class);
            if (state.version() != 1 || !query.fingerprint().equals(state.fingerprint())
                    || state.pit() == null || state.pit().isBlank() || state.sort() == null || state.sort().isEmpty()) { throw SearchErrors.invalid(); }
            state.sort().forEach(SearchCursor.Value::field);
            if (state.expiresAt() <= clock.millis()) { throw SearchErrors.expired(); }
            return state;
        } catch (ApiException known) { throw known; }
        catch (RuntimeException invalid) { throw SearchErrors.invalid(); }
    }
    private byte[] sign(String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("游标签名失败", failure); }
    }
}
