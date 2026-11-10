package com.febrie.backend.user.application;

import com.febrie.backend.shared.cache.*;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserBasicData;
import com.febrie.backend.user.infrastructure.cache.UserBasicCacheStore;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 公开资料查询独立于认证；缺失项批量回源，所有锁释放后才返回。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class UserBasicQueryService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserBasicQueryService.class);
    private final UserBasicReader reader;
    private final UserBasicCacheStore cache;
    private final Semaphore sourceReads = new Semaphore(4);
    public UserBasicQueryService(UserBasicReader reader, UserBasicCacheStore cache) {
        this.reader = reader; this.cache = cache;
    }
    public Optional<UserBasicData> get(long id) { return Optional.ofNullable(get(List.of(id)).get(id)); }

    public Map<Long, UserBasicData> get(Collection<Long> ids) {
        var ordered = new ArrayList<>(new LinkedHashSet<>(ids));
        Map<Long, UserBasicData> result = new HashMap<>();
        // 所有分批共享一个竞争等待预算，不能随作者数量无限累加。
        long deadline = System.nanoTime() + CacheProtocol.WAIT_BUDGET.toNanos();
        try {
            for (int start = 0; start < ordered.size(); start += 100) {
                loadBatch(ordered.subList(start, Math.min(start + 100, ordered.size())), result, deadline);
            }
            return result;
        } catch (RuntimeException error) {
            log.warn("用户基础信息读取失败 error={}", error.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private void loadBatch(List<Long> ids, Map<Long, UserBasicData> result, long deadline) {
        Set<Long> pending = new LinkedHashSet<>(ids);
        while (!pending.isEmpty()) {
            var cached = cache.get(pending);
            for (long id : new ArrayList<>(pending)) { accept(id, cached.get(id), pending, result); }
            if (pending.isEmpty()) { return; }
            // 必须先取得名额再竞争Redis锁，不能持锁等待数据库并发名额。
            if (!sourceReads.tryAcquire()) { throw unavailable(); }
            Map<Long, String> held = new LinkedHashMap<>();
            try {
                for (long id : pending) {
                    String token = cache.tryLock(id);
                    if (token != null) { held.put(id, token); }
                }
                if (!held.isEmpty()) { loadLocked(held, pending, result); }
            } finally {
                held.forEach(cache::release);
                sourceReads.release();
            }
            if (!pending.isEmpty() && !CacheProtocol.awaitRetry(deadline)) { throw unavailable(); }
        }
    }

    private void loadLocked(Map<Long, String> held, Set<Long> pending, Map<Long, UserBasicData> result) {
        var checked = cache.get(held.keySet());
        for (long id : new ArrayList<>(held.keySet())) {
            if (accept(id, checked.get(id), pending, result)) { release(held, id); }
        }
        if (held.isEmpty()) { return; }
        var loaded = reader.load(new ArrayList<>(held.keySet()));
        for (long id : new ArrayList<>(held.keySet())) {
            var data = loaded.get(id);
            boolean published;
            try {
                published = data == null ? cache.missing(id, held.get(id)) : cache.publish(id, held.get(id), data);
            } finally {
                // 每项写完立即释放，不能等待整批编码、回填或业务组装完成。
                release(held, id);
            }
            if (published) {
                if (data != null) { result.put(id, data); }
                pending.remove(id);
            }
            // token失效的回源结果不进入响应；释放其余锁后在预算内重新读取。
        }
    }

    private boolean accept(long id, CacheValue<UserBasicData> value, Set<Long> pending, Map<Long, UserBasicData> result) {
        Objects.requireNonNull(value, "用户缓存缺少确认项");
        if (value.state() == CacheValue.State.MISS) { return false; }
        if (value.state() == CacheValue.State.HIT) { result.put(id, value.data()); }
        pending.remove(id);
        return true;
    }
    private void release(Map<Long, String> held, long id) { cache.release(id, held.remove(id)); }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "用户资料暂时不可用");
    }
}
