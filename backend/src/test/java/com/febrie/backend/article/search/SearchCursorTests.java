package com.febrie.backend.article.search;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.febrie.backend.article.search.application.*;
import com.febrie.backend.shared.error.ApiException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 验证签名、查询绑定、过期和long精度，不建立持久分页会话。 */
class SearchCursorTests {
    @Test void preservesLongAndRejectsTamperingDifferentQueryAndExpiration() {
        var p = SearchTestSupport.properties(Map.of("app.search.cursor-secret", "test-secret-32-bytes-abcdefghijklmnop"));
        Clock now = Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC);
        var codec = new SearchCursorCodec(new ObjectMapper(), p, now);
        var query = new ArticleSearchQuery("search", " Java ", null, null, null, 20);
        long id = 9_007_199_254_740_993L;
        var cursor = codec.encode(query, "pit", List.of(SearchCursor.Value.from(FieldValue.of(id))));
        assertEquals(id, codec.decode(cursor, query).sort().getFirst().field().longValue());
        assertEquals(400, assertThrows(ApiException.class, () -> codec.decode("x" + cursor, query)).status().value());
        assertEquals(400, assertThrows(ApiException.class, () -> codec.decode(cursor,
                new ArticleSearchQuery("search", "Java", null, null, "latest", 20))).status().value());
        var later = new SearchCursorCodec(new ObjectMapper(), p, Clock.offset(now, Duration.ofMinutes(3)));
        assertEquals("ARTICLE_CURSOR_EXPIRED", assertThrows(ApiException.class, () -> later.decode(cursor, query)).code());
    }
    @Test void validatesOnlyPublicQueryChoices() {
        assertEquals("relevance", new ArticleSearchQuery("search", "Java", null, null, null, 20).sort());
        assertEquals("latest", new ArticleSearchQuery("search", null, 1L, null, null, 20).sort());
        assertThrows(ApiException.class, () -> new ArticleSearchQuery("search", " ", null, null, null, 20));
        assertThrows(ApiException.class, () -> new ArticleSearchQuery("search", null, 1L, null, "relevance", 20));
        assertThrows(ApiException.class, () -> new ArticleSearchQuery("search", "x", null, null, "arbitrary", 20));
    }

    @Test void authorQueriesAreValidatedAndCursorsBindAuthorEntryAndSize() {
        var p = SearchTestSupport.properties(Map.of("app.search.cursor-secret", "test-secret-32-bytes-abcdefghijklmnop"));
        var codec = new SearchCursorCodec(new ObjectMapper(), p, Clock.systemUTC());
        var mine = new ArticleSearchQuery("mine", null, null, 9L, "latest", 20);
        var cursor = codec.encode(mine, "pit", List.of(new SearchCursor.Value("long", "1")));
        assertEquals(2, codec.decode(cursor, mine).version());
        for (var other : List.of(new ArticleSearchQuery("mine", null, null, 10L, "latest", 20),
                new ArticleSearchQuery("search", null, null, 9L, "latest", 20),
                new ArticleSearchQuery("mine", null, null, 9L, "latest", 10))) {
            assertEquals(400, assertThrows(ApiException.class, () -> codec.decode(cursor, other)).status().value());
        }
        assertEquals("latest", new ArticleSearchQuery("search", null, null, 9L, null, 20).sort());
        assertThrows(ApiException.class, () -> new ArticleSearchQuery("search", null, null, 0L, null, 20));
        assertThrows(ApiException.class, () -> new ArticleSearchQuery("mine", null, null, null, "latest", 20));
        assertThrows(ApiException.class, () -> new ArticleSearchQuery("mine", "Java", null, 9L, "latest", 20));
        assertThrows(ApiException.class, () -> new ArticleSearchQuery("mine", null, 1L, 9L, "latest", 20));
        assertThrows(ApiException.class, () -> new ArticleSearchQuery("mine", null, null, 9L, "recommended", 20));
        assertThrows(ApiException.class, () -> new ArticleSearchQuery("feed", null, null, 9L, "recommended", 20));
    }

    @Test void signedLegacyCursorIsRejected() throws Exception {
        String secret = "test-secret-32-bytes-abcdefghijklmnop";
        var codec = new SearchCursorCodec(new ObjectMapper(),
                SearchTestSupport.properties(Map.of("app.search.cursor-secret", secret)), Clock.systemUTC());
        var query = new ArticleSearchQuery("search", "Java", null, null, null, 20);
        // 使用正确签名和当前指纹，仅将协议版本设为旧值，验证版本检查本身。
        var state = new SearchCursor(1, query.fingerprint(), "pit", List.of(new SearchCursor.Value("long", "1")), Long.MAX_VALUE);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(new ObjectMapper().writeValueAsBytes(state));
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(400, assertThrows(ApiException.class, () -> codec.decode(payload + "." + signature, query)).status().value());
    }
}
