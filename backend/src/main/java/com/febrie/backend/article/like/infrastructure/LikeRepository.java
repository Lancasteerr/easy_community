package com.febrie.backend.article.like.infrastructure;

import com.febrie.backend.article.like.domain.LikeState;
import com.febrie.backend.article.like.infrastructure.mapper.LikeMapper;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 封装Mapper，空批次不生成IN ()；不在此处开启独立事务。 */
@Repository
public class LikeRepository {
    private final LikeMapper mapper;
    public LikeRepository(LikeMapper mapper) { this.mapper = mapper; }
    public void initializeState(long user, long article) { mapper.initializeState(user, article); }
    public LikeState lock(long user, long article) { return mapper.lock(user, article); }
    public int update(LikeState state) { return mapper.update(state); }
    public int addDelta(long article, int delta) { return mapper.addDelta(article, delta); }
    public boolean publicArticle(long article) { return mapper.publicArticle(article); }
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
