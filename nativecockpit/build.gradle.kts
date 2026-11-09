import java.io.File
import java.io.DataOutputStream
import kotlin.math.sin
import kotlin.math.min
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Local release signing credentials live in ignored local.properties.
// GitHub Actions supplies the same values as environment variables.
val localSigning = Properties().apply {
    val config = rootProject.file("local.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, environmentKey: String): String? =
    providers.environmentVariable(environmentKey).orNull
        ?.takeIf { it.isNotBlank() }
        ?: localSigning.getProperty(propertyKey)?.trim()?.takeIf { it.isNotBlank() }

val storePath = signingValue("eya.release.storeFile", "EYA_RELEASE_STORE_FILE")
val storePasswordValue = signingValue("eya.release.storePassword", "EYA_RELEASE_STORE_PASSWORD")
val keyAliasValue = signingValue("eya.release.keyAlias", "EYA_RELEASE_KEY_ALIAS")
val keyPasswordValue = signingValue("eya.release.keyPassword", "EYA_RELEASE_KEY_PASSWORD")
val releaseStore = storePath?.let { path ->
    File(path).let { if (it.isAbsolute) it else rootProject.file(path) }
}
val signedReleaseReady = releaseStore?.isFile == true &&
    !storePasswordValue.isNullOrBlank() &&
    !keyAliasValue.isNullOrBlank() &&
    !keyPasswordValue.isNullOrBlank()

android {
    namespace = "com.evcockpit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.evcockpit"
        minSdk = 28
        targetSdk = 35
        versionCode = 29
        versionName = "1.0.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        // No debug-key fallback. This is populated only with real owner credentials.
        if (signedReleaseReady) {
            create("eyaProduction") {
                storeFile = releaseStore
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Separate install ID and launcher name: never replace a user's
            // real signed app or trigger an incompatible-signature update.
            applicationIdSuffix = ".debug"
            // Debug remains explicitly labeled and separately installed.
            // "-TEST" is removed; this is NOT a production signing identity.
            versionNameSuffix = "-DEBUG"
            // No synthetic GPS, BMS SoC or OBD test modes are enabled.
            isMinifyEnabled = false
        }
        getByName("release") {
            isDebuggable = false
            // Only 64-bit ARM native binaries are packaged in Release.
            // Debug keeps its broader ABI coverage for host/emulator QA.
            ndk { abiFilters += "arm64-v8a" }
            // Production build: R8 code and resource shrinking enabled.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signedReleaseReady) {
                signingConfig = signingConfigs.getByName("eyaProduction")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// Fail closed: a signing-less artifact must NEVER be called a production APK.
gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { task ->
        task.project.path == ":nativecockpit" &&
            task.name in setOf("assembleRelease", "packageRelease", "validateSigningRelease")
    }
    if (buildingRelease && !signedReleaseReady) {
        throw GradleException(
            "Production release signing is not configured. Set " +
                "eya.release.storeFile/storePassword/keyAlias/keyPassword " +
                "in ignored local.properties (or EYA_RELEASE_* environment variables)."
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("org.maplibre.gl:android-sdk-turf:6.0.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")

    // Android Auto Car App Library host testing on the JVM (no mock GPS/OBD).
    testImplementation("androidx.car.app:app-testing:1.7.0")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Generate ten original, copyright-free mono WAV loops during Gradle builds.
// GitHub's text-only mobile workflow does not require uploading binary assets.
val generatedSoundRes = layout.buildDirectory.dir("generated/active-sound/res")
android.sourceSets.getByName("main").res.srcDir(generatedSoundRes)

val generateOriginalEngineWavAssets = tasks.register("generateOriginalEngineWavAssets") {
    val output = generatedSoundRes
    outputs.dir(output)
    doLast {
        val raw = output.get().asFile.resolve("raw").apply { mkdirs() }
        val sampleRate = 22_050
        val count = sampleRate * 2
        val profiles = linkedMapOf(
            "v8" to 64.0, "f1" to 175.0, "futuristic" to 115.0,
            "turbine" to 93.0, "stealth" to 42.0
        )
        for ((profile, fundamental) in profiles) {
            for (accelerating in listOf(false, true)) {
                val mode = if (accelerating) "accel" else "idle"
                val file = raw.resolve("${profile}_${mode}.wav")
                DataOutputStream(file.outputStream().buffered()).use { out ->
                    fun word(n: Int) {
                        out.writeByte(n and 255)
                        out.writeByte((n ushr 8) and 255)
                    }
                    fun dword(n: Int) {
                        word(n and 0xffff)
                        word(n ushr 16)
                    }
                    out.writeBytes("RIFF")
                    dword(36 + count * 2)
                    out.writeBytes("WAVEfmt ")
                    dword(16)
                    word(1)  // signed 16-bit PCM, mono
                    word(1)
                    dword(sampleRate)
                    dword(sampleRate * 2)
                    word(2)
                    word(16)
                    out.writeBytes("data")
                    dword(count * 2)
                    for (i in 0 until count) {
                        val t = i.toDouble() / sampleRate
                        val f = fundamental * if (accelerating) 1.75 else 1.0
                        val pulse = 1.0 + 0.10 * sin(
                            2.0 * Math.PI * (if (profile == "v8") 8.0 else 5.0) * t
                        )
                        val signal = (
                            0.52 * sin(2 * Math.PI * f * t) +
                            0.24 * sin(2 * Math.PI * 2 * f * t) +
                            0.12 * sin(2 * Math.PI * 3 * f * t)
                        ) * pulse
                        // Short fades avoid audible clicks at repeat boundaries.
                        val edge = min(i, count - 1 - i)
                        val fade = (edge.toDouble() / 256).coerceIn(0.0, 1.0)
                        val pcm = (signal * fade * 0.23 * 32767)
                            .toInt().coerceIn(-32768, 32767)
                        word(pcm and 0xffff)
                    }
                }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(generateOriginalEngineWavAssets) }
