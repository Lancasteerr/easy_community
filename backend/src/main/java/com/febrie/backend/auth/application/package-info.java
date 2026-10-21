/**
 * 组织密码认证和会话校验。
 * 登录先检查失败次数再验证密码；受保护请求检查账户版本及会话有效性，不在这里操作 HTTP 请求或 Cookie。
 */
package com.febrie.backend.auth.application;
