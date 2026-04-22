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

# ── Guava (MediaController) ───────────────────────────────────────────────────
-dontwarn com.google.common.**
-keep class com.google.common.util.concurrent.** { *; }

# ── Suppress known-safe warnings ──────────────────────────────────────────────
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.**
-dontwarn okio.**
