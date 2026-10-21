package com.febrie.backend.user.web.request;

import com.febrie.backend.user.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 接收创建用户的请求参数；日志输出不包含密码。 */
public record CreateRequest(@NotBlank @Size(max = 128) String username,
                            @NotBlank @Size(max = 256) String password, Role role) {
    @Override
    public String toString() { return "CreateRequest[credentials=REDACTED]"; }
}
