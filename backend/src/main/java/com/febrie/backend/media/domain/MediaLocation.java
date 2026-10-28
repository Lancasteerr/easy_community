package com.febrie.backend.media.domain;

/** 定位规则不依赖尚未确认的MIME；部署已有文件后不能热切换前缀或桶。 */
public record MediaLocation(String prefix) {
    public String tempKey(String id) { return prefix + "/temp/" + MediaIds.canonical(id); }
    public String publicKey(String id) { return prefix + "/public/" + MediaIds.canonical(id); }

    public String idFromTempKey(String key) {
        String start = prefix + "/temp/";
        if (key == null || !key.startsWith(start)) { throw new IllegalArgumentException("通知不是当前环境临时对象"); }
        String id = MediaIds.canonical(key.substring(start.length()));
        if (!tempKey(id).equals(key)) { throw new IllegalArgumentException("通知对象路径无效"); }
        return id;
    }
}
