package com.febrie.backend.article.content.application;

import com.febrie.backend.article.common.ArticleTagIds;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** BIGINT数值在数据库JSON中无损往返，坏数据不得降级为空数组。 */
class ArticleTagIdsTests {
    @Test void roundTripsEmptyAndLargeNumericIds() {
        assertEquals("[]", ArticleTagIds.encode(List.of()));
        var ids = List.of(1L, 9007199254740995L, Long.MAX_VALUE);
        assertEquals(ids, ArticleTagIds.decode(ArticleTagIds.encode(ids)));
        assertThrows(UnsupportedOperationException.class, () -> ArticleTagIds.decode("[1]").add(2L));
    }
    @Test void rejectsInvalidPersistedCollections() {
        for (String json : new String[]{"null", "{}", "[\"1\"]", "[0]", "[-1]", "[1,1]", "[1.5]",
                "[9223372036854775808]", "[1,2,3,4,5,6]"}) {
            assertThrows(IllegalStateException.class, () -> ArticleTagIds.decode(json), json);
        }
    }
}
