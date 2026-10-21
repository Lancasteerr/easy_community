package com.febrie.backend.config;

import com.febrie.backend.auth.LocalAuthenticationProvider;
import com.febrie.backend.auth.RequestLimits;
import com.febrie.backend.auth.SecurityRequestFilter;
import com.febrie.backend.auth.SessionService;
import com.febrie.backend.auth.SessionValidation;
import com.febrie.backend.common.DependencyBoundaryFilter;
import com.febrie.backend.common.ApiException;
import com.febrie.backend.common.ProblemResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** Cookie会话必须启用CSRF，包括匿名可访问的登录接口。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    AuthenticationManager authenticationManager(LocalAuthenticationProvider provider) { return new ProviderManager(provider); }

    @Bean
    HttpSessionSecurityContextRepository securityContexts() { return new HttpSessionSecurityContextRepository(); }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokens() {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setSessionAttributeName("auth.csrfToken");
        return repository;
    }

    @Bean
    DefaultCookieSerializer cookieSerializer(AuthProperties properties) {
        var serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(properties.secureCookie());
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    FilterRegistrationBean<DependencyBoundaryFilter> dependencyBoundary(ProblemResponses problems) {
        var registration = new FilterRegistrationBean<>(new DependencyBoundaryFilter(problems));
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, HttpSessionSecurityContextRepository contexts,
            HttpSessionCsrfTokenRepository csrf, ProblemResponses problems, RequestLimits limits,
            SessionValidation validation, SessionService sessions, AuthProperties properties) throws Exception {
        http.addFilterBefore(new SecurityRequestFilter(limits, validation, sessions, properties), CsrfFilter.class);
        http.securityContext(config -> config.securityContextRepository(contexts).requireExplicitSave(true))
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(config -> config.csrfTokenRepository(csrf))
                .formLogin(config -> config.disable()).httpBasic(config -> config.disable())
                .logout(config -> config.disable()).requestCache(config -> config.disable())
                .authorizeHttpRequests(config -> config
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated().anyRequest().denyAll())
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, error) -> problems.write(response, ApiException.unauthorized()))
                        .accessDeniedHandler((request, response, error) -> problems.write(response,
                                new ApiException(HttpStatus.FORBIDDEN, "ACCESS_OR_CSRF_DENIED", "权限不足或CSRF令牌无效"))));
        return http.build();
    }
}
