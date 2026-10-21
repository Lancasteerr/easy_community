package com.febrie.backend.user.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 接收修改密码的请求参数；日志输出不包含密码。 */
public record PasswordRequest(@NotBlank @Size(max = 256) String oldPassword,
                              @NotBlank @Size(max = 256) String newPassword) {
    @Override
    public String toString() { return "PasswordRequest[credentials=REDACTED]"; }
}
