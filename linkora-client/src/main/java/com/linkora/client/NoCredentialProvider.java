package com.linkora.client;

import java.util.Collections;
import java.util.Map;

/** 匿名凭据：不附加任何认证头（本地开发/测试用）。 */
public final class NoCredentialProvider implements CredentialProvider {

    @Override
    public Map<String, String> authHeaders(RequestMaterial material) {
        return Collections.emptyMap();
    }
}
