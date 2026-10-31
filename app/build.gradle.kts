import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Written by scripts/prepare.py from the APP_CONFIG secret
val cfg = rootProject.file("app-config.json").let {
    if (it.exists()) JsonSlurper().parse(it) as Map<*, *> else emptyMap<String, Any>()
}
fun str(k: String, d: String) = (cfg[k] as? String) ?: d

android {
    namespace = "app.wrapper"
    compileSdk = 35

    defaultConfig {
        applicationId = str("packageName", "app.wrapper.demo")
        minSdk = 29
        targetSdk = 35
        versionCode = (cfg["versionCode"] as? Number)?.toInt() ?: 1
        versionName = str("versionName", "1.0")
        val url = str("homeUrl", "https://example.com").replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "HOME_URL", "\"$url\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "../signing/test.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("KEY_ALIAS") ?: "test"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }
    splits { abi { isEnable = false } } // one universal APK
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
