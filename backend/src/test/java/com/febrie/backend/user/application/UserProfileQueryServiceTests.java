package com.febrie.backend.user.application;

import com.febrie.backend.media.application.MediaUrls;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.*;
import com.febrie.backend.user.domain.Role;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 本人角色来自认证身份，指定用户响应始终只公开展示字段。 */
class UserProfileQueryServiceTests {
    // 固定加入时间，验证两个资料入口使用同一投影且保留时间精度。
    private static final Instant CREATED_AT = Instant.parse("2026-11-01T02:03:04.123456Z");
    private final UserBasicQueryService users = mock(UserBasicQueryService.class);
    private final MediaUrls urls = mock(MediaUrls.class);
    private final UserProfileQueryService service = new UserProfileQueryService(users, urls);

    @Test void bothViewsShareDisplayProjectionAndMeUsesAuthenticatedRole() {
        when(users.get(42)).thenReturn(Optional.of(new UserBasicData(42, "alice", "avatar", "简介", CREATED_AT)));
        when(urls.publicUrl("avatar")).thenReturn("https://media/avatar");
        var me = service.me(new UserActor(42, Role.PATROLLER, 3));
        var other = service.user(42);
        assertEquals(Role.PATROLLER, me.role());
        assertEquals(me.bio(), other.bio());
        assertEquals(me.avatarUrl(), other.avatarUrl());
        assertEquals(CREATED_AT, me.createdAt());
        assertEquals(me.createdAt(), other.createdAt());
        var json = new ObjectMapper().valueToTree(other);
        assertEquals(6, json.size());
        assertEquals(CREATED_AT.toString(), json.path("createdAt").asString());
        assertFalse(json.has("role"));
        assertFalse(json.has("status"));
        assertFalse(json.has("authVersion"));
    }

    @Test void nullAvatarAndEmptyBioRemainValid() {
        when(users.get(42)).thenReturn(Optional.of(new UserBasicData(42, "deleted", null, "", CREATED_AT)));
        assertNull(service.user(42).avatarUrl());
        assertEquals("", service.user(42).bio());
        verifyNoInteractions(urls);
    }

    @Test void onlyConfirmedMissingBecomes404AndDependencyFailurePropagates() {
        when(users.get(42)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.user(42)).status().value());
        var failure = new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "测试故障");
        when(users.get(42)).thenThrow(failure);
        assertSame(failure, assertThrows(ApiException.class, () -> service.user(42)));
        assertEquals(400, assertThrows(ApiException.class, () -> service.user(0)).status().value());
    }
}
