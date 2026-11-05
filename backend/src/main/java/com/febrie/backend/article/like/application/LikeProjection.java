package com.febrie.backend.article.like.application;

import com.febrie.backend.article.like.domain.LikeCommand;

/** 可失败的展示投影端口，不参与SQL事务或消息持久受理判定。 */
public interface LikeProjection {
    void pending(LikeCommand command);
    void committed(LikeSqlService.Result result);
}
