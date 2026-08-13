# Libravault ProGuard / R8 rules
# Applied to release builds only.

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# ── Kotlinx Serialization ─────────────────────────────────────────────────────
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }

# ── Room entities ─────────────────────────────────────────────────────────────
-keep class xyz.libravault.core.database.entity.** { *; }

# ── Hilt ──────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.** { *; }

# ── Readium ───────────────────────────────────────────────────────────────────
-keep class org.readium.** { *; }
-dontwarn org.readium.**

# ── Media3 / ExoPlayer ────────────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Coil 3 ────────────────────────────────────────────────────────────────────
-keep class coil3.** { *; }
-dontwarn coil3.**

# ── AndroidX PDF Viewer ───────────────────────────────────────────────────────
-keep class androidx.pdf.** { *; }
-dontwarn androidx.pdf.**

# ── sherpa-onnx JNI (Pocket TTS) ──────────────────────────────────────────────
# libsherpa-onnx-jni.so uses *static* JNI registration: the runtime resolves
# each `external fun` to a symbol built from the fully-qualified class name
# (Java_com_k2fsa_sherpa_onnx_OfflineTts_newFromFile, ...), and the native side
# additionally reads the config data classes' fields by name via GetFieldID and
# constructs GeneratedAudio itself. R8 renaming any of that silently breaks
# every native call in release builds only - debug builds are not minified, so
# neither PocketTtsAudioOutputTest nor manual debug testing would catch it.
# Keep names and members for the whole vendored package.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class com.k2fsa.sherpa.onnx.** { native <methods>; }

# ── Guava (MediaController) ───────────────────────────────────────────────────
-dontwarn com.google.common.**
-keep class com.google.common.util.concurrent.** { *; }

# ── Suppress known-safe warnings ──────────────────────────────────────────────
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.**
-dontwarn okio.**
