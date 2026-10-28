package com.febrie.backend.media.infrastructure.persistence;

import com.febrie.backend.media.application.ProcessingDeadline;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.NotificationTimeouts;
import com.zaxxer.hikari.*;
import java.time.Instant;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.febrie.backend.media.infrastructure.persistence.FileRepository.timestamp;

/** 通知独立连接池最多等待250ms，SQL网络上限1秒，不拖长普通业务事务。 */
@Component
public class NotificationFiles implements AutoCloseable {
    private final HikariDataSource source;
    private final JdbcTemplate jdbc;
    public NotificationFiles(Environment env) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env.getRequiredProperty("spring.datasource.url"));
        config.setUsername(env.getRequiredProperty("spring.datasource.username"));
        config.setPassword(env.getRequiredProperty("spring.datasource.password"));
        config.setPoolName("media-notifications"); config.setMaximumPoolSize(4); config.setMinimumIdle(0);
        config.setConnectionTimeout(250); config.setValidationTimeout(250); config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", "500"); config.addDataSourceProperty("socketTimeout", "1000");
        // 公布成功需要这一条UPDATE的隐式事务已经提交，不在调用者事务中执行。
        config.setAutoCommit(true);
        source = new HikariDataSource(config); jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(1);
    }
    public Optional<FileRecord> find(String id, ProcessingDeadline deadline) {
        requireOutsideTransaction(deadline);
        try {
            var file = FileRepository.find(jdbc, id, false);
            deadline.check();
            return file;
        } catch (RuntimeException failure) { throw NotificationTimeouts.translate(failure); }
    }
    public boolean ready(String id, long size, String mime, Instant now, Instant collectAt, ProcessingDeadline deadline) {
        requireOutsideTransaction(deadline);
        try {
            boolean updated = jdbc.update("""
                UPDATE media_file SET status='READY',size_bytes=?,mime_type=?,next_action_at=?
                WHERE id=? AND status='ISSUED' AND next_action_at>?
                """, size, mime, timestamp(collectAt), MediaIds.bytes(id), timestamp(now)) == 1;
            // 已发出的隐式提交无法撤销；迟到时只报告超时，不把结果当成本次成功。
            deadline.check();
            return updated;
        } catch (RuntimeException failure) { throw NotificationTimeouts.translate(failure); }
    }
    private static void requireOutsideTransaction(ProcessingDeadline deadline) {
        deadline.check();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("通知不得被外层数据库事务包围");
        }
    }
    @jakarta.annotation.PreDestroy
    @Override public void close() { source.close(); }
}
