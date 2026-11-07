package com.febrie.backend.article.search;

import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import java.util.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 只构造测试配置；真实测试始终使用随机索引和独立消息命名空间。 */
public final class SearchTestSupport {
    private SearchTestSupport() { }
    public static SearchProperties properties(Map<String, Object> overrides) {
        Map<String, Object> values = new HashMap<>();
        values.put("app.search.uri", System.getenv().getOrDefault("IT_ES_URL", "http://127.0.0.1:9200"));
        values.put("app.search.username", System.getenv().getOrDefault("IT_ES_USERNAME", ""));
        values.put("app.search.password", System.getenv().getOrDefault("IT_ES_PASSWORD", ""));
        values.putAll(overrides);
        return new Binder(new MapConfigurationPropertySource(values)).bind("app.search", Bindable.of(SearchProperties.class)).get();
    }
}
