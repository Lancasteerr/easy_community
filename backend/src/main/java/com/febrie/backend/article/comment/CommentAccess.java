package com.febrie.backend.article.comment;

import com.febrie.backend.article.content.application.ArticleListHydrator;
import com.febrie.backend.shared.error.ApiException;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 公开读和点赞先使用已有文章可见性缓存，再按需解析局部评论。 */
@Service
public class CommentAccess {
    private final ArticleListHydrator articles;
    private final CommentValues values;

    public CommentAccess(ArticleListHydrator articles, CommentValues values) {
        this.articles = articles;
        this.values = values;
    }

    public void article(long id) {
        try {
            if (!articles.isPublic(id)) throw CommentErrors.missing();
        } catch (DataAccessException failure) {
            throw CommentErrors.unavailable();
        } catch (ApiException failure) {
            if (failure.status().is5xxServerError()) throw CommentErrors.unavailable();
            throw failure;
        }
    }

    public void root(long article, long id) {
        try {
            var row = CommentWrites.belonging(article, values.objects(List.of(id)).get(id));
            if (row.rootId() != 0 || row.deleted()) throw CommentErrors.missing();
        } catch (DataAccessException failure) {
            throw CommentErrors.unavailable();
        }
    }
}
