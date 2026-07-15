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
    val os = System.getProperty("os.name").lowercase()
    val architecture = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported desktop architecture for FFmpeg capture: ${System.getProperty("os.arch")}")
    }
    return when {
        os.startsWith("windows") -> "windows-$architecture"
        os.startsWith("mac") || os.startsWith("darwin") -> "macosx-$architecture"
        os.contains("linux") -> "linux-$architecture"
        else -> error("Unsupported desktop operating system for FFmpeg capture: $os")
    }
}
