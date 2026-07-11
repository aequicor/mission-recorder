plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":capture-core"))
    implementation(project(":capture-platform-api"))
    implementation(libs.jna)
    implementation(libs.jnaPlatform)
    implementation(libs.kotlinxCoroutines)
    testImplementation(kotlin("test"))
}
