package com.febrie.backend.article.like.infrastructure;

import com.febrie.backend.article.like.application.*;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** 维护只运行于非Web模式，输出安全JSON结果；失败抛出并以非零状态退出。 */
@Component
public class LikeMaintenance implements ApplicationRunner {
    private final LikeProperties properties;
    private final ApplicationContext context;
    private final ObjectProvider<LikeVersionAllocator> allocator;
    private final ObjectProvider<LikeRecoveryService> recovery;
    private final ObjectProvider<LikeDeadLetterReplay> replay;
    private final LikeAuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public LikeMaintenance(LikeProperties properties, ApplicationContext context,
            ObjectProvider<LikeVersionAllocator> allocator, ObjectProvider<LikeRecoveryService> recovery,
            ObjectProvider<LikeDeadLetterReplay> replay, LikeAuditService audit, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.properties=properties; this.context=context; this.allocator=allocator; this.recovery=recovery;
        this.replay=replay; this.audit=audit; this.jdbc=jdbc; this.mapper=mapper;
    }
    @Override public void run(ApplicationArguments arguments) throws Exception {
        if (!properties.maintaining()) { return; }
        if (context instanceof WebApplicationContext || !properties.enabled() || properties.writeEnabled()) {
            throw new IllegalStateException("点赞维护必须启用模块、禁用写入口并使用非Web模式");
        }
        Object result = switch (properties.maintenance()) {
            case "status" -> Map.of("sqlEpoch", jdbc.queryForObject("SELECT epoch FROM like_version_epoch WHERE id=1", Integer.class),
                    "redis", allocator.getObject().status());
            case "pause" -> { allocator.getObject().pause(); yield Map.of("paused", true); }
            case "recover" -> Map.of("epoch", recovery.getObject().recover(properties.expectedEpoch()));
            case "replay" -> Map.of("replayed", replay.getObject().replay(properties.replayLimit()));
            case "audit" -> {
                var differences = audit.discrepancies();
                if (!differences.isEmpty()) { System.out.println(mapper.writeValueAsString(differences)); throw new IllegalStateException("点赞对账不一致，未执行修数"); }
                yield Map.of("consistent", true);
            }
            default -> throw new IllegalArgumentException("未知维护动作");
        };
        System.out.println(mapper.writeValueAsString(Map.of("action", properties.maintenance(), "result", result)));
    }
}
