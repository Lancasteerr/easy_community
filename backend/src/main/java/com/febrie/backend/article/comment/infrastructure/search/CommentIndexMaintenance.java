package com.febrie.backend.article.comment.infrastructure.search;

import com.febrie.backend.article.comment.infrastructure.messaging.CommentBroker;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentIndexTasks;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/** 使用显式非 Web 命令初始化、回填、对账或重放，普通服务启动不扫描历史。 */
@Component
class CommentIndexMaintenance implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CommentIndexMaintenance.class);
    private final CommentIndex index;
    private final CommentIndexTasks tasks;
    private final CommentBroker broker;
    private final ApplicationContext context;
    private final String action;

    CommentIndexMaintenance(CommentIndex index, CommentIndexTasks tasks, CommentBroker broker,
                            ApplicationContext context, @Value("${app.comment.maintenance:}") String action) {
        this.index = index;
        this.tasks = tasks;
        this.broker = broker;
        this.context = context;
        this.action = action;
    }

    @Override public void run(ApplicationArguments arguments) throws IOException {
        if (action.isBlank()) return;
        if (context instanceof WebApplicationContext) throw new IllegalStateException("评论索引维护需要非 Web 模式");
        switch (action) {
            case "init" -> index.initialize();
            case "backfill" -> traverse(false);
            case "audit" -> traverse(true);
            case "replay-index" -> log.info("重放评论索引死信数量={}", broker.replay(false, 1000));
            case "replay-write" -> log.info("重放评论写命令死信数量={}", broker.replay(true, 1000));
            default -> throw new IllegalArgumentException("未知评论索引维护动作");
        }
    }

    private void traverse(boolean audit) throws IOException {
        long high = tasks.maxId();
        long after = 0;
        long total = 0;
        long differences = 0;
        while (after < high) {
            var batch = tasks.scan(after, high);
            if (batch.isEmpty()) break;
            if (audit) {
                differences += index.differences(batch);
            } else if (index.write(batch).size() != batch.size()) {
                throw new IllegalStateException("评论索引回填部分失败，可重复执行");
            }
            after = batch.getLast().commentId();
            total += batch.size();
        }
        if (!audit) index.refresh();
        log.info("评论索引维护 action={} rows={} differences={} 待投递生命周期事件={}",
                action, total, differences, tasks.pendingLifecycle());
        if (differences > 0) throw new IllegalStateException("评论索引对账存在差异");
    }
}
