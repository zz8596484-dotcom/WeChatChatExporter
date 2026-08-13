package com.chat.exporter;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 微信聊天记录采集无障碍服务。
 * 仅读取用户在微信聊天窗口中对自身可见的界面内容：
 *  - 通过 AccessibilityNodeInfo 获取可访问的文本节点
 *  - 通过气泡在屏幕上的水平位置判断发送者（左=对方，右=我）
 *  - 通过气泡垂直位置与最近时间戳行判断归属
 *  - 自动向上缓慢滚动、自动去重
 *  - 全部数据仅保存在内存并在结束时导出到本地文件
 *
 * 不读取微信数据库、不读取私有文件、不提取密钥、不Root、不Hook。
 */
public class WeChatAccessibilityService extends AccessibilityService {
    private static final String TAG = "WeChatCapture";
    public static final String PKG_WECHAT = "com.tencent.mm";

    // 采集状态
    public static final int STATE_IDLE = 0;
    public static final int STATE_CAPTURING = 1;
    public static final int STATE_PAUSED = 2;

    private static WeChatAccessibilityService instance;

    // 状态
    private AtomicInteger state = new AtomicInteger(STATE_IDLE);
    private AtomicBoolean serviceConnected = new AtomicBoolean(false);

    // 采集核心
    private ChatExporter exporter;
    private Deduplicator deduplicator;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final Object messageLock = new Object();

    // 定时滚动
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable scrollTask;
    private static final long SCROLL_INTERVAL_MS = 1400; // 缓慢滚动

    // 消息位置追踪
    private String lastFingerprint = "";
    private final AtomicInteger doneCount = new AtomicInteger(0);

    // 接触到的联系人名（从标题栏获取）
    private volatile String contactName = "";

    // 最近一次在屏幕上看到的时间分隔行（yyy-MM-dd HH:mm 或 HH:mm）
    // 用于把屏幕上的时间行归属给其下方临近的消息，尽量还原真实时间线
    private volatile String lastSeenTime = "";

    // 监听器，用于把状态变化/日志推送给 UI
    public interface Listener {
        void onLog(String line);
        void onStateChanged(int state, int count);
        void onFinished(String summary);
    }
    private Listener listener;

    public static WeChatAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        serviceConnected.set(true);
        // 默认导出目录
        exporter = new ChatExporter(this, ConfigUtils.defaultOutputDir(this));
        deduplicator = new Deduplicator();
        log("无障碍服务已连接 ✅");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!serviceConnected.get()) return;
        if (event == null) return;
        // 只在采集中处理事件
        if (state.get() == STATE_CAPTURING) {
            // 触发一次内容抓取（防抖由 deduplicator 保证）
            // 但主要抓取在滚动周期中执行，这里仅做轻量判断
        }
    }

    @Override
    public void onInterrupt() {
        log("服务被中断");
    }

    @Override
    public void onDestroy() {
        instance = null;
        serviceConnected.set(false);
        stopScroll();
        super.onDestroy();
    }

    // ============= 对外控制接口 =============

    /** 开始采集（先清空本次会话） */
    public void startCapture() {
        if (!isInWeChatCurrent()) {
            log("⚠️ 未检测到微信聊天窗口。请先打开要采集的微信聊天对话。");
            return;
        }
        // 清空会话
        synchronized (messageLock) {
            messages.clear();
        }
        deduplicator.reset();
        doneCount.set(0);
        lastSeenTime = ""; // 重置时间追踪，避免把上次会话的时间归属给本次
        setState(STATE_CAPTURING);
        log("▶️ 开始采集。将缓慢向上滚动聊天记录……");
        startScroll();
    }

    /** 恢复采集（保留已采集数据） */
    public void resumeCapture() {
        setState(STATE_CAPTURING);
        log("▶️ 继续采集……");
        startScroll();
    }

    /** 暂停采集 */
    public void pauseCapture() {
        stopScroll();
        setState(STATE_PAUSED);
        int cnt = doneCount.get();
        if (exporter != null) {
            exporter.saveState(contactName, cnt, lastFingerprint);
        }
        log("⏸ 已暂停。目前共采集 " + cnt + " 条。进度已保存。");
    }

    /** 结束并导出 */
    public void finishAndExport() {
        stopScroll();
        if (exporter == null) return;

        // 使用最新的自定义输出目录（重建 exporter）
        exporter = new ChatExporter(this, ConfigUtils.getOutputDir(this));

        String baseName = "chat";
        if (contactName != null && !contactName.trim().isEmpty()) {
            // 简单清洗，避免文件名非法字符
            String cn = contactName.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
            baseName = cn + "_chat";
        }

        List<ChatMessage> snapshot;
        synchronized (messageLock) {
            snapshot = new ArrayList<>(messages);
        }
        String summary = exporter.export(snapshot, baseName);
        exporter.saveState(contactName, snapshot.size(), lastFingerprint);
        log("✅ 导出完成。" + summary);
        setState(STATE_IDLE);
        doneCount.set(snapshot.size());
        if (listener != null) {
            listener.onFinished(summary);
            listener.onStateChanged(STATE_IDLE, snapshot.size());
        }
    }

    // ============= 滚动采集引擎 =============

    private void startScroll() {
        stopScroll();
        scrollTask = new Runnable() {
            @Override
            public void run() {
                if (state.get() != STATE_CAPTURING) return;
                captureCurrentFrame();
                // 判断是否需要继续滚动（未到顶）
                if (deduplicator.reachedDuplicateLimit() || isAtTop()) {
                    log("🛑 已滚动到聊天记录顶部，停止。");
                    pauseCapture();  // 自动暂停（可继续收集时序部分）
                    return;
                }
                scrollUp(moderateAmount());
                handler.postDelayed(this, SCROLL_INTERVAL_MS);
            }
        };
        handler.postDelayed(scrollTask, 300);
    }

    private void stopScroll() {
        if (scrollTask != null) {
            handler.removeCallbacks(scrollTask);
            scrollTask = null;
        }
    }

    /** 获取当前屏幕的聊天消息并加入列表（含去重） */
    private void captureCurrentFrame() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            log("⚠️ 无法获取界面节点（可能不是微信或权限受限）");
            return;
        }

        // 采集当前屏幕上的消息节点
        List<ChatMessage> found = new ArrayList<>();
        collectItems(root, found);

        // 更新联系人名（从标题栏）
        detectContactName(root);

        int newCount = 0;
        for (ChatMessage cand : found) {
            if (deduplicator.isNew(cand)) {
                synchronized (messageLock) {
                    messages.add(cand);
                }
                newCount++;
                lastFingerprint = cand.fingerprint;
            }
        }

        if (newCount == 0) {
            deduplicator.registerEmptyCycle();
        } else {
            deduplicator.clearDuplicateCounter();
            doneCount.addAndGet(newCount);
        }

        if (listener != null) {
            listener.onStateChanged(state.get(), doneCount.get());
        }

        root.recycle();
    }

    /**
     * 递归遍历节点，提取聊天气泡。
     * 判断规则基于气泡在屏幕上的水平位置：左侧→对方，右侧→我。
     */
    private void collectItems(AccessibilityNodeInfo node, List<ChatMessage> out) {
        if (node == null) return;

        // 尝试识别消息内容节点
        CharSequence text = node.getText();
        String nodeText = text == null ? null : text.toString();
        String contentDesc = node.getContentDescription() == null ? null : node.getContentDescription().toString();

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        // 判断是否是消息气泡：有可见文本或内容描述
        boolean hasVisibleText = nodeText != null && !nodeText.trim().isEmpty() && bounds.width() > 20 && bounds.height() > 10;
        boolean hasContentDesc = contentDesc != null && !contentDesc.trim().isEmpty() && bounds.width() > 20 && bounds.height() > 10;

        // 排除非气泡：标题栏、输入框、按钮、菜单
        if ((hasVisibleText || hasContentDesc) && !isJunkNode(node, nodeText, contentDesc)) {
            // 识别时间分隔行（如 "14:30" 或 "2026-08-13 22:14"）：
            // 不采集为消息，仅更新 lastSeenTime 供其后消息做时间归属
            if (nodeText != null && isTimeRow(nodeText.trim())) {
                lastSeenTime = nodeText.trim();
                return; // 时间行通常无子节点，直接返回
            }

            ChatMessage msg = new ChatMessage();
            // 发送者：左=对方，右=我
            int centerX = bounds.centerX();
            int screenW = getResources().getDisplayMetrics().widthPixels;
            msg.sender = (centerX < screenW / 2) ? ChatMessage.SENDER_THEM : ChatMessage.SENDER_ME;

            // 类型与内容
            resolveTypeAndContent(node, msg, nodeText, contentDesc);

            // 时间：尽量从附近的"xx:xx"节点获取（本节点内或兄弟）
            msg.time = findTimeNear(node, bounds);

            // 内容如果是描述型（如图片），保留占位
            msg.computeFingerprint();
            out.add(msg);
        }

        // 递归子节点
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectItems(child, out);
                child.recycle();
            }
        }
    }

    /** 判断是否是无关节点（标题、输入框、按钮、菜单等） */
    private boolean isJunkNode(AccessibilityNodeInfo node, String text, String contentDesc) {
        String className = node.getClassName() == null ? "" : node.getClassName().toString();

        // 输入框
        if (className.contains("EditText")) return true;
        // 按钮、菜单项
        if (className.contains("Button")) return true;
        if (className.contains("MenuItem")) return true;
        // 标题栏文本（屏幕上部）
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        if (r.top < 100 && r.bottom < 180) return true; // 顶部标题区，通常为状态栏/标题

        if (contentDesc != null) {
            // 微信常见按钮的描述
            if (contentDesc.contains("更多") || contentDesc.contains("返回") ||
                contentDesc.contains("搜索") || contentDesc.contains("设置")) return true;
        }
        return false;
    }

    /** 识别消息类型与内容 */
    private void resolveTypeAndContent(AccessibilityNodeInfo node, ChatMessage msg,
                                       String text, String contentDesc) {
        String t = text;
        String cd = contentDesc;

        if (cd != null && !cd.trim().isEmpty() && (t == null || t.trim().isEmpty())) {
            // 内容描述节点：判断类型
            String lower = cd.toLowerCase();
            if (cd.contains("[图片]") || cd.contains("图片") || lower.contains("image")) {
                msg.type = ChatMessage.TYPE_IMAGE; msg.content = "[图片]";
            } else if (cd.contains("[视频]") || lower.contains("video")) {
                msg.type = ChatMessage.TYPE_VIDEO; msg.content = "[视频]";
            } else if (cd.contains("[语音]") || lower.contains("voice") || cd.contains("长按")) {
                msg.type = ChatMessage.TYPE_VOICE; msg.content = "[语音]";
            } else if (cd.contains("[动画表情]") || lower.contains("sticker") || cd.contains("表情")) {
                msg.type = ChatMessage.TYPE_EMOJI; msg.content = "[表情包]";
            } else if (cd.contains("[红包]") || cd.contains("红包")) {
                msg.type = ChatMessage.TYPE_RED_PACKET; msg.content = cd;
            } else if (cd.contains("转账")) {
                msg.type = ChatMessage.TYPE_TRANSFER; msg.content = cd;
            } else if (cd.contains("通话") || cd.contains("语音通话") || cd.contains("视频通话")) {
                msg.type = ChatMessage.TYPE_CALL; msg.content = cd;
            } else if (cd.contains("撤回")) {
                msg.type = ChatMessage.TYPE_RECALL; msg.content = cd;
            } else if (cd.contains("文件")) {
                msg.type = ChatMessage.TYPE_FILE; msg.content = "[文件] " + cd;
            } else if (cd.contains("[链接]") || lower.contains("http")) {
                msg.type = ChatMessage.TYPE_LINK; msg.content = cd;
            } else if (cd.contains("位置")) {
                msg.type = ChatMessage.TYPE_LOCATION; msg.content = "[位置]";
            } else {
                // 引用回复等
                if (cd.contains("引用")) {
                    msg.type = ChatMessage.TYPE_QUOTE; msg.content = cd;
                } else {
                    msg.type = ChatMessage.TYPE_UNKNOWN; msg.content = cd;
                }
            }
            return;
        }

        // 有纯文本：判断系统提示等
        if (t != null) {
            String trimmed = t.trim();
            if (isTimeRow(trimmed)) {
                // 时间分隔行——正常流程已在 collectItems 中过滤并更新 lastSeenTime，
                // 这里作为防御分支，不作为消息采集（不设类型，直接被丢弃）
                msg.type = ChatMessage.TYPE_SYSTEM; msg.content = trimmed; return;
            }
            // 撤回提示/系统消息特征
            if (trimmed.contains("撤回了一条消息")) {
                msg.type = ChatMessage.TYPE_RECALL; msg.content = trimmed; return;
            }
            if (trimmed.contains("已收款") || trimmed.contains("转账")) {
                msg.type = ChatMessage.TYPE_TRANSFER; msg.content = trimmed; return;
            }
            // 引用回复特征："引用 xxx" 或包含'“”'
            if (trimmed.contains("引用") || trimmed.contains("回复")) {
                msg.type = ChatMessage.TYPE_QUOTE; msg.content = trimmed; return;
            }

            // 图片/视频文本占位（微信可能用文本表示）
            if (trimmed.equals("[图片]")) { msg.type = ChatMessage.TYPE_IMAGE; msg.content = "[图片]"; return; }
            if (trimmed.equals("[视频]")) { msg.type = ChatMessage.TYPE_VIDEO; msg.content = "[视频]"; return; }
            if (trimmed.equals("[语音]")) { msg.type = ChatMessage.TYPE_VOICE; msg.content = "[语音]"; return; }
            if (trimmed.equals("[表情]") || trimmed.startsWith("[") && trimmed.endsWith("]")) {
                msg.type = ChatMessage.TYPE_EMOJI; msg.content = trimmed; return;
            }

            // 普通文字消息
            msg.type = ChatMessage.TYPE_TEXT;
            msg.content = trimmed;
        } else {
            // 有子节点但没有文本——可能是图片/视频容器
            // 此时交给递归的子节点处理
            msg.type = ChatMessage.TYPE_UNKNOWN;
            msg.content = "[内容]";
        }
    }

    /** 尽量从节点附近找时间戳（xx:xx 或 yyyy-MM-dd xx:xx） */
    private String findTimeNear(AccessibilityNodeInfo node, Rect bounds) {
        // 1. 本节点自身内容含时间（极少见，气泡通常不带时间）
        String txt = node.getText() != null ? node.getText().toString() : "";
        if (txt.matches(".*\\d{1,2}:\\d{2}.*")) {
            String t = extractTime(txt);
            if (t != null) return normalizeTime(t);
        }
        // 2. 优先使用最近一次途经的时间分隔行（还原真实时间线，最重要）
        if (lastSeenTime != null && !lastSeenTime.isEmpty()) {
            return normalizeTime(lastSeenTime);
        }
        // 3. 兜底：采集时刻（找不到任何时间行时）
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
    }

    /** 判断文本是否为时间分隔行（"HH:mm" 或 "yyyy-MM-dd HH:mm"） */
    private boolean isTimeRow(String trimmed) {
        if (trimmed == null || trimmed.isEmpty()) return false;
        return trimmed.matches("^\\d{1,2}:\\d{2}$")
            || trimmed.matches("^\\d{4}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{2}$");
    }

    /**
     * 归一化时间行：若只有时分（HH:mm），则补上日期。
     * 当天消息不足一天，使用采集当天补全；跨天会以完整日期形式出现而直接保留。
     */
    private String normalizeTime(String timeRow) {
        if (timeRow == null || timeRow.isEmpty()) return timeRow;
        if (timeRow.matches("^\\d{1,2}:\\d{2}$")) {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date())
                    + " " + timeRow;
        }
        return timeRow;
    }

    private String extractTime(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{2})").matcher(s);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("(\\d{1,2}:\\d{2})").matcher(s);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 检测联系人名（从标题栏获取） */
    private void detectContactName(AccessibilityNodeInfo root) {
        if (contactName != null && !contactName.isEmpty()) return;
        // 顶部标题通常是联系人名
        // 通过查找顶部文本节点（顶栏区域）
        AccessibilityNodeInfo title = findTopBarText(root);
        if (title != null) {
            String t = title.getText() != null ? title.getText().toString() : "";
            if (!t.isEmpty() && t.length() < 30 && !t.contains("聊天") && !t.equals("微信")) {
                contactName = t;
                log("识别到联系人：" + contactName);
            }
        }
    }

    private AccessibilityNodeInfo findTopBarText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.top >= 50 && r.bottom <= 200) {
            CharSequence t = node.getText();
            if (t != null && !t.toString().trim().isEmpty()) {
                String s = t.toString().trim();
                if (s.length() < 20 && !s.contains(":") && !s.matches("\\d+")) {
                    return node;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo res = findTopBarText(child);
            if (res != null) return res;
        }
        return null;
    }

    // ============= 手势与判断 =============

    private boolean isAtTop() {
        // 依据连续重复达到上限判断见顶（回到顶部会一直重复）
        return deduplicator.reachedDuplicateLimit();
    }

    private int moderateAmount() {
        return 400;
    }

    private void scrollUp(int amount) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        Rect rect = new Rect();
        root.getBoundsInScreen(rect);
        int screenH = rect.height();
        // 模拟缓慢向上滑动
        performSwipe(screenH * 3 / 4, screenH / 4, 600);
        root.recycle();
    }

    private void performSwipe(float fromY, float toY, long duration) {
        // 通过无障碍手势从下往上滑动（向上滚动=内容向上，手指从下往上）
        Path path = new Path();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        path.moveTo(screenW / 2f, fromY);
        path.lineTo(screenW / 2f, toY);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        dispatchGesture(builder.build(), null, null);
    }

    // ============= 工具方法 =============

    private boolean isInWeChatCurrent() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        // 粗略判断当前在聊天界面（标题栏存在）
        boolean inChat = findTopBarText(root) != null;
        root.recycle();
        return inChat;
    }

    private void setState(int s) {
        state.set(s);
        if (listener != null) listener.onStateChanged(s, doneCount.get());
    }

    /** 日志回调 */
    private void log(final String line) {
        Log.i(TAG, line);
        if (listener != null) listener.onLog(line);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 由外部界面设置联系人名（用于生成文件名） */
    public void applyContactName(String name) {
        if (name != null && !name.trim().isEmpty()) {
            this.contactName = name.trim();
        }
    }

    public int getState() {
        return state.get();
    }

    public String outputDir() {
        return exporter != null ? ConfigUtils.defaultOutputDir(this) : ConfigUtils.defaultOutputDir(this);
    }
}