package com.chat.exporter;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 聊天记录导出器。
 * 负责把消息按时间排序后导出为 chat.md / chat.json，
 * 支持按消息数量分卷（chat_001.md 等），并可保存/恢复采集进度。
 * 所有数据仅写入本地存储。
 */
public class ChatExporter {
    private static final String TAG = "ChatExporter";
    // 每卷最大消息数
    public static final int MAX_PER_VOLUME = 1000;
    // 会话状态文件名
    public static final String STATE_FILE = "capture_state.json";

    private final Context context;
    private final String baseDir;

    public ChatExporter(Context context, String baseDir) {
        this.context = context;
        this.baseDir = baseDir;
        File d = new File(baseDir);
        if (!d.exists()) d.mkdirs();
    }

    /**
     * 导出。
     * @param baseName 文件基名，如 chat 或 联系人名_chat
     * @return 导出结果描述
     */
    public String export(List<ChatMessage> all, String baseName) {
        if (all == null || all.isEmpty()) {
            return "没有可导出的消息";
        }

        // 按时间排序（稳定排序；时间相同的保留采集顺序）
        List<ChatMessage> sorted = new ArrayList<>(all);
        sortMessages(sorted);

        // 分卷
        int volumeCount = (sorted.size() + MAX_PER_VOLUME - 1) / MAX_PER_VOLUME;
        StringBuilder mdLog = new StringBuilder();
        StringBuilder jsonLog = new StringBuilder();

        for (int v = 0; v < volumeCount; v++) {
            int from = v * MAX_PER_VOLUME;
            int to = Math.min(from + MAX_PER_VOLUME, sorted.size());
            List<ChatMessage> slice = sorted.subList(from, to);
            String volTag = String.format(Locale.US, "%03d", v + 1);

            String mdName = baseName + "_" + volTag + ".md";
            String jsonName = baseName + "_" + volTag + ".json";
            // 单卷时仍用序号命名保证统一
            writeMd(new File(baseDir, mdName), slice, v + 1, volumeCount);
            writeJson(new File(baseDir, jsonName), slice);

            mdLog.append("📄 ").append(mdName).append(" (").append(slice.size()).append("条)\n");
            jsonLog.append("📄 ").append(jsonName).append("\n");
        }

        Log.i(TAG, "导出完成，共 " + sorted.size() + " 条，分 " + volumeCount + " 卷");
        return "导出完成 ✅\n" +
                "共 " + sorted.size() + " 条消息，分 " + volumeCount + " 卷：\n" +
                mdLog.toString() +
                "JSON:\n" + jsonLog.toString() +
                "输出目录：" + baseDir;
    }

    private void sortMessages(List<ChatMessage> list) {
        Collections.sort(list, new Comparator<ChatMessage>() {
            @Override
            public int compare(ChatMessage a, ChatMessage b) {
                String ta = a.time == null ? "" : a.time;
                String tb = b.time == null ? "" : b.time;
                int c = ta.compareTo(tb);
                if (c != 0) return c;
                // 时间相同按发送者稳定
                String sa = a.sender == null ? "" : a.sender;
                String sb = b.sender == null ? "" : b.sender;
                return sa.compareTo(sb);
            }
        });
    }

    private void writeMd(File file, List<ChatMessage> messages, int volNo, int totalVol) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 微信聊天记录\n\n");
        sb.append("> 卷 ").append(volNo).append(" / ").append(totalVol)
          .append(" ｜ 共 ").append(messages.size()).append(" 条\n\n");
        sb.append("| 时间 | 发送者 | 类型 | 内容 |\n");
        sb.append("| --- | --- | --- | --- |\n");
        for (ChatMessage m : messages) {
            String ts = esc(m.time == null ? "未知时间" : m.time);
            String s = esc(m.sender == null ? "对方" : m.sender);
            String ty = esc(m.type == null ? "未知" : m.type);
            String c = esc(m.content == null ? "" : m.content);
            c = c.replace("\n", "<br>");
            sb.append("| ").append(ts)
              .append(" | ").append(s)
              .append(" | ").append(ty)
              .append(" | ").append(c).append(" |\n");
        }
        writeFile(file, sb.toString());
    }

    private void writeJson(File file, List<ChatMessage> messages) {
        JSONArray arr = new JSONArray();
        try {
            for (ChatMessage m : messages) {
                arr.put(m.toJson());
            }
            JSONObject root = new JSONObject();
            root.put("count", messages.size());
            root.put("exported_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            root.put("messages", arr);
            writeFile(file, root.toString(2));
        } catch (Exception e) {
            Log.e(TAG, "JSON 导出失败", e);
        }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|");
    }

    private void writeFile(File f, String content) {
        try (FileOutputStream fos = new FileOutputStream(f);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            osw.write(content);
            osw.flush();
        } catch (Exception e) {
            Log.e(TAG, "写入失败:" + f.getAbsolutePath(), e);
        }
    }

    /** 保存采集会话状态，用于下次继续 */
    public void saveState(String contactName, int doneCount, String lastFingerprint) {
        JSONObject o = new JSONObject();
        try {
            o.put("contact", contactName == null ? "" : contactName);
            o.put("done_count", doneCount);
            o.put("last_fp", lastFingerprint == null ? "" : lastFingerprint);
            o.put("saved_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            writeFile(new File(baseDir, STATE_FILE), o.toString(2));
        } catch (Exception e) {
            Log.e(TAG, "保存状态失败", e);
        }
    }

    public boolean hasState(String contactName) {
        File f = new File(baseDir, STATE_FILE);
        return f.exists();
    }
}