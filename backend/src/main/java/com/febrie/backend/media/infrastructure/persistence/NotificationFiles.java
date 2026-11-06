package com.febrie.backend.media.infrastructure.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.febrie.backend.media.application.upload.ProcessingDeadline;
import com.febrie.backend.media.domain.FileRecord;
import com.febrie.backend.media.domain.MediaIds;
import com.febrie.backend.media.infrastructure.NotificationTimeouts;
import com.febrie.backend.media.infrastructure.persistence.entity.MediaFileEntity;
import com.febrie.backend.media.infrastructure.persistence.notification.NotificationFileMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.SQLExceptionOverride;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.MyBatisExceptionTranslator;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 持有通知私有连接池与执行器，不注册第二套数据源 Bean，主库仍由 Boot 自动配置。 */
@Component
public class NotificationFiles implements AutoCloseable {
    private final HikariDataSource source;
    private final NotificationFileMapper files;

    public NotificationFiles(Environment env) {
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(env.getRequiredProperty("spring.datasource.url"));
        pool.setUsername(env.getRequiredProperty("spring.datasource.username"));
        pool.setPassword(env.getRequiredProperty("spring.datasource.password"));
        pool.setPoolName("media-notifications");
        pool.setMaximumPoolSize(4);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(250);
        pool.setValidationTimeout(250);
        pool.setInitializationFailTimeout(-1);
        pool.addDataSourceProperty("connectTimeout", "500");
        pool.addDataSourceProperty("socketTimeout", "1000");
        // 一条 READY 更新提交后才允许返回成功，不参与业务事务。
        pool.setAutoCommit(true);
        // MySQL 取消超时语句后连接可能异步关闭；直接淘汰该连接，避免下次通知复用坏连接。
        pool.setExceptionOverride(new SQLExceptionOverride() {
            @java.lang.Override
            public Override adjudicate(SQLException failure) {
                return failure instanceof SQLTimeoutException ? Override.MUST_EVICT : Override.CONTINUE_EVICT;
            }
        });
        source = new HikariDataSource(pool);
        try {
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.setCacheEnabled(false);
            configuration.setDefaultStatementTimeout(1);

            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/notification/*.xml"));
            // 按异常类型转换，不为获取数据库错误码元数据再申请一次短等待连接。
            var translator = new MyBatisExceptionTranslator(SQLExceptionSubclassTranslator::new, true);
            var session = new SqlSessionTemplate(factory.getObject(), ExecutorType.SIMPLE, translator);
            files = session.getMapper(NotificationFileMapper.class);
        } catch (Exception failure) {
            source.close();
            throw new IllegalStateException("无法初始化通知持久化执行器", failure);
        }
    }

    public Optional<FileRecord> find(String id, ProcessingDeadline deadline) {
        requireOutsideTransaction(deadline);
        try {
            var file = Optional.ofNullable(files.find(MediaIds.bytes(id))).map(MediaFileEntity::toRecord);
            deadline.check();
            return file;
        } catch (RuntimeException failure) {
            throw NotificationTimeouts.translate(failure);
        }
    }

    public boolean ready(String id, long size, String mime, Instant now, Instant collectAt, ProcessingDeadline deadline) {
        requireOutsideTransaction(deadline);
        try {
            boolean updated = files.ready(MediaIds.bytes(id), size, mime, now, collectAt) == 1;
            // 隐式提交无法撤销；迟到时只报告超时，不把已提交结果当成本次成功。
            deadline.check();
            return updated;
        } catch (RuntimeException failure) {
            throw NotificationTimeouts.translate(failure);
        }
    }

    private static void requireOutsideTransaction(ProcessingDeadline deadline) {
        deadline.check();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("通知不得被外层数据库事务包围");
        }
    }

    @jakarta.annotation.PreDestroy
    @Override public void close() { source.close(); }
}
