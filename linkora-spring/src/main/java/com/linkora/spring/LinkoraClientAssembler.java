package com.linkora.spring;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.core.storage.client.ClientStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

/**
 * LinkoraClient 组装核心：缺省 SQLite 存储创建、必填校验与 builder 装配的唯一实现。
 *
 * <p>供 Spring 宿主 {@link LinkoraClientConfiguration} 与 Boot starter
 * 自动配置共用，避免两套装配代码平行维护；本类不依赖任何 Spring 类型。</p>
 *
 * @author lianghang
 */
public final class LinkoraClientAssembler {

    private static final Logger log = LoggerFactory.getLogger(LinkoraClientAssembler.class);

    private LinkoraClientAssembler() {
    }

    /**
     * 创建缺省的 SQLite 本地队列存储（WAL + synchronous=FULL）。
     *
     * @param dbPath 数据库文件路径，空白视为配置缺失
     */
    public static ClientStore sqliteStore(String dbPath) {
        if (dbPath == null || dbPath.trim().isEmpty()) {
            throw new IllegalStateException("启用 linkora.client 后必须配置 linkora.client.db-path，"
                    + "或由宿主提供自定义 ClientStore bean");
        }
        String trimmed = dbPath.trim();
        log.info("客户端本地队列使用 SQLite: {}", trimmed);
        return new SqliteClientStore(Paths.get(trimmed));
    }

    /**
     * 组装 {@link LinkoraClient}：server-url 与 client-id 必填，缺失时启动即失败；
     * 调优项为 null 时沿用 SDK 默认值。
     */
    public static LinkoraClient build(String serverUrl,
                                      String clientId,
                                      ClientStore clientStore,
                                      Long senderIntervalMs,
                                      Long recoveryIntervalMs,
                                      Long idleTimeoutMs) {
        if (serverUrl == null || serverUrl.trim().isEmpty()) {
            throw new IllegalStateException("启用 linkora.client 后必须配置 linkora.client.server-url");
        }
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalStateException("启用 linkora.client 后必须配置 linkora.client.client-id");
        }
        ClientConfig.Builder config = ClientConfig.builder();
        if (senderIntervalMs != null) {
            config.senderIntervalMs(senderIntervalMs);
        }
        if (recoveryIntervalMs != null) {
            config.recoveryIntervalMs(recoveryIntervalMs);
        }
        if (idleTimeoutMs != null) {
            config.idleTimeoutMs(idleTimeoutMs);
        }
        log.info("LinkoraClient 已装配（clientId: {}，serverUrl: {}）", clientId.trim(), serverUrl.trim());
        return LinkoraClient.builder()
                .serverUrl(serverUrl.trim())
                .clientId(clientId.trim())
                .clientStore(clientStore)
                .config(config.build())
                .build();
    }
}
