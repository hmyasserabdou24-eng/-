package org.darelihsan.parents;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * مسؤول عن مزامنة رمز Firebase Cloud Messaging (FCM) مع خادم Supabase
 * يتم استدعاء دوال RPC الآمنة:
 * 1. de_register_device_token عند تسجيل الدخول أو تجديد الرمز
 * 2. de_unregister_device_token عند تسجيل الخروج
 */
public class DeviceTokenManager {
    private static final String TAG = "DeviceTokenManager";

    // إعدادات الاتصال الافتراضية مع Supabase من ملف المشروع
    public static final String SUPABASE_URL = "https://qgjddtylriqdwvpihvmi.supabase.co";
    public static final String SUPABASE_ANON_KEY = "sb_publishable_8mgO37k9jB9_Dg4OQTfynw_NBC0gLjK";

    private final Context context;
    private final SecureSessionManager sessionManager;
    private final ExecutorService executor;

    public DeviceTokenManager(Context context, SecureSessionManager sessionManager) {
        this.context = context.getApplicationContext();
        this.sessionManager = sessionManager;
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * إرسال رمز FCM إلى Supabase وربطه بحساب الولي الحالي
     */
    public void syncTokenWithBackend(final String fcmToken, final String accessToken) {
        if (fcmToken == null || fcmToken.trim().isEmpty()) {
            Log.w(TAG, "Cannot sync empty FCM token");
            return;
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String tokenToUse = accessToken != null ? accessToken : sessionManager.getAccessToken();
                    if (tokenToUse == null || tokenToUse.trim().isEmpty()) {
                        Log.d(TAG, "No active access token yet, token saved locally until login");
                        return;
                    }

                    String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                    String deviceModel = Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")";

                    URL url = new URL(SUPABASE_URL + "/rest/v1/rpc/de_register_device_token");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("apikey", SUPABASE_ANON_KEY);
                    conn.setRequestProperty("Authorization", "Bearer " + tokenToUse);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);

                    JSONObject payload = new JSONObject();
                    payload.put("p_fcm_token", fcmToken.trim());
                    payload.put("p_device_id", deviceId != null ? deviceId : "unknown");
                    payload.put("p_device_model", deviceModel);
                    payload.put("p_app_version", "1.0.0");

                    byte[] postData = payload.toString().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postData);
                    }

                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        Log.i(TAG, "FCM token registered successfully on Supabase backend (HTTP " + code + ")");
                    } else {
                        Log.w(TAG, "Failed to register FCM token on Supabase (HTTP " + code + ")");
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    Log.e(TAG, "Error syncing FCM token with backend", e);
                }
            }
        });
    }

    /**
     * إلغاء ربط رمز FCM الخاص بهذا الجهاز عند تسجيل خروج الولي
     */
    public void unregisterCurrentDeviceToken() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String fcmToken = sessionManager.getFcmToken();
                    String accessToken = sessionManager.getAccessToken();

                    if (fcmToken == null || accessToken == null) {
                        return;
                    }

                    URL url = new URL(SUPABASE_URL + "/rest/v1/rpc/de_unregister_device_token");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("apikey", SUPABASE_ANON_KEY);
                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    JSONObject payload = new JSONObject();
                    payload.put("p_fcm_token", fcmToken.trim());

                    byte[] postData = payload.toString().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postData);
                    }

                    int code = conn.getResponseCode();
                    Log.i(TAG, "Unregister FCM token response code: " + code);
                    conn.disconnect();
                } catch (Exception e) {
                    Log.e(TAG, "Error unregistering FCM token", e);
                }
            }
        });
    }
}
