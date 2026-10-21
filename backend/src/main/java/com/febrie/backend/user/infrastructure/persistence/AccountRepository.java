package com.febrie.backend.user.infrastructure.persistence;

import com.febrie.backend.user.application.dto.AccountAuthState;
import com.febrie.backend.user.application.dto.AccountSnapshot;
import com.febrie.backend.user.domain.Role;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 显式SQL保证认证快照和安全版本的事务边界，复用现有JDBC依赖。 */
@Repository
public class AccountRepository {
    private static final String SNAPSHOT = """
            SELECT u.id, u.username, u.role, u.status, u.auth_version, i.password_hash
            FROM users u JOIN user_auth_identity i ON i.user_id=u.id AND i.provider='LOCAL'
            """;
    private static final RowMapper<AccountSnapshot> MAPPER = (rs, row) -> new AccountSnapshot(
            rs.getLong("id"), rs.getString("username"), Role.valueOf(rs.getString("role")),
            rs.getString("status"), rs.getLong("auth_version"), rs.getString("password_hash"));
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<AccountSnapshot> findByUsername(String username) {
        return jdbc.query(SNAPSHOT + " WHERE u.username=?", MAPPER, username).stream().findFirst();
    }

    public Optional<AccountSnapshot> lockById(long userId) {
        return jdbc.query(SNAPSHOT + " WHERE u.id=? FOR UPDATE", MAPPER, userId).stream().findFirst();
    }

    /** 每次鉴权使用独立主库读取，不加载密码或重新读取角色。 */
    public Optional<AccountAuthState> authState(long userId) {
        return jdbc.query("SELECT status, auth_version FROM users WHERE id=?",
                (rs, row) -> new AccountAuthState(rs.getString(1), rs.getLong(2)), userId).stream().findFirst();
    }

    public long insert(String username, Role role, String hash) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO users(username,role) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, username);
            statement.setString(2, role.name());
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        jdbc.update("INSERT INTO user_auth_identity(user_id,provider,provider_uid,password_hash) VALUES (?,'LOCAL',?,?)",
                id, username, hash);
        return id;
    }

    public void updatePassword(long userId, String hash) {
        jdbc.update("UPDATE user_auth_identity SET password_hash=? WHERE user_id=? AND provider='LOCAL'", hash, userId);
        jdbc.update("UPDATE users SET auth_version=auth_version+1 WHERE id=?", userId);
    }

    public void softDelete(long userId) {
        jdbc.update("UPDATE users SET status='DELETED',deleted_at=CURRENT_TIMESTAMP(6),auth_version=auth_version+1 WHERE id=?", userId);
    }

    /** 未来内部角色调整也必须通过此版本递增路径，不对外开放修改接口。 */
    public void updateRole(long userId, Role role) {
        jdbc.update("UPDATE users SET role=?,auth_version=auth_version+1 WHERE id=?", role.name(), userId);
    }

    public boolean hasAdmin() {
        return !jdbc.queryForList("SELECT id FROM users WHERE role='ADMIN' LIMIT 1", Long.class).isEmpty();
    }

}
