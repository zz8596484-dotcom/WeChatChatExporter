package com.chat.exporter;

import java.util.HashSet;
import java.util.Set;

/**
 * 消息去重器。
 * 通过指纹（时间+发送者+类型+内容）与前后邻近消息位置来避免滚动过程中重复写入。
 * 去重数据仅保存在内存中；会话结束后可通过导出记录持久化指纹。
 */
public class Deduplicator {
    // 已见指纹集合
    private final Set<String> seenFingerprints = new HashSet<>();
    // 最近一条消息指纹（用于检查前后位置重复）
    private String lastFingerprint = null;
    // 连续重复计数，防止同一气泡反复被抓取
    private int consecutiveDuplicates = 0;
    // 允许的最大连续重复次数（超过则可能滚动未生效，需判断是否到顶）
    public static final int MAX_CONSECUTIVE_DUP = 3;

    /** 是否为新消息；是则标记为已见并返回 true */
    public synchronized boolean isNew(ChatMessage msg) {
        if (msg == null) return false;
        String fp = msg.computeFingerprint();

        if (seenFingerprints.contains(fp)) {
            consecutiveDuplicates++;
            if (fp.equals(lastFingerprint)) {
                // 与上一条完全相同，属于同一气泡重复
                return false;
            }
            // 指纹出现过但与上一条不同，可能是滚动重访历史；保守起见仍拒绝
            return false;
        }

        seenFingerprints.add(fp);
        lastFingerprint = fp;
        consecutiveDuplicates = 0;
        return true;
    }

    /** 当滚动后一屏没有抓到任何新消息时调用，累计重复 */
    public synchronized void registerEmptyCycle() {
        consecutiveDuplicates++;
    }

    public synchronized void clearDuplicateCounter() {
        consecutiveDuplicates = 0;
    }

    /** 是否已达到连续重复上限（可能已滚动到聊天最顶部） */
    public synchronized boolean reachedDuplicateLimit() {
        return consecutiveDuplicates >= MAX_CONSECUTIVE_DUP;
    }

    public synchronized int size() {
        return seenFingerprints.size();
    }

    /** 会话开始前重置（保留历史指纹可用于跨会话去重，这里提供可选清除） */
    public synchronized void reset() {
        seenFingerprints.clear();
        lastFingerprint = null;
        consecutiveDuplicates = 0;
    }

    /** 检查是否已包含某指纹（跨会话去重用） */
    public synchronized boolean contains(String fingerprint) {
        return fingerprint != null && seenFingerprints.contains(fingerprint);
    }
}