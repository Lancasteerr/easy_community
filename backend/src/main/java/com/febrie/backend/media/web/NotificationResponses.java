package com.febrie.backend.media.web;

import com.febrie.backend.media.application.dto.UploadResult;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import tools.jackson.databind.ObjectMapper;

/** 通知专用小型JSON，不经过普通业务Problem响应转换。 */
public final class NotificationResponses {
    private static final ObjectMapper JSON = new ObjectMapper();
    private NotificationResponses() { }
    public static void write(HttpServletResponse response, int status, String id, UploadResult result) throws IOException {
        // 504仅表示通知处理超时，COS负责生成浏览器侧203，不序列化内部超时标识。
        if (status == HttpServletResponse.SC_GATEWAY_TIMEOUT) {
            response.setStatus(status);
            response.setHeader("Cache-Control", "no-store");
            response.setContentLength(0);
            return;
        }
        byte[] body = JSON.writeValueAsBytes(result);
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (id != null) { response.setHeader("X-Media-Id", id); }
        response.setHeader("X-Media-Result", result.success() ? "SUCCESS" : "FAILED");
        response.setHeader("X-Media-Stage", result.stage().name()); response.setHeader("X-Media-Code", result.code().name());
        response.setContentLength(body.length); response.getOutputStream().write(body);
    }
}
