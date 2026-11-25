package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.application.CommentAccess;
import com.febrie.backend.article.comment.application.CommentErrors;
import com.febrie.backend.article.comment.infrastructure.messaging.CommentBroker;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.AccountQueryService;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 同一篇文章的命令共享一次计数更新，事务结果交给消费端做提交后维护。 */
@Service
public class CommentCommandTransactions {
    private final CommentStore store;
    private final CommentCommandResults results;
    private final AccountQueryService accounts;
    private final Clock clock;

    CommentCommandTransactions(CommentStore store, CommentCommandResults results, AccountQueryService accounts, Clock clock) {
        this.store = store;
        this.results = results;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Applied apply(long article, List<CommentCommand> commands) {
        if (commands.isEmpty() || commands.size() > CommentBroker.BATCH_SIZE
                || commands.stream().anyMatch(command -> command.articleId() != article)) {
            throw CommentErrors.invalid();
        }
        // 先锁文章，再修改一级评论及计数。同步回复用同一文章的共享锁阻止删楼。
        boolean visible = store.lockPublicArticle(article, true);
        int delta = 0;
        var deleted = new ArrayList<CommentRow>();
        for (var command : commands) {
            var actor = command.actor();
            if (results.find(article, actor.userId(), command.operationId()) != null) {
                // 已存在的结果不可被重投或同 ID 的不同载荷替换。
                continue;
            }
            var state = accounts.authState(actor.userId()).orElse(null);
            if (state == null || !"ACTIVE".equals(state.status()) || state.authVersion() != actor.authVersion()) {
                saveResult(command, null, ApiException.unauthorized().code());
                continue;
            }
            if (!visible) {
                saveResult(command, null, CommentErrors.missing().code());
                continue;
            }
            if (command.commentId() == null) {
                var row = store.insert(article, 0, null, actor.userId(), command.content(),
                        clock.instant().truncatedTo(ChronoUnit.MILLIS));
                store.appendIndex(row.id());
                saveResult(command, row.id(), null);
                delta++;
                continue;
            }
            var row = store.find(command.commentId(), true);
            try {
                if (CommentAccess.belonging(article, row).rootId() != 0) throw CommentErrors.missing();
                CommentWrites.requireDeletePermission(row, actor);
            } catch (ApiException rejected) {
                saveResult(command, null, rejected.code());
                continue;
            }
            if (!row.deleted()) {
                store.delete(row);
                store.appendIndex(row.id());
                deleted.add(row);
                delta--;
            }
            saveResult(command, row.id(), null);
        }
        if (delta != 0) store.changeCount(article, delta);
        return new Applied(delta, List.copyOf(deleted));
    }

    private void saveResult(CommentCommand command, Long id, String error) {
        results.insert(command.articleId(), command.actor().userId(), command.operationId(), command.fingerprint(), id, error);
    }

    public record Applied(int delta, List<CommentRow> deleted) { }
}
