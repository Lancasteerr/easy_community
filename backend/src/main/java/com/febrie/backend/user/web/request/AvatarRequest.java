package com.febrie.backend.user.web.request;

import jakarta.validation.constraints.NotBlank;

/** 仅提交文件身份，不接受用户名、上传者或业务实体ID。 */
public record AvatarRequest(@NotBlank String mediaId) { }
