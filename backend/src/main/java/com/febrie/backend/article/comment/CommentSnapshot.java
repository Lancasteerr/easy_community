package com.febrie.backend.article.comment;

import java.util.UUID;

/** Redis 元数据同时用于校验单页读取与有条件发布。 */
record CommentSnapshot(String id, String version, long softAt, long hardAt, long count) {
    static CommentSnapshot parse(String value) {
        if (value == null) return null;
        String[] parts = value.split("\\|", -1);
        if (parts.length != 5) throw CommentErrors.unavailable();
        UUID.fromString(parts[0]);
        return new CommentSnapshot(parts[0], parts[1], Long.parseLong(parts[2]),
                Long.parseLong(parts[3]), Long.parseLong(parts[4]));
    }

    String payload() {
        return String.join("|", id, version, Long.toString(softAt), Long.toString(hardAt), Long.toString(count));
    }

    static String member(long sortValue, long id) {
        return padded(sortValue) + ":" + padded(id);
    }

    static long commentId(String member) {
        return Long.parseLong(member.substring(20));
    }

    private static String padded(long value) {
        String text = Long.toString(value);
        return "0".repeat(19 - text.length()) + text;
    }
}
