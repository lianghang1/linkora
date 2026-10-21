package com.linkora.server.pipeline;

import com.linkora.core.message.LinkoraMessage;

/**
 * 上行消息业务侧监听器：客户端上报消息通过认证、校验并持久化后被回调。
 * 回调在摄入调用线程上同步执行，实现方应快速返回，耗时处理请自行异步化；
 * 抛出的异常会被摄入服务捕获并记录告警，不影响上行结果。
 *
 * @author lianghang
 */
public interface IngestListener {

    /** 收到一条已持久化的客户端上报消息。 */
    void onIngested(LinkoraMessage message);
}
