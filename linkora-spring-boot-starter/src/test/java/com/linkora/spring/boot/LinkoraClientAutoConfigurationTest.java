package com.linkora.spring.boot;

import com.linkora.client.LinkoraClient;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.testkit.EmbeddedLinkoraServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端自动装配测试：enabled 门控、SQLite 默认存储、宿主 ClientStore 覆盖。
 * 用 testkit 内嵌服务端提供真实可达的对端，避免不可达地址造成重连风暴。
 */
class LinkoraClientAutoConfigurationTest {

    private static EmbeddedLinkoraServer embedded;

    @BeforeAll
    static void startEmbeddedServer() throws Exception {
        embedded = EmbeddedLinkoraServer.builder().registerClient("boot-client").build();
        embedded.start();
    }

    @AfterAll
    static void stopEmbeddedServer() {
        if (embedded != null) {
            embedded.close();
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class App {
    }

    /** 不可关闭的空实现（仅用于验证宿主 bean 覆盖路径）。 */
    @Configuration
    static class CustomStoreConfig {
        static final AtomicInteger CLOSED = new AtomicInteger();

        @Bean(destroyMethod = "close")
        ClientStore customClientStore() {
            return new NoopClientStore();
        }
    }

    static class NoopClientStore implements ClientStore {
        @Override public boolean saveOutbox(com.linkora.core.storage.client.OutboxEntry e) {
            return true;
        }
        @Override public java.util.Optional<com.linkora.core.storage.client.OutboxEntry>
                findOutbox(String messageId) { return java.util.Optional.empty(); }
        @Override public java.util.List<com.linkora.core.storage.client.OutboxEntry>
                findOutbox(com.linkora.core.message.OutboxStatus status,
                           long nextRetryBefore, int limit) { return java.util.Collections.emptyList(); }
        @Override public boolean updateOutboxStatus(
                com.linkora.core.storage.client.OutboxEntry updated,
                com.linkora.core.message.OutboxStatus expected) { return true; }
        @Override public int deleteOutboxAcceptedBefore(long deadline) { return 0; }
        @Override public boolean saveInbox(com.linkora.core.storage.client.InboxEntry e) {
            return true;
        }
        @Override public java.util.Optional<com.linkora.core.storage.client.InboxEntry>
                findInbox(String messageId) { return java.util.Optional.empty(); }
        @Override public java.util.List<com.linkora.core.storage.client.InboxEntry>
                findInboxPendingReceiveAck(int limit) { return java.util.Collections.emptyList(); }
        @Override public java.util.List<com.linkora.core.storage.client.InboxEntry>
                findInboxByProcessStatus(com.linkora.core.message.ProcessStatus status,
                                         long nextRetryBefore, int limit) { return java.util.Collections.emptyList(); }
        @Override public boolean updateInboxReceiveStatus(
                com.linkora.core.storage.client.InboxEntry updated,
                com.linkora.core.message.ReceiveStatus expected) { return true; }
        @Override public boolean updateInboxProcessStatus(
                com.linkora.core.storage.client.InboxEntry updated,
                com.linkora.core.message.ProcessStatus expected) { return true; }
        @Override public int deleteInboxProcessedBefore(long deadline) { return 0; }
        @Override public void saveMeta(String key, String value) { }
        @Override public java.util.Optional<String> getMeta(String key) {
            return java.util.Optional.empty();
        }
        public void close() { CustomStoreConfig.CLOSED.incrementAndGet(); }
    }

    private static ConfigurableApplicationContext runApp(String... extraProperties) {
        String[] props = new String[extraProperties.length + 1];
        props[0] = "--server.port=0";
        System.arraycopy(extraProperties, 0, props, 1, extraProperties.length);
        return new SpringApplicationBuilder(App.class).run(props);
    }

    private static String[] clientProperties() {
        return new String[] {
                "--linkora.client.enabled=true",
                "--linkora.client.server-url=" + embedded.baseUrl(),
                "--linkora.client.client-id=boot-client",
                "--linkora.client.db-path=target/boot-client-queue"};
    }

    @Test
    void clientDisabledByDefault() {
        ConfigurableApplicationContext context = runApp();
        try {
            assertEquals(0, context.getBeansOfType(LinkoraClient.class).size(),
                    "客户端默认不装配");
        } finally {
            context.close();
        }
    }

    @Test
    void clientAssembledWithSqliteStore() {
        ConfigurableApplicationContext context = runApp(clientProperties());
        try {
            context.getBean(LinkoraClient.class);
            assertTrue(context.getBean(ClientStore.class) instanceof SqliteClientStore);
        } finally {
            context.close();
        }
    }

    @Test
    void hostClientStoreOverridesSqlite() {
        String[] props = new String[] {
                "--server.port=0",
                "--linkora.client.enabled=true",
                "--linkora.client.server-url=" + embedded.baseUrl(),
                "--linkora.client.client-id=boot-client"};
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(App.class, CustomStoreConfig.class).run(props);
        try {
            ClientStore store = context.getBean(ClientStore.class);
            assertFalse(store instanceof SqliteClientStore,
                    "宿主自定义 ClientStore 生效时不应创建 SQLite 存储");
        } finally {
            context.close();
        }
        assertTrue(CustomStoreConfig.CLOSED.get() >= 1,
                "容器销毁时应关闭宿主自定义 ClientStore");
    }
}
