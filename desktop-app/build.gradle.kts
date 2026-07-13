import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
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
    implementation(project(":compose-ui"))
    implementation(project(":hotkey-core"))
    implementation(project(":hotkey-linux-x11"))
    implementation(project(":hotkey-macos-carbon"))
    implementation(project(":hotkey-windows-jna"))
    implementation(project(":media-desktop-ffmpeg"))
    implementation(project(":replay-buffer"))
    implementation(project(":settings"))
    implementation(compose.components.resources)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinxCoroutines)
    implementation(libs.kotlinxCoroutinesSwing)
    implementation(libs.jna)
    implementation(libs.jnaPlatform)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}

compose.desktop {
    application {
        mainClass = "io.aequicor.desktop.MainKt"
        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Mission Recorder"
            packageVersion = "0.1.0"
            description = "Local open-source screen recorder"
            vendor = "Mission Recorder"
            windows {
                iconFile.set(project.file("src/main/resources/icons/mission-recorder.ico"))
            }
            macOS {
                iconFile.set(project.file("src/main/resources/icons/mission-recorder.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSScreenCaptureUsageDescription</key>
                        <string>Mission Recorder captures only the screen, window, or application explicitly selected for recording.</string>
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Mission Recorder records microphone audio only when the microphone source is explicitly enabled.</string>
                    """.trimIndent()
                }
            }
            linux {
                iconFile.set(project.file("src/main/resources/icons/mission-recorder.png"))
            }
        }
    }
}

val guiRunRequested = gradle.startParameter.taskNames.any { requested ->
    requested == "runGui" ||
        requested == ":runGui" ||
        requested == "desktop-app:run" ||
        requested == ":desktop-app:run"
}

val stableRunClasspathDirectory = layout.buildDirectory.dir("compose/stable-run-classpath")
val prepareStableRunClasspath = tasks.register<Sync>("prepareStableRunClasspath") {
    group = "compose desktop"
    description = "Copies the GUI runtime classpath so concurrent builds cannot replace JARs used by the running app."
    from(tasks.named("jar"))
    from(configurations.named("runtimeClasspath"))
    into(stableRunClasspathDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

tasks.withType<JavaExec>().matching { task -> task.name == "run" }.configureEach {
    dependsOn(prepareStableRunClasspath)
    classpath = files(stableRunClasspathDirectory.map { directory -> directory.asFileTree })
    enabled = guiRunRequested
}
