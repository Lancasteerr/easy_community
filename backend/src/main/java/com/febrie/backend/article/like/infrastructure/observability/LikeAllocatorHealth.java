package com.febrie.backend.article.like.infrastructure.observability;

import com.febrie.backend.article.like.infrastructure.redis.LikeVersionAllocator;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** 只读验证当前签发条件，不消耗序号；健康正常不能证明异常宕机前的序号未回退。 */
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
