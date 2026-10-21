package com.linkora.core.message;

/**
 * ACK 语义类型。语义不可混淆：
 * RECEIVED 只代表接收方已持久化，PROCESSED 才代表业务处理成功。
 * ACK 接口必须幂等，支持重复提交。
 *
 * @author lianghang
 */
public enum AckType {

    /** 接收方已将消息持久化（客户端 SQLite 提交成功后发送）。 */
    RECEIVED,

    /** 业务处理成功。 */
    PROCESSED,

    /** 业务处理失败，附带可诊断的错误码与信息。 */
    FAILED
}
