package com.febrie.backend.media.infrastructure.persistence;

import com.febrie.backend.media.domain.*;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.*;
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
    public boolean hasReferences(String id) {
        return !jdbc.query("SELECT file_id FROM media_reference WHERE file_id=? LIMIT 1", (rs, row) -> rs.getBytes(1), MediaIds.bytes(id)).isEmpty();
    }
    public Set<String> references(ReferenceType type, long entity) {
        return new TreeSet<>(jdbc.query("SELECT file_id FROM media_reference WHERE ref_type=? AND ref_id=?",
                (rs, row) -> MediaIds.text(rs.getBytes(1)), type.name(), entity));
    }
    public void addReference(ReferenceType type, long entity, String id) {
        jdbc.update("INSERT INTO media_reference(file_id,ref_type,ref_id) VALUES (?,?,?)", MediaIds.bytes(id), type.name(), entity);
        jdbc.update("UPDATE media_file SET next_action_at=NULL WHERE id=? AND status='READY'", MediaIds.bytes(id));
    }
    public void removeReference(ReferenceType type, long entity, String id) {
        jdbc.update("DELETE FROM media_reference WHERE file_id=? AND ref_type=? AND ref_id=?", MediaIds.bytes(id), type.name(), entity);
    }
    /** 调用者持有文件行锁；RC查询的是其他实体最新提交的引用。 */
    public void scheduleIfUnreferenced(String id, Instant collectAt) {
        if (!hasReferences(id)) {
            jdbc.update("UPDATE media_file SET next_action_at=? WHERE id=? AND status='READY'", timestamp(collectAt), MediaIds.bytes(id));
        }
    }
    public List<String> candidates(MediaStatus status, Instant now, int limit) {
        return jdbc.query("SELECT id FROM media_file WHERE status=? AND next_action_at<=? ORDER BY next_action_at,id LIMIT ?",
                (rs, row) -> MediaIds.text(rs.getBytes(1)), status.name(), timestamp(now), limit);
    }
    public void deleting(String id, Instant now) {
        jdbc.update("UPDATE media_file SET status='DELETING',next_action_at=? WHERE id=?", timestamp(now), MediaIds.bytes(id));
    }
    public void rescheduleDeletion(String id, Instant next) {
        jdbc.update("UPDATE media_file SET next_action_at=? WHERE id=? AND status='DELETING'", timestamp(next), MediaIds.bytes(id));
    }
    public void deleteMetadata(String id) {
        jdbc.update("""
                DELETE FROM media_file WHERE id=? AND status='DELETING'
                AND NOT EXISTS(SELECT 1 FROM media_reference WHERE file_id=?)
                """, MediaIds.bytes(id), MediaIds.bytes(id));
    }
    public static Optional<FileRecord> find(JdbcTemplate connection, String id, boolean lock) {
        return connection.query("SELECT * FROM media_file WHERE id=?" + (lock ? " FOR UPDATE" : ""), MAPPER, MediaIds.bytes(id)).stream().findFirst();
    }
    public static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
}
