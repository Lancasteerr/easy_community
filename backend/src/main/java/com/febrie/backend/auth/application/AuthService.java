package com.febrie.backend.auth.application;

import com.febrie.backend.auth.infrastructure.ratelimit.RequestLimits;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.AccountPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/** 校验登录参数、检查失败次数并验证密码；HTTP 会话由接口层在认证成功后建立。 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final RequestLimits limits;

    public AuthService(AuthenticationManager authenticationManager, RequestLimits limits) {
        this.authenticationManager = authenticationManager;
        this.limits = limits;
    }

    /** 用户名去首尾空白并保留大小写，密码保持原样；仅认证失败增加对应账号的次数。 */
    public Authentication authenticate(String username, String password, String ip) {
        String trimmedUsername = AccountPolicy.username(username);
        limits.checkFailures(trimmedUsername, ip);
        try {
            return authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(trimmedUsername, password));
        } catch (AuthenticationException error) {
            limits.failed(trimmedUsername, ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
    }
}
