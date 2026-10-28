# قواعد حماية الكود وتأمين واجهات JavaScript وFirebase

# حماية واجهة جسر الجلسة بين جافاسكربت وأندرويد
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class org.darelihsan.parents.MainActivity$ParentSessionBridge {
    *;
}

# حماية مكتبة التخزين الآمن المشفر EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }

# حماية خدمة إشعارات Firebase Cloud Messaging
-keep class com.google.firebase.messaging.** { *; }
-keep class org.darelihsan.parents.DarElIhsanMessagingService { *; }
-keep class org.darelihsan.parents.DeviceTokenManager { *; }

# الاحتفاظ بخصائص WebKit
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void openFileChooser(...);
    public boolean onShowFileChooser(...);
}
