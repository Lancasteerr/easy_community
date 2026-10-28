package com.febrie.backend.media.domain;

import java.nio.ByteBuffer;
import java.util.UUID;

/** API与Key使用标准小写UUID，SQL使用不交换字节的16字节表示。 */
public final class MediaIds {
    private MediaIds() { }

    public static String canonical(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("文件ID必须为标准小写UUID");
        }
        return UUID.fromString(value).toString();
    }

    public static byte[] bytes(String value) {
        UUID id = UUID.fromString(canonical(value));
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    public static String text(byte[] value) {
        if (value == null) { return null; }
        if (value.length != 16) { throw new IllegalArgumentException("数据库文件ID必须为16字节"); }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }
}
