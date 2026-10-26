package com.linkora.core.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 上行报文体 gzip 压缩工具（0.6.0）。
 *
 * <p>语义：超过阈值的 JSON 报文体自动 gzip；HMAC 签名在压缩后的报文上计算
 * （签名覆盖线上实际字节），服务端验签后按 {@code X-Linkora-Content-Encoding: gzip}
 * 头解压再解码。阈值 {@value #DEFAULT_THRESHOLD} 字节。</p>
 *
 * @author lianghang
 */
public final class Gzip {

    /** 默认压缩阈值：64KB。 */
    public static final int DEFAULT_THRESHOLD = 64 * 1024;

    /** gzip 魔数首字节。 */
    private static final int GZIP_MAGIC_0 = 0x1f;
    private static final int GZIP_MAGIC_1 = 0x8b;

    private Gzip() {
    }

    /** 超过阈值时 gzip 压缩，否则原样返回（返回值需配合 {@link #isGzipped} 判断）。 */
    public static byte[] maybeCompress(byte[] body) {
        return maybeCompress(body, DEFAULT_THRESHOLD);
    }

    public static byte[] maybeCompress(byte[] body, int threshold) {
        if (body == null || body.length < threshold || isGzipped(body)) {
            return body;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length / 2);
            GZIPOutputStream gz = new GZIPOutputStream(out);
            gz.write(body);
            gz.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("gzip 压缩失败", e);
        }
    }

    /** 魔数判断是否为 gzip 数据（比仅依赖请求头更稳）。 */
    public static boolean isGzipped(byte[] body) {
        return body != null && body.length > 2
                && body[0] == (byte) GZIP_MAGIC_0 && body[1] == (byte) GZIP_MAGIC_1;
    }

    /** 解压 gzip 数据；非 gzip 输入抛 IOException。 */
    public static byte[] decompress(byte[] gzipped) throws IOException {
        GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(gzipped.length * 4);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
