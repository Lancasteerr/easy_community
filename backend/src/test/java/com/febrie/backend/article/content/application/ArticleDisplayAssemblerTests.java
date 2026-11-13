package com.febrie.backend.article.content.application;

import com.febrie.backend.article.content.application.dto.*;
import com.febrie.backend.media.application.MediaUrls;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.UserBasicQueryService;
import com.febrie.backend.user.application.dto.UserBasicData;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证文章与用户更新解耦、批量作者查询以及缓存和HTTP模型的边界。 */
class ArticleDisplayAssemblerTests {
    private final UserBasicQueryService users = mock(UserBasicQueryService.class);
    private final MediaUrls urls = mock(MediaUrls.class);
    private final com.febrie.backend.article.tag.application.TagCatalog tags = mock(com.febrie.backend.article.tag.application.TagCatalog.class);
    private final ArticleDisplayAssembler assembler = new ArticleDisplayAssembler(users, urls, tags);

    @Test void deduplicatesAuthorsAndRefreshesAvatarWithoutChangingArticle() {
        var articles = List.of(brief("1"), brief("2"));
        when(users.get(List.of(9L))).thenReturn(Map.of(9L, new UserBasicData(9, "author", "old", "简介")),
                Map.of(9L, new UserBasicData(9, "author", "new", "简介")));
        when(urls.publicUrl("old")).thenReturn("https://old");
        when(urls.publicUrl("new")).thenReturn("https://new");
        assertEquals("https://old", assembler.lists(articles).getFirst().authorAvatarUrl());
        var response = assembler.lists(articles);
        assertEquals(List.of("1", "2"), response.stream().map(ArticleListView::articleId).toList());
        assertEquals("https://new", response.getLast().authorAvatarUrl());
        verify(users, times(2)).get(List.of(9L));
    }

    @Test void nullAvatarIsValidButMissingAuthorIsNotSilentlyDropped() {
        when(users.get(List.of(9L))).thenReturn(Map.of(9L, new UserBasicData(9, "deleted-author", null, "简介")));
        var response = assembler.lists(List.of(brief("1"))).getFirst();
        assertEquals("deleted-author", response.authorName());
        assertNull(response.authorAvatarUrl());
        verifyNoInteractions(urls);
        when(users.get(List.of(9L))).thenReturn(Map.of());
        assertThrows(ApiException.class, () -> assembler.lists(List.of(brief("1"))));
    }

    @Test void cachePayloadDoesNotCarryAuthorDisplayOrGeneratedUrls() {
        String payload = new ObjectMapper().writeValueAsString(brief("9007199254740995"));
        assertFalse(payload.contains("authorName"));
        assertFalse(payload.contains("Avatar"));
        assertFalse(payload.contains("coverUrl"));
        assertTrue(payload.contains("\"9007199254740995\""));
        assertTrue(payload.contains("\"tagIds\""));
        assertFalse(payload.contains("\"tags\""));
    }

    @Test void resolvesPageTagsOnceAndDoesNotSilentlyDropMissingNames() {
        var tagged = new ArticleBriefData("1", "title", "9", "summary", null, List.of(3L), Instant.EPOCH, Instant.EPOCH);
        when(users.get(List.of(9L))).thenReturn(Map.of(9L, new UserBasicData(9, "author", null, "简介")));
        when(tags.resolve(List.of(3L))).thenReturn(Map.of(3L, new com.febrie.backend.article.tag.application.dto.TagView("3", "Java")));
        var page = assembler.lists(List.of(tagged, tagged));
        assertEquals("Java", page.getFirst().tags().getFirst().name());
        verify(tags).resolve(List.of(3L));
        when(tags.resolve(List.of(3L))).thenReturn(Map.of());
        assertEquals("DEPENDENCY_UNAVAILABLE", assertThrows(ApiException.class, () -> assembler.lists(List.of(tagged))).code());
    }

    private static ArticleBriefData brief(String id) {
        return new ArticleBriefData(id, "title", "9", "summary", null, List.of(), Instant.EPOCH, Instant.EPOCH);
    }
}
