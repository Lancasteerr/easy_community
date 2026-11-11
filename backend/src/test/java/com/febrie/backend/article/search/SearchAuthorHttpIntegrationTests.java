package com.febrie.backend.article.search;

import com.febrie.backend.article.content.application.ArticleService;
import com.febrie.backend.article.content.application.dto.SaveArticleCommand;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleIndexStore;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import com.febrie.backend.article.tag.infrastructure.persistence.TagRepository;
import com.febrie.backend.article.view.application.ArticleViewService;
import com.febrie.backend.media.application.UploadStorage;
import com.febrie.backend.support.PersistenceTestConfiguration;
import com.febrie.backend.support.persistence.*;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实Session与四服务验证公开作者搜索、本人分页和个人展示字段。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PersistenceTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "IT_SEARCH", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchAuthorHttpIntegrationTests {
    private static final String PREFIX = "ec:test:searchauthor:" + UUID.randomUUID();
    private static SearchEsFixture fixture;
    private static final String PASSWORD = "search-author-test-password";
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired TransactionTemplate transaction;
    @Autowired ArticleService articles;
    @Autowired ArticleIndexStore index;
    @Autowired TagRepository tags;
    @Autowired ArticleFixtureMapper articleFixtures;
    @Autowired DatabaseFixtureMapper fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired SearchProperties properties;
    @Autowired ConnectionFactory connectionFactory;
    @Autowired com.febrie.backend.article.like.infrastructure.config.LikeProperties likes;
    @Autowired @Qualifier("articleIndexListener") SimpleMessageListenerContainer indexListener;
    @Autowired @Qualifier("likeListener") SimpleMessageListenerContainer likeListener;
    @Autowired @Qualifier("articleIndexRelayScheduler") ThreadPoolTaskScheduler relay;
    @MockitoSpyBean ArticleViewService views;
    @MockitoBean UploadStorage storage;
    private final List<Long> owners = new ArrayList<>(), tagIds = new ArrayList<>();
    private final List<SearchHttpSession> sessions = new ArrayList<>();
    private UserActor owner, other;
    private SearchHttpSession mine, theirs, guest;
    private long first, second, third;

    @DynamicPropertySource static void dedicated(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("IT_DB_URL");
        if (url == null || !url.contains("/easy_community_search_test?")) { throw new IllegalArgumentException("仅允许检索专用测试库"); }
        fixture = new SearchEsFixture();
        registry.add("app.auth.redis-prefix", () -> PREFIX);
        registry.add("app.search.uri", () -> fixture.p.uri().toString());
        registry.add("app.search.username", () -> fixture.p.username());
        registry.add("app.search.password", () -> fixture.p.password());
        registry.add("app.search.index-name", () -> fixture.index);
        registry.add("app.search.read-alias", () -> fixture.p.readAlias());
        registry.add("app.search.write-alias", () -> fixture.p.writeAlias());
        registry.add("app.search.query-enabled", () -> true);
        registry.add("app.search.cursor-secret", () -> "author-test-shared-secret-32-bytes-1234");
        registry.add("app.search.rank-scheduling-enabled", () -> false);
    }

    @BeforeAll void seed() throws Exception {
        mine = session(); theirs = session(); guest = session();
        owner = user(mine); other = user(theirs);
        tagIds.add(tags.insert("sa_" + UUID.randomUUID().toString().substring(0, 20)));
        tagIds.add(tags.insert("sb_" + UUID.randomUUID().toString().substring(0, 20)));
        first = create(owner, "Java 缓存一", tagIds.get(0));
        second = create(owner, "Java 缓存二", tagIds.get(1));
        third = create(owner, "Java 其他", tagIds.get(0));
        long hidden = create(owner, "Java 缓存隐藏", tagIds.get(0));
        create(other, "Java 缓存另一作者", tagIds.get(0));
        articles.hide(owner, hidden);
        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).until(() -> index.metaVersion(hidden) >= 2);
        fixture.es.indices().refresh(r -> r.index(properties.readAlias()));
        // 本类验证读取个人状态；点赞写入与消费继续由独立点赞HTTP套件覆盖。
        transaction.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO article_like_state(user_id,article_id,liked,version) VALUES (?,?,1,1)", owner.userId(), first);
            jdbc.update("UPDATE article_like_count SET like_count=1 WHERE article_id=?", first);
        });
    }

    @Test void visitorCanFilterByAuthorAloneOrTogetherWithKeywordAndTag() throws Exception {
        var response = guest.get("/api/articles/search?authorId=" + owner.userId());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        assertEquals(List.of(third, second, first), ids(json.readTree(response.body())));
        String combined = "/api/articles/search?authorId=" + owner.userId() + "&q=Java&tagId=" + tagIds.get(1);
        assertEquals(List.of(second), ids(page(guest, combined)));
        assertTrue(ids(page(guest, "/api/articles/search?authorId=9223372036854775807")).isEmpty());
        assertEquals(400, guest.get("/api/articles/search?authorId=0").statusCode());
        assertEquals(400, guest.get("/api/articles/search").statusCode());
    }

    @Test void mineUsesSessionAndCursorCannotCrossAccountsOrPublicEntry() throws Exception {
        assertEquals(401, guest.get("/api/users/me/articles").statusCode());
        var firstPage = page(mine, "/api/users/me/articles?size=1&authorId=" + other.userId());
        assertEquals(List.of(third), ids(firstPage));
        assertFalse(firstPage.has("page")); assertFalse(firstPage.has("size")); assertFalse(firstPage.has("total"));
        var card = firstPage.path("items").get(0);
        assertFalse(card.has("hidden")); assertFalse(card.has("contentHtml")); assertFalse(card.has("contentJson"));
        String cursor = URLEncoder.encode(firstPage.path("nextCursor").asString(), StandardCharsets.UTF_8);
        String next = "/api/users/me/articles?size=1&cursor=" + cursor;
        var secondPage = page(mine, next);
        assertEquals(List.of(second), ids(secondPage));
        var lastPage = page(mine, "/api/users/me/articles?size=1&cursor="
                + URLEncoder.encode(secondPage.path("nextCursor").asString(), StandardCharsets.UTF_8));
        assertEquals(List.of(first), ids(lastPage)); assertTrue(lastPage.path("nextCursor").isNull());
        assertEquals(400, theirs.get(next).statusCode());
        assertEquals(400, guest.get("/api/articles/search?authorId=" + owner.userId() + "&size=1&cursor=" + cursor).statusCode());
        // 公开游标仅绑定查询条件，访问者变化时仍可续页并重新组装个人字段。
        var publicPage = page(mine, "/api/articles/search?authorId=" + owner.userId() + "&size=1");
        String publicCursor = URLEncoder.encode(publicPage.path("nextCursor").asString(), StandardCharsets.UTF_8);
        assertEquals(List.of(second), ids(page(guest, "/api/articles/search?authorId=" + owner.userId() + "&size=1&cursor=" + publicCursor)));
        assertTrue(ids(page(theirs, "/api/users/me/articles")).stream().noneMatch(id -> List.of(first, second, third).contains(id)));
    }

    @Test void allListsAssemblePersonalLikesWithoutRecordingViews() throws Exception {
        clearInvocations(views);
        for (String path : List.of("/api/articles/search?authorId=" + owner.userId(), "/api/articles/feed", "/api/users/me/articles")) {
            var items = page(mine, path).path("items");
            var card = find(items, first);
            assertTrue(card.path("likedByMe").asBoolean()); assertEquals(1, card.path("likeCount").asLong());
            assertEquals(0, card.path("viewCount").asLong());
        }
        var publicPath = "/api/articles/search?authorId=" + owner.userId();
        assertFalse(find(page(guest, publicPath).path("items"), first).path("likedByMe").asBoolean());
        assertFalse(find(page(theirs, publicPath).path("items"), first).path("likedByMe").asBoolean());
        verify(views, never()).record(anyLong());
    }

    private JsonNode page(SearchHttpSession session, String path) throws Exception {
        var response = session.get(path);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
    private List<Long> ids(JsonNode page) {
        List<Long> result = new ArrayList<>();
        page.path("items").forEach(card -> result.add(Long.parseLong(card.path("articleId").asString())));
        return result;
    }
    private JsonNode find(JsonNode items, long id) {
        for (var card : items) { if (Long.toString(id).equals(card.path("articleId").asString())) { return card; } }
        throw new AssertionError("列表缺少预期文章：" + id);
    }
    private SearchHttpSession session() {
        var session = new SearchHttpSession(port, json); sessions.add(session); return session;
    }
    private UserActor user(SearchHttpSession session) throws Exception {
        String name = "sa_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        long id = Objects.requireNonNull(transaction.execute(s -> accounts.insert(name, Role.USER, encoder.encode(PASSWORD))));
        owners.add(id); session.login(name, PASSWORD); return new UserActor(id, Role.USER, 1);
    }
    private long create(UserActor author, String title, long tag) {
        var body = json.readTree("{\"schemaVersion\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"测试正文\"}]}]}}");
        long id = articles.save(author, new SaveArticleCommand(null, title, body, null, List.of(tag)));
        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).until(() -> index.metaVersion(id) >= 1);
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("SELECT COUNT(*) FROM article_outbox WHERE article_id=?", Integer.class, id) == 0);
        return id;
    }

    @AfterAll void cleanup() throws Exception {
        relay.shutdown(); indexListener.stop(); likeListener.stop();
        sessions.forEach(SearchHttpSession::close);
        for (long author : owners) {
            articleFixtures.deleteTagsForAuthor(author); articleFixtures.deleteLikeStatesForAuthor(author);
            articleFixtures.deleteLikeCountsForAuthor(author); articleFixtures.deleteArticlesForAuthor(author);
            fixtures.deleteIdentities(author); fixtures.deleteUser(author);
        }
        tagIds.forEach(articleFixtures::deleteTag);
        try (var keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + ":*").count(100).build())) {
            while (keys.hasNext()) { redis.delete(keys.next()); }
        }
        var admin = new RabbitAdmin(connectionFactory);
        admin.deleteQueue(properties.name("work.q")); admin.deleteQueue(properties.name("dead.q"));
        admin.deleteExchange(properties.name("change.x")); admin.deleteExchange(properties.name("dead.x"));
        admin.deleteQueue(likes.name("command.q")); admin.deleteQueue(likes.name("dead.q"));
        admin.deleteExchange(likes.name("command.x")); admin.deleteExchange(likes.name("dead.x"));
        fixture.close();
    }
}
