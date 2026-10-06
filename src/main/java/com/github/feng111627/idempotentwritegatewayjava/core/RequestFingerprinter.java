package com.github.feng111627.idempotentwritegatewayjava.core;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 计算请求指纹（method + URI + 排序后参数 + body 的 SHA-256），用于识别同键不同内容。
 * 参数按名字典序排序，避免参数顺序不同被误判为冲突。
 */
@Component
public class RequestFingerprinter {

    public String fingerprint(String method, String uri, String queryString, byte[] body) {
        String normalizedParams = normalizeParams(queryString);
        String raw = (method == null ? "" : method.toUpperCase())
                + '|' + (uri == null ? "" : uri)
                + '|' + normalizedParams
                + '|' + (body == null ? "" : new String(body, StandardCharsets.UTF_8));
        return sha256Hex(raw);
    }

    private String normalizeParams(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            sorted.put(name, value);
        }
        return sorted.entrySet().stream()
                .map(Map.Entry::getKey)
                .map(k -> k + '=' + sorted.get(k))
                .collect(Collectors.joining("&"));
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
