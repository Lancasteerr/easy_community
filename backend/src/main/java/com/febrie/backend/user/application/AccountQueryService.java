package com.febrie.backend.user.application;

import com.febrie.backend.user.application.dto.AccountAuthState;
import com.febrie.backend.user.application.dto.AccountSnapshot;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 提供认证所需的账户读取入口，让其他业务无需直接调用用户数据库组件。 */
@Service
public class AccountQueryService {
    private final AccountRepository accounts;

    public AccountQueryService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** 单次读取密码、角色和版本的完整快照，仅供内部认证，不得作为 HTTP 响应。 */
    public Optional<AccountSnapshot> findByUsername(String username) {
        return accounts.findByUsername(username);
    }

    /** 每次读取当前账户状态，不缓存结果，也不重新获取角色。 */
    public Optional<AccountAuthState> authState(long userId) {
        return accounts.authState(userId);
    }
}
