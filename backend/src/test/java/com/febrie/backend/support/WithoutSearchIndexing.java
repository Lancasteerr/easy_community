package com.febrie.backend.support;

import java.lang.annotation.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 非检索专项不领取共享测试库的Outbox，避免事件被另一个随机索引命名空间接走。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MockitoBean(name = "articleIndexListener", types = SimpleMessageListenerContainer.class)
@MockitoBean(types = com.febrie.backend.article.search.infrastructure.scheduling.IndexRelay.class)
@TestPropertySource(properties = "app.search.rank-scheduling-enabled=false")
public @interface WithoutSearchIndexing { }
