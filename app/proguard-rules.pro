# ============================================================================
# NovelForge — R8 keep rules
#
# APPEND these to your existing app/proguard-rules.pro. They are the minimum
# needed before isMinifyEnabled = true is safe. Each block corresponds to a
# place where something outside the Kotlin type system reaches into your
# classes by NAME — reflection or JNI — which R8 cannot see and will happily
# rename or strip.
# ============================================================================


# ── Sherpa-ONNX JNI ─────────────────────────────────────────────────────────
# The native layer resolves these classes and reads their FIELDS by name from
# C++ (GetFieldID / GetMethodID). Renaming any of them produces a runtime
# NoSuchFieldError deep inside libsherpa-onnx-jni.so with no Kotlin stack
# frame, which is close to undebuggable. Keep the whole package.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}


# ── Gson ────────────────────────────────────────────────────────────────────
# BackupManager deserialises via fromJson(json, BackupData::class.java) and
# SourceHealthStore via a TypeToken<Map<String, SourceHealth>>. Gson maps JSON
# keys to field NAMES, so obfuscating these fields silently produces objects
# with every field null — a restored backup would look empty rather than
# throwing. Narrow keeps rather than blanket ones, so the rest still shrinks.
-keep class com.abhinavxt.novelforge.data.BackupManager$** { <fields>; }
-keep class com.abhinavxt.novelforge.data.source.health.SourceHealth { <fields>; }

# Gson's own reflective machinery.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# TypeToken subclasses are created as anonymous classes at each call site;
# their generic signature is the only carrier of the target type.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}


# ── Room ────────────────────────────────────────────────────────────────────
# Room ships its own consumer rules, so entities/DAOs are handled. This only
# silences the warning about the annotation processor's optional deps.
-dontwarn androidx.room.paging.**


# ── OkHttp / Okio ───────────────────────────────────────────────────────────
# Both ship consumer rules; these suppress known-benign warnings about
# optional Conscrypt / BouncyCastle / Animal Sniffer references.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**


# ── Jsoup ───────────────────────────────────────────────────────────────────
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**


# ── Kotlin coroutines ───────────────────────────────────────────────────────
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }


# ── Crash-report readability ────────────────────────────────────────────────
# Without this, every release stack trace is unmappable. Keep the mapping
# file that AGP writes to app/build/outputs/mapping/release/.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# ============================================================================
# VERIFY BEFORE SHIPPING — R8 failures are runtime-only and often silent:
#   1. Install a release build (not debug) on a real device.
#   2. Export a backup, wipe app data, restore it — checks Gson keeps.
#   3. Play TTS with a Sherpa voice — checks the JNI keeps.
#   4. Browse + open a chapter from a web source — checks Jsoup/OkHttp.
#   5. Open the source picker — checks SourceHealth deserialisation.
# ============================================================================