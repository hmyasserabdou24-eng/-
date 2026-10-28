#!/usr/bin/env python3
"""يرتّب الملفات المسطّحة في هذا المجلد داخل هيكل مشروع Android الذي يشترطه Gradle.
التشغيل:  python3 ضع-الملفات-في-أماكنها.py      (ينشئ مجلد darelihsan-parents-app بجانبه)
Gradle/Android Studio لا يبنيان من مجلد مسطّح؛ هذا الملف هو الحل."""
import os, shutil, sys
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "darelihsan-parents-app")
MAP = {
    "settings.gradle.kts": "settings.gradle.kts",
    "build.gradle.kts": "build.gradle.kts",
    "gradle.properties": "gradle.properties",
    "_gitignore": ".gitignore",
    "README-AR.md": "README-AR.md",
    "TESTING-AR.md": "TESTING-AR.md",
    "app_build.gradle.kts": "app/build.gradle.kts",
    "app_proguard-rules.pro": "app/proguard-rules.pro",
    "AndroidManifest.xml": "app/src/main/AndroidManifest.xml",
    "inject.js": "app/src/main/assets/inject.js",
    "MainActivity.kt": "app/src/main/java/org/darelihsan/parents/MainActivity.kt",
    "Notify.kt": "app/src/main/java/org/darelihsan/parents/Notify.kt",
    "Prefs.kt": "app/src/main/java/org/darelihsan/parents/Prefs.kt",
    "PushService.kt": "app/src/main/java/org/darelihsan/parents/PushService.kt",
    "SessionStore.kt": "app/src/main/java/org/darelihsan/parents/SessionStore.kt",
    "UrlPolicy.kt": "app/src/main/java/org/darelihsan/parents/UrlPolicy.kt",
    "UrlPolicyTest.kt": "app/src/test/java/org/darelihsan/parents/UrlPolicyTest.kt",
    "strings.xml": "app/src/main/res/values/strings.xml",
    "themes.xml": "app/src/main/res/values/themes.xml",
    "ic_stat_notify.xml": "app/src/main/res/drawable/ic_stat_notify.xml",
    "network_security_config.xml": "app/src/main/res/xml/network_security_config.xml",
    "data_extraction_rules.xml": "app/src/main/res/xml/data_extraction_rules.xml",
    "ic_launcher_xxhdpi.png": "app/src/main/res/mipmap-xxhdpi/ic_launcher.png",
    "ic_launcher_xxxhdpi.png": "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
    "build-apk.yml": ".github/workflows/build-apk.yml",
    "20261028000000_fcm_device_tokens.sql": "supabase/migrations/20261028000000_fcm_device_tokens.sql",
    "send-fcm-index.ts": "supabase/functions/send-fcm/index.ts"
}
missing = [k for k in MAP if not os.path.exists(os.path.join(HERE, k))]
if missing:
    sys.exit("ملفات ناقصة: " + ", ".join(missing))
for flat, dest in MAP.items():
    p = os.path.join(OUT, *dest.split("/"))
    os.makedirs(os.path.dirname(p), exist_ok=True)
    shutil.copyfile(os.path.join(HERE, flat), p)
print("تم. افتح المجلد في Android Studio:", OUT)
