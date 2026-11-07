package com.febrie.backend.article.search.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 外部参数只在入口规范化一次，内部查询只能使用明确的筛选与排序。 */
public record ArticleSearchQuery(String kind, String q, Long tagId, String sort, int size) {
    public ArticleSearchQuery {
        q = q == null ? "" : q.strip();
        sort = sort == null ? (q.isEmpty() ? "latest" : "relevance") : sort;
        if (!Set.of("search", "feed").contains(kind) || q.codePointCount(0, q.length()) > 100
                || (tagId != null && tagId <= 0) || size < 1 || size > 50
                || !Set.of("relevance", "latest", "recommended").contains(sort)
                || (sort.equals("relevance") && q.isEmpty())
                || (kind.equals("search") && q.isEmpty() && tagId == null)
                || (kind.equals("feed") && (!q.isEmpty() || tagId != null || !sort.equals("recommended")))) {
            throw SearchErrors.invalid();
        }
    }
    public String fingerprint() {
        // 关键词带长度前缀，避免关键词自身包含换行时产生歧义。
        String canonical = kind + "\n" + q.length() + ":" + q + "\n" + tagId + "\n" + sort + "\n" + size;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
