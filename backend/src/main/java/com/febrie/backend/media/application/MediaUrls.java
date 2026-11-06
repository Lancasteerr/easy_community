package com.febrie.backend.media.application;

import com.febrie.backend.media.domain.MediaLocation;
import com.febrie.backend.media.infrastructure.config.MediaProperties;
import org.springframework.stereotype.Component;

/** 根据可信文件ID生成固定公开地址，不访问SQL、Redis或对象存储。 */
@Component
public class MediaUrls {
    private final MediaProperties properties;
    private final MediaLocation location;

    public MediaUrls(MediaProperties properties) {
        this.properties = properties;
        this.location = new MediaLocation(properties.keyPrefix());
    }

    /** 调用者负责业务可见性与READY引用校验；地址转换沿用既有Key规则。 */
    public String publicUrl(String id) {
        return properties.publicUrl(location.publicKey(id));
    }
}
