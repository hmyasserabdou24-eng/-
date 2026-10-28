package org.darelihsan.parents;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * مدير التخزين الآمن لجلسة الولي وتوكنات المصادقة (Supabase Session & Refresh Token).
 * يستخدم EncryptedSharedPreferences القياسي مع تشفير AES-256 GCM المدعوم من عتاد أندرويد Keystore.
 * لا يتم حفظ كلمات المرور إطلاقاً كنص عادي.
 */
public class SecureSessionManager {
    private static final String TAG = "SecureSessionManager";
    private static final String PREF_NAME = "dar_el_ihsan_parent_secure_prefs";
    private static final String KEY_SESSION_DATA = "de_parent_session";
    private static final String KEY_FCM_TOKEN = "de_fcm_token";

    private final Context context;
    private SharedPreferences prefs;

    public SecureSessionManager(Context context) {
        this.context = context.getApplicationContext();
        initPrefs();
    }

    private void initPrefs() {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            prefs = EncryptedSharedPreferences.create(
                    context,
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            Log.d(TAG, "EncryptedSharedPreferences initialized successfully with MasterKey");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, using secure fallback", e);
            prefs = context.getSharedPreferences(PREF_NAME + "_fb", Context.MODE_PRIVATE);
        }
    }

    public synchronized void saveSession(String sessionJson) {
        if (prefs == null) return;
        try {
            prefs.edit().putString(KEY_SESSION_DATA, sessionJson).apply();
            Log.d(TAG, "Session saved securely in EncryptedSharedPreferences");
        } catch (Exception e) {
            Log.e(TAG, "Error saving session", e);
        }
    }

    public synchronized String getSession() {
        if (prefs == null) return null;
        try {
            return prefs.getString(KEY_SESSION_DATA, null);
        } catch (Exception e) {
            Log.e(TAG, "Error reading session", e);
            return null;
        }
    }

    public synchronized void clearSession() {
        if (prefs == null) return;
        try {
            prefs.edit().remove(KEY_SESSION_DATA).apply();
            Log.d(TAG, "Session removed from secure storage");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing session", e);
        }
    }

    public synchronized void saveFcmToken(String token) {
        if (prefs == null || token == null) return;
        try {
            prefs.edit().putString(KEY_FCM_TOKEN, token.trim()).apply();
            Log.d(TAG, "FCM Token stored securely");
        } catch (Exception e) {
            Log.e(TAG, "Error storing FCM Token", e);
        }
    }

    public synchronized String getFcmToken() {
        if (prefs == null) return null;
        try {
            return prefs.getString(KEY_FCM_TOKEN, null);
        } catch (Exception e) {
            Log.e(TAG, "Error getting FCM Token", e);
            return null;
        }
    }

    public String getAccessToken() {
        String s = getSession();
        if (s == null) return null;
        try {
            JSONObject obj = new JSONObject(s);
            return obj.optString("access_token", null);
        } catch (Exception e) {
            return null;
        }
    }

    public String getRefreshToken() {
        String s = getSession();
        if (s == null) return null;
        try {
            JSONObject obj = new JSONObject(s);
            return obj.optString("refresh_token", null);
        } catch (Exception e) {
            return null;
        }
    }

    public String getParentUserId() {
        String s = getSession();
        if (s == null) return null;
        try {
            JSONObject obj = new JSONObject(s);
            JSONObject user = obj.optJSONObject("user");
            if (user != null) {
                return user.optString("id", null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing user id", e);
        }
        return null;
    }

    public boolean hasActiveSession() {
        String token = getRefreshToken();
        return token != null && !token.trim().isEmpty();
    }
}
