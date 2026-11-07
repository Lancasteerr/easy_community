package com.febrie.backend.article.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.febrie.backend.article.search.infrastructure.config.*;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleIndexAdmin;
import java.util.*;

/** 只删除本对象创建的随机索引，测试异常时也关闭HTTP资源。 */
public final class SearchEsFixture implements AutoCloseable {
    public final String index = "article_it_" + UUID.randomUUID().toString().replace("-", "");
    public final SearchProperties p;
    public final ElasticsearchClient es;
    public SearchEsFixture() throws Exception {
        p = SearchTestSupport.properties(Map.of("app.search.index-name", index,
                "app.search.read-alias", index + "_read", "app.search.write-alias", index + "_write"));
        es = new SearchConfiguration().articleElasticsearch(p);
        try { new ArticleIndexAdmin(es, p).initialize(index, true); }
        catch (Exception failure) { es.close(); throw failure; }
    }
    @Override public void close() throws Exception {
        try { es.indices().delete(d -> d.index(index).ignoreUnavailable(true)); }
        finally { es.close(); }
    }
}
