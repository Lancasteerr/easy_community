package com.febrie.backend.article.like.domain;

/** 协议冲突或精确计数破坏必须隔离告警，不能当成瞬时错误反复修改数据。 */
public class LikeInvariantException extends RuntimeException {
    public LikeInvariantException(String message) { super(message); }
}
