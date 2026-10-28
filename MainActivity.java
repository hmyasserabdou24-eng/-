package org.darelihsan.parents;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.messaging.FirebaseMessaging;

/**
 * النشاط الرئيسي لتطبيق فضاء أولياء دار الإحسان المستقل.
 * - يفتح صفحة parent.html حصراً، ويمنع نهائياً أي وصول للموقع العام أو لوحة الإدارة.
 * - يوفر جسر التخزين الآمن للجلسة (ParentSessionBridge) مع EncryptedSharedPreferences.
 * - يدعم استقبال وتوجيه إشعارات Firebase Cloud Messaging (FCM) نحو التبويب الصحيح.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "DarElIhsanMain";
    private static final String PARENT_PORTAL_URL = "file:///android_asset/public/parent.html";
    private static final int PERMISSION_REQUEST_CODE_NOTIF = 1001;

    private WebView webView;
    private SecureSessionManager sessionManager;
    private DeviceTokenManager tokenManager;
    private ValueCallback<Uri[]> fileUploadCallback;
    private long backPressedTime = 0;
    private String pendingTargetTab = null;

    private final BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (webView != null && intent != null) {
                String title = intent.getStringExtra("title");
                String tab = intent.getStringExtra("target_tab");
                String js = String.format(
                        "window.dispatchEvent(new CustomEvent('dar_notification_received', { detail: { title: '%s', tab: '%s' } }));",
                        escapeJs(title != null ? title : "إشعار جديد"),
                        escapeJs(tab != null ? tab : "children")
                );
                webView.evaluateJavascript(js, null);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sessionManager = new SecureSessionManager(this);
        tokenManager = new DeviceTokenManager(this, sessionManager);

        setupViews();
        handleIntent(getIntent());
        requestNotificationPermission();
        fetchAndRegisterFcmToken();
        setupBackNavigation();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupViews() {
        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setSupportZoom(false);
        ws.setUserAgentString(ws.getUserAgentString() + " DarElIhsanParentsAndroid/1.0");

        // تسجيل واجهة الجلسة الآمنة بين JavaScript ونظام Android
        webView.addJavascriptInterface(new ParentSessionBridge(), "ParentSessionBridge");

        // عميل الويب الصارم لمنع التنقل لغير فضاء الأولياء
        webView.setWebViewClient(new StrictParentWebViewClient());

        // عميل متقدم لمعالجة تحميل الملفات والوثائق
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                fileUploadCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, 2001);
                    return true;
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
            }
        });

        // فتح صفحة فضاء الأولياء مباشرة
        webView.loadUrl(PARENT_PORTAL_URL);
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // استعلام الواجهة الأمامية إن كان هناك درج مفتوح أو نافذة حوارية لإغلاقها
                if (webView != null) {
                    webView.evaluateJavascript(
                            "(function() { " +
                                    "var m = document.getElementById('modal'); if (m && !m.hidden) { m.hidden = true; return true; } " +
                                    "var d = document.getElementById('parentDrawer'); if (d && d.classList.contains('open')) { d.classList.remove('open'); var sc = document.getElementById('parentScrim'); if (sc) sc.hidden = true; return true; } " +
                                    "return false; " +
                                    "})()",
                            new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    if ("true".equals(value)) {
                                        return; // تم إغلاق النافذة بنجاح
                                    }
                                    handleAppExit();
                                }
                            }
                    );
                } else {
                    handleAppExit();
                }
            }
        });
    }

    private void handleAppExit() {
        if (System.currentTimeMillis() - backPressedTime < 2000) {
            finish();
        } else {
            backPressedTime = System.currentTimeMillis();
            Toast.makeText(MainActivity.this, "اضغط مرة أخرى للخروج من التطبيق", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String tab = intent.getStringExtra("target_tab");
        if (tab != null && !tab.trim().isEmpty()) {
            pendingTargetTab = tab.trim();
            navigateToTargetTab(pendingTargetTab);
        }
    }

    private void navigateToTargetTab(final String tab) {
        if (webView == null || tab == null) return;
        webView.post(new Runnable() {
            @Override
            public void run() {
                String js = String.format(
                        "window.dispatchEvent(new CustomEvent('dar_open_tab', { detail: { tab: '%s' } }));",
                        escapeJs(tab)
                );
                webView.evaluateJavascript(js, null);
                pendingTargetTab = null;
            }
        });
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE_NOTIF
                );
            }
        }
    }

    private void fetchAndRegisterFcmToken() {
        try {
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(new OnCompleteListener<String>() {
                        @Override
                        public void onComplete(@NonNull Task<String> task) {
                            if (!task.isSuccessful()) {
                                Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                                return;
                            }
                            String token = task.getResult();
                            Log.i(TAG, "Current FCM Token: " + token);
                            sessionManager.saveFcmToken(token);

                            if (sessionManager.hasActiveSession()) {
                                tokenManager.syncTokenWithBackend(token, sessionManager.getAccessToken());
                            }
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "Firebase not yet initialized or google-services.json missing", e);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(DarElIhsanMessagingService.ACTION_NOTIFICATION_RECEIVED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(notificationReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(notificationReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2001 && fileUploadCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
        }
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    /**
     * واجهة JavaScript المصدَّرة لصفحة فضاء الأولياء
     */
    public class ParentSessionBridge {

        @JavascriptInterface
        public String getSession(String key) {
            return sessionManager.getSession();
        }

        @JavascriptInterface
        public void setSession(String key, String value) {
            sessionManager.saveSession(value);
            onSessionSaved(value);
        }

        @JavascriptInterface
        public void removeSession(String key) {
            sessionManager.clearSession();
            onSessionCleared();
        }

        @JavascriptInterface
        public void onSessionSaved(String sessionJson) {
            sessionManager.saveSession(sessionJson);
            String fcmToken = sessionManager.getFcmToken();
            String accessToken = sessionManager.getAccessToken();
            if (fcmToken != null && accessToken != null) {
                tokenManager.syncTokenWithBackend(fcmToken, accessToken);
            }
        }

        @JavascriptInterface
        public void onSessionCleared() {
            tokenManager.unregisterCurrentDeviceToken();
            sessionManager.clearSession();
        }

        @JavascriptInterface
        public boolean isAndroidApp() {
            return true;
        }

        @JavascriptInterface
        public String getFcmToken() {
            return sessionManager.getFcmToken();
        }

        @JavascriptInterface
        public void registerFcmToken(String parentUserId) {
            String token = sessionManager.getFcmToken();
            String accessToken = sessionManager.getAccessToken();
            if (token != null) {
                tokenManager.syncTokenWithBackend(token, accessToken);
            }
        }
    }

    /**
     * عميل WebView الصارم:
     * 1. يمنع ظهور الموقع العام أو لوحة الإدارة نهائياً
     * 2. يحظر التنقل إلى manage.html أو site.html أو الروابط الإدارية
     * 3. يسمح فقط بصفحة parent.html والأصول المصرح بها
     * 4. يفتح روابط WhatsApp والاتصال في التطبيقات المناسبة
     */
    private class StrictParentWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            return handleUrlNavigation(view, uri);
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrlNavigation(view, Uri.parse(url));
        }

        private boolean handleUrlNavigation(WebView view, Uri uri) {
            String urlStr = uri.toString().toLowerCase();

            // 1. فتح روابط واتساب والاتصال والبريد في التطبيقات الأصلية للهاتف
            if (urlStr.startsWith("whatsapp:") || urlStr.contains("wa.me") || urlStr.contains("api.whatsapp.com")) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تطبيق واتساب غير مثبت على الجهاز", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            if (urlStr.startsWith("tel:") || urlStr.startsWith("mailto:")) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "Cannot handle intent: " + urlStr, e);
                }
                return true;
            }

            // 2. الحظر الصارم لأي محاولة انتقال للوحة الإدارة أو الموقع التعريفي العام
            if (urlStr.contains("manage.html") || urlStr.contains("site.html") ||
                    urlStr.contains("/manage") || urlStr.contains("/admin") ||
                    urlStr.contains("archive-download.html") ||
                    (urlStr.endsWith("/index.html") && !urlStr.contains("parent"))) {

                Log.w(TAG, "Blocked forbidden navigation attempt: " + urlStr);
                Toast.makeText(MainActivity.this, "التطبيق مخصص لفضاء الأولياء فقط", Toast.LENGTH_SHORT).show();
                view.loadUrl(PARENT_PORTAL_URL);
                return true;
            }

            // 3. السماح بملف فضاء الأولياء ومساراته الفرعية
            if (urlStr.contains("parent.html") || urlStr.startsWith("file:///android_asset/public/")) {
                return false;
            }

            // 4. السماح باتصالات Supabase و Google Fonts
            if (urlStr.contains("supabase.co") || urlStr.contains("googleapis.com") || urlStr.contains("gstatic.com")) {
                return false;
            }

            // 5. أي رابط خارجي آخر يُفتح في متصفح النظام لحماية عزلة التطبيق
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(intent);
            } catch (Exception e) {
                Log.w(TAG, "Unable to launch external browser for " + urlStr, e);
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Log.d(TAG, "Loaded URL: " + url);
            if (pendingTargetTab != null) {
                navigateToTargetTab(pendingTargetTab);
            }
        }
    }
}
