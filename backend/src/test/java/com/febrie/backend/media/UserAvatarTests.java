package com.febrie.backend.media;

import com.febrie.backend.media.application.MediaReferenceService;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.*;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实MySQL验证头像字段、唯一引用、用户删除和并发替换在事务中保持一致。 */
class UserAvatarTests extends MediaTestSupport {
    private UserActor actor() { return new UserActor(owner, Role.USER, 1); }
    private AccountRepository accounts() { return new AccountRepository(jdbc); }
    private UserProfileService profiles(AccountRepository accounts) {
        return new UserProfileService(accounts, media, new MediaReferenceService(files, clock), manager);
    }

    @Test
    void replacementAndRepeatedRemovalKeepOneCurrentAvatarWithoutChangingAuthVersion() {
        var profiles = profiles(accounts());
        String first = uploaded(MediaPurpose.AVATAR); String second = uploaded(MediaPurpose.AVATAR);
        assertEquals(first, profiles.setAvatar(actor(), first).avatarMediaId());
        profiles.setAvatar(actor(), first);
        assertEquals(1, files.references(ReferenceType.USER_AVATAR, owner).size());
        profiles.setAvatar(actor(), second);
        assertEquals(second, profiles.profile(actor()).avatarMediaId());
        assertEquals(1, files.references(ReferenceType.USER_AVATAR, owner).size());
        assertFalse(files.hasReferences(first)); assertNotNull(files.find(first).orElseThrow().unreferencedAt());
        assertEquals(1, accounts().authState(owner).orElseThrow().authVersion());
        profiles.clearAvatar(actor()); profiles.clearAvatar(actor());
        assertNull(profiles.profile(actor()).avatarMediaId());
        assertFalse(files.hasReferences(second));
    }

    @Test
    void businessUpdateFailureRollsBackReferenceButLeavesTrackedOrphanForCleanup() {
        var accounts = spy(accounts()); var profiles = profiles(accounts);
        String first = uploaded(MediaPurpose.AVATAR); String second = uploaded(MediaPurpose.AVATAR);
        profiles.setAvatar(actor(), first);
        doThrow(new IllegalStateException("模拟资料更新失败")).when(accounts).updateAvatar(owner, second);
        assertThrows(IllegalStateException.class, () -> profiles.setAvatar(actor(), second));
        assertEquals(first, profiles.profile(actor()).avatarMediaId());
        assertTrue(files.hasReferences(first)); assertFalse(files.hasReferences(second));
        assertEquals(MediaStatus.READY, files.find(second).orElseThrow().status());
        assertNotNull(files.find(second).orElseThrow().unreferencedAt());
    }

    @Test
    void simultaneousReplacementsSerializeOnUserAndLeaveExactlyOneMatchingReference() throws Exception {
        var profiles = profiles(accounts());
        String first = uploaded(MediaPurpose.AVATAR); String second = uploaded(MediaPurpose.AVATAR);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return profiles.setAvatar(actor(), first); });
            var b = executor.submit(() -> { start.await(); return profiles.setAvatar(actor(), second); });
            start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        String current = profiles.profile(actor()).avatarMediaId();
        assertEquals(java.util.Set.of(current), files.references(ReferenceType.USER_AVATAR, owner));
        assertEquals(1, accounts().authState(owner).orElseThrow().authVersion());
    }

    @Test
    void accountDeletionDetachesAvatarAndVersionChangesBlockStaleActorBeforeCopy() {
        var accounts = accounts(); var profiles = profiles(accounts);
        String avatar = uploaded(MediaPurpose.AVATAR); profiles.setAvatar(actor(), avatar);
        var users = new UserService(accounts, new BCryptPasswordEncoder(), profiles);
        transaction.executeWithoutResult(status -> users.deleteManaged(new UserActor(owner + 1, Role.ADMIN, 1), owner));
        assertNull(accounts.profile(owner, false).orElseThrow().avatarMediaId());
        assertFalse(files.hasReferences(avatar));
        assertEquals("DELETED", accounts.authState(owner).orElseThrow().status());
        assertEquals(401, assertThrows(ApiException.class, () -> profiles.profile(actor())).status().value());
    }
}
