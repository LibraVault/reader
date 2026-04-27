plugins {
    id("libravault.android.feature")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.feature.library"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:logger"))
    implementation(project(":feature:player"))
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.activity:activity-compose:1.9.0")
}
