package com.febrie.backend.user.application;

import com.febrie.backend.user.application.dto.UserBasicData;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 独立代理建立新的主库短读取事务；调用方不得携带外层快照。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
public class UserBasicReader {
    private final AccountRepository accounts;
    public UserBasicReader(AccountRepository accounts) { this.accounts = accounts; }
    public Map<Long, UserBasicData> load(Collection<Long> ids) { return accounts.basics(ids); }
}
