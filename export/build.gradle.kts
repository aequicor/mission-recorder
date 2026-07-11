plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(libs.kotlinxSerialization)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}
