package com.febrie.backend.article.like;

import com.febrie.backend.BackendApplication;
import com.febrie.backend.article.like.application.LikeSqlService;
import com.febrie.backend.article.like.infrastructure.LikeCodec;
import org.springframework.boot.SpringApplication;

/** 测试子进程：领取真实消息，SQL提交后立即硬退出，刻意不ACK或更新投影。 */
public final class LikeCrashProcess {
    private LikeCrashProcess() { }
    public static void main(String[] args) throws Exception {
        String url=System.getenv("IT_DB_URL"),vhost=System.getenv("IT_RABBITMQ_VHOST");
        if(url==null||!url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")
                ||vhost==null||!vhost.contains("test")||!args[0].startsWith("ec.test.")){
            throw new IllegalArgumentException("崩溃进程仅允许专用测试资源");
        }
        var context=SpringApplication.run(BackendApplication.class,"--spring.profiles.active=test",
                "--spring.main.web-application-type=none","--app.like.enabled=false","--app.auth.redis-prefix="+args[1]);
        var factory=new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(System.getenv().getOrDefault("IT_RABBITMQ_HOST","127.0.0.1"));
        factory.setPort(Integer.parseInt(System.getenv().getOrDefault("IT_RABBITMQ_PORT","5672")));
        factory.setUsername(System.getenv("IT_RABBITMQ_USERNAME"));factory.setPassword(System.getenv("IT_RABBITMQ_PASSWORD"));factory.setVirtualHost(vhost);
        var connection=factory.newConnection();var channel=connection.createChannel();
        var source=channel.basicGet(args[0],false);
        if(source==null){throw new IllegalStateException("测试消息未到达");}
        context.getBean(LikeSqlService.class).apply(context.getBean(LikeCodec.class).decode(source.getBody()));
        Runtime.getRuntime().halt(0);
    }
}
