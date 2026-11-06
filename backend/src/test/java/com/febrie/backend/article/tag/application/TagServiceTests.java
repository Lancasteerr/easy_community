package com.febrie.backend.article.tag.application;

import com.febrie.backend.article.tag.infrastructure.persistence.TagRepository;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 目录校验覆盖权限、规范化及唯一约束冲突，不复制数据库实现。 */
class TagServiceTests {
    private final TagRepository tags = mock(TagRepository.class);
    private final TagService service = new TagService(tags);
    @Test void normalizesAndKeepsCase() {
        when(tags.insert("Java")).thenReturn(4L);
        assertEquals("4", service.create(new UserActor(1, Role.ADMIN, 1), " Java ").tagId());
        verify(tags).insert("Java");
    }
    @Test void rejectsNonAdminAndDuplicate() {
        assertThrows(ApiException.class, () -> service.create(new UserActor(1, Role.USER, 1), "x"));
        verifyNoInteractions(tags);
        when(tags.insert("x")).thenThrow(new DuplicateKeyException("private SQL"));
        assertEquals("TAG_EXISTS", assertThrows(ApiException.class,
                () -> service.create(new UserActor(1, Role.ADMIN, 1), "x")).code());
    }
    @Test void validatesReferencesWithoutChangingArticleErrors() {
        when(tags.allExist(List.of())).thenReturn(true);
        when(tags.allExist(List.of(4L))).thenReturn(true);
        assertDoesNotThrow(() -> service.requireAllExist(List.of()));
        assertDoesNotThrow(() -> service.requireAllExist(List.of(4L)));
        assertEquals("INVALID_ARTICLE", assertThrows(ApiException.class,
                () -> service.requireAllExist(List.of(99L))).code());
    }
}
