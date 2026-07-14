plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

val javaCppPlatform = providers.systemProperty("javacpp.platform").orNull ?: currentJavaCppPlatform()

dependencies {
    implementation(project(":capture-core"))
    implementation(project(":editor-core"))
    implementation(project(":replay-buffer"))
    implementation(libs.javacv)
    implementation(libs.ffmpeg)
    implementation(libs.kotlinxCoroutines)
    implementation(libs.kotlinxSerialization)
    runtimeOnly("org.bytedeco:javacpp:${libs.versions.javacv.get()}:$javaCppPlatform")
    runtimeOnly("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.get()}:$javaCppPlatform")
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}

fun currentJavaCppPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val architecture = System.getProperty("os.arch").lowercase()
    val normalizedArchitecture = when (architecture) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported desktop architecture for FFmpeg: $architecture")
    }
    return when {
        os.startsWith("windows") -> "windows-$normalizedArchitecture"
        os.startsWith("mac") || os.startsWith("darwin") -> "macosx-$normalizedArchitecture"
        os.contains("linux") -> "linux-$normalizedArchitecture"
        else -> error("Unsupported desktop operating system for FFmpeg: $os")
    }
}
