/**
 * 保存登录时确定的身份、角色、安全版本和到期时间。
 * SessionPrincipal 会写入 Redis 会话，不包含密码，普通请求不会自动更新其中的角色或版本。
 */
package com.febrie.backend.auth.application.dto;
