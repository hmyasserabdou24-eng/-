plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase (FCM) is enabled only when app/google-services.json exists (see README-AR.md).
val hasFirebase = file("google-services.json").exists()
if (hasFirebase) apply(plugin = "com.google.gms.google-services")

// Values come from -P properties or environment variables, never from source control.
fun cfg(name: String, default: String = ""): String =
    (findProperty(name) as String?) ?: System.getenv(name) ?: default

android {
    namespace = "org.darelihsan.parents"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.darelihsan.parents"
        minSdk = 24
        targetSdk = 35
        versionCode = cfg("VERSION_CODE", "1").toInt()
        versionName = cfg("VERSION_NAME", "1.0.0")

        // Fixed start URL of the parents' space (parent.html). Must be https.
        buildConfigField("String", "PARENT_URL", "\"" + cfg("PARENT_URL", "https://hmyasserabdou24-eng.github.io/DarElIhsan3/parent.html") + "\"")
        // Public project URL / publishable key are not secrets (same values as cms-config.js).
        buildConfigField("boolean", "FIREBASE_ENABLED", hasFirebase.toString())
    }

    signingConfigs {
        val ks = cfg("KEYSTORE_PATH")
        if (ks.isNotEmpty() && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = cfg("KEYSTORE_PASSWORD")
                keyAlias = cfg("KEY_ALIAS")
                keyPassword = cfg("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    testImplementation("junit:junit:4.13.2")
}
