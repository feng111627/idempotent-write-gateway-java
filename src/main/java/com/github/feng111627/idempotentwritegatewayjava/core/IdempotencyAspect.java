package com.github.feng111627.idempotentwritegatewayjava.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.feng111627.idempotentwritegatewayjava.annotation.Idempotent;
import com.github.feng111627.idempotentwritegatewayjava.config.IdempotencyProperties;
import com.github.feng111627.idempotentwritegatewayjava.error.IdempotencyKeyInvalidException;
import com.github.feng111627.idempotentwritegatewayjava.error.IdempotencyKeyMissingException;
import com.github.feng111627.idempotentwritegatewayjava.web.ReplayableRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.regex.Pattern;

/**
 * 拦截 @Idempotent 方法：校验幂等键 -> 计算指纹 -> 交给 IdempotencyService 执行/重放。
 */
@Aspect
@Component
public class IdempotencyAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final IdempotencyService idempotencyService;
    private final RequestFingerprinter fingerprinter;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;

    private volatile Pattern compiledPattern;

    public IdempotencyAspect(IdempotencyService idempotencyService,
                             RequestFingerprinter fingerprinter,
                             IdempotencyProperties properties,
                             ObjectMapper idempotencyObjectMapper) {
        this.idempotencyService = idempotencyService;
        this.fingerprinter = fingerprinter;
        this.properties = properties;
        this.objectMapper = idempotencyObjectMapper;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        HttpServletRequest request = currentRequest();
        String key = requireValidKey(request);
        byte[] body = cachedBody(request);
        String fingerprint = fingerprinter.fingerprint(
                request.getMethod(), request.getRequestURI(), request.getQueryString(), body);

        ExecutionOutcome outcome = idempotencyService.execute(key, fingerprint, () -> {
            try {
                return pjp.proceed();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        if (outcome instanceof ExecutionOutcome.Executed executed) {
            return executed.returnValue();
        }
        if (outcome instanceof ExecutionOutcome.Replayed replayed) {
            writeReplayed(replayed, request);
            return null;
        }
        throw new IllegalStateException("未知执行结果: " + outcome);
    }

    private String requireValidKey(HttpServletRequest request) {
        String headerName = properties.getHeaderName();
        String key = request.getHeader(headerName);
        if (key == null || key.isBlank()) {
            throw new IdempotencyKeyMissingException(headerName);
        }
        key = key.trim();
        Pattern pattern = pattern();
        if (!pattern.matcher(key).matches()) {
            throw new IdempotencyKeyInvalidException(key);
        }
        return key;
    }

    private Pattern pattern() {
        Pattern p = compiledPattern;
        if (p == null) {
            synchronized (this) {
                p = compiledPattern;
                if (p == null) {
                    p = Pattern.compile(properties.getKeyPattern());
                    compiledPattern = p;
                }
            }
        }
        return p;
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new IllegalStateException("当前线程没有 Servlet 请求上下文");
        }
        return attrs.getRequest();
    }

    private byte[] cachedBody(HttpServletRequest request) {
        if (request instanceof ReplayableRequestFilter.CachedBodyHttpServletRequest cached) {
            return cached.getCachedBody();
        }
        return new byte[0];
    }

    private void writeReplayed(ExecutionOutcome.Replayed replayed, HttpServletRequest request)
            throws java.io.IOException {
        HttpServletResponse response =
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getResponse();
        if (response == null) {
            throw new IllegalStateException("当前线程没有 Servlet 响应上下文");
        }
        response.setStatus(replayed.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(REPLAYED_HEADER, "true");
        log.info("返回幂等重放响应 key={}, status={}", request.getHeader(properties.getHeaderName()),
                replayed.httpStatus());
        if (replayed.responseBody() != null && !replayed.responseBody().isEmpty()) {
            response.getWriter().write(replayed.responseBody());
        }
    }
}
