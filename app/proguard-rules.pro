# Libravault ProGuard rules

# Keep Room entity classes
-keep class xyz.libravault.core.database.entity.** { *; }

# Keep Hilt generated components
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep Kotlin coroutines
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# Keep Readium (M2 — uncomment when added)
# -keep class org.readium.** { *; }

# Keep Media3 service (M3 — uncomment when added)
# -keep class androidx.media3.** { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
