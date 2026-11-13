package com.febrie.backend.user.application;

import com.febrie.backend.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 校验简介的实际字符边界，避免把UTF-16长度误当作用户字数。 */
class UserProfilePolicyTests {
    @Test void normalizesEmptyAndTrimsText() {
        assertEquals("", UserProfilePolicy.bio(null));
        assertEquals("", UserProfilePolicy.bio("　 "));
        assertEquals("社区生活", UserProfilePolicy.bio("　 社区生活  "));
    }

    @Test void countsChineseAndEmojiByCodePoint() {
        for (String character : new String[]{"中", "a", "😀"}) {
            assertEquals(character.repeat(50), UserProfilePolicy.bio(character.repeat(50)));
            assertThrows(ApiException.class, () -> UserProfilePolicy.bio(character.repeat(51)));
        }
        assertEquals("👩‍💻", UserProfilePolicy.bio("👩‍💻"));
    }

    @Test void rejectsControlsLineSeparatorsAndUnpairedSurrogates() {
        for (String value : new String[]{"\n简介", "简介\r", "\t", "a\u0000b", "a\u007fb", "a\u2028b", "a\u2029b", "\uD800"}) {
            assertThrows(ApiException.class, () -> UserProfilePolicy.bio(value));
        }
    }
}
