package com.febrie.backend.user.application.dto;

/** 跨业务展示资料，不读取密码、角色或认证状态；username创建后不可变。 */
public record UserDisplayView(long userId, String username) { }
