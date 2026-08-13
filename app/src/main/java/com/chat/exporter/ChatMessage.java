package com.chat.exporter;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一条聊天消息的模型。
 * 所有字段均为本地保存，仅采集用户在界面上能看到的内容。
 */
public class ChatMessage {
    // 发送者常量
    public static final String SENDER_ME = "我";
    public static final String SENDER_THEM = "对方";

    // 类型常量
    public static final String TYPE_TEXT = "文字";
    public static final String TYPE_IMAGE = "图片";
    public static final String TYPE_VIDEO = "视频";
    public static final String TYPE_VOICE = "语音";
    public static final String TYPE_EMOJI = "表情包";
    public static final String TYPE_QUOTE = "引用回复";
    public static final String TYPE_TRANSFER = "转账";
    public static final String TYPE_RED_PACKET = "红包";
    public static final String TYPE_RECALL = "撤回提示";
    public static final String TYPE_SYSTEM = "系统消息";
    public static final String TYPE_CALL = "通话记录";
    public static final String TYPE_FILE = "文件";
    public static final String TYPE_LINK = "链接";
    public static final String TYPE_LOCATION = "位置";
    public static final String TYPE_UNKNOWN = "未知";

    public String time;        // 消息时间，格式如 2026-08-13 22:14
    public String sender;      // 我 / 对方
    public String type;        // 上面类型之一
    public String content;     // 内容文本或占位说明
    public String fingerprint; // 去重指纹

    public ChatMessage() {
    }

    /** 生成去重指纹：时间+发送者+类型+内容 */
    public String computeFingerprint() {
        String c = content == null ? "" : content;
        String t = time == null ? "" : time;
        String s = sender == null ? "" : sender;
        String ty = type == null ? "" : type;
        this.fingerprint = Integer.toHexString((t + "|" + s + "|" + ty + "|" + c).hashCode());
        return this.fingerprint;
    }

    /**
     * 组装成 Markdown 行。
     * 例如：2026-08-13 22:14 对方 文字 你怎么又出现在这里
     */
    public String toMdLine() {
        String ts = time == null ? "未知时间" : time;
        String s = sender == null ? SENDER_THEM : sender;
        String ty = type == null ? TYPE_UNKNOWN : type;
        String c = content == null ? "" : content;
        return ts + "  " + s + "  " + ty + "  " + c;
    }

    /** 组装成 JSON 对象 */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("time", time == null ? "" : time);
        o.put("sender", sender == null ? "" : sender);
        o.put("type", type == null ? "" : type);
        o.put("content", content == null ? "" : content);
        o.put("fingerprint", fingerprint == null ? "" : fingerprint);
        return o;
    }
}