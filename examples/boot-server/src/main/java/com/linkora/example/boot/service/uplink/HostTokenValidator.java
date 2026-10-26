package com.linkora.example.boot.service.uplink;

import com.linkora.server.auth.TokenValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 宿主 Token 校验器（演示）：真实项目中这里回调宿主已有的账号体系
 * （如查询登录会话表、调用认证服务 /token/introspect、校验 JWT 签名等）。
 *
 * <p>演示约定：token 形如 {@code grp-<clientId>-<日期>}，
 * 例如 grp-demo-client-2026；校验逻辑就是"格式 + 当年"——
 * 真实场景替换为宿主自己的校验实现即可。</p>
 *
 * @author lianghang
 */
@Service
public class HostTokenValidator implements TokenValidator {

    private static final Logger log = LoggerFactory.getLogger(HostTokenValidator.class);

    @Override
    public boolean validate(String clientId, String token) {
        boolean ok = token != null && token.startsWith("grp-" + clientId + "-");
        if (!ok) {
            log.warn("token 校验未通过: clientId={}", clientId);
        }
        return ok;
    }
}
