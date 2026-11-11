package com.febrie.backend.article.tag.application;

import com.febrie.backend.article.common.ArticleErrors;
import com.febrie.backend.article.tag.application.dto.TagView;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 只有完整快照能证明不存在；所有刷新共用一把实例内锁。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class TagCatalog {
    private static final Logger log = LoggerFactory.getLogger(TagCatalog.class);
    private final TagDirectoryReader reader;
    private final AtomicReference<TagSnapshot> snapshot = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final LongSupplier nanos;
    private long retryAfter;

    @Autowired
    public TagCatalog(TagDirectoryReader reader) { this(reader, System::nanoTime); }
    TagCatalog(TagDirectoryReader reader, LongSupplier nanos) { this.reader = reader; this.nanos = nanos; }

    public List<TagView> search(String keyword, int limit) {
        if (limit < 1 || limit > 20 || length(keyword) > 128) { throw ArticleErrors.invalid(); }
        String query = TagSnapshot.normalize(keyword);
        if (length(query) > 32) { throw ArticleErrors.invalid(); }
        var current = required();
        return query.isEmpty() ? List.of() : current.search(query, limit);
    }

    /** 调用者已完成格式、数量和去重；本地存在的标签不会再失效。 */
    public void requireAllExist(Collection<Long> ids) {
        if (!ids.isEmpty() && !ensure(ids).containsAll(ids)) { throw ArticleErrors.invalid(); }
    }

    /** 展示数据缺ID是数据错误，不可漏标签或伪装成请求非法。 */
    public Map<Long, TagView> resolve(Collection<Long> ids) {
        if (ids.isEmpty()) { return Map.of(); }
        var current = ensure(ids);
        if (!current.containsAll(ids)) {
            log.error("文章标签不在完整目录中 version={}", current.version());
            throw ArticleErrors.unavailable();
        }
        Map<Long, TagView> result = new HashMap<>();
        ids.forEach(id -> result.put(id, current.byId().get(id)));
        return Map.copyOf(result);
    }

    private TagSnapshot ensure(Collection<Long> ids) {
        var initial = required();
        if (initial.containsAll(ids)) { return initial; }
        long version = readVersion();
        if (version < initial.version()) { throw ArticleErrors.unavailable(); }
        var current = required();
        if (current.containsAll(ids) || current.version() >= version) { return current; }
        return refresh(version);
    }

    /** 启动和提交后尽力刷新，错误不覆盖已经提交的管理员操作。 */
    public void refreshBestEffort() {
        try { checkForUpdates(); }
        catch (RuntimeException error) { log.warn("标签目录刷新失败 error={}", error.getClass().getSimpleName()); }
    }
    public void checkForUpdates() {
        var initial = snapshot.get();
        if (initial == null) { refresh(1); return; }
        long version = readVersion();
        if (version < initial.version()) { throw ArticleErrors.unavailable(); }
        if (version > initial.version()) { refresh(version); }
    }
    private long readVersion() {
        try { return reader.version(); }
        catch (RuntimeException error) {
            log.warn("标签目录版本读取失败 error={}", error.getClass().getSimpleName());
            throw ArticleErrors.unavailable();
        }
    }
    private TagSnapshot refresh(long minimumVersion) {
        boolean locked = false;
        try {
            locked = refreshLock.tryLock(300, TimeUnit.MILLISECONDS);
            if (!locked) { throw ArticleErrors.unavailable(); }
            var current = snapshot.get();
            if (current != null && current.version() >= minimumVersion) { return current; }
            if (retryAfter != 0 && nanos.getAsLong() - retryAfter < 0) { throw ArticleErrors.unavailable(); }
            try {
                var incoming = TagSnapshot.from(reader.load());
                // 不足目标版本的快照不能判非法，迟到快照不能覆盖更新版本。
                if (incoming.version() < minimumVersion) { throw ArticleErrors.unavailable(); }
                snapshot.updateAndGet(old -> old == null || incoming.version() > old.version() ? incoming : old);
                retryAfter = 0;
                return required();
            } catch (RuntimeException error) {
                retryAfter = nanos.getAsLong() + TimeUnit.SECONDS.toNanos(1);
                log.warn("标签目录加载失败 error={}", error.getClass().getSimpleName());
                throw ArticleErrors.unavailable();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw ArticleErrors.unavailable();
        } finally { if (locked) { refreshLock.unlock(); } }
    }
    private TagSnapshot required() {
        var current = snapshot.get();
        if (current == null) { throw ArticleErrors.unavailable(); }
        return current;
    }
    private static int length(String value) { return value == null ? 0 : value.codePointCount(0, value.length()); }
}
