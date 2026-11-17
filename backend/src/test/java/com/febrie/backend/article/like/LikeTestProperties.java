package com.febrie.backend.article.like;

import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import java.time.Duration;

/** 仅供无Spring上下文的测试共享标准配置。 */
final class LikeTestProperties {
    private LikeTestProperties() { }
    static LikeProperties defaults() {
        return new LikeProperties("ec:test:unit:like", Duration.ofSeconds(2), 128,4, 20,
                67108864, 60, 4, Duration.ofMillis(300), "none", -1, 0, 20, Duration.ofMillis(100));
    }
}
