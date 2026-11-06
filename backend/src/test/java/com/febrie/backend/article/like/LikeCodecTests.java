package com.febrie.backend.article.like;

import com.febrie.backend.article.like.domain.LikeCommand;
import com.febrie.backend.article.like.infrastructure.messaging.LikeCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 线协议严格校验字段类型，不能把格式错误命令宽松转换后执行。 */
class LikeCodecTests {
    final LikeCodec codec = new LikeCodec(new JsonMapper());
    final LikeCommand command = new LikeCommand(1, UUID.randomUUID().toString(), 1L, 2L, true, "10", Instant.now());
    @Test void explicitJsonRoundTripsWithoutJavaTypeMetadata() {
        var bytes = codec.encode(command);
        assertEquals(command, codec.decode(bytes));
        assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("@class"));
    }
    @Test void wrongTypesRangesAndTimestampAreRejected() {
        String json = new String(codec.encode(command), StandardCharsets.UTF_8);
        for (String invalid : java.util.List.of(json.replace("\"desiredLiked\":true", "\"desiredLiked\":\"true\""),
                json.replace("\"userId\":1", "\"userId\":1.5"), json.replace("\"articleId\":2", "\"articleId\":-2"),
                json.replace("\"version\":\"10\"", "\"version\":10"), json.replace(command.occurredAt().toString(), "yesterday"),
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), json.replace("{", "{\"extra\":1,"))) {
            assertThrows(RuntimeException.class, () -> codec.decode(invalid.getBytes(StandardCharsets.UTF_8)), invalid);
        }
        assertThrows(RuntimeException.class, () -> codec.decode(new byte[4097]));
    }
}
