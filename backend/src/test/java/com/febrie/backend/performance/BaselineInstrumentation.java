package com.febrie.backend.performance;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.febrie.backend.article.content.web.ArticleController;
import com.febrie.backend.article.view.application.ArticleViewFlush;
import com.febrie.backend.article.view.application.ArticleViewService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.Advised;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 增补仅本基准需要的观测，复用应用自己的日志、事务代理和指标注册表。 */
@TestConfiguration(proxyBeanMethods = false)
class BaselineInstrumentation {
    @Bean PerfSqlCounter baselineSqlCounter() { return new PerfSqlCounter(); }

    static AutoCloseable attach(BaselineEnvironment.Site site) {
        var registry = site.app.getBean(MeterRegistry.class);
        var timer = registry.timer("baseline.pv.flush");
        var failures = registry.counter("baseline.pv.failures", "step", "flush");
        var recording = registry.counter("baseline.pv.failures", "step", "record");
        var flush = (Advised) site.app.getBean(ArticleViewFlush.class);
        MethodInterceptor advice = invocation -> {
            if (!invocation.getMethod().getName().equals("flush")) { return invocation.proceed(); }
            long started = System.nanoTime();
            try { return invocation.proceed(); }
            finally { timer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS); }
        };
        flush.addAdvice(0, advice);
        var loggers = List.of(logger(ArticleViewFlush.class), logger(ArticleViewService.class), logger(ArticleController.class));
        var appender = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if (!event.getLevel().isGreaterOrEqual(Level.WARN)) { return; }
                String message = event.getMessage();
                if (message.startsWith("文章浏览量刷盘或修复失败")) { failures.increment(); }
                else if (message.startsWith("文章浏览计数失败") || message.startsWith("记录文章浏览失败")) {
                    recording.increment();
                }
            }
        };
        appender.start();
        loggers.forEach(logger -> logger.addAppender(appender));
        return () -> {
            loggers.forEach(logger -> logger.detachAppender(appender));
            appender.stop();
            flush.removeAdvice(advice);
        };
    }

    private static Logger logger(Class<?> type) { return (Logger) LoggerFactory.getLogger(type); }
}
