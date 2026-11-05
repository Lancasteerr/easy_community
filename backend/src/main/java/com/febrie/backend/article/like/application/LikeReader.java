package com.febrie.backend.article.like.application;

import com.febrie.backend.article.like.domain.LikeState;
import com.febrie.backend.article.like.infrastructure.LikeRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 每批短主库查询；代理返回后才能回填Redis，缺计数不伪造零。 */
@Service
@Transactional(readOnly=true, isolation=Isolation.READ_COMMITTED, timeout=3)
public class LikeReader {
    private final LikeRepository repository;
    public LikeReader(LikeRepository repository) { this.repository=repository; }
    public Map<Long, Long> counts(Collection<Long> ids) {
        var result=repository.counts(ids);
        if (!result.keySet().containsAll(ids)) { throw LikeErrors.unavailable(); }
        return result;
    }
    public Map<Long, LikeState> states(long user, Collection<Long> ids) {
        var result=repository.states(user,ids);
        ids.forEach(id -> result.putIfAbsent(id,new LikeState(user,id,false,0)));
        return result;
    }
}
