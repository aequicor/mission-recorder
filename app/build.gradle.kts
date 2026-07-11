plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)

    // Apply the Application plugin to add support for building an executable JVM application.
    application
}

dependencies {
    implementation(project(":audio-core"))
    implementation(project(":audio-desktop-javasound"))
    implementation(project(":audio-linux-pulse"))
    implementation(project(":audio-windows-wasapi"))
    implementation(project(":capture-core"))
    implementation(project(":capture-desktop-awt"))
    implementation(project(":capture-linux-x11"))
    implementation(project(":capture-macos-coregraphics"))
    implementation(project(":capture-platform-api"))
    implementation(project(":capture-windows-jna"))
    implementation(project(":cli"))
    implementation(project(":encoder"))
    implementation(project(":export"))
    implementation(project(":media-desktop-ffmpeg"))
    implementation(project(":replay-buffer"))
    implementation(project(":settings"))
    implementation(libs.kotlinxCoroutines)
    implementation(libs.kotlinxSerialization)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}

application {
    mainClass = "io.aequicor.app.AppKt"
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}
