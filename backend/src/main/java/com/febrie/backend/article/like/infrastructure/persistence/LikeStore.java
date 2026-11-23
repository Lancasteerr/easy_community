package com.febrie.backend.article.like.infrastructure.persistence;

import com.febrie.backend.article.like.domain.LikeState;
import com.febrie.backend.article.like.infrastructure.persistence.mapper.LikeMapper;
import java.util.*;

/** 封装Mapper，空批次不生成IN ()；不在此处开启独立事务。 */
public class LikeStore {
    private final LikeMapper mapper;
    LikeStore(LikeMapper mapper) { this.mapper = mapper; }
    public void initializeState(long user, long article) { mapper.initializeState(user, article); }
    public LikeState lock(long user, long article) { return mapper.lock(user, article); }
    public int update(LikeState state) { return mapper.update(state); }
    public int addDelta(long article, int delta) { return mapper.addDelta(article, delta); }
    /** 零净增量也需核验计数基线；锁保持至文章事务结束。 */
    public Long lockCount(long article) { return mapper.lockCount(article); }
    public Map<Long, LikeState> states(long user, Collection<Long> ids) {
        Map<Long, LikeState> result = new LinkedHashMap<>();
        if (!ids.isEmpty()) { mapper.states(user, ids).forEach(s -> result.put(s.articleId(), s)); }
        return result;
    }
    public Map<Long, Long> counts(Collection<Long> ids) {
        Map<Long, Long> result = new LinkedHashMap<>();
        if (!ids.isEmpty()) { mapper.counts(ids).forEach(s -> result.put(s.articleId(), s.likeCount())); }
        return result;
    }
}
