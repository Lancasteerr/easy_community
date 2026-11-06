package com.febrie.backend.article.like.application;

import com.febrie.backend.article.like.domain.*;
import com.febrie.backend.article.like.infrastructure.persistence.LikeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 仅由消费者经代理调用；退出代理才表示提交完成，事务内不访问Redis/MQ。 */
@Service
public class LikeSqlService {
    private final LikeRepository repository;
    public LikeSqlService(LikeRepository repository) { this.repository = repository; }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Result apply(LikeCommand command) {
        repository.initializeState(command.userId(), command.articleId());
        LikeState old = repository.lock(command.userId(), command.articleId());
        long version = command.number();
        if (version < old.version()) { return new Result(Status.STALE, old); }
        if (version == old.version()) {
            if (command.desiredLiked() != old.liked()) { throw new LikeInvariantException("同版本点赞状态冲突"); }
            return new Result(Status.DUPLICATE, old);
        }
        LikeState next = new LikeState(old.userId(), old.articleId(), command.desiredLiked(), version);
        if (repository.update(next) != 1) { throw new LikeInvariantException("点赞状态更新行数异常"); }
        int delta = (next.liked() ? 1 : 0) - (old.liked() ? 1 : 0);
        if (delta != 0 && repository.addDelta(next.articleId(), delta) != 1) {
            throw new LikeInvariantException("点赞计数缺行或下溢");
        }
        return new Result(Status.APPLIED, next);
    }

    public enum Status { APPLIED, DUPLICATE, STALE }
    public record Result(Status status, LikeState state) { }
}
