package com.febrie.backend.auth;

import com.febrie.backend.config.AuthProperties;
import com.febrie.backend.user.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 密码、角色和版本取自同一SQL快照，后续创建Session不可重新读取版本。 */
@Component
public class LocalAuthenticationProvider implements AuthenticationProvider {
    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final AuthProperties properties;
    private final String dummyHash;

    public LocalAuthenticationProvider(AccountRepository accounts, PasswordEncoder encoder,
                                       Clock clock, AuthProperties properties) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.clock = clock;
        this.properties = properties;
        this.dummyHash = encoder.encode("dummy-credential-for-timing");
    }

    @Override
    public Authentication authenticate(Authentication request) {
        var snapshot = accounts.findByUsername(request.getName()).orElse(null);
        String password = request.getCredentials() instanceof String value ? value : "";
        boolean validLength = password.getBytes(StandardCharsets.UTF_8).length <= 72;
        // 未知用户名也执行一次哈希匹配，避免直接暴露账号存在性。
        boolean matched = encoder.matches(validLength ? password : "invalid-too-long",
                snapshot == null ? dummyHash : snapshot.passwordHash());
        if (!validLength || !matched || snapshot == null || !"ACTIVE".equals(snapshot.status())) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        var now = clock.instant();
        var principal = new SessionPrincipal(snapshot.userId(), snapshot.username(), snapshot.role(),
                snapshot.authVersion(), now, now.plus(properties.sessionTtl()));
        return UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
    }

    @Override
    public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
}
