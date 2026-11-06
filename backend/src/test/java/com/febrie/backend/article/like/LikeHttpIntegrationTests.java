package com.febrie.backend.article.like;

import com.febrie.backend.article.content.application.dto.PreparedArticle;
import com.febrie.backend.article.content.infrastructure.persistence.ArticleRepository;
import com.febrie.backend.article.like.application.*;
import com.febrie.backend.article.like.domain.*;
import com.febrie.backend.article.like.infrastructure.*;
import com.febrie.backend.media.application.UploadStorage;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** 专用MySQL/Redis/vhost上的真实HTTP和MQ闭环，测试结束只删除本次随机资源。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="IT_LIKE",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LikeHttpIntegrationTests {
    static final String PREFIX="ec:test:likehttp:"+UUID.randomUUID();
    static final String PASSWORD="like-test-password";
    @Value("${local.server.port}") int port;
    @Value("${local.management.port}") int managementPort;
    @Autowired LikeProperties properties;
    @Autowired LikeVersionAllocator allocator;
    @MockitoSpyBean LikeRepository likes;
    @Autowired LikeSqlService sql;
    @Autowired LikeCodec codec;
    @Autowired LikeDeadLetterReplay replay;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ArticleRepository articles;
    @Autowired AccountRepository accounts;
    @Autowired TransactionTemplate transaction;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redis;
    @Autowired AmqpAdmin admin;
    @Autowired @Qualifier("likeRabbitTemplate") RabbitTemplate rabbit;
    @Autowired @Qualifier("likeListener") SimpleMessageListenerContainer listener;
    @MockitoBean UploadStorage storage;
    @MockitoSpyBean LikeCache cache;
    @MockitoSpyBean LikeReader reader;
    @MockitoSpyBean LikePublisher publisher;
    final List<Long> users=new ArrayList<>();
    final List<Browser> browsers=new ArrayList<>();
    Browser owner;
    long article;
    String hash;

    @DynamicPropertySource static void dedicated(DynamicPropertyRegistry registry){
        String url=System.getenv("IT_DB_URL"),vhost=System.getenv("IT_RABBITMQ_VHOST");
        if(url==null||!url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")||vhost==null||!vhost.contains("test")){
            throw new IllegalArgumentException("HTTP集成测试只允许专用数据库和测试vhost");
        }
        registry.add("spring.datasource.url",()->url);registry.add("app.auth.redis-prefix",()->PREFIX);
        registry.add("app.like.enabled",()->true);registry.add("app.like.write-enabled",()->true);
        registry.add("app.like.read-timeout",()->"2s");registry.add("app.like.queue-bytes",()->4096);
        registry.add("spring.rabbitmq.host",()->System.getenv().getOrDefault("IT_RABBITMQ_HOST","127.0.0.1"));
        registry.add("spring.rabbitmq.port",()->System.getenv().getOrDefault("IT_RABBITMQ_PORT","5672"));
        registry.add("spring.rabbitmq.username",()->System.getenv("IT_RABBITMQ_USERNAME"));
        registry.add("spring.rabbitmq.password",()->System.getenv("IT_RABBITMQ_PASSWORD"));
        registry.add("spring.rabbitmq.virtual-host",()->vhost);
    }

    @BeforeEach void seed()throws Exception{
        reset(cache,reader,publisher,likes);
        var maintenance=new LikeProperties(true,false,false,properties.namespace(),properties.confirmTimeout(),128,2,20,4096,60,4,Duration.ofSeconds(2),"none",-1,0);
        int epoch=jdbc.queryForObject("SELECT epoch FROM like_version_epoch WHERE id=1",Integer.class);
        new LikeRecoveryService(dataSource,allocator,maintenance).recover(epoch);
        if(hash==null){hash=encoder.encode(PASSWORD);}
        owner=login(Role.USER);
        article=transaction.execute(s->articles.insert(owner.user,new PreparedArticle("like-it","summary","{}","<p>like-it</p>",null,Set.of(),List.of()),Instant.now()));
        listener.start();
    }

    @AfterEach void clean(){
        listener.stop();reset(cache,reader,publisher,likes);
        // 队列属于本类随机命名空间；清理前先停消费者，防止测试数据删后又被旧命令插入。
        admin.purgeQueue(properties.name("command.q"),false);admin.purgeQueue(properties.name("dead.q"),false);
        jdbc.update("DELETE FROM article_like_state WHERE article_id=?",article);
        jdbc.update("DELETE FROM article_like_count WHERE article_id=?",article);
        jdbc.update("DELETE FROM article WHERE id=?",article);
        for(long user:users){jdbc.update("DELETE FROM user_auth_identity WHERE user_id=?",user);jdbc.update("DELETE FROM users WHERE id=?",user);}
        users.clear();browsers.forEach(Browser::close);browsers.clear();
    }
    @AfterAll void cleanupNamespace(){
        admin.deleteQueue(properties.name("command.q"));admin.deleteQueue(properties.name("dead.q"));
        admin.deleteExchange(properties.name("command.x"));admin.deleteExchange(properties.name("dead.x"));
        try(var keys=redis.scan(ScanOptions.scanOptions().match(PREFIX+":*").count(100).build())){while(keys.hasNext()){redis.delete(keys.next());}}
    }

    @Test void httpLikeUnlikeAndReusedOperationAreAcceptedAndViewsStayPrivate()throws Exception{
        String operation=UUID.randomUUID().toString();
        var first=owner.call("PUT",path(),null,true,operation);assertEquals(202,first.statusCode(),first.body());
        long v1=mapper.readTree(first.body()).path("version").asLong();
        var duplicateHttp=owner.call("PUT",path(),null,true,operation);assertEquals(202,duplicateHttp.statusCode(),duplicateHttp.body());
        long v2=mapper.readTree(duplicateHttp.body()).path("version").asLong();assertTrue(v2>v1);
        committed(v2,true,1);
        var mine=owner.call("GET","/api/articles/"+article,null,false,null);assertEquals(200,mine.statusCode(),mine.body());
        var body=mapper.readTree(mine.body());assertEquals("like-it",body.path("title").asString());
        assertFalse(body.has("article"));assertTrue(body.path("likedByMe").asBoolean());assertEquals(1,body.path("likeCount").asLong());
        assertEquals("no-store",mine.headers().firstValue("Cache-Control").orElseThrow());
        var page=mapper.readTree(owner.call("GET","/api/users/me/articles",null,false,null).body());
        assertTrue(page.path("items").get(0).path("likedByMe").asBoolean());
        var visitor=browser();var publicView=mapper.readTree(visitor.call("GET","/api/articles/"+article,null,false,null).body());
        assertFalse(publicView.path("likedByMe").asBoolean());assertEquals(1,publicView.path("likeCount").asLong());
        var cancelled=owner.call("DELETE",path(),null,true,UUID.randomUUID().toString());assertEquals(202,cancelled.statusCode());
        committed(mapper.readTree(cancelled.body()).path("version").asLong(),false,0);
    }

    @Test void authenticationCsrfValidationPauseHiddenAndRateLimitAreEnforced()throws Exception{
        var visitor=browser();visitor.csrf();
        assertEquals(401,visitor.call("PUT",path(),null,true,UUID.randomUUID().toString()).statusCode());
        assertEquals(403,owner.call("PUT",path(),null,false,UUID.randomUUID().toString()).statusCode());
        assertEquals(400,owner.call("PUT",path(),null,true,"bad").statusCode());
        assertEquals(400,owner.call("PUT",path(),null,true,null).statusCode());
        allocator.pause();
        var paused=owner.call("PUT",path(),null,true,UUID.randomUUID().toString());
        assertEquals("LIKE_ALLOCATOR_UNAVAILABLE",mapper.readTree(paused.body()).path("code").asString());
        allocator.activate(jdbc.queryForObject("SELECT epoch FROM like_version_epoch WHERE id=1",Integer.class),allocator.recoveryRuntime());
        String limit=properties.key("limit:"+owner.user+":"+(Instant.now().getEpochSecond()/60));
        redis.opsForValue().set(limit,"999",Duration.ofMinutes(2));
        var limited=owner.call("PUT",path(),null,true,UUID.randomUUID().toString());assertEquals(429,limited.statusCode());assertTrue(limited.headers().firstValue("Retry-After").isPresent());
        redis.delete(limit);jdbc.update("UPDATE article SET hidden=TRUE WHERE id=?",article);
        assertEquals(404,owner.call("PUT",path(),null,true,UUID.randomUUID().toString()).statusCode());
    }

    @Test void allActiveRolesCanLikeAndCacheFailuresPreserve202OrUseSql()throws Exception{
        for(Role role:List.of(Role.ADMIN,Role.PATROLLER)){
            Browser actor=login(role);var response=actor.call("PUT",path(),null,true,UUID.randomUUID().toString());
            assertEquals(202,response.statusCode(),response.body());
            await().atMost(Duration.ofSeconds(10)).until(()->likes.states(actor.user,List.of(article)).containsKey(article));
        }
        doThrow(new IllegalStateException("test")).when(cache).pending(any());
        var accepted=owner.call("PUT",path(),null,true,UUID.randomUUID().toString());assertEquals(202,accepted.statusCode());
        committed(mapper.readTree(accepted.body()).path("version").asLong(),true,3);
        doThrow(new IllegalStateException("test")).when(cache).counts(any());
        doThrow(new IllegalStateException("test")).when(cache).states(anyLong(),any());
        assertEquals(200,owner.call("GET","/api/articles/"+article,null,false,null).statusCode());
        doThrow(LikeErrors.unavailable()).when(reader).counts(any());
        assertEquals(503,owner.call("GET","/api/articles/"+article,null,false,null).statusCode());
    }

    @Test void hideAfterExistenceCheckAndDeletionAfterAcceptanceDoNotRevokeCommand()throws Exception{
        listener.stop();
        var checked=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{
            boolean visible=(boolean)call.callRealMethod();checked.countDown();
            assertTrue(release.await(5,TimeUnit.SECONDS));return visible;
        }).when(likes).publicArticle(article);
        try(var requests=Executors.newSingleThreadExecutor()){
            var response=requests.submit(()->owner.call("PUT",path(),null,true,UUID.randomUUID().toString()));
            try{
                assertTrue(checked.await(5,TimeUnit.SECONDS));
                // 写侧存在性检查已完成，隐藏不持有也不等待点赞事务中的article行锁。
                jdbc.update("UPDATE article SET hidden=TRUE WHERE id=?",article);
            }finally{release.countDown();}
            var accepted=response.get(10,TimeUnit.SECONDS);assertEquals(202,accepted.statusCode(),accepted.body());
            jdbc.update("UPDATE users SET status='DELETED',auth_version=auth_version+1 WHERE id=?",owner.user);
            listener.start();committed(mapper.readTree(accepted.body()).path("version").asLong(),true,1);
            assertEquals(404,browser().call("GET","/api/articles/"+article,null,false,null).statusCode());
        }
    }

    @Test void lostAckRedeliversAfterSqlCommitWithoutDoubleCount()throws Exception{
        listener.stop();var command=command(true);publisher.publish(command);
        var nativeFactory=new com.rabbitmq.client.ConnectionFactory();
        nativeFactory.setHost(System.getenv().getOrDefault("IT_RABBITMQ_HOST","127.0.0.1"));
        nativeFactory.setPort(Integer.parseInt(System.getenv().getOrDefault("IT_RABBITMQ_PORT","5672")));
        nativeFactory.setUsername(System.getenv("IT_RABBITMQ_USERNAME"));nativeFactory.setPassword(System.getenv("IT_RABBITMQ_PASSWORD"));nativeFactory.setVirtualHost(System.getenv("IT_RABBITMQ_VHOST"));
        try(var connection=nativeFactory.newConnection()){
            var channel=connection.createChannel();
            var delivery=channel.basicGet(properties.name("command.q"),false);assertNotNull(delivery);
            sql.apply(codec.decode(delivery.getBody()));
            // 真实关闭连接而不ACK，Broker必须重新投递已经提交的同一版本。
            channel.abort();
        }
        listener.start();committed(command.number(),true,1);
        await().atMost(Duration.ofSeconds(10)).until(()->admin.getQueueInfo(properties.name("command.q")).getMessageCount()==0);
        assertEquals(1L,likes.counts(List.of(article)).get(article));
    }

    @Test void realDatabaseConnectionLossRollsBackAndRetriesWholeCommand()throws Exception{
        var killOnce=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call->{
            if(killOnce.getAndSet(false)){
                long connectionId=jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
                // 只杀当前测试消费事务的连接，不停止数据库或其他测试会话。
                try(var killer=dataSource.getConnection();var statement=killer.createStatement()){
                    assertTrue(killer.getCatalog().contains("test"));
                    statement.execute("KILL CONNECTION "+connectionId);
                }
            }
            return call.callRealMethod();
        }).when(likes).addDelta(eq(article),anyInt());
        var command=command(true);publisher.publish(command);committed(command.number(),true,1);
        verify(likes,atLeast(2)).addDelta(article,1);
    }

    @Test @EnabledIfEnvironmentVariable(named="IT_LIKE_FAULTS",matches="true")
    void hardProcessExitAfterCommitRedeliversWithoutDoubleCounting()throws Exception{
        listener.stop();var command=command(true);publisher.publish(command);
        String executable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        List<String> args=new ArrayList<>(List.of(executable));
        String temporary=System.getProperty("jdk.net.unixdomain.tmpdir");if(temporary!=null){args.add("-Djdk.net.unixdomain.tmpdir="+temporary);}
        args.addAll(List.of("-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),LikeCrashProcess.class.getName(),properties.name("command.q"),PREFIX));
        var output=java.nio.file.Path.of("target","like-crash-child.log").toFile();
        var process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output).start();
        try{assertTrue(process.waitFor(40,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),"检查like-crash-child.log");}
        finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
        assertEquals(command.number(),likes.states(owner.user,List.of(article)).get(article).version());
        listener.start();committed(command.number(),true,1);
    }

    @Test @EnabledIfEnvironmentVariable(named="IT_LIKE_FAULTS",matches="true")
    void brokerRestartPreservesConfirmedUnconsumedCommands()throws Exception{
        assertEquals("17772",System.getenv("IT_RABBITMQ_PORT"));listener.stop();
        var command=command(true);publisher.publish(command);
        LikeTestContainers.restart("easy-community-like-it-rabbitmq-1");
        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).until(()->admin.getQueueInfo(properties.name("command.q")).getMessageCount()==1);
        listener.start();committed(command.number(),true,1);
    }

    @Test void deadLetterMissingOrFullRetainsMessagesAndReplayFailureKeepsOriginal()throws Exception{
        admin.deleteQueue(properties.name("dead.q"));
        publisher.publish(raw("invalid-one"));
        var parked=new Queue(properties.name("dead.q"),true,false,false,Map.of("x-queue-type","quorum","x-quorum-initial-group-size",1,"x-overflow","reject-publish","x-max-length-bytes",4096L,"x-delivery-limit",-1));
        admin.declareQueue(parked);admin.declareBinding(BindingBuilder.bind(parked).to(new DirectExchange(properties.name("dead.x"))).with("article.like.dead"));
        await().atMost(Duration.ofSeconds(65)).until(()->admin.getQueueInfo(properties.name("dead.q")).getMessageCount()==1);
        assertEquals("invalid-one",new String(Objects.requireNonNull(rabbit.receive(properties.name("dead.q"))).getBody(),java.nio.charset.StandardCharsets.UTF_8));
        publisher.publish(raw("x".repeat(4096)));
        await().atMost(Duration.ofSeconds(15)).until(()->admin.getQueueInfo(properties.name("dead.q")).getMessageCount()==1);
        publisher.publish(raw("second"));
        assertEquals(4096,Objects.requireNonNull(rabbit.receive(properties.name("dead.q"))).getBody().length);
        await().atMost(Duration.ofSeconds(65)).until(()->admin.getQueueInfo(properties.name("dead.q")).getMessageCount()==1);
        assertEquals("second",new String(Objects.requireNonNull(rabbit.receive(properties.name("dead.q"))).getBody(),java.nio.charset.StandardCharsets.UTF_8));
        var command=command(true);var original=new Message(codec.encode(command),raw("").getMessageProperties());
        var correlation=new CorrelationData();rabbit.send(properties.name("dead.x"),"article.like.dead",original,correlation);
        assertTrue(correlation.getFuture().get(3,TimeUnit.SECONDS).ack());
        doThrow(LikeErrors.unknown()).when(publisher).publish(any(Message.class));
        assertThrows(RuntimeException.class,()->replay.replay(1));
        await().atMost(Duration.ofSeconds(5)).until(()->admin.getQueueInfo(properties.name("dead.q")).getMessageCount()==1);
        reset(publisher);assertEquals(1,replay.replay(1));committed(command.number(),true,1);
    }

    @Test void returnedMessagesAreNotAcceptedAndMetricsStayOnManagementPort()throws Exception{
        var binding=new Binding(properties.name("command.q"),Binding.DestinationType.QUEUE,properties.name("command.x"),"article.like.set",Map.of());
        admin.removeBinding(binding);
        try{
            var response=owner.call("PUT",path(),null,true,UUID.randomUUID().toString());
            assertEquals(503,response.statusCode());assertEquals("LIKE_PUBLISH_REJECTED",mapper.readTree(response.body()).path("code").asString());
        }finally{admin.declareBinding(binding);}
        try(var http=HttpClient.newHttpClient()){
            var local=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+managementPort+"/actuator/prometheus")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,local.statusCode(),local.body());assertTrue(local.body().contains("article_like"));
            assertNotEquals(200,owner.call("GET","/actuator/prometheus",null,false,null).statusCode());
        }
    }

    LikeCommand command(boolean liked){return new LikeCommand(1,UUID.randomUUID().toString(),owner.user,article,liked,Long.toString(allocator.next()),Instant.now());}
    Message raw(String body){var metadata=new MessageProperties();metadata.setDeliveryMode(MessageDeliveryMode.PERSISTENT);return new Message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),metadata);}
    String path(){return "/api/articles/"+article+"/like";}
    void committed(long version,boolean liked,long count){
        await().atMost(Duration.ofSeconds(15)).until(()->{
            var state=likes.states(owner.user,List.of(article)).get(article);
            return state!=null&&state.version()==version&&state.liked()==liked&&Objects.equals(count,likes.counts(List.of(article)).get(article));
        });
    }
    Browser login(Role role)throws Exception{
        String name="lh_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        long user=transaction.execute(s->accounts.insert(name,role,hash));users.add(user);
        Browser browser=browser();browser.user=user;browser.csrf();
        var login=browser.call("POST","/api/auth/login",Map.of("username",name,"password",PASSWORD),true,null);
        assertEquals(200,login.statusCode(),login.body());browser.csrf();return browser;
    }
    Browser browser(){var browser=new Browser();browsers.add(browser);return browser;}
    class Browser implements AutoCloseable{
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
        String csrf;long user;
        void csrf()throws Exception{csrf=mapper.readTree(call("GET","/api/auth/csrf",null,false,null).body()).path("token").asString();}
        HttpResponse<String> call(String method,String path,Object body,boolean token,String operation)throws Exception{
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
            if(token){request.header("X-CSRF-TOKEN",csrf);}if(operation!=null){request.header("X-Operation-Id",operation);}
            if(body!=null){request.header("Content-Type","application/json");}
            return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        public void close(){http.close();}
    }
}
