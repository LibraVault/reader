import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Registered as `libravault.kmp.library` (see convention/build.gradle.kts). Not yet applied to
 * any module — this only fixes the dangling plugin ID (the class this registration pointed at
 * didn't exist). Converting an actual module (starting with core:domain) to multiplatform is
 * follow-up work, blocked on replacing java.time.Instant with kotlinx-datetime's Instant first
 * (core:domain's model classes aren't commonMain-compatible as-is, and that swap ripples into
 * core:database, core:logger, feature:reader, and feature:player too).
 */
class KotlinMultiplatformLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.multiplatform")
        }

        configure<LibraryExtension> {
            configureAndroidCommon(this)
        }

        configure<KotlinMultiplatformExtension> {
            androidTarget {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }

            iosArm64()
            iosSimulatorArm64()
            iosX64()

            jvm {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }

        dependencies {
            add("commonTestImplementation", "org.jetbrains.kotlin:kotlin-test")
        }
    }
}
