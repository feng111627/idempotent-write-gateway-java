package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 幂等基础能力：幂等键校验、请求内容指纹计算。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** 幂等键请求头名称。 */
    public static final String HEADER_NAME = "Idempotency-Key";

    /** 允许的幂等键字符：字母/数字开头，后续可为字母、数字、下划线、短横线、冒号。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_-]*$");

    private final IdempotencyProperties properties;

    public IdempotencyService(IdempotencyProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验幂等键是否存在且格式合法。
     *
     * @throws IdempotencyRejectedException 缺失（MISSING_KEY）或格式非法（INVALID_KEY_FORMAT）
     */
    public void validateKey(String key) {
        if (key == null || key.isBlank()) {
            log.info("[幂等] 请求未携带 Idempotency-Key，拒绝原因=MISSING_KEY");
            throw new IdempotencyRejectedException(RejectionReason.MISSING_KEY,
                    "缺少幂等键：请通过 Idempotency-Key 请求头传入非空的幂等键");
        }
        String normalized = key.trim();
        if (normalized.length() > properties.getMaxKeyLength()
                || !KEY_PATTERN.matcher(normalized).matches()) {
            log.info("[幂等] Idempotency-Key 格式非法，拒绝原因=INVALID_KEY_FORMAT，长度={}",
                    normalized.length());
            throw new IdempotencyRejectedException(RejectionReason.INVALID_KEY_FORMAT,
                    "幂等键格式非法：需以字母或数字开头，仅允许字母、数字、下划线、短横线、冒号，"
                            + "长度不超过 " + properties.getMaxKeyLength());
        }
    }

    /**
     * 对原始请求体计算内容指纹（SHA-256，小写十六进制）。
     * 同一幂等键搭配不同请求体提交时，凭此识别 REQUEST_MISMATCH。
     */
    public String hashBody(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body == null ? new byte[0] : body);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论上不会缺失
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    /** 便于日志展示：指纹只取前 12 位，避免冗长。 */
    public static String shortHash(String hash) {
        if (hash == null) {
            return "null";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
