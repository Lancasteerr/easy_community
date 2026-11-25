package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.infrastructure.persistence.CommentIndexTasks;
import org.springframework.stereotype.Service;

/** 评论点赞在同一数据库事务内锁定有效目标，并登记索引同步。 */
@Service
public class CommentLikeIndex {
    private final CommentIndexTasks tasks;

    CommentLikeIndex(CommentIndexTasks tasks) {
        this.tasks = tasks;
    }

    /** 调用方已开启点赞事务，且尚未取得点赞关系锁。 */
    public boolean lockActiveRoot(long commentId) {
        return tasks.lockActiveRoot(commentId);
    }

    /** 调用方仍持有一级评论锁，且已写入非零的净点赞数变更。 */
    public void scheduleLike(long commentId) {
        tasks.scheduleLike(commentId);
    }
}
