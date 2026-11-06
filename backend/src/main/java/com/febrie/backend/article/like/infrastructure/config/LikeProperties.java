package com.febrie.backend.article.like.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 点赞固定集成；仅配置容量、命名空间和显式非Web维护动作。 */
@ConfigurationProperties("app.like")
public record LikeProperties(@DefaultValue("ec:dev:like") String namespace, @DefaultValue("2s") Duration confirmTimeout,
        @DefaultValue("128") int pendingLimit, @DefaultValue("2") int concurrency,
        @DefaultValue("20") int prefetch, @DefaultValue("67108864") long queueBytes,
        @DefaultValue("60") int perMinute, @DefaultValue("4") int readConcurrency,
        @DefaultValue("300ms") Duration readTimeout, @DefaultValue("none") String maintenance,
        @DefaultValue("-1") int expectedEpoch, @DefaultValue("0") int replayLimit) {
    public LikeProperties {
        if (namespace == null || !namespace.matches("ec:(dev|test|prod)[A-Za-z0-9:_-]*:like")
                || confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.isZero()
                || pendingLimit < 1 || concurrency < 1 || prefetch < 1 || queueBytes < 4096
                || perMinute < 1 || readConcurrency < 1 || readTimeout == null
                || readTimeout.isNegative() || readTimeout.isZero()
                || !java.util.Set.of("none", "status", "pause", "recover", "replay", "audit").contains(maintenance)) {
            throw new IllegalArgumentException("点赞配置无效");
        }
    }
    public boolean maintaining() { return !maintenance.equals("none"); }
    public String name(String suffix) { return namespace.replace(':', '.') + ".article.like." + suffix; }
    public String key(String suffix) { return namespace + ":" + suffix; }
}
