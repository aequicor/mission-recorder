import org.gradle.api.DefaultTask
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinPluginSerialization)
}

val releaseVersion = project.version.toString()
val macOsPackageVersion = releaseVersion
    .split(".")
    .let { components ->
        if (components.first() == "0") listOf("1") + components.drop(1) else components
    }
    .joinToString(".")

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
    implementation(project(":editor-core"))
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
    implementation(libs.kotlinxSerialization)
    implementation(libs.jna)
    implementation(libs.jnaPlatform)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}

compose.desktop {
    application {
        mainClass = "io.aequicor.desktop.MainKt"
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Mission Recorder"
            packageVersion = releaseVersion
            description = "Local open-source screen recorder"
            vendor = "Mission Recorder"
            windows {
                iconFile.set(project.file("src/main/resources/icons/mission-recorder.ico"))
            }
            macOS {
                bundleID = "io.aequicor.missionrecorder"
                packageVersion = macOsPackageVersion
                iconFile.set(project.file("src/main/resources/icons/mission-recorder.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSScreenCaptureUsageDescription</key>
                        <string>Record the screen, window, application, or area you explicitly select in Mission Recorder.</string>
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Record your voice commentary with the screen when you explicitly select a microphone.</string>
                    """.trimIndent()
                }
            }
            linux {
                iconFile.set(project.file("src/main/resources/icons/mission-recorder.png"))
            }
        }
    }
}

val innoSetupCompiler = providers.gradleProperty("innoSetupCompiler")
    .orElse(providers.environmentVariable("INNO_SETUP_COMPILER"))
    .orElse("ISCC.exe")
val windowsInstallerScript = layout.projectDirectory.file("src/main/installer/windows/MissionRecorder.iss")
val windowsAppImageDirectory = layout.buildDirectory.dir("compose/binaries/main-release/app/Mission Recorder")
val windowsInstallerOutputDirectory = layout.buildDirectory.dir("compose/binaries/main-release/inno")
val windowsInstallerFile = windowsInstallerOutputDirectory.map { directory ->
    directory.file("Mission Recorder-$releaseVersion-setup.exe")
}

tasks.register<Exec>("packageReleaseWindowsInstaller") {
    group = "compose desktop"
    description = "Builds the current-user Windows installer with Inno Setup."
    dependsOn("createReleaseDistributable")
    inputs.file(windowsInstallerScript)
    inputs.dir(windowsAppImageDirectory)
    inputs.property("appVersion", releaseVersion)
    inputs.property("innoSetupCompiler", innoSetupCompiler)
    outputs.file(windowsInstallerFile)
    commandLine(
        innoSetupCompiler.get(),
        "/DAppVersion=$releaseVersion",
        "/DSourceDir=${windowsAppImageDirectory.get().asFile.absolutePath}",
        "/DOutputDir=${windowsInstallerOutputDirectory.get().asFile.absolutePath}",
        windowsInstallerScript.asFile.absolutePath,
    )
}

val guiRunRequested = gradle.startParameter.taskNames.any { requested ->
    requested == "run" ||
        requested == ":run" ||
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

@DisableCachingByDefault(because = "Uses macOS disk image tools and a mounted writable volume.")
abstract class BrandDmgVolumeIconTask @Inject constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDmg: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val volumeIcon: RegularFileProperty

    @get:Input
    abstract val outputFileName: Property<String>

    @get:OutputDirectory
    abstract val destinationDirectory: DirectoryProperty

    @TaskAction
    fun brandVolume() {
        val workingDirectory = temporaryDir.toPath()
        fileSystemOperations.delete { delete(workingDirectory) }
        Files.createDirectories(workingDirectory)
        val writableImage = workingDirectory.resolve("writable.dmg")
        exec(
            "/usr/bin/hdiutil",
            "convert",
            inputDmg.get().asFile.absolutePath,
            "-format",
            "UDRW",
            "-o",
            writableImage.toString(),
        )

        val attachOutput = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(
                "/usr/bin/hdiutil",
                "attach",
                "-readwrite",
                "-noverify",
                "-noautoopen",
                "-nobrowse",
                writableImage.toString(),
            )
            standardOutput = attachOutput
        }.assertNormalExitValue()
        val mounted = parseMountedImage(attachOutput.toString(Charsets.UTF_8))
        try {
            Files.copy(
                volumeIcon.get().asFile.toPath(),
                mounted.path.resolve(".VolumeIcon.icns"),
                StandardCopyOption.REPLACE_EXISTING,
            )
            exec("/usr/bin/SetFile", "-a", "C", mounted.path.toString())
            removeUnexpectedVolumeEntries(mounted.path)
        } finally {
            exec("/usr/bin/hdiutil", "detach", mounted.device)
        }

        val destination = destinationDirectory.get().asFile.toPath()
        fileSystemOperations.delete { delete(destination) }
        Files.createDirectories(destination)
        exec(
            "/usr/bin/hdiutil",
            "convert",
            writableImage.toString(),
            "-format",
            "UDZO",
            "-imagekey",
            "zlib-level=9",
            "-o",
            destination.resolve(outputFileName.get()).toString(),
        )
    }

    private fun exec(vararg command: String) {
        execOperations.exec { commandLine(*command) }.assertNormalExitValue()
    }

    private fun parseMountedImage(output: String): MountedImage {
        val line = output.lineSequence().firstOrNull { it.contains(VOLUME_MOUNT_PREFIX) }
            ?: error("Could not find a mounted volume in hdiutil output:\n$output")
        val mountIndex = line.lastIndexOf(VOLUME_MOUNT_PREFIX)
        val device = line.trimStart().takeWhile { character -> !character.isWhitespace() }
        return MountedImage(
            device = device,
            path = Path.of(line.substring(mountIndex).trim()),
        )
    }

    private fun removeUnexpectedVolumeEntries(volumeRoot: Path) {
        Files.list(volumeRoot).use { entries ->
            entries
                .filter { entry -> entry.fileName.toString() !in REQUIRED_VOLUME_ENTRIES }
                .forEach { entry -> fileSystemOperations.delete { delete(entry) } }
        }
    }

    private data class MountedImage(
        val device: String,
        val path: Path,
    )

    private companion object {
        const val VOLUME_MOUNT_PREFIX = "/Volumes/"
        val REQUIRED_VOLUME_ENTRIES = setOf(
            ".DS_Store",
            ".VolumeIcon.icns",
            "Applications",
            "Mission Recorder.app",
        )
    }
}

fun configureBrandedDmg(
    packageTaskName: String,
    brandTaskName: String,
    binaryClassifier: String,
) {
    val rawDmgDirectory = layout.buildDirectory.dir("compose/binaries/$binaryClassifier/dmg-raw")
    val finalDmgDirectory = layout.buildDirectory.dir("compose/binaries/$binaryClassifier/dmg")
    val dmgFileName = "Mission Recorder-$macOsPackageVersion.dmg"
    val brandTask = tasks.register<BrandDmgVolumeIconTask>(brandTaskName) {
        inputDmg.set(rawDmgDirectory.map { directory -> directory.file(dmgFileName) })
        volumeIcon.set(layout.projectDirectory.file("src/main/resources/icons/mission-recorder.icns"))
        outputFileName.set(dmgFileName)
        destinationDirectory.set(finalDmgDirectory)
        onlyIf("DMG branding is available only on macOS") {
            System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)
        }
    }
    afterEvaluate {
        (tasks.findByName(packageTaskName) as? AbstractJPackageTask)?.apply {
            destinationDir.set(rawDmgDirectory)
            outputs.dir(rawDmgDirectory)
            finalizedBy(brandTask)
        }
    }
}

configureBrandedDmg(
    packageTaskName = "packageReleaseDmg",
    brandTaskName = "brandReleaseDmgVolumeIcon",
    binaryClassifier = "main-release",
)
