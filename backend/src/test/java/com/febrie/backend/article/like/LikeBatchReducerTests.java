package com.febrie.backend.article.like;

import com.febrie.backend.article.like.application.LikeBatchReducer;
import com.febrie.backend.article.like.domain.LikeCommand;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 聚合不依赖消息到达顺序，确认集合必须覆盖所有合法原始投递。 */
class LikeBatchReducerTests {
    private final LikeBatchReducer reducer = new LikeBatchReducer();

    @Test void highestVersionWinsAndEveryOriginalRemainsAcknowledgable() {
        var latest = command(1, true, 12);
        var other = command(2, false, 8);
        var result = reducer.reduce(List.of(command(1, true, 10), latest,
                other, command(1, false, 11), command(1, true, 12)));
        assertEquals(List.of(latest, other), result.commands());
        assertEquals(Set.of(0, 1, 2, 3, 4), new HashSet<>(result.accepted()));
        assertTrue(result.rejected().isEmpty());
    }

    @Test void evenShadowedConflictIsolatesTheEntireRelationOnly() {
        var safe = command(2, true, 20);
        var result = reducer.reduce(List.of(command(1, true, 10), command(1, false, 10),
                command(1, true, 30), safe));
        assertEquals(List.of(safe), result.commands());
        assertEquals(List.of(3), result.accepted());
        assertEquals(List.of(0, 1, 2), result.rejected());
        assertThrows(UnsupportedOperationException.class, () -> result.commands().clear());
    }

    static LikeCommand command(long user, boolean liked, long version) {
        return new LikeCommand(1, UUID.randomUUID().toString(), user, 2L, liked, Long.toString(version), Instant.now());
    }
}
