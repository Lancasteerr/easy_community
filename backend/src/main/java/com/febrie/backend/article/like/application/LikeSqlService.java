package com.febrie.backend.article.like.application;

import com.febrie.backend.article.like.domain.*;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.persistence.LikeRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 每次代理调用只提交一篇文章；事务内不访问Redis或MQ。 */
@Service
public class LikeSqlService {
    private final LikeRepository repository;
    private final int batchSize;

    public LikeSqlService(LikeRepository repository, LikeProperties properties) {
        this.repository = repository;
        this.batchSize = properties.batchSize();
    }

    /** 单条入口本身开启事务，不依赖类内调用触发事务代理。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Result apply(LikeCommand command) {
        return applyArticle(command.articleId(), List.of(command)).results().getFirst();
    }

    /** 调用方已合并最高版本；这里只校验事务边界，按固定顺序取得关系锁。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ArticleResult applyArticle(long articleId, List<LikeCommand> commands) {
        if (commands.isEmpty() || commands.size() > batchSize) {
            throw new IllegalArgumentException("文章事务的关系数量超出批次边界");
        }
        Set<Long> users = new HashSet<>();
        for (var command : commands) {
            if (command.articleId() != articleId || !users.add(command.userId())) {
                throw new IllegalArgumentException("文章事务必须属于同一文章且用户不重复");
            }
        }
        var ordered = commands.stream().sorted(Comparator.comparingLong(LikeCommand::userId)).toList();
        List<Result> results = new ArrayList<>();
        int delta = 0;
        for (var command : ordered) {
            repository.initializeState(command.userId(), articleId);
            LikeState old = repository.lock(command.userId(), articleId);
            Result result = decide(command, old);
            results.add(result);
            if (result.status() == Status.APPLIED) {
                if (repository.update(result.state()) != 1) {
                    throw new LikeInvariantException("点赞状态更新行数异常");
                }
                delta += (result.state().liked() ? 1 : 0) - (old.liked() ? 1 : 0);
            }
        }
        Long count = repository.lockCount(articleId);
        if (count == null || count < 0 || (delta < 0 && count < -delta)
                || (delta > 0 && count > Long.MAX_VALUE - delta)) {
            throw new LikeInvariantException("点赞计数缺行、下溢或溢出");
        }
        if (delta != 0 && repository.addDelta(articleId, delta) != 1) {
            throw new LikeInvariantException("点赞计数更新行数异常");
        }
        return new ArticleResult(articleId, results, delta);
    }

    private Result decide(LikeCommand command, LikeState old) {
        long version = command.number();
        if (version < old.version()) { return new Result(Status.STALE, old); }
        if (version == old.version()) {
            if (command.desiredLiked() != old.liked()) { throw new LikeInvariantException("同版本点赞状态冲突"); }
            return new Result(Status.DUPLICATE, old);
        }
        return new Result(Status.APPLIED, new LikeState(old.userId(), old.articleId(), command.desiredLiked(), version));
    }

    public enum Status { APPLIED, DUPLICATE, STALE }
    public record Result(Status status, LikeState state) { }
    /** SQL结果不携带MQ消息，原始投递归属由消费端管理。 */
    public record ArticleResult(long articleId, List<Result> results, int delta) {
        public ArticleResult { results = List.copyOf(results); }
    }
}
