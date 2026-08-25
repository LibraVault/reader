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

# androidx.pdf:pdf-viewer transitively depends on com.tom-roush:pdfbox-android,
# whose optional JPEG2000 image-filter path (JPXFilter) references
# com.gemalto.jp2.JP2Decoder -- a separate, non-bundled JP2 codec library.
# The app never adds that codec and never hits this path (JP2 is a rare
# encoding for embedded PDF images), but R8 fails minifyPlayReleaseWithR8
# outright on the unresolved reference unless told it's known-safe to skip.
# Found 2026-08-25: this had apparently never been exercised by any real
# assemblePlayRelease/assembleFdroidRelease build since pdf-viewer was added,
# only surfaced when actually cutting a release build again.
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.gemalto.jp2.**

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

# ── Strip Log.* calls in release (issue #528) ─────────────────────────────────
# Belt-and-suspenders on top of LibravaultLogger's own BuildConfig.DEBUG gate:
# any android.util.Log.* call anywhere in the app (including third-party code
# and any future call site that forgets the gate) is dropped from release
# builds entirely by R8, rather than merely relying on every call site
# behaving. R8 removes the call (and, where possible, the now-dead argument
# expressions) because these methods are declared to have no side effects.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
    public static int wtf(...);
}
