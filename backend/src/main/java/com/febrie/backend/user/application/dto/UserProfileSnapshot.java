package com.febrie.backend.user.application.dto;

import com.febrie.backend.user.domain.Role;
import java.time.Instant;

/** 用户资料与安全版本的同次读取；createdAt保留原始加入时间，仅供用户应用服务使用。 */
public record UserProfileSnapshot(long id, String username, Role role, String status, long authVersion,
                                  String avatarMediaId, String bio, Instant createdAt) { }
