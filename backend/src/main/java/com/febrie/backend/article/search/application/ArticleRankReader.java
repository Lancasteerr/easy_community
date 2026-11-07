package com.febrie.backend.article.search.application;

import com.febrie.backend.article.search.infrastructure.persistence.mapper.ArticleIndexMapper;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 每批统计查询在独立只读代理内结束，之后才能执行ES网络操作。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ArticleRankReader {
    private final ArticleIndexMapper mapper;
    public ArticleRankReader(ArticleIndexMapper mapper) { this.mapper = mapper; }
    public List<ArticleIndexMapper.RankRow> load(Collection<Long> ids, LocalDate day) {
        return ids.isEmpty() ? List.of() : mapper.ranking(ids, day.minusDays(6), day);
    }
}
