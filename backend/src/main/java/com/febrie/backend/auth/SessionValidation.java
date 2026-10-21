package com.febrie.backend.auth;

import com.febrie.backend.common.ApiException;
import com.febrie.backend.user.AccountRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/** 只校验当前SQL状态和版本，匹配后继续使用Session中的角色快照。 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionValidation {
    private final AccountRepository accounts;
    private final ActiveSessions sessions;
    private final Clock clock;

    public SessionValidation(AccountRepository accounts, ActiveSessions sessions, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.clock = clock;
    }

    public void validate(SessionPrincipal principal, String sessionId) {
        if (!clock.instant().isBefore(principal.expiresAt())) { throw ApiException.unauthorized(); }
        var state = accounts.authState(principal.userId()).orElseThrow(ApiException::unauthorized);
        if (!"ACTIVE".equals(state.status()) || state.authVersion() != principal.authVersion()
                || !sessions.contains(principal, sessionId)) {
            throw ApiException.unauthorized();
        }
    }
}
