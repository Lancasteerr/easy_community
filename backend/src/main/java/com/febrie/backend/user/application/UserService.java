package com.febrie.backend.user.application;

import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.application.dto.UserView;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 用户安全变更集中在SQL事务中；Redis清理成功与否不影响版本撤销。 */
@Service
public class UserService {
    private final AccountRepository accounts;
    private final PasswordEncoder encoder;

    public UserService(AccountRepository accounts, PasswordEncoder encoder) { this.accounts = accounts; this.encoder = encoder; }

    @Transactional
    public UserView createManaged(UserActor actor, String username, String password, Role role) {
        requireAdmin(actor);
        Role selected = role == null ? Role.USER : role;
        if (selected == Role.ADMIN) { throw forbidden("本接口不能创建管理员"); }
        return create(username, password, selected);
    }

    /** SERIALIZABLE的范围锁使并发首次初始化最多一笔事务成功，死锁失败者不自动重试。 */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public UserView bootstrapAdmin(String username, String password) {
        if (accounts.hasAdmin()) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_ALREADY_INITIALIZED", "管理员已初始化");
        }
        return create(username, password, Role.ADMIN);
    }

    /** 管理员初始化与用户创建共享规则，入库及返回的用户名均保留大小写。 */
    private UserView create(String username, String password, Role role) {
        String trimmedUsername = AccountPolicy.username(username);
        AccountPolicy.password(password);
        try {
            long userId = accounts.insert(trimmedUsername, role, encoder.encode(password));
            return new UserView(Long.toString(userId), trimmedUsername, role);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已被使用");
        }
    }

    @Transactional
    public void changePassword(UserActor actor, String oldPassword, String newPassword) {
        AccountPolicy.password(newPassword);
        var account = accounts.lockById(actor.userId()).orElseThrow(ApiException::unauthorized);
        if (!"ACTIVE".equals(account.status()) || account.authVersion() != actor.authVersion()) {
            throw ApiException.unauthorized();
        }
        // 锁内检查预期版本和旧哈希，避免两个改密请求覆盖彼此的结果。
        if (oldPassword == null || oldPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
                || !encoder.matches(oldPassword, account.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OLD_PASSWORD_MISMATCH", "旧密码不正确");
        }
        if (encoder.matches(newPassword, account.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "新密码不能与旧密码相同");
        }
        accounts.updatePassword(actor.userId(), encoder.encode(newPassword));
    }

    @Transactional
    public void deleteManaged(UserActor actor, long userId) {
        requireAdmin(actor);
        var account = accounts.lockById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        if (account.role() == Role.ADMIN) { throw forbidden("本接口不能删除管理员"); }
        if (!"DELETED".equals(account.status())) { accounts.softDelete(userId); }
    }

    /** 仅供未来内部角色管理调用；本期不开放HTTP入口。 */
    @Transactional
    public void changeRole(long userId, Role role) {
        accounts.lockById(userId).filter(account -> "ACTIVE".equals(account.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        accounts.updateRole(userId, role);
    }

    private static void requireAdmin(UserActor principal) {
        if (principal == null || principal.role() != Role.ADMIN) { throw forbidden("仅管理员可执行此操作"); }
    }

    private static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "ADMIN_OPERATION_FORBIDDEN", message);
    }
}
