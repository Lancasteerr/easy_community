package com.febrie.backend.user.application;

import com.febrie.backend.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/** 文字展示资料的规范独立于登录账号规则。 */
public final class UserProfilePolicy {
    private UserProfilePolicy() { }

    public static String bio(String value) {
        if (value == null) { return ""; }
        // 在去空白前拒绝换行与控制字符，避免非法内容被 strip 静默吞掉。
        if (value.codePoints().anyMatch(code -> Character.isISOControl(code)
                || code == 0x2028 || code == 0x2029)) {
            throw invalidBio();
        }
        String normalized = value.strip();
        if (normalized.codePointCount(0, normalized.length()) > 50
                || normalized.codePoints().anyMatch(code -> code >= 0xD800 && code <= 0xDFFF)) {
            throw invalidBio();
        }
        return normalized;
    }

    private static ApiException invalidBio() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BIO", "个人简介须为最多50个Unicode码点的单行文本，不能包含控制字符");
    }
}
