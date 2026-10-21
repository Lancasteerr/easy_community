package com.febrie.backend.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 环境隔离、绝对有效期及限流阈值，启动时校验必填值。 */
@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(@NotBlank String environment, @NotBlank String redisPrefix,
                             boolean secureCookie, @NotNull Duration sessionTtl,
                             @Min(1) int csrfPerMinute, @Min(1) int loginPerMinute,
                             @Min(1) int userPerMinute, @Min(1) int accountFailures,
                             @Min(1) int ipFailures, @NotNull Duration cleanupDelay) { }
