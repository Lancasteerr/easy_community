package com.febrie.backend.article.common;

import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** JSON边界集中处理，数据库坏数据不能被转换成空标签集合。 */
public final class ArticleTagIds {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ArticleTagIds() { }

    public static String encode(List<Long> ids) {
        requireValid(ids);
        return JSON.writeValueAsString(ids);
    }

    public static List<Long> decode(String json) {
        if (json == null) { throw new IllegalStateException("文章标签JSON缺失"); }
        var value = JSON.readTree(json);
        if (value == null || !value.isArray() || value.size() > 5) { throw new IllegalStateException("文章标签JSON不是有效数组"); }
        var ids = new ArrayList<Long>();
        for (var item : value) {
            if (!item.isIntegralNumber() || !item.canConvertToLong()) { throw new IllegalStateException("文章标签ID类型无效"); }
            ids.add(item.asLong());
        }
        requireValid(ids);
        return List.copyOf(ids);
    }

    private static void requireValid(List<Long> ids) {
        if (ids.size() > 5 || ids.stream().anyMatch(id -> id == null || id <= 0) || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalStateException("文章标签ID集合无效");
        }
    }
}
