package com.febrie.backend.article.like.infrastructure.persistence;

import com.febrie.backend.article.like.infrastructure.persistence.mapper.CommentLikeMapper;
import com.febrie.backend.article.like.infrastructure.persistence.mapper.LikeMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

/** 文章与评论使用各自的表，通过同一事务算法持久化。 */
@Repository
public class LikeRepository extends LikeStore {
    private final LikeStore comments;

    public LikeRepository(@Qualifier("likeMapper") LikeMapper articles, CommentLikeMapper comments) {
        super(articles);
        this.comments = new LikeStore(comments);
    }

    public LikeStore comments() {
        return comments;
    }
}
