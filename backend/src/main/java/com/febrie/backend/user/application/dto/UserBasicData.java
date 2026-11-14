package com.febrie.backend.user.application.dto;

import java.time.Instant;

/** 用户展示投影供资料与历史署名共享；createdAt为账号加入时间，不携带认证字段。 */
public record UserBasicData(long userId, String username, String avatarMediaId, String bio, Instant createdAt) { }
