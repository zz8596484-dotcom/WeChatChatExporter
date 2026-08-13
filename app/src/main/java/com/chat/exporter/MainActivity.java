package com.chat.exporter;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 主界面。
 * 负责：
 *  - 展示采集状态、消息数量、滚动位置
 *  - 输入联系人昵称（可选）、指定保存目录（可选）
 *  - 控制：开始 / 暂停 / 继续 / 结束并导出
 *  - 引导开启无障碍服务
 *  - 显示运行日志
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvStatus, tvCount, tvScrollPos, tvLog;
    private EditText etContact, etOutdir;
    private Button btnStart, btnPause, btnResume, btnFinish, btnEnableService;

    private String savedContact = "";
    private String savedOutdir = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        loadPrefs();

        // 默认填入已保存的联系人与目录
        etContact.setText(savedContact);
        etOutdir.setText(savedOutdir.isEmpty() ? ConfigUtils.defaultOutputDir(this) : savedOutdir);

        setupButtons();
        startForegroundService();
    }

    private void bindViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvCount = findViewById(R.id.tv_count);
        tvScrollPos = findViewById(R.id.tv_scroll_pos);
        tvLog = findViewById(R.id.tv_log);
        etContact = findViewById(R.id.et_contact);
        etOutdir = findViewById(R.id.et_outdir);
        btnStart = findViewById(R.id.btn_start);
        btnPause = findViewById(R.id.btn_pause);
        btnResume = findViewById(R.id.btn_resume);
        btnFinish = findViewById(R.id.btn_finish);
        btnEnableService = findViewById(R.id.btn_enable_service);
    }

    private void loadPrefs() {
        savedContact = getSharedPreferences("chat_exporter_prefs", MODE_PRIVATE)
                .getString("contact_name", "");
        savedOutdir = getSharedPreferences("chat_exporter_prefs", MODE_PRIVATE)
                .getString("output_dir", "");
    }

    private void setupButtons() {
        btnEnableService.setOnClickListener(v -> {
            // 检查无障碍服务是否已开启
            if (isAccessibilityEnabled()) {
                openMainActivity();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("开启无障碍服务")
                        .setMessage("需要开启「微信聊天采集助手」的无障碍服务权限，才能读取聊天界面内容。\n\n请到：设置 → 更多设置 → 无障碍 → 已安装的服务 → 开启「微信聊天采集助手」。\n\n（不同品牌路径可能略微不同，搜索“无障碍”即可）")
                        .setPositiveButton("去开启", (d, w) ->
                                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        btnStart.setOnClickListener(v -> {
            if (!isAccessibilityEnabled()) {
                toast("请先开启无障碍服务");
                return;
            }
            savePrefsFromInput();
            WeChatAccessibilityService svc = WeChatAccessibilityService.get();
            if (svc == null) {
                toast("无障碍服务尚未就绪");
                return;
            }
            setListenerOnService(svc);
            svc.startCapture();
        });

        btnPause.setOnClickListener(v -> {
            WeChatAccessibilityService svc = WeChatAccessibilityService.get();
            if (svc != null) svc.pauseCapture();
        });

        btnResume.setOnClickListener(v -> {
            WeChatAccessibilityService svc = WeChatAccessibilityService.get();
            if (svc != null) svc.resumeCapture();
        });

        btnFinish.setOnClickListener(v -> {
            WeChatAccessibilityService svc = WeChatAccessibilityService.get();
            if (svc != null) svc.finishAndExport();
        });
    }

    private void setListenerOnService(WeChatAccessibilityService svc) {
        svc.setListener(new WeChatAccessibilityService.Listener() {
            @Override
            public void onLog(String line) {
                runOnUiThread(() -> appendLog(line));
            }

            @Override
            public void onStateChanged(int state, int count) {
                runOnUiThread(() -> {
                    String s;
                    switch (state) {
                        case WeChatAccessibilityService.STATE_CAPTURING:
                            s = getString(R.string.status_capturing);
                            break;
                        case WeChatAccessibilityService.STATE_PAUSED:
                            s = getString(R.string.status_paused);
                            // 暂停时标记为“过顶/已暂停”可后续处理
                            tvScrollPos.setText("已暂停");
                            break;
                        default:
                            s = getString(R.string.status_idle);
                    }
                    tvStatus.setText(s);
                    tvCount.setText(String.valueOf(count));
                });
            }

            @Override
            public void onFinished(String summary) {
                runOnUiThread(() -> {
                    tvStatus.setText(getString(R.string.status_finished));
                    appendLog(summary);
                });
            }
        });
    }

    /** 简单判断无障碍服务是否已开启 */
    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabledServices != null && enabledServices.contains(getPackageName());
    }

    private void openMainActivity() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void savePrefsFromInput() {
        String contact = etContact.getText().toString().trim();
        String outdir = etOutdir.getText().toString().trim();
        if (!TextUtils.isEmpty(contact)) ConfigUtils.setContactName(this, contact);
        if (!TextUtils.isEmpty(outdir)) ConfigUtils.setOutputDir(this, outdir);
        // 保存联系人到服务
        WeChatAccessibilityService svc = WeChatAccessibilityService.get();
        if (svc != null) svc.applyContactName(contact);
    }

    private void appendLog(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        tvLog.append("[" + ts + "] " + line + "\n");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void startForegroundService() {
        try {
            Intent i = new Intent(this, CaptureForegroundService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            // 前台服务启动失败不影响主功能（可能缺权限）
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 若无障碍已开启则给出提示并刷新服务引用
        WeChatAccessibilityService svc = WeChatAccessibilityService.get();
        if (svc != null) setListenerOnService(svc);
        if (isAccessibilityEnabled()) {
            btnEnableService.setText("无障碍服务已开启 ✅（点这里重进聊天界面）");
        }
    }
}