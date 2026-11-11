package com.febrie.backend.article.tag.application;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** 每例创建随机专用测试库，真实执行旧版到JSON的Flyway迁移并核对历史数据。 */
@EnabledIfEnvironmentVariable(named = "IT_ARTICLE", matches = "true")
class ArticleTagMigrationIntegrationTests {
    @Test void backfillsOrderedIdsEmptyAndHiddenArticlesAndEnforcesJsonCheck() throws Exception {
        withDatabase((url, user, password) -> {
            migrate(url, user, password, "8");
            try (var connection = DriverManager.getConnection(url, user, password); var sql = connection.createStatement()) {
                seed(sql);
                sql.executeUpdate("INSERT INTO tag(id,name) VALUES(1,'Java'),(2,'Redis'),(9007199254740995,'LongId')");
                sql.executeUpdate("INSERT INTO article_tag VALUES(1,9007199254740995),(1,1),(1,2),(2,2)");
                migrate(url, user, password, "9");
                try (var rows = sql.executeQuery("SELECT id,CAST(tag_ids AS CHAR) AS tags FROM article ORDER BY id")) {
                    assertTrue(rows.next()); assertEquals("[1, 2, 9007199254740995]", rows.getString("tags"));
                    assertTrue(rows.next()); assertEquals("[2]", rows.getString("tags"));
                    assertTrue(rows.next()); assertEquals("[]", rows.getString("tags"));
                }
                assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE article SET tag_ids='[1,2,3,4,5,6]' WHERE id=1"));
                assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE article SET tag_ids='{}' WHERE id=1"));
                // 首次发布保留旧关系，不能自动清理核对依据。
                try (var rows = sql.executeQuery("SELECT COUNT(*) FROM article_tag")) { assertTrue(rows.next()); assertEquals(4, rows.getInt(1)); }
            }
        });
    }

    @Test void historicalOverLimitAbortsMigrationInsteadOfTruncating() throws Exception {
        withDatabase((url, user, password) -> {
            migrate(url, user, password, "8");
            try (var connection = DriverManager.getConnection(url, user, password); var sql = connection.createStatement()) {
                seed(sql);
                for (int id = 1; id <= 6; id++) {
                    sql.executeUpdate("INSERT INTO tag(id,name) VALUES(" + id + ",'tag" + id + "')");
                    sql.executeUpdate("INSERT INTO article_tag VALUES(1," + id + ")");
                }
                assertThrows(RuntimeException.class, () -> migrate(url, user, password, "9"));
                try (var rows = sql.executeQuery("SELECT JSON_LENGTH(tag_ids) FROM article WHERE id=1")) {
                    assertTrue(rows.next()); assertEquals(6, rows.getInt(1));
                }
            }
        });
    }

    private static void seed(Statement sql) throws SQLException {
        sql.executeUpdate("INSERT INTO users(id,username,role) VALUES(1,'migration_user','USER')");
        for (int id = 1; id <= 3; id++) {
            sql.executeUpdate("INSERT INTO article(id,author_id,title,summary,content_json,content_html,hidden,created_at,updated_at,published_at) "
                    + "VALUES(" + id + ",1,'t','s','{}','t'," + (id == 2) + ",UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
        }
    }
    private static void migrate(String url, String user, String password, String target) {
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target(target).load().migrate();
    }
    private static void withDatabase(DatabaseTest test) throws Exception {
        String source = System.getenv("IT_DB_URL");
        if (source == null || !source.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")) {
            throw new IllegalArgumentException("迁移测试仅允许专用测试服务");
        }
        String name = "easy_community_tag_migration_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String url = source.replaceFirst("/easy_community_[a-z0-9_]+(?=\\?|$)", "/" + name);
        String user = System.getenv("IT_DB_USERNAME"), password = System.getenv("IT_DB_PASSWORD");
        try (var connection = DriverManager.getConnection(source, user, password); var sql = connection.createStatement()) {
            sql.executeUpdate("CREATE DATABASE " + name + " CHARACTER SET utf8mb4");
            try { test.run(url, user, password); }
            finally {
                // 仅清理本例生成且严格匹配测试前缀的数据库。
                if (!name.matches("easy_community_tag_migration_test_[a-f0-9]{12}")) { throw new IllegalStateException("测试库名称越界"); }
                sql.executeUpdate("DROP DATABASE " + name);
            }
        }
    }
    @FunctionalInterface private interface DatabaseTest { void run(String url, String user, String password) throws Exception; }
}
