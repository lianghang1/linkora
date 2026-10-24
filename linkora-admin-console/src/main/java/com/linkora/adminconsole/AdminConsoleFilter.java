package com.linkora.adminconsole;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 控制台鉴权过滤器：校验 {@code Authorization: Bearer <token>}。
 *
 * <p>令牌独立于设备 HMAC 体系（设备凭据是机器身份，控制台令牌是人的入口）。
 * 比较使用恒定时间算法，避免时序侧信道。未携带或错误令牌一律 401，
 * 页面端据此跳回令牌输入页。</p>
 *
 * @author lianghang
 */
public class AdminConsoleFilter implements Filter {

    private final byte[] expectedToken;

    public AdminConsoleFilter(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("控制台令牌不能为空");
        }
        this.expectedToken = token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // 无需初始化
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String header = req.getHeader("Authorization");
        boolean authorized = false;
        if (header != null && header.startsWith("Bearer ")) {
            byte[] provided = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
            authorized = MessageDigest.isEqual(expectedToken, provided);
        }
        if (!authorized) {
            resp.setStatus(401);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"未授权：令牌缺失或错误\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        // 无需销毁逻辑
    }
}
