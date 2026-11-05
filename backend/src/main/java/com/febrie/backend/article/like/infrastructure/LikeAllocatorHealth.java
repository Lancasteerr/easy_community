package com.febrie.backend.article.like.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** 健康检查只读验证签发栅栏，不消耗序号；只消费实例不因签发暂停而停止排空MQ。 */
@Component
@ConditionalOnProperty(name = "app.like.enabled", havingValue = "true")
public class LikeAllocatorHealth implements HealthIndicator {
    private final LikeVersionAllocator allocator;
    private final LikeProperties properties;
    public LikeAllocatorHealth(LikeVersionAllocator allocator, LikeProperties properties) {
        this.allocator = allocator; this.properties = properties;
    }
    @Override public Health health() {
        if (!properties.writeEnabled()) { return Health.up().withDetail("writer", "disabled").build(); }
        return allocator.ready() ? Health.up().build() : Health.down().build();
    }
}
