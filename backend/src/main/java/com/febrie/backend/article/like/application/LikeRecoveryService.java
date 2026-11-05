package com.febrie.backend.article.like.application;

import com.febrie.backend.article.like.domain.LikeVersion;
import com.febrie.backend.article.like.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 离线恢复的前提是所有写实例已停用；专用连接持有命名锁，不能归还池后继续恢复。 */
@Service
@ConditionalOnProperty(name = "app.like.enabled", havingValue = "true")
public class LikeRecoveryService {
    private final DataSource dataSource;
    private final LikeVersionAllocator allocator;
    private final LikeProperties properties;
    public LikeRecoveryService(DataSource dataSource, LikeVersionAllocator allocator, LikeProperties properties) {
        this.dataSource = dataSource; this.allocator = allocator; this.properties = properties;
    }

    public int recover(int expected) throws SQLException {
        if (properties.writeEnabled()) { throw new IllegalStateException("维护进程必须禁用写入口，并确认其他写实例已停止"); }
        String name = "like-recover:" + UUID.nameUUIDFromBytes(properties.namespace().getBytes(StandardCharsets.UTF_8));
        try (Connection connection = dataSource.getConnection()) {
            if (scalar(connection, "SELECT GET_LOCK(?, 0)", name) != 1) { throw new IllegalStateException("已有点赞恢复执行者"); }
            int target = -1;
            String runtime = null;
            try {
                runtime = allocator.recoveryRuntime();
                connection.setAutoCommit(false);
                int current = (int) scalar(connection, "SELECT epoch FROM like_version_epoch WHERE id=1 FOR UPDATE");
                if (current != expected || current + 1 >= LikeVersion.EPOCH_LIMIT) { throw new IllegalStateException("SQL纪元与预期不符或已耗尽"); }
                allocator.pause();
                target = current + 1;
                try (var update = connection.prepareStatement("UPDATE like_version_epoch SET epoch=? WHERE id=1")) {
                    update.setInt(1, target);
                    if (update.executeUpdate() != 1) { throw new IllegalStateException("SQL纪元行缺失"); }
                }
                connection.commit();
                connection.setAutoCommit(true);
                allocator.initialize(target, runtime);
                if (scalar(connection, "SELECT IS_USED_LOCK(?) = CONNECTION_ID()", name) != 1
                        || scalar(connection, "SELECT epoch FROM like_version_epoch WHERE id=1") != target) {
                    throw new IllegalStateException("恢复锁失效或SQL纪元发生变化");
                }
                allocator.activate(target, runtime);
                return target;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException ignored) { /* 提交未知时不假定回滚成功。 */ }
                if (target >= 0 && runtime != null) {
                    try { allocator.pauseIf(target, runtime); } catch (RuntimeException ignored) { /* 新纪元不能被迟到失败清理覆盖。 */ }
                }
                throw failure;
            } finally {
                try { connection.setAutoCommit(true); scalar(connection, "SELECT RELEASE_LOCK(?)", name); }
                catch (SQLException | RuntimeException ignored) {
                    // 释放结果不明时销毁底层连接，不能把持有命名锁的会话归还连接池。
                    try { connection.abort(Runnable::run); } catch (SQLException closeFailure) { /* 保留原始故障。 */ }
                }
            }
        }
    }

    private static long scalar(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) { statement.setObject(i + 1, values[i]); }
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) { throw new IllegalStateException("恢复元数据缺失"); }
                long value = rows.getLong(1);
                if (rows.wasNull()) { throw new IllegalStateException("恢复锁或元数据未确认"); }
                return value;
            }
        }
    }
}
