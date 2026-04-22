import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.application")
            apply("org.jetbrains.kotlin.android")
            apply("org.jetbrains.kotlin.plugin.compose")
        }
        configure<ApplicationExtension> {
            configureAndroidCommon(this)
            defaultConfig.targetSdk = 35
            buildFeatures.compose = true
            compileOptions {
                isCoreLibraryDesugaringEnabled = true
            }
        }
        target.dependencies {
            add("coreLibraryDesugaring", "com.android.tools:desugar_jdk_libs:2.0.4")
        }
    }
}
