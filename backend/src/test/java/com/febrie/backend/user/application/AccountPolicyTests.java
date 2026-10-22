package com.febrie.backend.user.application;

import com.febrie.backend.shared.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖用户名大小写、空白和长度规则，以及BCrypt字节边界，防止隐式截断密码。 */
class AccountPolicyTests {
    @Test
    void trimsUsernameAndPreservesCase() {
        assertEquals("ALIce_1", AccountPolicy.username("  ALIce_1  "));
        assertEquals("alice_1", AccountPolicy.username("alice_1"));
        assertEquals("ALICE_1", AccountPolicy.username("ALICE_1"));
    }

    @Test
    void acceptsUsernameLengthBoundaries() {
        assertEquals("Aa0_", AccountPolicy.username("Aa0_"));
        String maximum = "Aa0_".repeat(8);
        assertEquals(maximum, AccountPolicy.username(maximum));
    }

    @Test
    void rejectsMissingOutOfRangeAndUnsupportedUsernames() {
        // 内部空白、非英文字符及越界长度均返回稳定的用户名错误码。
        for (String invalid : new String[]{null, "", "   ", "abc", "a".repeat(33),
                "中文用户名", "alice bob", "alice\tbob", "alice-bob", "alice.bob", "Ａlice", "alice!"}) {
            var error = assertThrows(ApiException.class, () -> AccountPolicy.username(invalid));
            assertEquals("INVALID_USERNAME", error.code());
        }
    }

    @Test
    void checksUtf8BytesWithoutTrimmingPassword() {
        assertDoesNotThrow(() -> AccountPolicy.password("中".repeat(24)));
        assertThrows(ApiException.class, () -> AccountPolicy.password("中".repeat(25)));
        assertDoesNotThrow(() -> AccountPolicy.password("        "));
        assertThrows(ApiException.class, () -> AccountPolicy.password("short"));
    }
}
