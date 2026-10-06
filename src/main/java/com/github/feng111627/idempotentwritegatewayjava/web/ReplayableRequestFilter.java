package com.github.feng111627.idempotentwritegatewayjava.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 缓存请求体，使指纹计算与 Controller 反序列化可以重复读取同一请求体。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReplayableRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    /** 可重复读 body 的请求包装。 */
    public static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        public byte[] getCachedBody() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
    }

    /** 基于缓存字节数组的 ServletInputStream。 */
    static class CachedBodyServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream inputStream;

        CachedBodyServletInputStream(byte[] body) {
            this.inputStream = new ByteArrayInputStream(body);
        }

        @Override
        public boolean isFinished() {
            return inputStream.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("异步读取不支持");
        }

        @Override
        public int read() {
            return inputStream.read();
        }
    }
}
