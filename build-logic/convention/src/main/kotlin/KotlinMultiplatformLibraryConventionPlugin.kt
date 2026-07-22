import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

class KotlinMultiplatformLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")

        extensions.configure<LibraryExtension> {
            compileSdk = 34
            defaultConfig {
                minSdk = 24
            }
        }

        extensions.configure<KotlinMultiplatformExtension> {
            androidTarget {
                compilations.configureEach {
                    compilerOptions.configure {
                        jvmTarget.set(JvmTarget.JVM_17)
                    }
                }
            }

            iosArm64()
            iosSimulatorArm64()
            iosX64()

            jvm {
                compilations.configureEach {
                    compilerOptions.configure {
                        jvmTarget.set(JvmTarget.JVM_17)
                    }
                }
            }
        }
    }
}
