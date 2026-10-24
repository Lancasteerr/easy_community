package com.febrie.backend.user.application;

import com.febrie.backend.media.application.*;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.*;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 当前头像字段和文件引用共享事务；复制准备在事务外，头像不改变认证版本。 */
@Service
public class UserProfileService {
    private final AccountRepository accounts;
    private final MediaService media;
    private final MediaReferenceService references;
    private final TransactionTemplate transaction;

    public UserProfileService(AccountRepository accounts, MediaService media, MediaReferenceService references,
                              PlatformTransactionManager manager) {
        this.accounts = accounts; this.media = media; this.references = references;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public UserProfileView profile(UserActor actor) {
        var current = accounts.profile(actor.userId(), false).orElseThrow(ApiException::unauthorized);
        requireActive(actor, current);
        return view(current, current.avatarMediaId());
    }

    public UserProfileView setAvatar(UserActor actor, String mediaId) {
        requireActive(actor, accounts.profile(actor.userId(), false).orElseThrow(ApiException::unauthorized));
        media.prepareAvatar(actor.userId(), mediaId);
        return transaction.execute(status -> {
            var current = accounts.profile(actor.userId(), true).orElseThrow(ApiException::unauthorized);
            requireActive(actor, current);
            references.replaceAvatar(actor.userId(), current.avatarMediaId(), mediaId);
            accounts.updateAvatar(actor.userId(), mediaId);
            return view(current, mediaId);
        });
    }

    public void clearAvatar(UserActor actor) {
        transaction.executeWithoutResult(status -> {
            var current = accounts.profile(actor.userId(), true).orElseThrow(ApiException::unauthorized);
            requireActive(actor, current);
            references.replaceAvatar(actor.userId(), current.avatarMediaId(), null);
            accounts.updateAvatar(actor.userId(), null);
        });
    }

    /** 仅供用户删除流程调用，调用者已持有用户行锁并验证管理员权限。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void detachFromLockedUser(long userId) {
        var current = accounts.profile(userId, true).orElseThrow(ApiException::unauthorized);
        references.replaceAvatar(userId, current.avatarMediaId(), null);
        accounts.updateAvatar(userId, null);
    }

    private UserProfileView view(UserProfileSnapshot profile, String mediaId) {
        return new UserProfileView(Long.toString(profile.id()), profile.username(), profile.role(), mediaId,
                mediaId == null ? null : media.publicUrl(mediaId));
    }

    private static void requireActive(UserActor actor, UserProfileSnapshot current) {
        if (!"ACTIVE".equals(current.status()) || current.authVersion() != actor.authVersion()) { throw ApiException.unauthorized(); }
    }
}
