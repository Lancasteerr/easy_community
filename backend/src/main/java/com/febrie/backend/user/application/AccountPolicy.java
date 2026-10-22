package com.febrie.backend.user.application;

import com.febrie.backend.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;

/** 各入口共享账号规范，密码保持原样以避免无声改变凭证。 */
public final class AccountPolicy {
    private AccountPolicy() { }

    /** 仅去除首尾空白；创建、登录和判重均区分用户名大小写。 */
    public static String username(String value) {
        String trimmedUsername = value == null ? "" : value.strip();
        if (!trimmedUsername.matches("[A-Za-z0-9_]{4,32}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USERNAME", "用户名须为4至32位大小写英文字母、数字或下划线，区分大小写");
        }
        return trimmedUsername;
    }

    public static void password(String value) {
        if (value == null || value.codePointCount(0, value.length()) < 8
                || value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "密码至少8个字符且UTF-8编码不超过72字节");
        }
    }
}
