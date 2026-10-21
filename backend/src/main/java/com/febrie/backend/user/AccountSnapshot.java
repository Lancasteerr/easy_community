package com.febrie.backend.user;

/** 单条JOIN查询获得的一致快照；仅供SQL认证流程使用，不可序列化进Session。 */
public record AccountSnapshot(long userId, String username, Role role, String status,
                              long authVersion, String passwordHash) {
    @Override
    public String toString() {
        return "AccountSnapshot[userId=" + userId + ", credentials=REDACTED]";
    }
}
