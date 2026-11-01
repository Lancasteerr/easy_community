package com.febrie.backend.user.application;

import com.febrie.backend.user.application.dto.AccountAuthState;
import com.febrie.backend.user.application.dto.AccountSnapshot;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.util.Optional;
import java.util.Collection;
import java.util.Map;
import com.febrie.backend.user.application.dto.UserDisplayView;
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

    /** 加入调用者当前读取事务，供文章在一致快照内批量组装署名。 */
    public Map<Long, UserDisplayView> displays(Collection<Long> ids) { return accounts.displays(ids); }
}
