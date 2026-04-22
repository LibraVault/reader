import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "xyz.libravault.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.plugins.android.application.get().let { "${it.pluginId}:${it.version}" })
    compileOnly(libs.plugins.android.library.get().let    { "${it.pluginId}:${it.version}" })
    compileOnly(libs.plugins.kotlin.android.get().let     { "${it.pluginId}:${it.version}" })
    compileOnly(libs.plugins.compose.compiler.get().let   { "${it.pluginId}:${it.version}" })
    compileOnly(libs.plugins.ksp.get().let                { "${it.pluginId}:${it.version}" })
    compileOnly(libs.plugins.hilt.get().let               { "${it.pluginId}:${it.version}" })
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "libravault.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "libravault.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidFeature") {
            id = "libravault.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "libravault.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidCompose") {
            id = "libravault.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
    }
}
