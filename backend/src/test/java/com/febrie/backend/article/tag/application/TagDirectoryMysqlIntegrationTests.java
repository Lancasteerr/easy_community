package com.febrie.backend.article.tag.application;

import com.febrie.backend.article.tag.infrastructure.persistence.TagRepository;
import com.febrie.backend.article.tag.infrastructure.persistence.mapper.TagMapper;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.support.PersistenceTestConfiguration;
import com.febrie.backend.support.persistence.ArticleFixtureMapper;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 专用主库验证提交版本、RR目录快照以及真实Mapper调用次数。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PersistenceTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "IT_ARTICLE", matches = "true")
class TagDirectoryMysqlIntegrationTests {
    private static final String PREFIX = "ec:test:tag:" + UUID.randomUUID();
    @Autowired TagRepository repository;
    @Autowired TagCreationService creation;
    @Autowired TagService service;
    @Autowired TagCatalog catalog;
    @Autowired TagDirectoryReader reader;
    @Autowired ArticleFixtureMapper fixtures;
    @Autowired TransactionTemplate transaction;
    @Autowired SqlSessionTemplate sessions;
    @MockitoSpyBean TagMapper mapper;
    private final List<Long> created = new ArrayList<>();
    private final UserActor admin = new UserActor(1, Role.ADMIN, 1);

    @DynamicPropertySource static void dedicated(DynamicPropertyRegistry registry) {
        String url = System.getenv("IT_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")) {
            throw new IllegalArgumentException("标签测试仅允许专用测试库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("app.auth.redis-prefix", () -> PREFIX);
    }
    @AfterEach void cleanup() {
        reset(mapper);
        created.forEach(fixtures::deleteTag);
        if (!created.isEmpty()) { mapper.advanceDirectoryVersion(); }
    }
    private String unique() { return "tag_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); }
    private long insert(String name) {
        long id = repository.insert(name); created.add(id); return id;
    }

    @Test void createRollbackAndDuplicatePreserveAuthoritativeVersion() {
        long before = repository.version();
        String name = unique();
        var result = service.create(admin, " " + name + " ");
        long id = Long.parseLong(result.tagId()); created.add(id);
        assertEquals(before + 1, repository.version());
        assertEquals("TAG_EXISTS", assertThrows(ApiException.class, () -> creation.create(admin, name)).code());
        assertEquals(before + 1, repository.version());
        assertThrows(ApiException.class, () -> service.create(new UserActor(2, Role.USER, 1), unique()));
        String rolledBack = unique();
        transaction.executeWithoutResult(status -> {
            creation.create(admin, rolledBack);
            assertEquals(before + 2, repository.version());
            status.setRollbackOnly();
        });
        assertEquals(before + 1, repository.version());
        assertFalse(repository.all().stream().anyMatch(t -> t.name().equals(rolledBack)));
    }

    @Test void knownIdsAreLocalUnknownSameVersionOnlyChecksVersionAndStaleLoadsExactlyOnce() {
        long first = insert(unique());
        catalog.checkForUpdates();
        clearInvocations(mapper);
        catalog.requireAllExist(List.of(first));
        catalog.resolve(List.of(first));
        catalog.search("tag_", 10);
        verifyNoInteractions(mapper);
        assertEquals("INVALID_ARTICLE", assertThrows(ApiException.class,
                () -> catalog.requireAllExist(List.of(Long.MAX_VALUE))).code());
        verify(mapper).directoryVersion(); verifyNoMoreInteractions(mapper);
        long second = insert(unique());
        clearInvocations(mapper);
        catalog.requireAllExist(List.of(first, second));
        verify(mapper, times(2)).directoryVersion();
        verify(mapper).directory(); verifyNoMoreInteractions(mapper);
    }

    @Test void versionAndDirectoryRemainInTheSameRepeatableReadView() throws Exception {
        long first = insert(unique());
        long version = repository.version();
        CountDownLatch versionRead = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            versionRead.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return sessions.getMapper(TagMapper.class).directory();
        }).when(mapper).directory();
        long second;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var load = executor.submit(reader::load);
            try {
                assertTrue(versionRead.await(5, TimeUnit.SECONDS));
                second = insert(unique());
            } finally { release.countDown(); }
            var snapshot = load.get(5, TimeUnit.SECONDS);
            assertEquals(version, snapshot.version());
            assertTrue(snapshot.tags().stream().anyMatch(t -> t.tagId().equals(Long.toString(first))));
            assertFalse(snapshot.tags().stream().anyMatch(t -> t.tagId().equals(Long.toString(second))));
        }
        assertEquals(version + 1, repository.version());
    }
}
