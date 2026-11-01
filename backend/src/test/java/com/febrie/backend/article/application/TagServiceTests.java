package com.febrie.backend.article.application;

import com.febrie.backend.article.infrastructure.persistence.TagRepository;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
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
}
