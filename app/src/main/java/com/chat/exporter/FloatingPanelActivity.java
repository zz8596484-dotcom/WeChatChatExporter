package com.chat.exporter;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 悬浮球控制界面。
 * 以全屏透明 Activity 承载一个系统级悬浮球（需要悬浮窗权限）。
 * 点击悬浮球展开/收起控制面板，可从任意界面快速控制采集。
 * 悬浮球为纯本地 UI，不采集任何屏幕内容。
 */
public class FloatingPanelActivity extends Activity {
    private WindowManager wm;
    private View floatView;          // 悬浮球本体
    private View panelView;          // 展开面板
    private TextView tvFloatIcon;
    private boolean panelVisible = false;

    private int initX = 0, initY = 0;
    private float touchStartX = 0, touchStartY = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 透明承载背景
        getWindow().setFormat(PixelFormat.TRANSLUCENT);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        buildFloatView();
        buildPanelView();
        addFloatToWindow();
    }

    private void buildFloatView() {
        floatView = getLayoutInflater().inflate(R.layout.overlay_floating, null);
        tvFloatIcon = floatView.findViewById(R.id.tv_float_icon);

        // 拖拽与点击
        floatView.setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initX = lp.x;
                    initY = lp.y;
                    touchStartX = event.getRawX();
                    touchStartY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = initX + (int) (event.getRawX() - touchStartX);
                    lp.y = initY + (int) (event.getRawY() - touchStartY);
                    wm.updateViewLayout(v, lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    // 判定点击（位移很小）则展开面板
                    if (Math.abs(event.getRawX() - touchStartX) < 8
                            && Math.abs(event.getRawY() - touchStartY) < 8) {
                        togglePanel();
                    }
                    return true;
            }
            return false;
        });
    }

    private void buildPanelView() {
        panelView = getLayoutInflater().inflate(R.layout.overlay_panel, null);

        Button bStart = panelView.findViewById(R.id.btn_fp_start);
        Button bPause = panelView.findViewById(R.id.btn_fp_pause);
        Button bResume = panelView.findViewById(R.id.btn_fp_resume);
        Button bFinish = panelView.findViewById(R.id.btn_fp_finish);

        bStart.setOnClickListener(v -> {
            WeChatAccessibilityService svc = WeChatAccessibilityService.get();
            if (svc != null) svc.startCapture();
        });
        bPause.setOnClickListener(v -> {
            WeChatAccessibilityService svc = WeChatAccessibilityService.get();
            if (svc != null) svc.pauseCapture();
        });
        bResume.setOnClickListener(v -> {
            WeChatAccessibilityService svc = WeChatAccessibilityService.get();
            if (svc != null) svc.resumeCapture();
        });
        bFinish.setOnClickListener(v -> {
            WeChatAccessibilityService svc = WeChatAccessibilityService.get();
            if (svc != null) svc.finishAndExport();
        });
    }

    private void togglePanel() {
        panelVisible = !panelVisible;
        if (panelVisible) {
            // 在悬浮球上方显示面板
            addPanelToWindow();
        } else {
            removePanelFromWindow();
        }
    }

    private void addFloatToWindow() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (getResources().getDisplayMetrics().widthPixels * 0.8);
        lp.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.3);
        wm.addView(floatView, lp);
    }

    private void addPanelToWindow() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // 放在悬浮球旁边
        WindowManager.LayoutParams flp = (WindowManager.LayoutParams) floatView.getLayoutParams();
        lp.x = flp.x + 60;
        lp.y = flp.y;
        wm.addView(panelView, lp);
    }

    private void removePanelFromWindow() {
        try {
            wm.removeView(panelView);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (floatView != null) wm.removeView(floatView);
            if (panelView != null) wm.removeView(panelView);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}