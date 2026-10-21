package com.febrie.backend.auth.infrastructure.scheduling;

import com.febrie.backend.auth.infrastructure.session.ActiveSessions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按配置周期触发过期会话集合清理，具体扫描与失败处理交给会话组件。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionCleanupTask {
    private final ActiveSessions sessions;

    public SessionCleanupTask(ActiveSessions sessions) {
        this.sessions = sessions;
    }

    /** 延续原来的启动延迟和执行间隔，避免启动时立即扫描。 */
    @Scheduled(fixedDelayString = "${app.auth.cleanup-delay}", initialDelayString = "${app.auth.cleanup-delay}")
    public void cleanup() {
        sessions.cleanup();
    }
}
