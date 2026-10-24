package com.febrie.backend.user.application.dto;

import com.febrie.backend.user.domain.Role;

/** 用户资料与安全版本的同次读取，不包含密码，仅供用户应用服务使用。 */
public record UserProfileSnapshot(long id, String username, Role role, String status, long authVersion, String avatarMediaId) { }
