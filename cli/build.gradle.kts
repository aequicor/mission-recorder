plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":capture-core"))
    implementation(project(":capture-platform-api"))
    implementation(libs.kotlinxCoroutines)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}
