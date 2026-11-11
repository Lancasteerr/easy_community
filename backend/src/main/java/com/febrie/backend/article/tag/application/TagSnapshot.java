package com.febrie.backend.article.tag.application;

import com.febrie.backend.article.tag.application.dto.TagView;
import java.text.Normalizer;
import java.util.*;

/** 整份不可变目录，搜索和ID解析共享同一提交版本。 */
record TagSnapshot(long version, List<SearchableTag> searchable, Map<Long, TagView> byId) {
    TagSnapshot { searchable = List.copyOf(searchable); byId = Map.copyOf(byId); }
    static TagSnapshot from(TagDirectoryReader.Directory directory) {
        if (directory.version() < 1) { throw new IllegalStateException("标签目录版本无效"); }
        var entries = new ArrayList<SearchableTag>();
        Map<Long, TagView> ids = new HashMap<>();
        for (var tag : directory.tags()) {
            long id = Long.parseLong(tag.tagId());
            if (id <= 0 || tag.name() == null || ids.put(id, tag) != null) { throw new IllegalStateException("标签目录数据无效"); }
            entries.add(new SearchableTag(id, tag, normalize(tag.name())));
        }
        entries.sort(Comparator.comparing(SearchableTag::searchKey).thenComparingLong(SearchableTag::id));
        return new TagSnapshot(directory.version(), entries, ids);
    }
    static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }
    boolean containsAll(Collection<Long> ids) { return byId.keySet().containsAll(ids); }
    List<TagView> search(String query, int limit) {
        var result = new ArrayList<TagView>(limit);
        // 两轮遍历让前缀优先，同级沿用加载时的稳定顺序。
        for (var tag : searchable) {
            if (tag.searchKey().startsWith(query)) {
                result.add(tag.view());
                if (result.size() == limit) { return List.copyOf(result); }
            }
        }
        for (var tag : searchable) {
            if (!tag.searchKey().startsWith(query) && tag.searchKey().contains(query)) {
                result.add(tag.view());
                if (result.size() == limit) { break; }
            }
        }
        return List.copyOf(result);
    }
}

/** 搜索字段只存在于内存，不进入数据库或HTTP响应。 */
record SearchableTag(long id, TagView view, String searchKey) { }
