package com.febrie.backend.performance;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 真实烟测在专用库制造可撤销的行锁等待，验证两类指标能观察到同一事件。 */
final class BaselineFailureProbe {
    private BaselineFailureProbe() { }

    static Map<String, Object> verify(BaselineEnvironment.Site site, BaselineProbe probe) throws Exception {
        var before = probe.sample("lock-before", false).mysql();
        if (before == null) { throw new IllegalStateException("缺少行锁探针基线"); }
        var source = site.jdbc.getDataSource();
        try (var workers = Executors.newSingleThreadExecutor(); var blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.createStatement();
                     var locked = statement.executeQuery("SELECT view_count FROM article WHERE id=1 FOR UPDATE")) {
                    if (!locked.next()) { throw new IllegalStateException("未找到探针文章"); }
                }
                var waiting = new CountDownLatch(1);
                var future = workers.submit(() -> {
                    try (var other = source.getConnection(); var statement = other.createStatement()) {
                        statement.execute("SET SESSION innodb_lock_wait_timeout=5");
                        try {
                            waiting.countDown();
                            return statement.executeUpdate("UPDATE article SET view_count=view_count WHERE id=1");
                        } finally { statement.execute("SET SESSION innodb_lock_wait_timeout=DEFAULT"); }
                    }
                });
                if (!waiting.await(3, TimeUnit.SECONDS)) { throw new IllegalStateException("行锁探针未启动"); }
                boolean observed = false;
                for (int attempt = 0; attempt < 4 && !observed; attempt++) {
                    Thread.sleep(150);
                    var snapshot = probe.sample("lock-held", false).mysql();
                    observed = snapshot != null && snapshot.blocking().stream().anyMatch(row -> "article".equals(row.get("tableName")));
                }
                blocker.rollback();
                future.get(5, TimeUnit.SECONDS);
                var after = probe.sample("lock-released", false).mysql();
                if (!observed || after == null || after.waits() <= before.waits() || after.waitMs() <= before.waitMs()) {
                    throw new IllegalStateException("未同时观察到行锁阻塞和累计等待增量");
                }
                return Map.of("blockingObserved", true, "waits", after.waits() - before.waits(),
                        "waitMs", after.waitMs() - before.waitMs());
            } finally { blocker.rollback(); }
        }
    }
}
