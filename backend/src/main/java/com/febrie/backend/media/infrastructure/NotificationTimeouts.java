package com.febrie.backend.media.infrastructure;

import com.febrie.backend.media.application.upload.ProcessingDeadline;
import com.qcloud.cos.exception.CosClientException;
import java.net.SocketTimeoutException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.TimeoutException;
import org.apache.http.conn.ConnectTimeoutException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;

/** 根据SDK标识和异常类型识别超时，不解析可能包含凭证的异常文案。 */
public final class NotificationTimeouts {
    private NotificationTimeouts() { }

    public static RuntimeException translate(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ProcessingDeadline.TimedOut timeout) { return timeout; }
            if (cause instanceof CosClientException cos && (cos.isRequestTimeout() || "RequestTimeout".equals(cos.getErrorCode()))
                    || cause instanceof SocketTimeoutException || cause instanceof ConnectTimeoutException
                    || cause instanceof SQLTimeoutException || cause instanceof QueryTimeoutException
                    || cause instanceof TimeoutException || cause instanceof ApiCallTimeoutException
                    || cause instanceof ApiCallAttemptTimeoutException) {
                return new ProcessingDeadline.TimedOut(failure);
            }
            // 此连接异常来自Hikari有界等待失败；普通SQL错误不按超时处理。
            if (cause instanceof CannotGetJdbcConnectionException && cause.getCause() instanceof SQLTransientConnectionException) {
                return new ProcessingDeadline.TimedOut(failure);
            }
            // MyBatis 的连接获取失败会保留 Hikari 的瞬时连接异常，可能不再包装为 Spring JDBC 异常。
            if (cause instanceof SQLTransientConnectionException) {
                return new ProcessingDeadline.TimedOut(failure);
            }
        }
        return failure;
    }
}
