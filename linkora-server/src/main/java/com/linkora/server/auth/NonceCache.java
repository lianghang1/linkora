package com.linkora.server.auth;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Nonce 防重放缓存（有界）：容量超限时按先进先出粗略驱逐，
 * 记录仅在 2 × 时钟窗口内有效，过期条目访问时惰性清理。线程安全。
 */
final class NonceCache {

    private final long ttlMs;
    private final int capacity;
    private final ConcurrentHashMap<String, Long> nonces = new ConcurrentHashMap<String, Long>();

    NonceCache(long ttlMs, int capacity) {
        if (ttlMs < 1 || capacity < 1) {
            throw new IllegalArgumentException("nonce 缓存参数非法");
        }
        this.ttlMs = ttlMs;
        this.capacity = capacity;
    }

    /** 已见过返回 true；否则记录并返回 false。 */
    boolean seenOrRecord(String nonce, long now) {
        Long recordedAt = nonces.get(nonce);
        if (recordedAt != null && now - recordedAt <= ttlMs) {
            return true;
        }
        if (nonces.size() >= capacity) {
            evictExpired(now);
            if (nonces.size() >= capacity) {
                // 容量仍满：按无序方式驱逐一部分（安全性兜底优先于精确 LRU）
                int toRemove = capacity / 10 + 1;
                for (String key : nonces.keySet()) {
                    nonces.remove(key);
                    if (--toRemove <= 0) {
                        break;
                    }
                }
            }
        }
        nonces.put(nonce, now);
        return false;
    }

    int size() {
        return nonces.size();
    }

    private void evictExpired(long now) {
        for (java.util.Map.Entry<String, Long> entry : nonces.entrySet()) {
            if (now - entry.getValue() > ttlMs) {
                nonces.remove(entry.getKey(), entry.getValue());
            }
        }
    }
}
