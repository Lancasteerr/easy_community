package com.febrie.backend.user.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.febrie.backend.media.domain.MediaIds;
import com.febrie.backend.user.application.dto.AccountAuthState;
import com.febrie.backend.user.application.dto.AccountSnapshot;
import com.febrie.backend.user.application.dto.UserProfileSnapshot;
import com.febrie.backend.user.application.dto.UserBasicData;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.entity.AccountRow;
import com.febrie.backend.user.infrastructure.persistence.entity.AuthIdentityEntity;
import com.febrie.backend.user.infrastructure.persistence.entity.UserEntity;
import com.febrie.backend.user.infrastructure.persistence.mapper.AuthIdentityMapper;
import com.febrie.backend.user.infrastructure.persistence.mapper.UserMapper;
import java.util.Optional;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/** 封装数据库映射；事务继续由应用服务管理，业务层不接触 Mapper。 */
@Repository
public class AccountRepository {
    private final UserMapper users;
    private final AuthIdentityMapper identities;

    public AccountRepository(UserMapper users, AuthIdentityMapper identities) {
        this.users = users;
        this.identities = identities;
    }

    public Optional<AccountSnapshot> findByUsername(String username) {
        return Optional.ofNullable(users.findAccountByUsername(username)).map(AccountRepository::snapshot);
    }

    /** 历史文章仍保留软删除账号的署名，不能以ACTIVE筛选代替作者存在性。 */
    public Map<Long, UserBasicData> basics(Collection<Long> ids) {
        if (ids.isEmpty()) { return Map.of(); }
        return users.selectList(Wrappers.<UserEntity>lambdaQuery().select(UserEntity::getId, UserEntity::getUsername, UserEntity::getAvatarMediaId, UserEntity::getBio)
                .in(UserEntity::getId, ids)).stream().collect(Collectors.toMap(UserEntity::getId,
                        row -> new UserBasicData(row.getId(), row.getUsername(), MediaIds.text(row.getAvatarMediaId()), row.getBio())));
    }

    public Optional<AccountSnapshot> lockById(long userId) {
        return Optional.ofNullable(users.lockAccountById(userId)).map(AccountRepository::snapshot);
    }

    /** 资料不读取凭证表；锁内取得旧头像，避免并发替换使用过期 ID。 */
    public Optional<UserProfileSnapshot> profile(long userId, boolean lock) {
        UserEntity user = lock ? users.lockProfile(userId) : users.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .select(UserEntity::getId, UserEntity::getUsername, UserEntity::getRole,
                        UserEntity::getStatus, UserEntity::getAuthVersion, UserEntity::getAvatarMediaId, UserEntity::getBio)
                .eq(UserEntity::getId, userId));
        return Optional.ofNullable(user).map(row -> new UserProfileSnapshot(row.getId(), row.getUsername(),
                row.getRole(), row.getStatus(), row.getAuthVersion(), MediaIds.text(row.getAvatarMediaId()), row.getBio()));
    }

    /** 调用者持有用户行锁，只更新简介，避免覆盖并发提交的其他资料。 */
    public void updateBio(long userId, String bio) {
        users.update(null, Wrappers.<UserEntity>lambdaUpdate().eq(UserEntity::getId, userId)
                .set(UserEntity::getBio, bio));
    }

    public void updateAvatar(long userId, String mediaId) {
        // 显式 set 会写入 NULL，不能使用默认忽略空字段的 updateById。
        users.update(null, Wrappers.<UserEntity>lambdaUpdate().eq(UserEntity::getId, userId)
                .set(UserEntity::getAvatarMediaId, mediaId == null ? null : MediaIds.bytes(mediaId)));
    }

    /** 每次鉴权读取主库当前状态和版本，不加载密码或角色。 */
    public Optional<AccountAuthState> authState(long userId) {
        return Optional.ofNullable(users.selectOne(Wrappers.<UserEntity>lambdaQuery()
                        .select(UserEntity::getStatus, UserEntity::getAuthVersion).eq(UserEntity::getId, userId)))
                .map(row -> new AccountAuthState(row.getStatus(), row.getAuthVersion()));
    }

    /** 调用方提供事务：用户 ID 回填后再创建身份，任一步失败均回滚。 */
    public long insert(String username, Role role, String hash) {
        var user = new UserEntity();
        user.setUsername(username);
        user.setRole(role);
        users.insert(user);

        var identity = new AuthIdentityEntity();
        identity.setUserId(user.getId());
        identity.setProvider("LOCAL");
        identity.setProviderUid(username);
        identity.setPasswordHash(hash);
        identities.insert(identity);
        return user.getId();
    }

    public void updatePassword(long userId, String hash) {
        identities.update(null, Wrappers.<AuthIdentityEntity>lambdaUpdate()
                .eq(AuthIdentityEntity::getUserId, userId).eq(AuthIdentityEntity::getProvider, "LOCAL")
                .set(AuthIdentityEntity::getPasswordHash, hash));
        users.incrementAuthVersion(userId);
    }

    public void softDelete(long userId) { users.softDelete(userId); }

    /** 角色调整必须同时递增认证版本，不对外提供通用角色更新入口。 */
    public void updateRole(long userId, Role role) { users.updateRole(userId, role); }

    public boolean hasAdmin() {
        return users.exists(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getRole, Role.ADMIN));
    }

    private static AccountSnapshot snapshot(AccountRow row) {
        return new AccountSnapshot(row.getId(), row.getUsername(), row.getRole(), row.getStatus(),
                row.getAuthVersion(), row.getPasswordHash());
    }
}
