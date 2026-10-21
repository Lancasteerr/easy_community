package com.febrie.backend.auth;

import com.febrie.backend.config.AuthProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

/** Set是会话有效性的必要条件，普通请求只能查询或移除成员，不能重新登记。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ActiveSessions {
    private static final Logger log = LoggerFactory.getLogger(ActiveSessions.class);
    private final StringRedisTemplate redis;
    private final RedisAtomic atomic;
    private final AuthProperties properties;
    private final SessionRepository<? extends Session> repository;
    private final Clock clock;

    public ActiveSessions(StringRedisTemplate redis, RedisAtomic atomic, AuthProperties properties,
                          SessionRepository<? extends Session> repository, Clock clock) {
        this.redis = redis;
        this.atomic = atomic;
        this.properties = properties;
        this.repository = repository;
        this.clock = clock;
    }

    public String key(SessionPrincipal principal) {
        return properties.redisPrefix() + ":auth:user:" + principal.userId() + ":v:" + principal.authVersion() + ":sessions";
    }

    public void register(SessionPrincipal principal, String sessionId) {
        atomic.execute(operations -> {
            operations.opsForSet().add(key(principal), sessionId);
            operations.expire(key(principal), properties.sessionTtl());
        });
    }

    public boolean contains(SessionPrincipal principal, String sessionId) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(key(principal), sessionId));
    }

    public void remove(SessionPrincipal principal, String sessionId) {
        redis.opsForSet().remove(key(principal), sessionId);
    }

    /** 分批清理集合中的过期成员；清理失败不改变SQL版本校验的正确性。 */
    @Scheduled(fixedDelayString = "${app.auth.cleanup-delay}", initialDelayString = "${app.auth.cleanup-delay}")
    public void cleanup() {
        try (var keys = redis.scan(ScanOptions.scanOptions()
                .match(properties.redisPrefix() + ":auth:user:*:v:*:sessions").count(100).build())) {
            while (keys.hasNext()) {
                String key = keys.next();
                try (var members = redis.opsForSet().scan(key, ScanOptions.scanOptions().count(100).build())) {
                    while (members.hasNext()) {
                        String id = members.next();
                        Session session = repository.findById(id);
                        Long expiresAt = session == null ? null : session.getAttribute("auth.expiresAt");
                        if (expiresAt == null || expiresAt <= clock.millis()) {
                            redis.opsForSet().remove(key, id);
                        }
                    }
                }
            }
        } catch (RuntimeException error) {
            // 不记录Session ID或Redis连接凭证，下一周期继续回收。
            log.warn("活跃会话集合清理暂未完成：{}", error.getClass().getSimpleName());
        }
    }
}
