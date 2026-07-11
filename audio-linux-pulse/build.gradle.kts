plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":capture-core"))
    implementation(project(":capture-platform-api"))
    implementation(libs.kotlinxCoroutines)
    implementation(libs.kotlinxSerialization)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}
