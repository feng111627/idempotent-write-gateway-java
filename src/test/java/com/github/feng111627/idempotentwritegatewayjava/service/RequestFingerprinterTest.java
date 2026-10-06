package com.github.feng111627.idempotentwritegatewayjava.service;

import com.github.feng111627.idempotentwritegatewayjava.core.RequestFingerprinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 请求指纹：识别“同键不同请求内容”，同时保证参数顺序不影响指纹。
 */
class RequestFingerprinterTest {

    private final RequestFingerprinter fingerprinter = new RequestFingerprinter();

    private byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("完全相同的请求指纹一致")
    void identicalRequestSameFingerprint() {
        String fp1 = fingerprinter.fingerprint("POST", "/api/orders", "a=1&b=2",
                body("{\"productId\":\"P1\",\"quantity\":2}"));
        String fp2 = fingerprinter.fingerprint("POST", "/api/orders", "a=1&b=2",
                body("{\"productId\":\"P1\",\"quantity\":2}"));
        assertEquals(fp1, fp2);
    }

    @Test
    @DisplayName("body 不同（连点修改了参数）指纹不同，会被识别为冲突")
    void differentBodyDifferentFingerprint() {
        String fp1 = fingerprinter.fingerprint("POST", "/api/orders", null,
                body("{\"productId\":\"P1\",\"quantity\":1}"));
        String fp2 = fingerprinter.fingerprint("POST", "/api/orders", null,
                body("{\"productId\":\"P1\",\"quantity\":2}"));
        assertNotEquals(fp1, fp2);
    }

    @Test
    @DisplayName("URI 不同指纹不同")
    void differentUriDifferentFingerprint() {
        String fp1 = fingerprinter.fingerprint("POST", "/api/orders", null, body("{}"));
        String fp2 = fingerprinter.fingerprint("POST", "/api/refunds", null, body("{}"));
        assertNotEquals(fp1, fp2);
    }

    @Test
    @DisplayName("query 参数顺序不同但内容相同，指纹一致（不误报冲突）")
    void parameterOrderInsensitive() {
        String fp1 = fingerprinter.fingerprint("POST", "/api/orders", "a=1&b=2", body("{}"));
        String fp2 = fingerprinter.fingerprint("POST", "/api/orders", "b=2&a=1", body("{}"));
        assertEquals(fp1, fp2);
    }
}
