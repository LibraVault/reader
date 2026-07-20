plugins {
    id("libravault.android.feature")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.feature.settings"

    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") { dimension = "distribution" }
        create("play")   { dimension = "distribution" }
    }
}

dependencies {
    api(project(":core:storage"))
    api(project(":core:logger"))
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("com.google.zxing:core:3.5.3")
    // OkHttp is play-only — fdroid build has no network calls
    "playImplementation"("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // SettingsViewModelTest constructs ~12 MockK mocks per test plus mocks
    // Uri.parse statically, which inflates Metaspace. Bump the heap so the
    // runner doesn't OOM at classload time.
    tasks.withType<Test>().configureEach {
        maxHeapSize = "2g"
        jvmArgs("-XX:MaxMetaspaceSize=768m")
    }
}
