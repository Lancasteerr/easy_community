package com.febrie.backend.article.application;

import com.febrie.backend.article.application.dto.*;
import com.febrie.backend.user.application.dto.UserActor;
import java.util.*;
import org.springframework.stereotype.Service;

/** 查询入口不建立长事务，主库短视图由独立Reader负责。 */
@Service
public class ArticleQueryService {
    private final ArticleReader reader;
    public ArticleQueryService(ArticleReader reader) { this.reader = reader; }
    public ArticleDetailView detail(long id) {
        var loaded = reader.detail(id);
        if (loaded.state() != LoadedArticle.State.PUBLIC) { throw ArticleErrors.notFound(); }
        return loaded.data();
    }
    public ArticleEditView edit(UserActor actor, long id) { return reader.edit(actor, id); }
    public ArticlePage page(UserActor actor, int page, int size) {
        if (page < 1 || size < 1 || size > 50) { throw ArticleErrors.invalid(); }
        var selected = reader.page(actor.userId(), page, size);
        var loaded = reader.lists(selected.ids());
        var items = selected.ids().stream().map(loaded::get).filter(value -> value.state() == LoadedArticle.State.PUBLIC)
                .map(LoadedArticle::data).toList();
        return new ArticlePage(items, page, size, selected.total());
    }
}
