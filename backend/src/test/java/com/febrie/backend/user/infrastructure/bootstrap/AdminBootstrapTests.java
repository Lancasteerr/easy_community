package com.febrie.backend.user.infrastructure.bootstrap;

import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.UserService;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只验证启动编排及冲突处理，服务和仓储使用替身，不连接任何外部服务。 */
class AdminBootstrapTests {
    private static final String USERNAME = "local_admin";
    private static final String PASSWORD = "test-password-123";
    private final UserService users = mock(UserService.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final DefaultApplicationArguments arguments = new DefaultApplicationArguments(new String[0]);

    @Test
    void existingAdminSkipsWithoutCredentials() {
        when(accounts.hasAdmin()).thenReturn(true);

        assertDoesNotThrow(this::run);

        verifyNoInteractions(users);
    }

    @Test
    void createsThroughExistingServiceWithConfiguredCredentials() {
        credentials();

        run();

        verify(users).bootstrapAdmin(USERNAME, PASSWORD);
        verify(accounts).hasAdmin();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN_USERNAME", "ADMIN_PASSWORD"})
    void missingCredentialFailsBeforeCreation(String missing) {
        if ("ADMIN_USERNAME".equals(missing)) {
            environment.withProperty("ADMIN_PASSWORD", PASSWORD);
        } else {
            environment.withProperty("ADMIN_USERNAME", USERNAME);
        }

        assertThrows(IllegalStateException.class, this::run);

        verifyNoInteractions(users);
    }

    @Test
    void concurrentAdminBusinessResultIsSuccessfulSkip() {
        credentials();
        when(users.bootstrapAdmin(USERNAME, PASSWORD)).thenThrow(
                new ApiException(HttpStatus.CONFLICT, "ADMIN_ALREADY_INITIALIZED", "管理员已初始化"));

        assertDoesNotThrow(this::run);

        verify(users).bootstrapAdmin(USERNAME, PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USERNAME_EXISTS", "INVALID_PASSWORD", "UNEXPECTED_ERROR"})
    void otherBusinessFailuresAreNotRetriedOrSuppressed(String code) {
        credentials();
        var failure = new ApiException(HttpStatus.BAD_REQUEST, code, "测试业务错误");
        when(users.bootstrapAdmin(USERNAME, PASSWORD)).thenThrow(failure);

        assertSame(failure, assertThrows(ApiException.class, this::run));

        verify(users).bootstrapAdmin(USERNAME, PASSWORD);
    }

    @Test
    void lockConflictSkipsWhenConcurrentAdminAppears() {
        credentials();
        when(accounts.hasAdmin()).thenReturn(false, true);
        when(users.bootstrapAdmin(USERNAME, PASSWORD)).thenThrow(
                new PessimisticLockingFailureException("测试锁冲突"));

        assertDoesNotThrow(this::run);

        verify(users).bootstrapAdmin(USERNAME, PASSWORD);
        verify(accounts, times(2)).hasAdmin();
    }

    @Test
    void serializationConflictRetriesAfterCheckingDatabase() {
        credentials();
        when(users.bootstrapAdmin(USERNAME, PASSWORD))
                .thenThrow(new CannotSerializeTransactionException("测试序列化冲突"))
                .thenReturn(null);

        run();

        var order = inOrder(accounts, users);
        order.verify(accounts).hasAdmin();
        order.verify(users).bootstrapAdmin(USERNAME, PASSWORD);
        order.verify(accounts).hasAdmin();
        order.verify(users).bootstrapAdmin(USERNAME, PASSWORD);
        verifyNoMoreInteractions(accounts, users);
    }

    @Test
    void repeatedLockConflictFailsAfterThreeAttemptsAndFinalCheck() {
        credentials();
        var conflict = new PessimisticLockingFailureException("测试锁冲突");
        when(users.bootstrapAdmin(USERNAME, PASSWORD)).thenThrow(conflict);

        assertSame(conflict, assertThrows(PessimisticLockingFailureException.class, this::run));

        verify(users, times(3)).bootstrapAdmin(USERNAME, PASSWORD);
        verify(accounts, times(4)).hasAdmin();
    }

    @Test
    void finalConflictCanStillObserveConcurrentSuccess() {
        credentials();
        when(accounts.hasAdmin()).thenReturn(false, false, false, true);
        when(users.bootstrapAdmin(USERNAME, PASSWORD)).thenThrow(
                new PessimisticLockingFailureException("测试锁冲突"));

        assertDoesNotThrow(this::run);

        verify(users, times(3)).bootstrapAdmin(USERNAME, PASSWORD);
        verify(accounts, times(4)).hasAdmin();
    }

    @Test
    void connectionFailureIsNotRetriedOrSuppressed() {
        credentials();
        var failure = new DataAccessResourceFailureException("测试连接失败");
        when(users.bootstrapAdmin(USERNAME, PASSWORD)).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, this::run));

        verify(users).bootstrapAdmin(USERNAME, PASSWORD);
        verify(accounts).hasAdmin();
    }

    @Test
    void disabledSwitchDoesNotRequireDependenciesOrCredentials() {
        new ApplicationContextRunner().withUserConfiguration(AdminBootstrap.class)
                .withPropertyValues("app.bootstrap.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(AdminBootstrap.class).isEmpty());
                });
        verifyNoInteractions(accounts, users);
    }

    @Test
    void webContextAllowsEnabledInitialization() {
        new WebApplicationContextRunner().withUserConfiguration(AdminBootstrap.class)
                .withBean(UserService.class, () -> users)
                .withBean(AccountRepository.class, () -> accounts)
                .withPropertyValues("app.bootstrap.enabled=true", "ADMIN_USERNAME=" + USERNAME,
                        "ADMIN_PASSWORD=" + PASSWORD)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    // 最小上下文不会自动执行 runner，显式调用以验证 Web 模式没有限制。
                    context.getBean(AdminBootstrap.class).run(arguments);
                    verify(users).bootstrapAdmin(USERNAME, PASSWORD);
                });
    }

    private void credentials() {
        environment.withProperty("ADMIN_USERNAME", USERNAME).withProperty("ADMIN_PASSWORD", PASSWORD);
    }

    private void run() {
        new AdminBootstrap(users, accounts, environment).run(arguments);
    }
}
