package com.febrie.backend.article.comment;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/** 一次回调内按文章归批，由 Rabbit 单活分片保证同文章不跨实例并行。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class CommentCommandConsumer {
    private static final Logger log = LoggerFactory.getLogger(CommentCommandConsumer.class);
    private final CommentBroker broker;
    private final CommentCommandTransactions transactions;
    private final CommentMaintenance maintenance;
    private final Executor articles;

    CommentCommandConsumer(CommentBroker broker, CommentCommandTransactions transactions, CommentMaintenance maintenance,
                           @Qualifier("commentArticleExecutor") Executor articles) {
        this.broker = broker;
        this.transactions = transactions;
        this.maintenance = maintenance;
        this.articles = articles;
    }

    void consume(int partition, List<Message> messages, Channel channel) throws IOException {
        var groups = new LinkedHashMap<Long, List<Delivery>>();
        for (var message : messages) {
            long tag = message.getMessageProperties().getDeliveryTag();
            CommentCommand command;
            try {
                command = broker.decode(message, CommentCommand.class);
                if (broker.partition(command.articleId()) != partition) throw CommentErrors.invalid();
            } catch (RuntimeException invalid) {
                channel.basicNack(tag, false, false);
                continue;
            }
            groups.computeIfAbsent(command.articleId(), ignored -> new ArrayList<>()).add(new Delivery(tag, command));
        }
        var running = new LinkedHashMap<List<Delivery>, CompletableFuture<Outcome>>();
        groups.forEach((article, group) -> running.put(group,
                CompletableFuture.supplyAsync(() -> commit(article, group), articles)));
        // 由分片回调统一确认，工作线程不持有 Channel；本批结束后才接下批。
        for (var item : running.entrySet()) {
            Outcome outcome = item.getValue().join();
            for (var delivery : item.getKey()) {
                if (outcome == Outcome.COMMITTED) channel.basicAck(delivery.tag(), false);
                else channel.basicNack(delivery.tag(), false, outcome == Outcome.RETRY);
            }
        }
    }

    private Outcome commit(long article, List<Delivery> group) {
        CommentCommandTransactions.Applied committed;
        try {
            committed = transactions.apply(article, group.stream().map(Delivery::command).toList());
        } catch (TransientDataAccessException | RecoverableDataAccessException
                 | DataAccessResourceFailureException | TransactionException failure) {
            log.warn("评论批次暂时无法提交 articleId={}", article, failure);
            return Outcome.RETRY;
        } catch (RuntimeException failure) {
            log.error("评论批次进入死信 articleId={}", article, failure);
            return Outcome.REJECTED;
        }
        // 代理已经提交，缓存失败时仍确认这组持久化命令。
        committed.deleted().forEach(maintenance::visibility);
        if (committed.delta() != 0) maintenance.count(article);
        return Outcome.COMMITTED;
    }

    private enum Outcome { COMMITTED, RETRY, REJECTED }

    private record Delivery(long tag, CommentCommand command) { }
}
