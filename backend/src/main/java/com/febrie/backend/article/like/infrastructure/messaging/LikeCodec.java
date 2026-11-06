package com.febrie.backend.article.like.infrastructure.messaging;

import com.febrie.backend.article.like.domain.LikeCommand;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 限定JSON类型和消息大小；从不根据MQ类型头反序列化任意Java类。 */
@Component
public class LikeCodec {
    private final ObjectMapper mapper;
    public LikeCodec(ObjectMapper mapper) { this.mapper = mapper; }
    public byte[] encode(LikeCommand command) {
        // 固定线协议的时间和版本为字符串，不随全局Jackson日期/数值配置改变。
        return mapper.writeValueAsBytes(Map.of("schemaVersion", command.schemaVersion(), "operationId", command.operationId(),
                "userId", command.userId(), "articleId", command.articleId(), "desiredLiked", command.desiredLiked(),
                "version", command.version(), "occurredAt", command.occurredAt().toString()));
    }
    public LikeCommand decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 4096) { throw new IllegalArgumentException("点赞消息大小无效"); }
        var value = mapper.readTree(bytes);
        // 拒绝字符串布尔值、浮点ID、数值版本/时间及未知字段，不能依靠宽松类型转换通过schema校验。
        if (value == null || !value.isObject() || value.size() != 7
                || !value.path("schemaVersion").isIntegralNumber() || !value.path("schemaVersion").canConvertToInt()
                || !value.path("userId").isIntegralNumber() || !value.path("userId").canConvertToLong()
                || !value.path("articleId").isIntegralNumber() || !value.path("articleId").canConvertToLong()
                || !value.path("desiredLiked").isBoolean() || !value.path("version").isString()
                || !value.path("operationId").isString() || !value.path("occurredAt").isString()) {
            throw new IllegalArgumentException("点赞消息schema或字段类型无效");
        }
        return new LikeCommand(value.path("schemaVersion").asInt(), value.path("operationId").asString(),
                value.path("userId").asLong(), value.path("articleId").asLong(), value.path("desiredLiked").asBoolean(),
                value.path("version").asString(), Instant.parse(value.path("occurredAt").asString()));
    }
}
