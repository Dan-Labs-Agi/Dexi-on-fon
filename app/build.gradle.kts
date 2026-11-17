plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.danny.glasses"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.danny.glasses"
        minSdk = 31          // Android 12+: the new Bluetooth permissions
        targetSdk = 34
        versionCode = 5
        versionName = "0.2.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // WebSocket client for the Gemini Live API (Android has none built in).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Google sign-in for Gmail and Calendar (Authorization API)
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    testImplementation("junit:junit:4.13.2")
}
