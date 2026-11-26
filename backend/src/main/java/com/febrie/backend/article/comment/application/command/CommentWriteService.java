package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults;
import com.febrie.backend.article.comment.infrastructure.redis.CommentMaintenance;
import com.febrie.backend.user.application.dto.UserActor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 删除先校验归属和权限，再按评论层级选择锁范围。 */
@Service
public class CommentWriteService {
    private final CommentWrites writes;
    private final CommentSubmissionTransactions transactions;
    private final CommentCommandResults results;
    private final CommentMaintenance maintenance;

    CommentWriteService(CommentWrites writes, CommentSubmissionTransactions transactions,
                        CommentCommandResults results, CommentMaintenance maintenance) {
        this.writes = writes;
        this.transactions = transactions;
        this.results = results;
        this.maintenance = maintenance;
    }

    public void delete(long article, long comment, UserActor actor, String operation) {
        var target = writes.deletionTarget(article, comment, actor);
        if (target.rootId() != 0) {
            writes.deleteReply(target);
            return;
        }
        String id = CommentOperation.id(operation);
        String fingerprint = CommentOperation.deletion(comment);
        try {
            if (transactions.deleteRoot(target, actor.userId(), id, fingerprint)) maintenance.visibility(target);
        } catch (DuplicateKeyException concurrent) {
            // 事务已回滚，同号删除共享结果；同号发布仍按摘要判定冲突。
            var existing = results.find(article, actor.userId(), id);
            if (existing == null) throw concurrent;
            existing.requireFingerprint(fingerprint);
        }
    }
}
