package com.febrie.backend.article.search.application;

import com.febrie.backend.article.search.infrastructure.persistence.mapper.IndexOutboxMapper;
import java.nio.ByteBuffer;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 经Spring代理领取；返回后事务已结束，网络确认不占主库行锁。 */
@Service
public class IndexOutbox {
    private final IndexOutboxMapper mapper;
    public IndexOutbox(IndexOutboxMapper mapper) { this.mapper = mapper; }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim() {
        var events = mapper.available();
        UUID uuid = UUID.randomUUID();
        byte[] token = ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
        if (!events.isEmpty() && mapper.claim(events.stream().map(IndexEvent::eventId).toList(), token) != events.size()) {
            throw new IllegalStateException("索引事件领取数量不一致");
        }
        return new Claim(List.copyOf(events), token);
    }
    public void complete(long id, byte[] token) { mapper.complete(id, token); }
    public void retry(long id, byte[] token) { mapper.retry(id, token, java.util.concurrent.ThreadLocalRandom.current().nextInt(5, 16)); }
    public record Claim(List<IndexEvent> events, byte[] token) { }
}
