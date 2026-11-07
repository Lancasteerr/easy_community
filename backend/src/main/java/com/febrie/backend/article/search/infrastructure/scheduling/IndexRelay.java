package com.febrie.backend.article.search.infrastructure.scheduling;

import com.febrie.backend.article.search.application.IndexOutbox;
import com.febrie.backend.article.search.infrastructure.messaging.IndexPublisher;
import com.febrie.backend.article.search.infrastructure.persistence.mapper.IndexOutboxMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** 网络阶段禁止外层事务；每轮最多10条，失败保留Outbox并温和重试。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Transactional(propagation = Propagation.NEVER)
public class IndexRelay {
    private static final Logger log = LoggerFactory.getLogger(IndexRelay.class);
    private final IndexOutbox outbox;
    private final IndexPublisher publisher;
    private final MeterRegistry metrics;
    public IndexRelay(IndexOutbox outbox, IndexPublisher publisher, IndexOutboxMapper mapper, MeterRegistry metrics) {
        this.outbox = outbox; this.publisher = publisher; this.metrics = metrics;
        metrics.gauge("article.index.outbox.count", mapper, IndexOutboxMapper::count);
        metrics.gauge("article.index.outbox.oldest.seconds", mapper, IndexOutboxMapper::oldestAge);
    }
    @Scheduled(scheduler = "articleIndexRelayScheduler", fixedDelay = 1000, initialDelay = 1000)
    public void relay() {
        try {
            var claim = outbox.claim();
            Map<Long, CorrelationData> sent = new LinkedHashMap<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (var event : claim.events()) {
                try { sent.put(event.eventId(), publisher.send(event)); }
                catch (RuntimeException failure) { outbox.retry(event.eventId(), claim.token()); }
            }
            for (var entry : sent.entrySet()) {
                if (publisher.confirmed(entry.getValue(), deadline)) { outbox.complete(entry.getKey(), claim.token()); }
                else {
                    metrics.counter("article.index.publish.failure").increment();
                    outbox.retry(entry.getKey(), claim.token());
                }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (RuntimeException failure) { log.warn("索引事件投递失败，保留事件等待重试 error={}", failure.getClass().getSimpleName()); }
    }
}
