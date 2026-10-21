/**
 * 封装认证使用的 Redis 事务，将写入和过期时间放在同一连接上提交。
 * 事务失败继续抛出错误，不将认证当作成功。
 */
package com.febrie.backend.auth.infrastructure.redis;
