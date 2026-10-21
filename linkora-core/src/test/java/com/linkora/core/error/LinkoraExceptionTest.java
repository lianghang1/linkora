package com.linkora.core.error;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * 验证统一异常对错误码与消息 ID 的携带行为，以及空错误码被拒绝的防御逻辑。
 *
 * @author lianghang
 */
public class LinkoraExceptionTest {

    @Test
    public void carriesErrorCodeAndMessageId() {
        LinkoraException ex = new LinkoraException(
                LinkoraErrorCode.AUTH_FAILED, "认证失败", null, "m-1");

        assertEquals(LinkoraErrorCode.AUTH_FAILED, ex.getErrorCode());
        assertEquals("m-1", ex.getMessageId());
        assertEquals("认证失败", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    public void nullErrorCodeRejected() {
        try {
            new LinkoraException(null, "boom");
            fail("预期抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期行为
        }
    }

    @Test
    public void errorCodeValuesStable() {
        assertEquals("AUTH_FAILED", LinkoraErrorCode.AUTH_FAILED.getCode());
        assertEquals("DUPLICATE_MESSAGE", LinkoraErrorCode.DUPLICATE_MESSAGE.getCode());
        assertEquals("RETRY_EXHAUSTED", LinkoraErrorCode.RETRY_EXHAUSTED.getCode());
    }
}
