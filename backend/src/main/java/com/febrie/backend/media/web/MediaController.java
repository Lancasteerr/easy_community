package com.febrie.backend.media.web;

import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.media.application.MediaService;
import com.febrie.backend.media.application.dto.*;
import com.febrie.backend.media.web.request.UploadRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 文件归属来自Session，客户端不能指定上传者或自由绑定业务实体。 */
@RestController
@RequestMapping("/api/media")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MediaController {
    private final MediaService media;

    public MediaController(MediaService media) { this.media = media; }

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadGrant create(@AuthenticationPrincipal SessionPrincipal principal, @Valid @RequestBody UploadRequest request) {
        return media.create(principal.userId(), request.purpose(), request.filename(), request.contentType(), request.size());
    }

    @PostMapping("/{id}/complete")
    public MediaView complete(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id) {
        return media.complete(principal.userId(), id);
    }

    @GetMapping("/{id}")
    public MediaView get(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id) {
        return media.get(principal.userId(), id);
    }
}
