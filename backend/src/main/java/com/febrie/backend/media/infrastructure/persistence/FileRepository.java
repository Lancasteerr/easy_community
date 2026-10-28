package com.febrie.backend.media.infrastructure.persistence;

import com.febrie.backend.media.domain.*;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

/** 普通业务使用主数据源；通知的短等待数据源复用相同SQL和映射。 */
@Repository
public class FileRepository {
    public static final RowMapper<FileRecord> MAPPER = (rs, row) -> new FileRecord(
            MediaIds.text(rs.getBytes("id")), rs.getLong("uploader_id"), MediaPurpose.valueOf(rs.getString("purpose")),
            MediaStatus.valueOf(rs.getString("status")), rs.getString("mime_type"), rs.getObject("size_bytes", Long.class),
            instant(rs, "created_at"), instant(rs, "next_action_at"));
    private final JdbcTemplate jdbc;
    public FileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void insert(FileRecord file) {
        jdbc.update("INSERT INTO media_file(id,uploader_id,purpose,status,created_at,next_action_at) VALUES (?,?,?,'ISSUED',?,?)",
                MediaIds.bytes(file.id()), file.uploaderId(), file.purpose().name(), timestamp(file.createdAt()), timestamp(file.nextActionAt()));
    }
    public Optional<FileRecord> find(String id) { return find(jdbc, id, false); }
    public Optional<FileRecord> lock(String id) { return find(jdbc, id, true); }
    public static Optional<FileRecord> find(JdbcTemplate connection, String id, boolean lock) {
        return connection.query("SELECT * FROM media_file WHERE id=?" + (lock ? " FOR UPDATE" : ""), MAPPER, MediaIds.bytes(id)).stream().findFirst();
    }
    public static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
}
