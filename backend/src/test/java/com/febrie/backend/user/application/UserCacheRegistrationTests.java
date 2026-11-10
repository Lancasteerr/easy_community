package com.febrie.backend.user.application;

import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.mockito.Mockito.*;

/** 管理创建与首次管理员初始化均复用创建规则并登记缓存失效。 */
class UserCacheRegistrationTests {
    @Test void managedAndBootstrapCreationBothRegisterInvalidation() {
        var accounts = mock(AccountRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var cache = mock(UserCacheMaintenance.class);
        var users = new UserService(accounts, encoder, mock(UserProfileService.class), cache);
        when(encoder.encode("StrongPass123!")).thenReturn("hash");
        when(accounts.insert("managed", Role.USER, "hash")).thenReturn(10L);
        when(accounts.insert("admin", Role.ADMIN, "hash")).thenReturn(11L);
        users.createManaged(new UserActor(1, Role.ADMIN, 1), "managed", "StrongPass123!", Role.USER);
        users.bootstrapAdmin("admin", "StrongPass123!");
        verify(cache).invalidateAfterCommit(10);
        verify(cache).invalidateAfterCommit(11);
    }
}
