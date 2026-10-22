package com.linkora.spring;

import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.server.LinkoraServer;
import com.linkora.server.auth.AllowAllClientAuthenticator;
import com.linkora.server.storage.jdbc.JdbcClientCursorRepository;
import com.linkora.server.storage.jdbc.JdbcClientRegistry;
import com.linkora.server.storage.jdbc.JdbcDeliveryRepository;
import com.linkora.server.storage.jdbc.JdbcMessageRepository;
import com.linkora.server.storage.jdbc.JdbcServerSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;

import javax.sql.DataSource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * 认证器 fail-fast 语义：缺认证器必须拒绝启动，除非显式允许匿名。
 *
 * @author lianghang
 */
public class LinkoraServerAssemblerSecurityTest {

    private static DataSource freshDataSource(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + "_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcServerSchema.migrate(ds);
        return ds;
    }

    @Test
    public void missingAuthenticatorFailsFastByDefault() {
        DataSource ds = freshDataSource("auth-failfast");
        try {
            LinkoraServerAssembler.build(null,
                    new JdbcClientRegistry(ds),
                    new JdbcMessageRepository(ds),
                    new JdbcDeliveryRepository(ds),
                    new JdbcClientCursorRepository(ds),
                    null, null, null);
            fail("缺少 ClientAuthenticator 时必须拒绝启动，而不是回退为放行全部请求");
        } catch (IllegalStateException e) {
            // 期望：错误信息必须指出怎么修（配置认证器或显式允许匿名）
            org.junit.Assert.assertTrue(e.getMessage().contains("allow-anonymous"));
        }
    }

    @Test
    public void anonymousFallbackRequiresExplicitOptIn() {
        DataSource ds = freshDataSource("auth-anonymous");
        ClientRegistry registry = new JdbcClientRegistry(ds);
        MessageRepository messages = new JdbcMessageRepository(ds);
        DeliveryRepository deliveries = new JdbcDeliveryRepository(ds);
        LinkoraServer server = LinkoraServerAssembler.build(null, registry, messages, deliveries,
                new JdbcClientCursorRepository(ds), null, null, null, true);
        try {
            assertNotNull(server);
            assertEquals(AllowAllClientAuthenticator.class, serverAuthenticatorType(server));
        } finally {
            server.close();
        }
    }

    /** 通过反射读取服务端持有的认证器类型，确认走的是显式匿名分支。 */
    private static Class<?> serverAuthenticatorType(LinkoraServer server) {
        try {
            java.lang.reflect.Field field = LinkoraServer.class.getDeclaredField("authenticator");
            field.setAccessible(true);
            return field.get(server).getClass();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
