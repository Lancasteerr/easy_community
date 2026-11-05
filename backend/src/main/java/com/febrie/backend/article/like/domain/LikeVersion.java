package com.febrie.backend.article.like.domain;

import java.util.Locale;

/** 低43位是同纪元序号；完整long不交给Lua浮点数或JS Number处理。 */
public final class LikeVersion {
    public static final long SEQUENCE_LIMIT = 1L << 43;
    public static final int EPOCH_LIMIT = 1 << 20;
    private LikeVersion() { }

    public static long pack(long epoch, long sequence) {
        if (epoch < 0 || epoch >= EPOCH_LIMIT || sequence < 1 || sequence >= SEQUENCE_LIMIT) {
            throw new IllegalArgumentException("点赞版本超出范围");
        }
        return (epoch << 43) | sequence;
    }

    public static long parse(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) { throw new IllegalArgumentException("点赞版本格式错误"); }
        long version = Long.parseLong(value);
        if (version <= 0 || (version & (SEQUENCE_LIMIT - 1)) == 0) { throw new IllegalArgumentException("点赞版本序号无效"); }
        return version;
    }

    public static String cache(long version) {
        if (version < 0) { throw new IllegalArgumentException("缓存版本不能为负数"); }
        return String.format(Locale.ROOT, "%019d", version);
    }
}
