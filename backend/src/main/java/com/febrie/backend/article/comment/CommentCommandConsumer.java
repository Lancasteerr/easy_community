package com.febrie.backend.article.comment;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/** 一次回调内按文章归批，由 Rabbit 单活分片保证同文章不跨实例并行。 */
@Component
class CommentCommandConsumer {
    private static final Logger log = LoggerFactory.getLogger(CommentCommandConsumer.class);
    private final CommentBroker broker;
    private final CommentCommandTransactions transactions;
    private final CommentMaintenance maintenance;

    CommentCommandConsumer(CommentBroker broker, CommentCommandTransactions transactions, CommentMaintenance maintenance) {
        this.broker = broker;
        this.transactions = transactions;
        this.maintenance = maintenance;
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
        for (var entry : groups.entrySet()) {
            long article = entry.getKey();
            var group = entry.getValue();
            CommentCommandTransactions.Applied committed;
            try {
                committed = transactions.apply(article, group.stream().map(Delivery::command).toList());
            } catch (RuntimeException failure) {
                log.warn("评论批次提交失败 articleId={}", article, failure);
                for (var delivery : group) channel.basicNack(delivery.tag(), false, true);
                continue;
            }
            // 代理已经提交，缓存失败时仍确认这组持久化命令。
            committed.deleted().forEach(maintenance::visibility);
            if (committed.delta() != 0) maintenance.count(article);
            for (var delivery : group) channel.basicAck(delivery.tag(), false);
        }
    }

    private record Delivery(long tag, CommentCommand command) { }
}
