package com.febrie.backend.media;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/** 在专用真实MySQL库验证生成列唯一性；每个用例回滚，绝不连接业务库。 */
@EnabledIfEnvironmentVariable(named = "IT_DB_PASSWORD", matches = ".+")
class MediaSchemaTests {
    private static final String URL = System.getenv().getOrDefault("IT_MEDIA_DB_URL",
            "jdbc:mysql://127.0.0.1:3306/easy_community_media_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    private Connection connection;
    private long owner;
    private String first;
    private String second;

    @BeforeAll
    static void migrate() {
        assertTrue(URL.matches("jdbc:mysql://[^/]+/easy_community_media_test(?:\\?.*)?"), "文件测试只能使用专用数据库");
        Flyway.configure().dataSource(URL, username(), System.getenv("IT_DB_PASSWORD")).load().migrate();
    }

    @BeforeEach
    void fixture() throws SQLException {
        connection = DriverManager.getConnection(URL, username(), System.getenv("IT_DB_PASSWORD"));
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("INSERT INTO users(username,role) VALUES (?,'USER')", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "media_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) { keys.next(); owner = keys.getLong(1); }
        }
        first = file();
        second = file();
    }

    @AfterEach
    void rollback() throws SQLException {
        if (connection != null) { connection.rollback(); connection.close(); }
    }

    @Test
    void oneCurrentAvatarPerUserButOtherUsersAreIndependent() throws SQLException {
        reference("USER_AVATAR", owner, first);
        assertThrows(SQLException.class, () -> reference("USER_AVATAR", owner, second));
        reference("USER_AVATAR", owner + 1, second);
        try (var statement = connection.prepareStatement("UPDATE media_reference SET media_id=? WHERE avatar_user_id=?")) {
            statement.setString(1, second); statement.setLong(2, owner);
            assertEquals(1, statement.executeUpdate());
        }
    }

    @Test
    void articleFileIsUniqueButFilesCanBeSharedAcrossArticles() throws SQLException {
        reference("ARTICLE", 1001, first);
        assertThrows(SQLException.class, () -> reference("ARTICLE", 1001, first));
        reference("ARTICLE", 1001, second);
        reference("ARTICLE", 1002, first);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT COUNT(*),COUNT(avatar_user_id) FROM media_reference WHERE reference_type='ARTICLE'")) {
            rows.next(); assertEquals(3, rows.getInt(1)); assertEquals(0, rows.getInt(2));
        }
    }

    @Test
    void deletingOneArticleKeepsOtherReferencesAndRejectsInvalidEntities() throws SQLException {
        reference("ARTICLE", 1001, first);
        reference("ARTICLE", 1002, first);
        try (var statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("DELETE FROM media_reference WHERE reference_type='ARTICLE' AND entity_id=1001"));
        }
        assertThrows(SQLException.class, () -> reference("ARTICLE", 0, first));
        assertThrows(SQLException.class, () -> reference("UNKNOWN", 1001, first));
        assertThrows(SQLException.class, () -> reference("ARTICLE", 1003, UUID.randomUUID().toString()));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM media_reference")) {
            rows.next(); assertEquals(1, rows.getInt(1));
        }
    }

    private String file() throws SQLException {
        String id = UUID.randomUUID().toString();
        try (var statement = connection.prepareStatement("""
                INSERT INTO media_file(id,owner_user_id,purpose,filename,declared_content_type,declared_size,
                    storage_provider,bucket,temp_key,public_key,upload_expires_at,expires_at)
                VALUES (?,?,'AVATAR','test.png','image/png',10,'minio','test',?,?,NOW()+INTERVAL 5 MINUTE,NOW()+INTERVAL 1 DAY)
                """)) {
            statement.setString(1, id); statement.setLong(2, owner);
            statement.setString(3, "temp/" + id); statement.setString(4, "public/" + id + ".png"); statement.executeUpdate();
        }
        return id;
    }

    private void reference(String type, long entity, String media) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO media_reference(reference_type,entity_id,media_id) VALUES (?,?,?)")) {
            statement.setString(1, type); statement.setLong(2, entity); statement.setString(3, media); statement.executeUpdate();
        }
    }

    private static String username() { return System.getenv().getOrDefault("IT_DB_USERNAME", "root"); }
}
