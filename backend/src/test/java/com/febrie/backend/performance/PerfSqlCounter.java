package com.febrie.backend.performance;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;

/** 仅测试上下文注册，按Mapper语句统计实际执行次数；不把事务提交计作SQL写语句。 */
@Intercepts({
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
    @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public final class PerfSqlCounter implements Interceptor {
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    @Override public Object intercept(Invocation invocation) throws Throwable {
        String id = ((MappedStatement) invocation.getArgs()[0]).getId();
        counters.computeIfAbsent(id, ignored -> new LongAdder()).increment();
        return invocation.proceed();
    }
    Map<String, Long> snapshot() {
        Map<String, Long> result = new TreeMap<>();
        counters.forEach((id, count) -> result.put(id, count.sum())); return result;
    }
    Map<String, Long> delta(Map<String, Long> before) {
        Map<String, Long> result = new TreeMap<>();
        snapshot().forEach((id, count) -> {
            long difference = count - before.getOrDefault(id, 0L);
            if (difference != 0) { result.put(id, difference); }
        });
        return result;
    }
}
