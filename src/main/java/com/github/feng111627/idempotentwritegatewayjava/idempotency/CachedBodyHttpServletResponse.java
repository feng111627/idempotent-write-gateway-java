package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 缓存响应体的 response 包装。
 *
 * <p>业务执行期间，写出的内容一边透传给客户端，一边复制到内存缓冲区；
 * 执行结束后幂等层读取缓冲区固化结果，之后重放时返回完全一致的状态码与响应体。
 */
public class CachedBodyHttpServletResponse extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream cachedContent = new ByteArrayOutputStream();
    private ServletOutputStream teeStream;
    private PrintWriter writer;

    public CachedBodyHttpServletResponse(HttpServletResponse response) {
        super(response);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("该响应已通过 getWriter() 获取写入器，不能再获取输出流");
        }
        if (teeStream == null) {
            ServletOutputStream original = super.getOutputStream();
            teeStream = new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    original.write(b);
                    cachedContent.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    original.write(b, off, len);
                    cachedContent.write(b, off, len);
                }

                @Override
                public boolean isReady() {
                    return original.isReady();
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                    original.setWriteListener(writeListener);
                }
            };
        }
        return teeStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (teeStream != null) {
            throw new IllegalStateException("该响应已通过 getOutputStream() 获取输出流，不能再获取写入器");
        }
        if (writer == null) {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            // getOutputStream() 本身就是“透传+缓存”的 tee 流，可直接作为 Writer 的底层输出
            writer = new PrintWriter(new java.io.OutputStreamWriter(
                    getOutputStream(), charset), true);
        }
        return writer;
    }

    /** 刷新写入器缓冲，保证业务响应完整进入缓存。 */
    public void flush() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        if (teeStream != null) {
            teeStream.flush();
        }
    }

    /** 获取被缓存的响应体字节（包含已透传给客户端的完整内容）。 */
    public byte[] getCachedBody() {
        return cachedContent.toByteArray();
    }
}
