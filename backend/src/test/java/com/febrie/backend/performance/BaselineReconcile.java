package com.febrie.backend.performance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** 受理后的 SQL 判断采用关系最高版本；队列是否真正排空由 Broker 独立给出。 */
final class BaselineReconcile {
    record Key(long user, long article) { }
    record Desired(long version, boolean liked, long acceptedAt) { }
    record Audit(long accepted, long invalidAccepted, int relations, long mismatched, long wrongCounters) {
        boolean passed() { return invalidAccepted == 0 && mismatched == 0 && wrongCounters == 0; }
    }
    record Pv(long httpResponses, long sqlTotal, long sqlDaily, long sqlObservedBeforeDrain) {
        long missing() { return Math.max(0, httpResponses - sqlTotal); }
        boolean passed() { return httpResponses == sqlTotal && sqlTotal == sqlDaily; }
    }

    private final JdbcTemplate jdbc;
    private final SequencedMap<Key, Desired> expected = new LinkedHashMap<>();
    private final List<Long> coverage = new ArrayList<>();
    private final Map<Key, Long> sampledVersions = new HashMap<>();
    private long accepted;
    private long invalid;
    private long detail;

    BaselineReconcile(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void append(List<BaselineJournal.Event> events, int from) {
        for (var event : events.subList(from, events.size())) {
            if (event.viewed()) { detail++; }
            if (!event.accepted()) { continue; }
            accepted++;
            try {
                if (event.version() == null) { throw new NumberFormatException(); }
                var key = new Key(event.user(), event.article());
                var next = new Desired(Long.parseLong(event.version()), event.liked(), event.end());
                var old = expected.get(key);
                if (old == null || next.version() > old.version()) { expected.putLast(key, next); }
            } catch (NumberFormatException malformed) { invalid++; }
        }
    }

    long accepted() { return accepted; }
    long views() { return detail; }

    Map<String, Object> sample(long now) {
        // 固定上限只看近期触达关系，防止观察查询随数据量增加抢占应用连接。
        var keys = expected.reversed().keySet().stream().limit(32).toList();
        var actual = select(keys);
        long oldest = 0;
        int pending = 0;
        for (var key : keys) {
            var target = expected.get(key);
            var current = actual.get(key);
            long age = Math.max(0, now - target.acceptedAt());
            if (current == null || current.version() < target.version()) {
                pending++;
                oldest = Math.max(oldest, age);
            } else if (!Long.valueOf(target.version()).equals(sampledVersions.put(key, target.version()))) {
                coverage.add(age);
            }
        }
        return Map.of("sampled", keys.size(), "pending", pending, "oldestMs", oldest);
    }

    List<Long> coverage() { return List.copyOf(coverage); }

    Audit audit() {
        var keys = new ArrayList<>(expected.keySet());
        long mismatched = 0;
        for (int start = 0; start < keys.size(); start += 250) {
            var batch = keys.subList(start, Math.min(start + 250, keys.size()));
            var actual = select(batch);
            mismatched += batch.stream().filter(key -> !matches(expected.get(key), actual.get(key))).count();
        }
        // 独占库允许停压后做全库计数核验，也能发现未受理却错误写入的计数失衡。
        long wrong = jdbc.queryForObject("""
                SELECT COUNT(*) FROM article a LEFT JOIN article_like_count c ON c.article_id=a.id
                LEFT JOIN (SELECT article_id,SUM(liked) AS total FROM article_like_state GROUP BY article_id) s
                ON s.article_id=a.id WHERE c.article_id IS NULL OR c.like_count <> COALESCE(s.total,0)
                """, Long.class);
        return new Audit(accepted, invalid, keys.size(), mismatched, wrong);
    }

    long persistedViews() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(view_count),0) FROM article", Long.class);
    }

    Pv pv(long beforeDrain) {
        long daily = jdbc.queryForObject("SELECT COALESCE(SUM(pv),0) FROM article_view_stat", Long.class);
        return new Pv(detail, persistedViews(), daily, beforeDrain);
    }

    private Map<Key, Desired> select(List<Key> keys) {
        if (keys.isEmpty()) { return Map.of(); }
        String pairs = String.join(",", Collections.nCopies(keys.size(), "(?,?)"));
        Object[] parameters = keys.stream().flatMap(key -> java.util.stream.Stream.of(key.user(), key.article())).toArray();
        var result = new HashMap<Key, Desired>();
        jdbc.query("""
                SELECT user_id,article_id,version,liked FROM article_like_state WHERE (user_id,article_id) IN (
                """ + pairs + ")", (RowCallbackHandler) row -> result.put(new Key(row.getLong(1), row.getLong(2)),
                new Desired(row.getLong(3), row.getBoolean(4), 0)), parameters);
        return result;
    }

    static boolean matches(Desired expected, Desired actual) {
        return actual != null && actual.version() == expected.version() && actual.liked() == expected.liked();
    }
}
