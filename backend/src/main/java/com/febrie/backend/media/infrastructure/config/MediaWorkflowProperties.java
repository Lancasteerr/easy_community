package com.febrie.backend.media.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 上传期限与回收调度集中配置；验签和通知密钥绝不进入toString。 */
@ConfigurationProperties("app.media.workflow")
public record MediaWorkflowProperties(
        @DefaultValue("5m") Duration policyTtl,
        @DefaultValue("15m") Duration notificationTtl,
        @DefaultValue("2h") Duration issuedTtl,
        @DefaultValue("24h") Duration unreferencedTtl,
        @DefaultValue("5m") Duration deleteRetryDelay,
        @DefaultValue("4500ms") Duration processingBudget,
        @DefaultValue("30") int uploadsPerMinute,
        @DefaultValue("100") int cleanupBatchSize,
        @DefaultValue("65536") int callbackMaxBytes,
        @DefaultValue("1048576") int webhookMaxBytes,
        @DefaultValue("10") int webhookMaxRecords,
        @DefaultValue("ec:test") String redisPrefix,
        @DefaultValue("") String callbackUrl,
        @DefaultValue("") String callbackKey,
        @DefaultValue("") String moderationBizType,
        @DefaultValue("") String webhookToken) {
    public MediaWorkflowProperties {
        for (Duration value : new Duration[]{policyTtl, notificationTtl, issuedTtl, unreferencedTtl, deleteRetryDelay, processingBudget}) {
            if (value == null || value.isZero() || value.isNegative()) { throw new IllegalArgumentException("文件期限必须为正数"); }
        }
        if (policyTtl.compareTo(notificationTtl) >= 0 || notificationTtl.plus(processingBudget).compareTo(issuedTtl) >= 0
                || processingBudget.compareTo(Duration.ofMillis(4500)) > 0 || processingBudget.toMillis() < 100
                || uploadsPerMinute <= 0 || cleanupBatchSize <= 0 || callbackMaxBytes <= 0
                || webhookMaxBytes <= 0 || webhookMaxRecords <= 0) {
            throw new IllegalArgumentException("文件期限顺序、处理预算或容量限制无效");
        }
    }

    @Override public String toString() { return "MediaWorkflowProperties[secrets=REDACTED]"; }
}
