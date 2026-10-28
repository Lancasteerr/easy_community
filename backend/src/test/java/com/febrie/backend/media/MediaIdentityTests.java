package com.febrie.backend.media;

import com.febrie.backend.media.domain.*;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 使用已知字节序断言，避免两端同时交换字节却仍通过往返测试。 */
class MediaIdentityTests {
    private static final String ID = "00112233-4455-6677-8899-aabbccddeeff";
    @Test void uuidUsesStandardBytesAndExactKeys() {
        assertEquals("00112233445566778899aabbccddeeff", HexFormat.of().formatHex(MediaIds.bytes(ID)));
        assertEquals(ID, MediaIds.text(MediaIds.bytes(ID)));
        var location = new MediaLocation("ec/test");
        assertEquals("ec/test/public/" + ID, location.publicKey(ID));
        assertEquals(ID, location.idFromTempKey(location.tempKey(ID)));
        assertThrows(IllegalArgumentException.class, () -> location.idFromTempKey("ec/prod/temp/" + ID));
        assertThrows(IllegalArgumentException.class, () -> location.idFromTempKey(location.tempKey(ID) + ".png"));
        assertThrows(IllegalArgumentException.class, () -> MediaIds.bytes("1-1-1-1-1"));
    }
}
