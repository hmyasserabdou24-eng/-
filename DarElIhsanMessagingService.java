package org.darelihsan.parents;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * خدمة استقبال وإدارة إشعارات Firebase Cloud Messaging (FCM) لتطبيق دار الإحسان.
 * تدعم استقبال الإشعارات حتى عندما يكون التطبيق مغلقاً أو يعمل في الخلفية،
 * مع مراعاة كاملة لخصوصية شاشة القفل وعدم إظهار أي بيانات حساسة.
 */
public class DarElIhsanMessagingService extends FirebaseMessagingService {
    private static final String TAG = "DarElIhsanFCM";
    public static final String CHANNEL_ID = "dar_el_ihsan_parents_channel";
    public static final String ACTION_NOTIFICATION_RECEIVED = "org.darelihsan.parents.NOTIFICATION_RECEIVED";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.i(TAG, "New FCM Token received: " + token);

        SecureSessionManager sessionManager = new SecureSessionManager(this);
        sessionManager.saveFcmToken(token);

        DeviceTokenManager tokenManager = new DeviceTokenManager(this, sessionManager);
        if (sessionManager.hasActiveSession()) {
            tokenManager.syncTokenWithBackend(token, sessionManager.getAccessToken());
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Log.d(TAG, "From: " + remoteMessage.getFrom());

        String title = "إشعار جديد من دار الإحسان";
        String body = "لديك تحديث جديد في فضاء الأولياء.";
        String targetTab = "children";
        String outboxId = null;

        // قراءة البيانات من حمولة الإشعار
        if (remoteMessage.getNotification() != null) {
            if (remoteMessage.getNotification().getTitle() != null) {
                title = remoteMessage.getNotification().getTitle();
            }
            if (remoteMessage.getNotification().getBody() != null) {
                body = remoteMessage.getNotification().getBody();
            }
        }

        // قراءة الحقول الإضافية المخصصة
        Map<String, String> data = remoteMessage.getData();
        if (data != null && !data.isEmpty()) {
            if (data.containsKey("title")) title = data.get("title");
            if (data.containsKey("body")) body = data.get("body");
            if (data.containsKey("target_tab")) targetTab = data.get("target_tab");
            if (data.containsKey("outbox_id")) outboxId = data.get("outbox_id");
            if (data.containsKey("url") && targetTab == null) {
                String u = data.get("url");
                if (u.contains("#msgs")) targetTab = "msgs";
                else if (u.contains("#reports")) targetTab = "reports";
                else if (u.contains("#evaluations")) targetTab = "evaluations";
                else if (u.contains("#programs")) targetTab = "programs";
                else if (u.contains("#docs")) targetTab = "docs";
            }
        }

        sendNotification(title, body, targetTab, outboxId);

        // إشعار الواجهة الأمامية إن كانت مفتوحة حالياً لتحديث الأرقام والشارات
        Intent broadcast = new Intent(ACTION_NOTIFICATION_RECEIVED);
        broadcast.putExtra("title", title);
        broadcast.putExtra("body", body);
        broadcast.putExtra("target_tab", targetTab);
        sendBroadcast(broadcast);
    }

    private void sendNotification(String title, String body, String targetTab, String outboxId) {
        createNotificationChannel();

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("target_tab", targetTab);
        if (outboxId != null) intent.putExtra("outbox_id", outboxId);

        int reqCode = (int) System.currentTimeMillis();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        // نسخة عامة تظهر على شاشة القفل لحماية الخصوصية
        NotificationCompat.Builder publicBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setColor(Color.parseColor("#123E73"))
                .setContentTitle("دار الإحسان – فضاء الأولياء")
                .setContentText("لديك إشعار وتحديث جديد")
                .setAutoCancel(true);

        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setColor(Color.parseColor("#123E73"))
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                        .setAutoCancel(true)
                        .setSound(defaultSoundUri)
                        .setContentIntent(pendingIntent)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setPublicVersion(publicBuilder.build());

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        try {
            notificationManager.notify(reqCode, notificationBuilder.build());
        } catch (SecurityException se) {
            Log.e(TAG, "Notification permission not granted", se);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "إشعارات فضاء الأولياء";
            String description = "إشعارات الرسائل والتقارير والبرامج التربوية من دار الإحسان";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.enableLights(true);
            channel.setLightColor(Color.parseColor("#123E73"));
            channel.enableVibration(true);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
}
