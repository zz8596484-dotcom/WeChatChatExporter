package com.chat.exporter;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * 配置与路径工具。
 * 集中管理默认输出目录与用户偏好（联系人名、保存目录、滚动速度等）。
 * 所有配置仅保存在本机 SharedPreferences，不上传任何地方。
 */
public class ConfigUtils {
    private static final String PREFS = "chat_exporter_prefs";
    private static final String KEY_CONTACT = "contact_name";
    private static final String KEY_OUTDIR = "output_dir";
    private static final String KEY_SCROLL_SPEED = "scroll_speed";

    // 默认输出根目录
    public static final String DEFAULT_ROOT = "/sdcard/WeChatChatExporter";
    public static final String DEFAULT_OUTPUT = DEFAULT_ROOT + "/output";

    private ConfigUtils() {
    }

    /** 默认输出目录（不存在则创建） */
    public static String defaultOutputDir(Context ctx) {
        File dir = new File(DEFAULT_OUTPUT);
        if (!dir.exists()) dir.mkdirs();
        return dir.getAbsolutePath();
    }

    /** 读取用户配置的保存目录；为空则用默认 */
    public static String getOutputDir(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String custom = sp.getString(KEY_OUTDIR, "");
        if (custom != null && !custom.trim().isEmpty()) {
            File f = new File(custom);
            if (!f.exists()) f.mkdirs();
            return f.getAbsolutePath();
        }
        return defaultOutputDir(ctx);
    }

    public static void setOutputDir(Context ctx, String dir) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putString(KEY_OUTDIR, dir).apply();
    }

    public static void setContactName(Context ctx, String name) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putString(KEY_CONTACT, name).apply();
    }

    /** 滚动速度（毫秒/屏），数值越大滚动越慢越稳妥 */
    public static int getScrollInterval(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getInt(KEY_SCROLL_SPEED, 1400);
    }

    public static void setScrollInterval(Context ctx, int ms) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putInt(KEY_SCROLL_SPEED, ms).apply();
    }
}
