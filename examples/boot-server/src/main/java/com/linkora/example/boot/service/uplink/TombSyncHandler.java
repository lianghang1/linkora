package com.linkora.example.boot.service.uplink;

import com.linkora.core.message.LinkoraMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.linkora.serialize.jackson.JsonUplinkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 墓位数据同步处理器（线上侧，0.6.0）：接收数据同步客户端的增量/分片报文，
 * 按业务主键 upsert 内存视图（真实场景应落业务库）。
 *
 * <p>payload 形态见 {@link com.linkora.client.sync.DataSync}：</p>
 * <ul>
 *   <li>变更事件：{@code {"syncKey","op":"ADD|UPDATE|DELETE","key","row"}}；</li>
 *   <li>全量分片：{@code {"syncKey","chunk":{"seq","total","events":[事件...]}}}。</li>
 * </ul>
 *
 * @author lianghang
 */
@Service
public class TombSyncHandler extends JsonUplinkHandler<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(TombSyncHandler.class);

    public static final String TOPIC_TOMB_SYNC = "tomb-sync";

    /** 线上视图：tomb_code → 行（演示用内存，真实场景落业务库）。 */
    private final Map<String, Map<String, Object>> view =
            new ConcurrentHashMap<String, Map<String, Object>>();

    public TombSyncHandler() {
        super(new TypeReference<Map<String, Object>>() { });
    }

    @Override
    public String messageType() {
        return TOPIC_TOMB_SYNC;
    }

    @Override
    protected void handle(LinkoraMessage message, Map<String, Object> payload) {
        Object chunk = payload.get("chunk");
        if (chunk instanceof Map) {
            // 全量分片：按 seq 顺序应用片内事件
            Map<?, ?> meta = (Map<?, ?>) chunk;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events =
                    (List<Map<String, Object>>) meta.get("events");
            log.info("墓位同步分片 {}/{}，{} 条事件", meta.get("seq"), meta.get("total"),
                    events == null ? 0 : events.size());
            if (events != null) {
                for (Map<String, Object> event : events) {
                    applyEvent(event);
                }
            }
            return;
        }
        applyEvent(payload);
    }

    /** 应用单条变更事件。 */
    @SuppressWarnings("unchecked")
    private void applyEvent(Map<String, Object> event) {
        String op = String.valueOf(event.get("op"));
        String key = String.valueOf(event.get("key"));
        if ("DELETE".equals(op)) {
            view.remove(key);
        } else {
            Object row = event.get("row");
            if (row instanceof Map) {
                view.put(key, (Map<String, Object>) row);
            }
        }
        log.info("[上行-墓位同步] {} key={}（线上视图现 {} 行）", op, key, view.size());
    }


    /** 线上视图快照（供查询端点演示对账）。 */
    public Map<String, Map<String, Object>> getView() {
        return new LinkedHashMap<String, Map<String, Object>>(view);
    }
}
