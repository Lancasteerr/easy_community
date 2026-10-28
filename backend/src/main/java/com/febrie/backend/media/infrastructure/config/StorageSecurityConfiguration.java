package com.febrie.backend.media.infrastructure.config;

import com.febrie.backend.media.web.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;

/** 存储通知先匹配，拒绝加载Cookie中的安全上下文或访问社区会话。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StorageSecurityConfiguration {
    @Bean @Order(0)
    SecurityFilterChain storageNotifications(HttpSecurity http, StorageNotificationHandler handler, MediaWorkflowProperties flow) throws Exception {
        http.securityMatcher("/api/storage/**")
                .securityContext(config -> config.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(config -> config.disable()).requestCache(config -> config.disable())
                .formLogin(config -> config.disable()).httpBasic(config -> config.disable()).logout(config -> config.disable())
                .authorizeHttpRequests(config -> config.anyRequest().denyAll());
        http.addFilterBefore(new StorageNotificationFilter(handler, flow), SecurityContextHolderFilter.class);
        return http.build();
    }
}
