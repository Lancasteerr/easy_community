/**
 * 使用 Redis 统计每分钟请求数及十五分钟内的登录失败次数。
 * 计数和过期时间一起提交，超过阈值时返回重试等待时间。
 */
package com.febrie.backend.auth.infrastructure.ratelimit;
