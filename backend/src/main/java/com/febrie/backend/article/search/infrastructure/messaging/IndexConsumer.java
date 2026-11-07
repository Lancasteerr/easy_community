package com.febrie.backend.article.search.infrastructure.messaging;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleIndexStore;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import org.slf4j.*;
import org.springframework.amqp.core.Message;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.ObjectMapper;

/** ACK只发生在索引成功或确定事件已过时之后，退避不持SQL事务。 */
@Component
public class IndexConsumer {
    private static final Logger log = LoggerFactory.getLogger(IndexConsumer.class);
    private final ArticleIndexReader reader;
    private final ArticleIndexStore store;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final MeterRegistry metrics;
    public IndexConsumer(ArticleIndexReader reader, ArticleIndexStore store, ObjectMapper json,
                         ApplicationEventPublisher events, MeterRegistry metrics) {
        this.reader = reader; this.store = store; this.json = json; this.events = events; this.metrics = metrics;
    }
    public void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        IndexEvent event;
        try { event = json.readValue(message.getBody(), IndexEvent.class); }
        catch (RuntimeException invalid) { channel.basicNack(tag, false, false); return; }
        boolean created = false;
        ArticleIndexMetadata snapshot = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (store.metaVersion(event.articleId()) >= event.metaVersion()) { break; }
                snapshot = reader.load(event.articleId());
                if (snapshot == null || snapshot.metaVersion() < event.metaVersion()) {
                    throw new IllegalStateException("主库元数据缺失或低于事件版本");
                }
                created = store.upsert(snapshot);
                break;
            } catch (IOException | RuntimeException failure) {
                metrics.counter("article.index.consume.failure").increment();
                if (attempt == 2 || !retryable(failure)) {
                    log.error("索引消费进入死信 eventId={} articleId={} error={}", event.eventId(), event.articleId(), failure.getClass().getSimpleName());
                    channel.basicNack(tag, false, false);
                    return;
                }
                try { Thread.sleep(1000L << attempt); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("索引消费中断，保留未确认消息", interrupted); }
            }
        }
        channel.basicAck(tag, false);
        if (created && snapshot != null && snapshot.visible()) {
            try { events.publishEvent(new ArticleIndexed(event.articleId())); }
            catch (RuntimeException failure) { log.warn("首次算分触发失败 articleId={}", event.articleId()); }
        }
    }
    private static boolean retryable(Exception failure) {
        return failure instanceof IOException || failure instanceof TransientDataAccessException || failure instanceof TransactionException
                || failure instanceof org.springframework.dao.DataAccessResourceFailureException
                || failure instanceof org.springframework.dao.RecoverableDataAccessException
                || (failure instanceof ElasticsearchException e && (e.status() == 429 || e.status() == 409 || e.status() >= 500));
    }
}
