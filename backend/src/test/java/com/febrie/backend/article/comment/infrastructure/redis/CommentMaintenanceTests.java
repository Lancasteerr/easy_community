package com.febrie.backend.article.comment.infrastructure.redis;

import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 提交后的 Redis 故障可观测，且不会改变已完成的删除结果。 */
class CommentMaintenanceTests {
    @Test
    void visibilityFailureIsRecordedWithoutThrowing() {
        var metrics = new SimpleMeterRegistry();
        try {
            var values = mock(CommentValues.class);
            when(values.invalidateObject(3)).thenThrow(new RedisConnectionFailureException("测试连接失败"));
            var maintenance = new CommentMaintenance(metrics, values);
            assertDoesNotThrow(() -> maintenance.visibility(new CommentRow(3, 1, 0, null, 2, "正文", Instant.EPOCH, false)));
            assertEquals(1, metrics.counter("community.comment.invalidate.failure").count());
        } finally {
            metrics.close();
        }
    }
}
