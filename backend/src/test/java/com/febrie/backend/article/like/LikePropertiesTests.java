package com.febrie.backend.article.like;

import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** 启动时验证有界配置，避免错误预取量导致批次无法按预期收集。 */
class LikePropertiesTests {
    @Test void defaultsAndInvalidBoundariesAreCheckedByRealBinder() {
        var defaults = bind(Map.of());
        assertEquals(4, defaults.partitionCount()); assertEquals(20, defaults.batchSize());
        assertEquals(Duration.ofMillis(100), defaults.batchWait());
        for (var invalid : List.of(Map.of("partition-count", "0"), Map.of("partition-count", "65"),
                Map.of("batch-size", "0"), Map.of("prefetch", "19"), Map.of("batch-wait", "0ms"), Map.of("batch-wait", "-1ms"))) {
            assertThrows(BindException.class, () -> bind(invalid));
        }
    }
    private static LikeProperties bind(Map<String, String> values) {
        Map<String, Object> source = new HashMap<>(); source.put("app.like.namespace", "ec:test:properties:like");
        values.forEach((key, value) -> source.put("app.like." + key, value));
        return new Binder(new MapConfigurationPropertySource(source)).bind("app.like", Bindable.of(LikeProperties.class)).get();
    }
}
