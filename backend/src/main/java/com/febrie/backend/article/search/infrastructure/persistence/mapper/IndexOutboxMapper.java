package com.febrie.backend.article.search.infrastructure.persistence.mapper;

import com.febrie.backend.article.search.application.IndexEvent;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 领取事务持有短行锁，完成/重试按领取令牌条件执行。 */
public interface IndexOutboxMapper {
    List<IndexEvent> available();
    int claim(@Param("ids") List<Long> ids, @Param("token") byte[] token);
    int complete(@Param("id") long id, @Param("token") byte[] token);
    int retry(@Param("id") long id, @Param("token") byte[] token, @Param("delay") int delay);
    long count();
    long oldestAge();
}
