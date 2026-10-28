package com.febrie.backend.media.web;

import com.febrie.backend.media.infrastructure.storage.PostPolicies;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** 验签使用原始body和query；path按COS规范解码，不能先解析JSON再重序列化。 */
public final class CosCallbackAuth {
    private CosCallbackAuth() { }
    public static boolean verify(String path, String query, byte[] body, String signature, String key) {
        if (signature == null || key == null || key.isBlank()) { return false; }
        try {
            // URLDecoder的+属于表单语义，路径中的字面+必须保留。
            String decoded = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
            byte[] prefix = (decoded + (query == null ? "" : "?" + query) + "\n").getBytes(StandardCharsets.UTF_8);
            byte[] input = new byte[prefix.length + body.length];
            System.arraycopy(prefix, 0, input, 0, prefix.length); System.arraycopy(body, 0, input, prefix.length, body.length);
            byte[] sha = PostPolicies.digest("SHA-1", input);
            byte[] expected = PostPolicies.hmac("HmacSHA1", key.getBytes(StandardCharsets.UTF_8), sha);
            return MessageDigest.isEqual(expected, Base64.getDecoder().decode(signature));
        } catch (IllegalArgumentException error) { return false; }
    }
}
