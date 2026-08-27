# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================
# TensorFlow Lite (Wake Word Detection)
# ============================================
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.**

# ============================================
# Supabase / Ktor / Kotlinx Serialization
# ============================================
# Keep Supabase classes
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**

# Ktor (used by Supabase)
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}

# Keep serializable data classes
-keep @kotlinx.serialization.Serializable class * { *; }

# ============================================
# OkHttp / Okio
# ============================================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ============================================
# Metadata-Extractor (EXIF/GPS extraction)
# ============================================
# Keep the metadata-extractor library for photo EXIF extraction
-keep class com.drew.** { *; }
-dontwarn com.drew.**

# ============================================
# RootEncoder / RTSP-Server (Camera Streaming)
# ============================================
# Keep all RootEncoder classes (video encoding, RTSP protocol)
-keep class com.pedro.** { *; }
-dontwarn com.pedro.**

# ============================================
# Dashie App Classes
# ============================================
# Keep all Halite classes (voice, sidebar, wake word)
-keep class com.dashieapp.Dashie.halite.** { *; }

# Keep WebView JavaScript interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ============================================
# Android / General
# ============================================
# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep R classes
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Suppress warnings for missing classes from optional dependencies
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============================================
# Eclipse Paho MQTT
# ============================================
# LoggerFactory resolves the concrete logger via Class.forName("...JSR47Logger"),
# so R8 strips the class and connect fails with "Error locating the logging class"
# on minified release builds. Keep the whole package + its logging bundle.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-keepnames class org.eclipse.paho.client.mqttv3.logging.** { *; }
-keep class org.eclipse.paho.client.mqttv3.internal.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
# Keep the bundled logging resource bundle (referenced by name in LoggerFactory)
-keepnames class ** implements java.util.ResourceBundle

# ============================================
# JNI-BOUND CLASSES — R8 must not rename these
# ============================================
# 🔴 1.1.0 release blocker, 2026-08-20 (T s42 cont.1/cont.3). Selecting On-Device STT made the
# MINIFIED prod build SIGABRT on every launch:
#     JNI DETECTED ERROR: fid == null in GetObjectField from Vad.newFromAsset
#
# THE TRAP, and why a spot check misses it: a class that owns `native` methods keeps its NAME for
# free — the Java_com_k2fsa_sherpa_onnx_* symbols in the .so pin it. So the class names look
# untouched in mapping.txt. But R8 still renames the FIELDS underneath, and the fields are what
# native reads: `VadModelConfig.sileroVadModelConfig -> a`. GetFieldID on the original name
# returns null and the runtime aborts. Measured in the shipped mapping: of 30 sherpa classes the
# 7 kept were exactly the ones with native methods; ALL 22 CONFIG classes were renamed
# (OfflineMoonshineModelConfig -> j5.k, SileroVadModelConfig -> j5.v, …).
#
# ⇒ THE RULE: if a native lib binds a package, keep the package WITH MEMBERS. Never keep only the
# class that crashed — the next config object aborts on the next cut. (Keeping Vad + VadModelConfig
# alone would have cleared the VAD and then died identically in OfflineRecognizer.)
#
# The authoritative list is the JNI symbol table, not intuition. To re-derive it:
#     strings app/src/**/jniLibs/*/lib*.so | grep -oE 'Java_[A-Za-z0-9_]+'
# `npm run lint:jni-keeps` (scripts/check-jni-keeps.mjs) enforces this automatically.

# sherpa-onnx (libsherpa-onnx-jni.so) — on-device STT + silero VAD. The blocker above.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# WebRTC AECM (libAEC.so) — found by sweeping the OTHER native libs while fixing sherpa, NOT by a
# crash: it had never been exercised on a minified build. Same mechanism exactly — AEC.java:458
# passes an AecmConfig OBJECT to nativeSetConfig, and the shipped mapping renamed
# AEC$AecmConfig.mAecmMode -> a. AEC.java:368 even says so in capitals: "DO NOT modify the name of
# members, or ... the native code could not find pre-binding members name."
-keep class ru.theeasiestway.libaecm.** { *; }
-dontwarn ru.theeasiestway.libaecm.**

# AEC3 (libdashie_aec3.so) — our own. Currently SAFE by construction: every external takes the
# handle as a Long parameter and passes only primitives/arrays, so native never reads a field by
# name. Kept anyway because that safety is a property of the current signatures, not of the
# binding — one future `external fun nativeX(cfg: SomeConfig)` would reintroduce the abort
# silently, and this is the class of bug that only shows up on a shipped artifact.
-keep class com.dashieapp.Dashie.voice.realtime.RealtimeAec3 { *; }
