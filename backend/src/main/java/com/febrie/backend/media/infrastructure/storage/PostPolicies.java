package com.febrie.backend.media.infrastructure.storage;

import com.febrie.backend.media.infrastructure.config.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;

/** 两家POST签名均签署完整Policy，不复用PUT预签名URL或另一厂商字段。 */
public final class PostPolicies {
    private static final ObjectMapper JSON = new ObjectMapper();
    private PostPolicies() { }

    public static Map<String,String> minio(MediaProperties p, String key, String mime, long size, Instant created, Instant expires) {
        String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(java.time.ZoneOffset.UTC).format(created);
        String time = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(created);
        String scope = date + "/" + p.region() + "/s3/aws4_request";
        Map<String,String> fields = base(key, mime);
        fields.put("x-amz-algorithm", "AWS4-HMAC-SHA256");
        fields.put("x-amz-credential", p.accessKey() + "/" + scope);
        fields.put("x-amz-date", time);
        String policy = policy(p.bucket(), fields, size, expires);
        byte[] signing = hmac("HmacSHA256", utf8("AWS4" + p.secretKey()), date);
        for (String part : new String[]{p.region(), "s3", "aws4_request"}) { signing = hmac("HmacSHA256", signing, part); }
        fields.put("policy", policy);
        fields.put("x-amz-signature", HexFormat.of().formatHex(hmac("HmacSHA256", signing, policy)));
        return Collections.unmodifiableMap(fields);
    }

    public static Map<String,String> cos(MediaProperties p, MediaWorkflowProperties flow, String key, String mime,
                                          long size, Instant created, Instant expires) {
        Map<String,String> fields = base(key, mime);
        fields.put("acl", "private");
        fields.put("x-cos-forbid-overwrite", "true");
        // JSON模板使用COS系统变量，身份不取自可替换的前端自定义字段。
        String body = "{\"bucket\":${bucket},\"key\":${object},\"size\":${size},\"mimeType\":${mimeType},\"etag\":${etag}}";
        fields.put("x-cos-callback", Base64.getEncoder().encodeToString(utf8(JSON.writeValueAsString(Map.of(
                "callbackUrl", flow.callbackUrl(), "callbackBodyType", "application/json", "callbackBody", body)))));
        String keyTime = created.getEpochSecond() + ";" + expires.getEpochSecond();
        fields.put("q-sign-algorithm", "sha1");
        fields.put("q-ak", p.accessKey());
        fields.put("q-key-time", keyTime);
        String policy = policy(p.bucket(), fields, size, expires);
        // COS对原始Policy JSON求SHA1，SignKey使用十六进制HMAC结果作为下一步密钥。
        String signKey = HexFormat.of().formatHex(hmac("HmacSHA1", utf8(p.secretKey()), keyTime));
        String policyHash = HexFormat.of().formatHex(digest("SHA-1", Base64.getDecoder().decode(policy)));
        fields.put("policy", policy);
        fields.put("q-signature", HexFormat.of().formatHex(hmac("HmacSHA1", utf8(signKey), policyHash)));
        return Collections.unmodifiableMap(fields);
    }

    private static Map<String,String> base(String key, String mime) {
        Map<String,String> fields = new LinkedHashMap<>();
        fields.put("key", key); fields.put("Content-Type", mime); fields.put("success_action_status", "200");
        return fields;
    }
    private static String policy(String bucket, Map<String,String> fields, long size, Instant expires) {
        List<Object> conditions = new ArrayList<>();
        conditions.add(Map.of("bucket", bucket));
        // COS规范中表单名为q-key-time，而Policy对应条件名为q-sign-time。
        fields.forEach((name,value) -> conditions.add(Map.of(name.equals("q-key-time") ? "q-sign-time" : name, value)));
        conditions.add(List.of("content-length-range", size, size));
        return Base64.getEncoder().encodeToString(utf8(JSON.writeValueAsString(Map.of("expiration", expires.toString(), "conditions", conditions))));
    }
    public static byte[] hmac(String algorithm, byte[] key, String message) { return hmac(algorithm, key, utf8(message)); }
    public static byte[] hmac(String algorithm, byte[] key, byte[] message) {
        try { Mac mac = Mac.getInstance(algorithm); mac.init(new SecretKeySpec(key, algorithm)); return mac.doFinal(message); }
        catch (GeneralSecurityException error) { throw new IllegalStateException("签名算法不可用", error); }
    }
    public static byte[] digest(String algorithm, byte[] value) {
        try { return MessageDigest.getInstance(algorithm).digest(value); }
        catch (GeneralSecurityException error) { throw new IllegalStateException("摘要算法不可用", error); }
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
