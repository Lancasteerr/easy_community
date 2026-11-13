package com.febrie.backend.user.application;

import com.febrie.backend.media.application.MediaUrls;
import com.febrie.backend.media.application.reference.MediaReferenceService;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.*;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 锁内比较保证局部更新；响应使用写入值，不依赖可能尚未失效的展示缓存。 */
class UserProfileServiceTests {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final UserCacheMaintenance cache = mock(UserCacheMaintenance.class);
    private final MediaReferenceService references = mock(MediaReferenceService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final UserActor actor = new UserActor(42, Role.USER, 3);

    private UserProfileService service() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new UserProfileService(accounts, mock(MediaUrls.class), references, manager, cache);
    }

    @Test void editsOnlyBioAndReturnsNormalizedCommittedValue() {
        when(accounts.profile(42, true)).thenReturn(Optional.of(snapshot("ACTIVE", 3, "旧简介")));
        var response = service().editBio(actor, " 新简介 ");
        assertEquals("新简介", response.bio());
        assertEquals("42", response.userId());
        verify(accounts).updateBio(42, "新简介");
        verify(accounts, never()).updateAvatar(anyLong(), any());
        verify(cache).invalidateAfterCommit(42);
        verify(manager).commit(any());
        verifyNoInteractions(references);
    }

    @Test void unchangedBioDoesNotWriteOrInvalidate() {
        when(accounts.profile(42, true)).thenReturn(Optional.of(snapshot("ACTIVE", 3, "简介")));
        assertEquals("简介", service().editBio(actor, " 简介 ").bio());
        verify(accounts, never()).updateBio(anyLong(), anyString());
        verifyNoInteractions(cache, references);
    }

    @Test void nullClearsBioWithoutChangingAuthentication() {
        when(accounts.profile(42, true)).thenReturn(Optional.of(snapshot("ACTIVE", 3, "简介")));
        assertEquals("", service().editBio(actor, null).bio());
        verify(accounts).updateBio(42, "");
        verify(accounts, never()).updatePassword(anyLong(), anyString());
    }

    @Test void inactiveOrStaleActorCannotWrite() {
        for (var snapshot : new UserProfileSnapshot[]{snapshot("DELETED", 3, "简介"),
                snapshot("DISABLED", 3, "简介"), snapshot("ACTIVE", 4, "简介")}) {
            when(accounts.profile(42, true)).thenReturn(Optional.of(snapshot));
            assertEquals(401, assertThrows(ApiException.class, () -> service().editBio(actor, "变更")).status().value());
        }
        verify(accounts, never()).updateBio(anyLong(), anyString());
        verifyNoInteractions(cache);
    }

    private UserProfileSnapshot snapshot(String status, long version, String bio) {
        return new UserProfileSnapshot(42, "alice", Role.USER, status, version, null, bio);
    }
}
