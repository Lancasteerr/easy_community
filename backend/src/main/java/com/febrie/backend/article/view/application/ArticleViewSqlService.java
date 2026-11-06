package com.febrie.backend.article.view.application;

import com.febrie.backend.article.view.infrastructure.persistence.mapper.ArticleViewStatMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 一日期一事务；只处理Redis快照，不在事务或提交回调中访问Redis。 */
@Service
public class ArticleViewSqlService {
    private final ArticleViewStatMapper stats;

    public ArticleViewSqlService(ArticleViewStatMapper stats) { this.stats = stats; }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public FlushResult flush(LocalDate date, Map<Long, Long> snapshot) {
        Map<Long, Long> dailyFloors = new LinkedHashMap<>();
        Map<Long, Long> totalFloors = new LinkedHashMap<>();
        // 固定锁顺序；首次建行冲突或SQL失败均回滚整个日期，下一轮重试。
        for (var entry : new TreeMap<>(snapshot).entrySet()) {
            long id = entry.getKey();
            long sampled = entry.getValue();
            Long persisted = stats.selectPvForUpdate(id, date);
            long previous = persisted == null ? 0 : persisted;
            if (sampled > previous) {
                if (stats.incrementTotal(id, sampled - previous) == 0) { continue; }
                stats.upsertPv(id, date, sampled);
            } else if (sampled < previous) {
                dailyFloors.put(id, previous);
            }
            Long total = stats.selectTotal(id);
            if (total != null) { totalFloors.put(id, total); }
        }
        return new FlushResult(dailyFloors, totalFloors);
    }

    /** 仅在事务代理确认提交后，编排层才能使用这些SQL下限修复Redis。 */
    public record FlushResult(Map<Long, Long> dailyFloors, Map<Long, Long> totalFloors) {
        public FlushResult {
            dailyFloors = Map.copyOf(dailyFloors);
            totalFloors = Map.copyOf(totalFloors);
        }
    }
}
