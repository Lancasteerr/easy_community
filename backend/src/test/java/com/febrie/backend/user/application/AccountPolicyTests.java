package com.febrie.backend.user.application;

import com.febrie.backend.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖用户名规范化和BCrypt字节边界，防止隐式截断密码。 */
class AccountPolicyTests {
    @Test
    void normalizesUsernameAndRejectsUnsupportedCharacters() {
        assertEquals("alice_1", AccountPolicy.username("  ALIce_1  "));
        assertThrows(ApiException.class, () -> AccountPolicy.username("中文用户名"));
        assertThrows(ApiException.class, () -> AccountPolicy.username("abc"));
    }

    @Test
    void checksUtf8BytesWithoutTrimmingPassword() {
        assertDoesNotThrow(() -> AccountPolicy.password("中".repeat(24)));
        assertThrows(ApiException.class, () -> AccountPolicy.password("中".repeat(25)));
        assertDoesNotThrow(() -> AccountPolicy.password("        "));
        assertThrows(ApiException.class, () -> AccountPolicy.password("short"));
    }
}
