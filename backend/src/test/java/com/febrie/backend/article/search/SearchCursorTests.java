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
        var query = new ArticleSearchQuery("search", " Java ", null, null, 20);
        long id = 9_007_199_254_740_993L;
        var cursor = codec.encode(query, "pit", List.of(SearchCursor.Value.from(FieldValue.of(id))));
        assertEquals(id, codec.decode(cursor, query).sort().getFirst().field().longValue());
        assertEquals(400, assertThrows(ApiException.class, () -> codec.decode("x" + cursor, query)).status().value());
        assertEquals(400, assertThrows(ApiException.class, () -> codec.decode(cursor,
                new ArticleSearchQuery("search", "Java", null, "latest", 20))).status().value());
        var later = new SearchCursorCodec(new ObjectMapper(), p, Clock.offset(now, Duration.ofMinutes(3)));
        assertEquals("ARTICLE_CURSOR_EXPIRED", assertThrows(ApiException.class, () -> later.decode(cursor, query)).code());
    }
    @Test void validatesOnlyPublicQueryChoices() {
        assertEquals("relevance", new ArticleSearchQuery("search", "Java", null, null, 20).sort());
        assertEquals("latest", new ArticleSearchQuery("search", null, 1L, null, 20).sort());
        assertThrows(ApiException.class, () -> new ArticleSearchQuery("search", " ", null, null, 20));
        assertThrows(ApiException.class, () -> new ArticleSearchQuery("search", null, 1L, "relevance", 20));
        assertThrows(ApiException.class, () -> new ArticleSearchQuery("search", "x", null, "arbitrary", 20));
    }
}
