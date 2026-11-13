package com.febrie.backend.user.application.dto;

/** 用户展示投影供资料与历史署名共享；停用和软删除账号仍保留资料，不携带认证字段。 */
public record UserBasicData(long userId, String username, String avatarMediaId, String bio) { }
