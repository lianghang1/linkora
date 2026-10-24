package com.linkora.adminconsole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkora.core.admin.ClientCommands;
import com.linkora.core.message.IllegalStateTransitionException;
import com.linkora.core.message.MessageState;
import com.linkora.serialize.jackson.LinkoraJsonUtil;
import com.linkora.server.LinkoraServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维控制台入口：同一 Servlet 服务单页控制台与全部 REST 端点。
 *
 * <p>GET {@code <prefix>/} 返回内置单页控制台（静态资源
 * {@code /linkora-admin-console/index.html}，无构建、无外部 CDN）；
 * {@code <prefix>/api/**} 为控制台专用 JSON 端点，直接调用
 * {@link LinkoraServer} 的管理/观测方法。所有写操作记录 reason 留痕。</p>
 *
 * <p>鉴权由 {@link AdminConsoleFilter} 承担（Bearer Token，恒定时间比较）。
 * 错误统一返回 {@code {"error": "..."}} + 400/404/500。</p>
 *
 * @author lianghang
 */
public class AdminConsoleServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(AdminConsoleServlet.class);

    private static final String INDEX_RESOURCE = "/linkora-admin-console/index.html";
    private static final long MAX_BODY_BYTES = 64 * 1024;

    private final LinkoraServer server;
    private final ObjectMapper mapper = LinkoraJsonUtil.getObjectMapper();

    public AdminConsoleServlet(LinkoraServer server) {
        if (server == null) {
            throw new IllegalArgumentException("server 不能为 null");
        }
        this.server = server;
    }

    // ---------------- GET：页面 + 查询端点 ----------------

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path == null || "/".equals(path) || "/index.html".equals(path)) {
            serveIndex(resp);
            return;
        }
        try {
            handleGet(path, req, resp);
        } catch (IllegalArgumentException e) {
            sendError(resp, 400, e.getMessage());
        } catch (Exception e) {
            log.error("控制台查询端点异常: path={}", path, e);
            sendError(resp, 500, "内部错误: " + e.getMessage());
        }
    }

    private void handleGet(String path, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String[] seg = path.split("/");
        // seg[0] 为空（路径以 / 开头）

        if ("/api/overview".equals(path)) {
            ArrayNode clients = mapper.valueToTree(server.getClientOverviews());
            ObjectNode stats = mapper.valueToTree(server.getDailyStats(7));
            ObjectNode body = mapper.createObjectNode();
            body.set("clients", clients);
            body.set("publishedPerDay", stats.get("publishedPerDay"));
            body.set("deliveryStateDistribution", stats.get("deliveryStateDistribution"));
            writeJson(resp, 200, body);
            return;
        }
        if ("/api/clients".equals(path)) {
            writeJson(resp, 200, server.getClientOverviews());
            return;
        }
        if (seg.length == 5 && "api".equals(seg[1]) && "clients".equals(seg[2])
                && "unfinished".equals(seg[4])) {
            writeJson(resp, 200, server.listUnfinishedDeliveries(seg[3], intParam(req, "limit", 100)));
            return;
        }
        if ("/api/messages".equals(path)) {
            writeJson(resp, 200, server.listMessages(
                    req.getParameter("clientId"),
                    req.getParameter("type"),
                    longParam(req, "begin"),
                    longParam(req, "end"),
                    intParam(req, "page", 1),
                    intParam(req, "size", 20)));
            return;
        }
        if (seg.length == 4 && "api".equals(seg[1]) && "messages".equals(seg[2])) {
            String messageId = seg[3];
            Object detail = server.getMessageDetail(messageId);
            if (detail == null) {
                sendError(resp, 404, "消息不存在: " + messageId);
            } else {
                writeJson(resp, 200, detail);
            }
            return;
        }
        if ("/api/dead-letters".equals(path)) {
            String clientId = req.getParameter("clientId");
            if (clientId == null || clientId.trim().isEmpty()) {
                sendError(resp, 400, "clientId 不能为空");
            } else {
                writeJson(resp, 200, server.listDeadLetters(clientId, intParam(req, "limit", 100)));
            }
            return;
        }
        if ("/api/stats".equals(path)) {
            writeJson(resp, 200, server.getDailyStats(intParam(req, "days", 7)));
            return;
        }
        if (seg.length == 4 && "api".equals(seg[1]) && "command-result".equals(seg[2])) {
            String queryId = seg[3];
            ObjectNode body = mapper.createObjectNode();
            body.put("queryId", queryId);
            body.put("result", server.getCommandResult(queryId));
            writeJson(resp, 200, body);
            return;
        }
        if ("/api/seq-blocked".equals(path)) {
            String clientId = req.getParameter("clientId");
            String messageId = req.getParameter("messageId");
            if (clientId == null || clientId.trim().isEmpty()
                    || messageId == null || messageId.trim().isEmpty()) {
                sendError(resp, 400, "clientId 与 messageId 不能为空");
            } else {
                ObjectNode body = mapper.createObjectNode();
                body.put("blocked", server.countBlockedBySequenceKey(clientId, messageId));
                writeJson(resp, 200, body);
            }
            return;
        }
        sendError(resp, 404, "未知端点: " + path);
    }

    // ---------------- POST：写操作端点 ----------------

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        try {
            JsonNode body = readBody(req);
            String clientId = text(body, "clientId");
            String messageId = text(body, "messageId");
            String reason = text(body, "reason");

            if ("/api/actions/retry-dead-letter".equals(path)) {
                audit("重放死信", clientId, messageId, reason);
                writeJson(resp, 200, result(server.retryDeadLetter(clientId, messageId)));
                return;
            }
            if ("/api/actions/skip".equals(path)) {
                audit("跳过消息", clientId, messageId, reason);
                writeJson(resp, 200, result(server.skipMessage(clientId, messageId)));
                return;
            }
            if ("/api/actions/batch-skip".equals(path)) {
                audit("批量跳过死信", clientId, null, reason);
                ObjectNode out = mapper.createObjectNode();
                out.put("count", server.skipAllDeadLetters(clientId, intOf(body, "limit", 100)));
                writeJson(resp, 200, out);
                return;
            }
            if ("/api/actions/batch-retry".equals(path)) {
                audit("批量重放死信", clientId, null, reason);
                ObjectNode out = mapper.createObjectNode();
                out.put("count", server.retryAllDeadLetters(clientId, intOf(body, "limit", 100)));
                writeJson(resp, 200, out);
                return;
            }
            if ("/api/actions/disconnect".equals(path)) {
                audit("断开连接", clientId, null, reason);
                ObjectNode out = mapper.createObjectNode();
                out.put("count", server.disconnectClient(clientId));
                writeJson(resp, 200, out);
                return;
            }
            if ("/api/actions/enable".equals(path)) {
                audit("启用客户端", clientId, null, reason);
                writeJson(resp, 200, result(server.enableClient(clientId)));
                return;
            }
            if ("/api/actions/disable".equals(path)) {
                audit("禁用客户端", clientId, null, reason);
                writeJson(resp, 200, result(server.disableClient(clientId)));
                return;
            }
            if ("/api/actions/register-client".equals(path)) {
                String newId = requireText(body, "newClientId");
                audit("注册客户端", newId, null, reason);
                writeJson(resp, 200, result(server.registerClient(
                        newId, body.path("enabled").asBoolean(true))));
                return;
            }
            if ("/api/actions/remove-client".equals(path)) {
                audit("注销客户端", clientId, null, reason);
                writeJson(resp, 200, result(server.removeClient(clientId)));
                return;
            }
            if ("/api/actions/force-transition".equals(path)) {
                MessageState target = MessageState.valueOf(requireText(body, "target"));
                audit("状态变更→" + target, clientId, messageId, reason);
                writeJson(resp, 200, result(server.forceTransition(
                        clientId, messageId, target, reason)));
                return;
            }
            if ("/api/actions/client-command".equals(path)) {
                String command = requireText(body, "command");
                audit("下发指令" + command, clientId, null, reason);
                ObjectNode out = mapper.createObjectNode();
                if (ClientCommands.QUERY_OUTBOX.equals(command)) {
                    String queryId = server.queryClientOutbox(clientId,
                            text(body, "status"), intOf(body, "limit", 50));
                    out.put("queryId", queryId);
                } else {
                    Map<String, Object> params = new LinkedHashMap<>();
                    JsonNode paramsNode = body.get("params");
                    if (paramsNode != null && paramsNode.isObject()) {
                        paramsNode.fields().forEachRemaining(
                                e -> params.put(e.getKey(), e.getValue().asText()));
                    }
                    out.put("delivered", server.sendClientCommand(clientId, command, params));
                }
                writeJson(resp, 200, out);
                return;
            }
            sendError(resp, 404, "未知端点: " + path);
        } catch (IllegalStateException e) {
            sendError(resp, 400, e.getMessage());
        } catch (IllegalArgumentException e) {
            sendError(resp, 400, e.getMessage());
        } catch (IllegalStateTransitionException e) {
            sendError(resp, 400, e.getMessage());
        } catch (Exception e) {
            log.error("控制台写操作端点异常: path={}", path, e);
            sendError(resp, 500, "内部错误: " + e.getMessage());
        }
    }

    // ---------------- 内部工具 ----------------

    /** 写操作留痕：操作 + 目标 + 原因统一进服务日志。 */
    private static void audit(String action, String clientId, String messageId, String reason) {
        log.warn("控制台操作: action={}, clientId={}, messageId={}, reason={}",
                action, clientId, messageId, reason == null || reason.isEmpty() ? "（未填写）" : reason);
    }

    private ObjectNode result(boolean ok) {
        ObjectNode node = mapper.createObjectNode();
        node.put("ok", ok);
        return node;
    }

    private JsonNode readBody(HttpServletRequest req) throws IOException {
        InputStream in = req.getInputStream();
        byte[] buffer = new byte[(int) MAX_BODY_BYTES + 1];
        int total = 0, read;
        while ((read = in.read(buffer, total, buffer.length - total)) > 0) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("请求体过大");
            }
        }
        if (total == 0) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(buffer, 0, total);
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String requireText(JsonNode body, String field) {
        String value = text(body, field);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static int intOf(JsonNode body, String field, int def) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? def : node.asInt(def);
    }

    private static int intParam(HttpServletRequest req, String name, int def) {
        String value = req.getParameter(name);
        return value == null || value.isEmpty() ? def : Integer.parseInt(value);
    }

    private static Long longParam(HttpServletRequest req, String name) {
        String value = req.getParameter(name);
        return value == null || value.isEmpty() ? null : Long.valueOf(value);
    }

    private void serveIndex(HttpServletResponse resp) throws IOException {
        InputStream in = AdminConsoleServlet.class.getResourceAsStream(INDEX_RESOURCE);
        if (in == null) {
            sendError(resp, 500, "控制台页面资源缺失: " + INDEX_RESOURCE);
            return;
        }
        resp.setStatus(200);
        resp.setContentType("text/html;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        OutputStream out = resp.getOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(mapper.writeValueAsString(body));
    }

    private void sendError(HttpServletResponse resp, int status, String message) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message == null ? "" : message);
        writeJson(resp, status, node);
    }
}
