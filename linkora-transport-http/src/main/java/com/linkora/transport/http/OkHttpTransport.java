package com.linkora.transport.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/**
 * 基于 OkHttp 4.x 的 {@link HttpTransport} 实现（JDK 8 兼容）。
 * 同步调用模型，重试与并发调度由上层负责。
 *
 * <p>失败分类规则见 {@link TransportErrorType}；任何 2xx~5xx 响应都作为
 * {@link TransportResponse} 正常返回，不视为异常。</p>
 *
 * @author lianghang
 */
public final class OkHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(OkHttpTransport.class);

    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 10_000L;
    private static final long DEFAULT_READ_TIMEOUT_MS = 30_000L;
    private static final long DEFAULT_WRITE_TIMEOUT_MS = 30_000L;
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 2 * 1024 * 1024L;

    private final OkHttpClient client;
    /** 是否由本对象创建 client（决定 close 时是否负责关闭）。 */
    private final boolean ownsClient;
    private final long maxResponseBytes;

    private volatile boolean closed;

    private OkHttpTransport(OkHttpClient client, boolean ownsClient, long maxResponseBytes) {
        this.client = client;
        this.ownsClient = ownsClient;
        this.maxResponseBytes = maxResponseBytes;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public TransportResponse execute(TransportRequest request) throws TransportException {
        if (closed) {
            throw new TransportException(TransportErrorType.NETWORK_ERROR,
                    "transport 已关闭，不得再执行请求");
        }
        OkHttpClient effective = applyPerRequestTimeouts(request);
        Request okRequest = toOkRequest(request);
        Response response;
        try {
            response = effective.newCall(okRequest).execute();
        } catch (SocketTimeoutException e) {
            throw TransportException.timeout("请求超时: " + request.getUrl(), e);
        } catch (InterruptedIOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw TransportException.interrupted("线程被中断: " + request.getUrl(), e);
            }
            throw TransportException.timeout("请求被中断式超时: " + request.getUrl(), e);
        } catch (IOException e) {
            throw TransportException.networkError("网络请求失败: " + request.getUrl(), e);
        }
        try {
            byte[] body = readBodyBounded(response);
            return new TransportResponse(response.code(), response.headers().toMultimap(), body);
        } catch (SocketTimeoutException e) {
            throw TransportException.timeout("读取响应超时: " + request.getUrl(), e);
        } catch (InterruptedIOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw TransportException.interrupted("读取响应被中断: " + request.getUrl(), e);
            }
            throw TransportException.timeout("读取响应中断式超时: " + request.getUrl(), e);
        } catch (IOException e) {
            throw TransportException.networkError("读取响应失败: " + request.getUrl(), e);
        } finally {
            response.close();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (ownsClient) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
            log.info("OkHttpTransport 已关闭（连接池与调度线程已释放）");
        } else {
            log.info("OkHttpTransport 已关闭（外部 client，不负责其生命周期）");
        }
    }

    private OkHttpClient applyPerRequestTimeouts(TransportRequest request) {
        if (request.getConnectTimeoutMs() == null && request.getReadTimeoutMs() == null
                && request.getWriteTimeoutMs() == null && request.getCallTimeoutMs() == null) {
            return client;
        }
        OkHttpClient.Builder b = client.newBuilder();
        if (request.getConnectTimeoutMs() != null) {
            b.connectTimeout(request.getConnectTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        if (request.getReadTimeoutMs() != null) {
            b.readTimeout(request.getReadTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        if (request.getWriteTimeoutMs() != null) {
            b.writeTimeout(request.getWriteTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        if (request.getCallTimeoutMs() != null) {
            b.callTimeout(request.getCallTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        return b.build();
    }

    private Request toOkRequest(TransportRequest request) {
        Request.Builder builder = new Request.Builder().url(request.getUrl());
        for (java.util.Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        byte[] body = request.getBody();
        if ("GET".equals(request.getMethod()) && body == null) {
            return builder.get().build();
        }
        MediaType contentType = null;
        String ct = request.getHeaders().get("Content-Type");
        if (ct != null) {
            MediaType parsed = MediaType.parse(ct);
            if (parsed != null) {
                contentType = parsed;
            }
        }
        RequestBody requestBody = body == null
                ? RequestBody.create(null, new byte[0])
                : RequestBody.create(contentType, body);
        return builder.method(request.getMethod(), requestBody).build();
    }

    /**
     * 有界读取响应体，防止异常超大响应导致 OOM。
     */
    private byte[] readBodyBounded(Response response) throws IOException {
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            return new byte[0];
        }
        BufferedSource source = responseBody.source();
        Buffer buffer = new Buffer();
        while (true) {
            long read = source.read(buffer, 8192);
            if (read == -1) {
                break;
            }
            if (buffer.size() > maxResponseBytes) {
                throw TransportException.responseTooLarge(maxResponseBytes);
            }
        }
        return buffer.readByteArray();
    }

    public static final class Builder {
        private Long connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        private Long readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
        private Long writeTimeoutMs = DEFAULT_WRITE_TIMEOUT_MS;
        private Long callTimeoutMs;
        private Integer maxIdleConnections;
        private Long keepAliveMs;
        private Integer maxRequests;
        private Integer maxRequestsPerHost;
        private Boolean followRedirects;
        private OkHttpClient externalClient;
        private Long maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;

        public Builder connectTimeoutMs(Long timeoutMs) {
            this.connectTimeoutMs = timeoutMs;
            return this;
        }

        public Builder readTimeoutMs(Long timeoutMs) {
            this.readTimeoutMs = timeoutMs;
            return this;
        }

        public Builder writeTimeoutMs(Long timeoutMs) {
            this.writeTimeoutMs = timeoutMs;
            return this;
        }

        public Builder callTimeoutMs(Long timeoutMs) {
            this.callTimeoutMs = timeoutMs;
            return this;
        }

        public Builder connectionPool(int maxIdleConnections, long keepAliveMs) {
            this.maxIdleConnections = maxIdleConnections;
            this.keepAliveMs = keepAliveMs;
            return this;
        }

        public Builder dispatcher(int maxRequests, int maxRequestsPerHost) {
            this.maxRequests = maxRequests;
            this.maxRequestsPerHost = maxRequestsPerHost;
            return this;
        }

        public Builder followRedirects(boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }

        /** 使用宿主提供的 OkHttp 实例时，close() 不会关闭它。 */
        public Builder client(OkHttpClient client) {
            this.externalClient = client;
            return this;
        }

        public Builder maxResponseBytes(long maxResponseBytes) {
            if (maxResponseBytes <= 0) {
                throw new IllegalArgumentException("maxResponseBytes 必须为正数");
            }
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        public OkHttpTransport build() {
            OkHttpClient client;
            boolean owns;
            if (externalClient != null) {
                client = externalClient;
                owns = false;
            } else {
                OkHttpClient.Builder b = new OkHttpClient.Builder();
                if (connectTimeoutMs != null) {
                    b.connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS);
                }
                if (readTimeoutMs != null) {
                    b.readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS);
                }
                if (writeTimeoutMs != null) {
                    b.writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS);
                }
                if (callTimeoutMs != null) {
                    b.callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS);
                }
                if (maxIdleConnections != null && keepAliveMs != null) {
                    b.connectionPool(new okhttp3.ConnectionPool(
                            maxIdleConnections, keepAliveMs, TimeUnit.MILLISECONDS));
                }
                if (maxRequests != null && maxRequestsPerHost != null) {
                    okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher();
                    dispatcher.setMaxRequests(maxRequests);
                    dispatcher.setMaxRequestsPerHost(maxRequestsPerHost);
                    b.dispatcher(dispatcher);
                }
                if (followRedirects != null) {
                    b.followRedirects(followRedirects);
                }
                client = b.build();
                owns = true;
            }
            return new OkHttpTransport(client, owns, maxResponseBytes);
        }
    }
}
