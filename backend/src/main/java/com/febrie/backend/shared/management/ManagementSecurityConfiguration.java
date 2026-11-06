package com.febrie.backend.shared.management;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** 指标仅允许本机管理端口GET读取；主业务端口同路径不可用，不创建状态修改入口。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ManagementSecurityConfiguration {
    @Bean @Order(0)
    SecurityFilterChain managementReadOnly(HttpSecurity http,Environment environment)throws Exception{
        http.securityMatcher("/actuator/**").authorizeHttpRequests(requests->requests.anyRequest().access((authentication,context)->{
            var request=context.getRequest();
            boolean local=false;
            try{local=java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();}catch(java.net.UnknownHostException ignored){ /* 无法识别来源时拒绝。 */ }
            boolean endpoint=request.getServletPath().equals("/actuator/health")||request.getServletPath().equals("/actuator/prometheus");
            return new AuthorizationDecision(local&&endpoint&&request.getMethod().equals("GET")
                    &&request.getLocalPort()==environment.getProperty("local.management.port",Integer.class,-1));
        }));
        return http.build();
    }
}
