package com.febrie.backend.article.like.infrastructure.messaging;

import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/** 分区算法是部署约定，发布、重放、监听与运维必须使用同一个固定分区数。 */
@Component
public class LikePartitionRouter {
    private final LikeProperties properties;
    public LikePartitionRouter(LikeProperties properties) { this.properties = properties; }
    public int partition(long articleId) { return Math.floorMod(Long.hashCode(articleId), properties.partitionCount()); }
    public String routingKey(int partition) { return "article.like.set.p" + partition; }
    public String queue(int partition) { return properties.name("command.p" + partition + ".q"); }
    public List<String> queues() { return IntStream.range(0, properties.partitionCount()).mapToObj(this::queue).toList(); }
}
