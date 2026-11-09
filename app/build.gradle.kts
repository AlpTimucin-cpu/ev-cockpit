plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.evcockpit.mediahub"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.evcockpit.mediahub"
        minSdk = 28
        targetSdk = 35
        versionCode = 63
        versionName = "0.62-mediaengine-media"
        // POCO F5 runs arm64-v8a. Prevent packaging 3 unused VLC/MapLibre ABIs.
        if (providers.gradleProperty("evcockpitEmulatorTest").orNull != "true") {
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = false }
        getByName("debug") {
            // Separate app ID preserves the user's existing media data despite
            // GitHub-hosted debug keystores changing between workflow runs.
            applicationIdSuffix = ".test"
            isDebuggable = false
        }
    }
}
dependencies {
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("com.google.guava:guava:33.4.8-android")
    // LibVLC engine version used by the GPL MediaEngine source project.
    implementation("org.videolan.android:libvlc-all:3.7.2")
    // Native Android map renderer; no Google Maps API key required.
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
