package com.chat.exporter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * 前台服务。
 * 用于在采集过程中让采集状态保持不被系统回收，仅在本地运行。
 * 不涉及任何后台违规行为，只是一个带常驻通知的普通前台服务。
 */
public class CaptureForegroundService extends Service {
    private static final String CHANNEL_ID = "capture_channel";
    private static final int NOTIF_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("微信聊天采集助手")
                .setContentText("采集服务运行中，数据仅保存在本机")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "采集服务",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}