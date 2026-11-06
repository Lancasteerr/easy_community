package com.febrie.backend.article.like;

import com.febrie.backend.BackendApplication;
import com.febrie.backend.article.content.application.dto.PreparedArticle;
import com.febrie.backend.article.content.infrastructure.persistence.ArticleRepository;
import com.febrie.backend.article.like.application.LikeWriteService;
import com.febrie.backend.article.like.application.maintenance.LikeRecoveryService;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.redis.LikeVersionAllocator;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 两个默认Web实例轮换重启，真实验证签发器连续及消费者自动启动；不执行容量实验。 */
@EnabledIfEnvironmentVariable(named="IT_LIKE",matches="true")
class LikeApplicationRestartTests {
    final String prefix="ec:test:restart:"+UUID.randomUUID();
    final List<ConfigurableApplicationContext> applications=new ArrayList<>();

    @Test void ordinaryRestartPreservesEpochSequenceAndDefaultConsumerAcrossTwoInstances() {
        String url=System.getenv("IT_DB_URL"),vhost=System.getenv("IT_RABBITMQ_VHOST");
        assertNotNull(url);assertTrue(url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?"));
        assertNotNull(vhost);assertTrue(vhost.contains("test"));
        // 先在无业务实例的非Web上下文初始化，符合人工恢复的停实例前提。
        int epoch;
        try(var maintenance=start(false)){
            assertTrue(maintenance.getBeansOfType(SimpleMessageListenerContainer.class).isEmpty());
            assertTrue(maintenance.getBeansOfType(Declarables.class).isEmpty());
            var jdbc=maintenance.getBean(JdbcTemplate.class);
            int before=jdbc.queryForObject("SELECT epoch FROM like_version_epoch WHERE id=1",Integer.class);
            try{epoch=maintenance.getBean(LikeRecoveryService.class).recover(before);}
            catch(java.sql.SQLException failure){throw new IllegalStateException(failure);}
        }
        long user=0,article=0;
        try{
            var first=start(true);var second=start(true);
            for(var app:List.of(first,second)){
                var listener=app.getBean(SimpleMessageListenerContainer.class);
                assertTrue(listener.isAutoStartup());assertTrue(listener.isRunning());
                assertEquals(Integer.toString(epoch),app.getBean(LikeVersionAllocator.class).status().get(0));
            }
            var transaction=second.getBean(TransactionTemplate.class);
            user=transaction.execute(s->second.getBean(AccountRepository.class).insert("lr_"+UUID.randomUUID().toString().substring(0,20),Role.USER,"unused"));
            long owner=user;
            article=transaction.execute(s->second.getBean(ArticleRepository.class).insert(owner,
                    new PreparedArticle("restart","restart","{}","<p>restart</p>",null,Set.of(),List.of()),Instant.now()));
            var one=first.getBean(LikeWriteService.class).set(user,article,true,UUID.randomUUID().toString());
            first.close();
            var two=second.getBean(LikeWriteService.class).set(user,article,false,UUID.randomUUID().toString());
            var allocator=second.getBean(LikeVersionAllocator.class);String sequence=allocator.status().get(1);
            var replacement=start(true);
            assertEquals(sequence,replacement.getBean(LikeVersionAllocator.class).status().get(1),"重启不能归零或消耗序号");
            assertEquals(epoch,second.getBean(JdbcTemplate.class).queryForObject("SELECT epoch FROM like_version_epoch WHERE id=1",Integer.class));
            var three=replacement.getBean(LikeWriteService.class).set(user,article,true,UUID.randomUUID().toString());
            assertTrue(Long.parseLong(two.version())>Long.parseLong(one.version()));
            assertTrue(Long.parseLong(three.version())>Long.parseLong(two.version()));
            long target=article;var jdbc=second.getBean(JdbcTemplate.class);
            await().atMost(Duration.ofSeconds(15)).until(()->jdbc.queryForObject("SELECT version FROM article_like_state WHERE user_id=? AND article_id=?",Long.class,owner,target)==Long.parseLong(three.version())
                    &&jdbc.queryForObject("SELECT like_count FROM article_like_count WHERE article_id=?",Long.class,target)==1L);
        }finally{
            // 只清本次随机队列与账号；所有监听停止后才删除关系，避免迟到消费重新插入。
            var active=applications.stream().filter(ConfigurableApplicationContext::isActive).toList();
            for(var app:active){app.getBeansOfType(SimpleMessageListenerContainer.class).values().forEach(SimpleMessageListenerContainer::stop);}
            if(!active.isEmpty()){
                var app=active.getLast();var jdbc=app.getBean(JdbcTemplate.class);var p=app.getBean(LikeProperties.class);
                var admin=app.getBean(AmqpAdmin.class);
                admin.deleteQueue(p.name("command.q"));admin.deleteQueue(p.name("dead.q"));
                admin.deleteExchange(p.name("command.x"));admin.deleteExchange(p.name("dead.x"));
                jdbc.update("DELETE FROM article_like_state WHERE article_id=?",article);
                jdbc.update("DELETE FROM article_like_count WHERE article_id=?",article);
                jdbc.update("DELETE FROM article WHERE id=?",article);
                jdbc.update("DELETE FROM user_auth_identity WHERE user_id=?",user);jdbc.update("DELETE FROM users WHERE id=?",user);
                var redis=app.getBean(StringRedisTemplate.class);
                try(var keys=redis.scan(ScanOptions.scanOptions().match(prefix+":*").count(100).build())){while(keys.hasNext()){redis.delete(keys.next());}}
            }
            for(var app:applications){app.close();}
        }
    }

    private ConfigurableApplicationContext start(boolean web) {
        var app=SpringApplication.run(BackendApplication.class,"--spring.profiles.active=test",
                "--spring.main.web-application-type="+(web?"servlet":"none"),"--server.port=0","--management.server.port=0",
                "--app.auth.redis-prefix="+prefix,"--app.auth.cleanup-delay=24h","--app.media.cleanup-delay=24h");
        applications.add(app);return app;
    }
}
