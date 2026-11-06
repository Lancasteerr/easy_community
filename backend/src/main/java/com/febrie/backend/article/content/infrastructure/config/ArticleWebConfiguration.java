package com.febrie.backend.article.content.infrastructure.config;

import com.febrie.backend.article.content.web.ArticleRequestFilter;
import com.febrie.backend.shared.web.ProblemResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.session.web.http.SessionRepositoryFilter;

/** 在会话/安全链前设置文章no-store并限制请求体，不改变现有Session和CSRF协议。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ArticleWebConfiguration {
    @Bean FilterRegistrationBean<ArticleRequestFilter> articleRequestFilter(ProblemResponses problems) {
        var registration = new FilterRegistrationBean<>(new ArticleRequestFilter(problems));
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 2);
        return registration;
    }
}
