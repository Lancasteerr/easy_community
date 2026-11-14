package com.febrie.backend.auth.web.response;

import com.febrie.backend.user.domain.Role;

/** 当前有效会话的最小身份；ID使用字符串，资料与内部认证字段均不在此协议中。 */
public record SessionView(String userId, Role role) { }
