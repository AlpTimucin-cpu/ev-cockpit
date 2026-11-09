# :nativecockpit production R8 preservation rules.
# R8 optimization is enabled in the release build. Consumer rules from
# AndroidX, Hilt, Retrofit, Gson, MapLibre and Media3 remain in effect.
# Broad keeps below prioritize compatibility but reduce shrinking efficiency.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-keepclasseswithmembers class * { native <methods>; }

# Hilt/Dagger: preserve generated bindings, entrypoints and reflection.
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep class dagger.hilt.** { *; }
-keep class com.evcockpit.EyaApplication { *; }
-keep class com.evcockpit.MainActivity { *; }
-keep class com.evcockpit.Hilt_* { *; }
-keep class com.evcockpit.**.Hilt_* { *; }
-keep class com.evcockpit.**_Factory { *; }
-keep class com.evcockpit.**_MembersInjector { *; }
-keep class com.evcockpit.Dagger* { *; }
-keep class com.evcockpit.ObdForegroundService { *; }
-keep class com.evcockpit.car.EyaCarAppService { *; }

# Retrofit and Gson: preserve reflective API interfaces, model signatures,
# coroutine continuation and annotation-driven field mappings.
-keep class kotlin.coroutines.Continuation
-keep class com.evcockpit.data.** { *; }
-keep interface com.evcockpit.data.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# MapLibre native JNI / dynamic class loading.
-keep class org.maplibre.** { *; }
-keep class org.maplibre.android.** { *; }

# Media3 service and player internals. Preserve for conservative initial R8
# rollout, then trim with tested consumer rules once a signed build is verified.
-keep class androidx.media3.** { *; }

# Compatibility for old integrations that used a top-level obd package.
# The current implementation also has concrete data.obd and domain.usecase keeps.
-keep class com.evcockpit.obd.** { *; }

# Retain volatile state used by Kotlin coroutines under R8.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Real Bluetooth ELM327 state machine and Android Auto entrypoints.
-keep class com.evcockpit.domain.usecase.Elm327Session { *; }
-keep class com.evcockpit.data.repository.Elm327VehicleRepository { *; }
-keep class com.evcockpit.data.obd.** { *; }
-keep class com.evcockpit.car.** { *; }

# No -dontoptimize / -dontwarn. Missing classes remain build errors.
