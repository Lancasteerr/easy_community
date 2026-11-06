package com.febrie.backend.article.like.infrastructure.messaging;

import com.febrie.backend.article.like.application.LikeProjection;
import com.febrie.backend.article.like.application.LikeSqlService;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;

import com.febrie.backend.article.like.domain.*;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.*;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/** 每次尝试调用独立事务代理；SQL提交、可失败投影、ACK保持三个阶段。 */
@Component
@ConditionalOnProperty(name = "app.like.enabled", havingValue = "true")
public class LikeConsumer {
    private static final Logger log = LoggerFactory.getLogger(LikeConsumer.class);
    private final LikeCodec codec;
    private final LikeSqlService sql;
    private final ObjectProvider<LikeProjection> projection;
    private final LikeTelemetry telemetry;
    private final Delay delay;

    @org.springframework.beans.factory.annotation.Autowired
    public LikeConsumer(LikeCodec codec, LikeSqlService sql, ObjectProvider<LikeProjection> projection, LikeTelemetry telemetry) {
        this(codec, sql, projection, telemetry, Thread::sleep);
    }

    /** 测试可替换等待器，用屏障控制故障，不让单元测试真实等待15秒。 */
    public LikeConsumer(LikeCodec codec, LikeSqlService sql, ObjectProvider<LikeProjection> projection,
                        LikeTelemetry telemetry, Delay delay) {
        this.codec = codec; this.sql = sql; this.projection = projection; this.telemetry = telemetry; this.delay = delay;
    }

    public void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        LikeCommand command;
        try { command = codec.decode(message.getBody()); }
        catch (RuntimeException invalid) { isolate(channel, tag, "invalid_message"); return; }
        telemetry.queueAge(command.occurredAt());
        LikeSqlService.Result result = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            long started = System.nanoTime();
            try { result = sql.apply(command); break; }
            catch (TransientDataAccessException | RecoverableDataAccessException | DataAccessResourceFailureException | TransactionException retryable) {
                telemetry.event("consume", "retry");
                if (attempt == 4) { isolate(channel, tag, "retry_exhausted"); return; }
            } catch (RuntimeException fatal) {
                log.error("点赞SQL消费错误 operationId={} errorType={}",command.operationId(),fatal.getClass().getSimpleName());
                isolate(channel, tag, "sql_invariant_or_error"); return;
            }
            finally { telemetry.time("sql", started); }
            // 退避在事务计时之外；否则数据库延迟指标会混入人为等待的1/2/4/8秒。
            try { delay.await(1000L << attempt); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("消费已中断，保留未确认消息", interrupted);
            }
        }
        if (result == null) { throw new IllegalStateException("SQL消费未得到结果"); }
        telemetry.event("consume", result.status().name().toLowerCase(java.util.Locale.ROOT));
        // 缓存失败不能让已经提交的SQL被报成未提交；STALE不会带原消息写投影。
        if (result.status() != LikeSqlService.Status.STALE) {
            var committed = result;
            try { projection.ifAvailable(p -> p.committed(committed)); }
            catch (RuntimeException cacheFailure) { telemetry.event("cache", "commit_failure"); log.warn("点赞已提交，缓存维护失败 articleId={}", command.articleId()); }
        }
        channel.basicAck(tag, false);
    }

    private void isolate(Channel channel, long tag, String reason) throws IOException {
        telemetry.event("consume", reason);
        log.error("点赞消息隔离 reason={} deliveryTag={}", reason, tag);
        // Broker的at-least-once死信转发负责保留消息，不能先ACK再自行发送死信。
        channel.basicNack(tag, false, false);
    }

    @FunctionalInterface public interface Delay { void await(long milliseconds) throws InterruptedException; }
}
