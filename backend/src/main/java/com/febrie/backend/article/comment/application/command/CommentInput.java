package com.febrie.backend.article.comment.application.command;

/** 进入发布流程的正文与可选回复目标。 */
public record CommentInput(String content, Long replyToId) { }
