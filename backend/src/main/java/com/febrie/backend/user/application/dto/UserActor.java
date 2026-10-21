package com.febrie.backend.user.application.dto;

import com.febrie.backend.user.domain.Role;

/** 从已认证身份提取操作人信息；只能由服务端构造，不接收客户端提交的身份。 */
public record UserActor(long userId, Role role, long authVersion) {
}
