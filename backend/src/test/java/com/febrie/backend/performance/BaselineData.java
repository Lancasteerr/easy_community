package com.febrie.backend.performance;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.springframework.jdbc.core.JdbcTemplate;

/** 固定种子的压测数据只写入新建的运行专用库，ID 在各档之间保持一致。 */
final class BaselineData {
    static final long SEED = 20261120L;
    static final int TAGS = 24;
    static final String PASSWORD = "baseline-fixture-only-password";
    static final int PARTITIONS = 4;
    private static final int BATCH = 500;
    private static final LocalDateTime EPOCH = LocalDateTime.of(2026, 1, 1, 0, 0);
    static final String[] TOPICS = {"社区", "摄影", "学习", "旅行", "读书", "编程"};

    record Size(int users, int articles, int likesPerArticle) {
        static final Size SMOKE = new Size(32, 96, 4);
        static final Size STANDARD = new Size(10_000, 50_000, 10);
        long likes() { return (long) articles * likesPerArticle; }
    }
    record Article(long id, long author, String title, String html, long tag, LocalDateTime published) { }
    record Query(String kind, String q, Long tagId, long articleId, boolean empty, boolean page) { }
    record Workload(long seed, int articles, List<List<Long>> partitions, List<Query> queries) { }

    private BaselineData() { }

    static String username(long user) { return "baseline_" + user; }

    static Article article(Size size, long id) {
        var random = new SplittableRandom(SEED + id);
        long author = 1 + random.nextLong(Math.max(1, size.users() / 3));
        String topic = TOPICS[(int) ((id - 1) % TOPICS.length)];
        String title = topic + " 交流记录 " + tail(id);
        int paragraphs = id % 20 == 0 ? 48 : 1 + random.nextInt(12);
        String html = ("<p>" + topic + " 城市社区的资料、经验分享和讨论，欢迎留下建议。</p>").repeat(paragraphs);
        return new Article(id, author, title, html, 1 + (id - 1) % TAGS, EPOCH.plusSeconds(id));
    }

    static Workload workload(Size size) {
        var partitions = new ArrayList<List<Long>>();
        for (int i = 0; i < PARTITIONS; i++) { partitions.add(new ArrayList<>()); }
        for (long id = 1; id <= size.articles(); id++) {
            partitions.get(Math.floorMod(Long.hashCode(id), PARTITIONS)).add(id);
        }
        return new Workload(SEED, size.articles(), partitions, List.of(
                new Query("hot", TOPICS[0], null, 0, false, false),
                new Query("tail", tail(size.articles()), null, size.articles(), false, false),
                new Query("tag", null, 1L, 0, false, false),
                new Query("empty", "zxqnonexistentbaseline", null, 0, true, false),
                new Query("page", TOPICS[1], null, 0, false, true)));
    }

    private static String tail(long id) { return String.format(Locale.ROOT, "baselineword%06d", id); }

    static void insert(JdbcTemplate jdbc, Size size, String encodedPassword) {
        List<Long> users = LongStream.rangeClosed(1, size.users()).boxed().toList();
        jdbc.batchUpdate("INSERT INTO users(id,username,role) VALUES (?,?,'USER')", users, BATCH, (ps, id) -> {
            ps.setLong(1, id); ps.setString(2, username(id));
        });
        jdbc.batchUpdate("""
                INSERT INTO user_auth_identity(user_id,provider,provider_uid,password_hash) VALUES (?,'LOCAL',?,?)
                """, users, BATCH, (ps, id) -> {
            ps.setLong(1, id); ps.setString(2, username(id)); ps.setString(3, encodedPassword);
        });
        jdbc.batchUpdate("INSERT INTO tag(id,name) VALUES (?,?)", LongStream.rangeClosed(1, TAGS).boxed().toList(),
                BATCH, (ps, id) -> { ps.setLong(1, id); ps.setString(2, "基线标签" + id); });
        for (long first = 1; first <= size.articles(); first += BATCH) {
            var ids = LongStream.range(first, Math.min(first + BATCH, size.articles() + 1L)).boxed().toList();
            insertArticles(jdbc, size, ids);
        }
        jdbc.execute("CREATE TABLE baseline_seed_like LIKE article_like_state");
        jdbc.execute("INSERT INTO baseline_seed_like SELECT * FROM article_like_state");
        requireCount(jdbc, "SELECT COUNT(*) FROM users", size.users());
        requireCount(jdbc, "SELECT COUNT(*) FROM article", size.articles());
        requireCount(jdbc, "SELECT COUNT(*) FROM article_like_state", size.likes());
        requireCount(jdbc, "SELECT SUM(like_count) FROM article_like_count", size.likes());
        requireCount(jdbc, """
                SELECT COUNT(*) FROM article_like_count c LEFT JOIN
                  (SELECT article_id, SUM(liked) AS total FROM article_like_state GROUP BY article_id) s
                  ON c.article_id=s.article_id WHERE c.like_count <> COALESCE(s.total,0)
                """, 0);
    }

    private static void insertArticles(JdbcTemplate jdbc, Size size, List<Long> ids) {
        jdbc.batchUpdate("""
                INSERT INTO article(id,author_id,title,summary,content_json,content_html,created_at,updated_at,published_at)
                VALUES (?,?,?,?,'{}',?,?,?,?)
                """, ids, BATCH, (ps, id) -> {
            var item = article(size, id);
            ps.setLong(1, id); ps.setLong(2, item.author()); ps.setString(3, item.title());
            ps.setString(4, item.title()); ps.setString(5, item.html());
            for (int column = 6; column <= 8; column++) { ps.setObject(column, item.published()); }
        });
        jdbc.batchUpdate("INSERT INTO article_tag(article_id,tag_id) VALUES (?,?)", ids, BATCH, (ps, id) -> {
            ps.setLong(1, id); ps.setLong(2, 1 + (id - 1) % TAGS);
        });
        jdbc.batchUpdate("INSERT INTO article_like_count(article_id,like_count) VALUES (?,?)", ids, BATCH, (ps, id) -> {
            ps.setLong(1, id); ps.setInt(2, size.likesPerArticle());
        });
        var relations = new ArrayList<long[]>();
        for (long id : ids) {
            for (int offset = 0; offset < size.likesPerArticle(); offset++) {
                relations.add(new long[]{1 + (id * 31 + offset) % size.users(), id});
            }
        }
        jdbc.batchUpdate("INSERT INTO article_like_state(user_id,article_id,liked,version) VALUES (?,?,1,0)",
                relations, BATCH, (ps, pair) -> { ps.setLong(1, pair[0]); ps.setLong(2, pair[1]); });
    }

    /** 只归位上一档改动的关系和计数，避免每档重写整份快照。 */
    static void restore(JdbcTemplate jdbc, Size size) {
        jdbc.execute("""
                UPDATE article_like_state s JOIN baseline_seed_like seed USING(user_id,article_id)
                SET s.version=seed.version, s.liked=seed.liked, s.updated_at=seed.updated_at WHERE s.version>0
                """);
        jdbc.update("DELETE FROM article_like_state WHERE version>0");
        jdbc.update("UPDATE article_like_count SET like_count=? WHERE like_count<>?",
                size.likesPerArticle(), size.likesPerArticle());
        jdbc.update("DELETE FROM article_view_stat");
        jdbc.update("UPDATE article SET view_count=0 WHERE view_count<>0");
    }

    static void requireCount(JdbcTemplate jdbc, String query, long expected) {
        if (!Long.valueOf(expected).equals(jdbc.queryForObject(query, Long.class))) {
            throw new IllegalStateException("压测数据数量或计数不符：" + query);
        }
    }
}
