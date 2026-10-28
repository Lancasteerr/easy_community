package com.febrie.backend.media;

import com.febrie.backend.media.application.ProcessingDeadline;
import com.febrie.backend.media.infrastructure.NotificationTimeouts;
import com.qcloud.cos.exception.CosClientException;
import java.net.SocketTimeoutException;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import software.amazon.awssdk.core.exception.*;
import static org.junit.jupiter.api.Assertions.*;

/** 超时按类型及SDK错误码识别，普通业务错误的文案即使含timeout也不能误分类。 */
class NotificationTimeoutTests {
    @Test void sdkNetworkPoolAndSqlTimeoutsAreTranslated() {
        var cos = new CosClientException("test"); cos.setErrorCode(new String("RequestTimeout"));
        List<RuntimeException> failures = List.of(cos,
                new CosClientException("test", new SocketTimeoutException()),
                new QueryTimeoutException("test", new SQLTimeoutException()),
                new CannotGetJdbcConnectionException("test", new SQLTransientConnectionException()),
                ApiCallTimeoutException.create(10),
                ApiCallAttemptTimeoutException.create(10));
        for (RuntimeException failure : failures) {
            var mapped = NotificationTimeouts.translate(failure);
            assertInstanceOf(ProcessingDeadline.TimedOut.class, mapped);
            assertSame(failure, mapped.getCause());
        }
        var ordinary = new IllegalStateException("timeout is only a message");
        assertSame(ordinary, NotificationTimeouts.translate(ordinary));
    }
    @Test void onlyActualExpiryEndsTheBudget() {
        AtomicLong nanos = new AtomicLong();
        var deadline = new ProcessingDeadline(Duration.ofMillis(4500), nanos::get);
        nanos.set(3_300_000_000L);
        deadline.check(); assertFalse(deadline.expired()); assertEquals(1200, deadline.remainingMillis());
        nanos.set(4_499_500_000L); assertEquals(1, deadline.remainingMillis());
        nanos.set(4_500_000_000L);
        assertTrue(deadline.expired()); assertThrows(ProcessingDeadline.TimedOut.class, deadline::check);
    }
}
