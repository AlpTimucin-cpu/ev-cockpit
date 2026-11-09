plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.evcockpit.mediahub"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.evcockpit.evguide.lite"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "1.2-embedded-map-beta"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = false }
        debug {
            // Side-by-side beta prevents differing CI debug signing keys from
            // overwriting existing EV Lite and user charging records.
            applicationIdSuffix = ".tvbeta"
            isDebuggable = false
        }
    }
}
dependencies {
    // Android WebViewAssetLoader safely serves our bundled map HTML.
    implementation("androidx.webkit:webkit:1.16.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
