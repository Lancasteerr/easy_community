package com.febrie.backend.article.content.application;

import com.febrie.backend.article.content.infrastructure.persistence.mapper.ArticleVisibilityRepairMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 只管理主库待办，Redis补写由调用方在领取事务提交后执行。 */
@Service
public class ArticleVisibilityRepairs {
    private final ArticleVisibilityRepairMapper mapper;

    public ArticleVisibilityRepairs(ArticleVisibilityRepairMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long articleId) {
        mapper.enqueue(articleId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 3)
    public List<Long> claim() {
        List<Long> ids = mapper.available();
        if (!ids.isEmpty() && mapper.defer(ids) != ids.size()) {
            throw new IllegalStateException("文章墓碑待办领取数量不一致");
        }
        return ids;
    }

    /** 隐藏不可逆，只要墓碑已写入，任何一次成功尝试都可以删除待办。 */
    public void complete(long articleId) {
        mapper.complete(articleId);
    }
}
