plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":hotkey-core"))
    implementation(libs.jna)
    implementation(libs.jnaPlatform)
    implementation(libs.kotlinxCoroutines)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}
