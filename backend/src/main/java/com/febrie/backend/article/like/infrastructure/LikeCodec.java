package com.febrie.backend.article.like.infrastructure;

import com.febrie.backend.article.like.domain.LikeCommand;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 限定JSON类型和消息大小；从不根据MQ类型头反序列化任意Java类。 */
@Component
public class LikeCodec {
    private final ObjectMapper mapper;
    public LikeCodec(ObjectMapper mapper) { this.mapper = mapper; }
    public byte[] encode(LikeCommand command) { return mapper.writeValueAsBytes(command); }
    public LikeCommand decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 4096) { throw new IllegalArgumentException("点赞消息大小无效"); }
        return mapper.readValue(bytes, LikeCommand.class);
    }
}
