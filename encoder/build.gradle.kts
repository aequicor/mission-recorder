plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(project(":capture-core"))
    implementation(libs.kotlinxSerialization)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}
