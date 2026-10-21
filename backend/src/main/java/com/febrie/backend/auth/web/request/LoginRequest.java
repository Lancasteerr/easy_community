package com.febrie.backend.auth.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 接收登录账号和密码；仅用户名会规范化，日志输出不包含凭证。 */
public record LoginRequest(@NotBlank @Size(max = 128) String username,
                           @NotBlank @Size(max = 256) String password) {
    @Override
    public String toString() { return "LoginRequest[credentials=REDACTED]"; }
}
