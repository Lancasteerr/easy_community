package com.febrie.backend.user.application;

import com.febrie.backend.media.application.MediaUrls;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 资料读取与文章作者共享回源保护，不查询目标账号状态；当前请求的身份由认证链校验。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class UserProfileQueryService {
    private final UserBasicQueryService users;
    private final MediaUrls urls;

    public UserProfileQueryService(UserBasicQueryService users, MediaUrls urls) {
        this.users = users;
        this.urls = urls;
    }

    /** 调用前提：actor来自已经完成状态、认证版本和有效期校验的Session。 */
    public UserProfileView me(UserActor actor) {
        var data = requireUser(actor.userId());
        return new UserProfileView(Long.toString(data.userId()), data.username(), actor.role(),
                data.avatarMediaId(), avatarUrl(data), data.bio());
    }

    public UserMetadataView user(long userId) {
        if (userId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_ID", "用户ID须为正整数");
        }
        var data = requireUser(userId);
        return new UserMetadataView(Long.toString(data.userId()), data.username(), data.bio(),
                data.avatarMediaId(), avatarUrl(data));
    }

    private UserBasicData requireUser(long userId) {
        return users.get(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
    }

    private String avatarUrl(UserBasicData data) {
        return data.avatarMediaId() == null ? null : urls.publicUrl(data.avatarMediaId());
    }
}
