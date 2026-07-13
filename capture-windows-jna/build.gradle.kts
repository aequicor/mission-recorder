plugins {
    id("buildsrc.convention.kotlin-jvm")
}

val javaCppPlatform = providers.systemProperty("javacpp.platform").orNull ?: currentJavaCppPlatform()

dependencies {
    implementation(project(":capture-core"))
    implementation(project(":capture-platform-api"))
    implementation(libs.javacv)
    implementation(libs.ffmpeg)
    implementation(libs.jna)
    implementation(libs.jnaPlatform)
    implementation(libs.kotlinxCoroutines)
    runtimeOnly("org.bytedeco:javacpp:${libs.versions.javacv.get()}:$javaCppPlatform")
    runtimeOnly("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.get()}:$javaCppPlatform")
    testImplementation(kotlin("test"))
}

fun currentJavaCppPlatform(): String {
    val architecture = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported Windows architecture for FFmpeg capture: ${System.getProperty("os.arch")}")
    }
    return "windows-$architecture"
}
