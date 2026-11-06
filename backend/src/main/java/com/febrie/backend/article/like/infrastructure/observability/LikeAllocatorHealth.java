package com.febrie.backend.article.like.infrastructure.observability;

import com.febrie.backend.article.like.infrastructure.redis.LikeVersionAllocator;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** 始终只读验证签发栅栏，不消耗序号；健康异常不停止后台消费。 */
@Component
public class LikeAllocatorHealth implements HealthIndicator {
    private final LikeVersionAllocator allocator;
    public LikeAllocatorHealth(LikeVersionAllocator allocator) {
        this.allocator = allocator;
    }
    @Override public Health health() {
        return allocator.ready() ? Health.up().build() : Health.down().build();
    }
}
