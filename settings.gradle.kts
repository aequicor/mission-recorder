// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

dependencyResolutionManagement {
    // Use Maven Central as the default repository (where Gradle will download dependencies) in all subprojects.
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Include the `app` and `utils` subprojects in the build.
// If there are changes in only one of the projects, Gradle will rebuild only the one that has changed.
// Learn more about structuring projects with Gradle - https://docs.gradle.org/8.7/userguide/multi_project_builds.html
include(":app")
include(":audio-core")
include(":audio-desktop-javasound")
include(":audio-linux-pulse")
include(":audio-windows-wasapi")
include(":capture-core")
include(":capture-desktop-awt")
include(":capture-linux-x11")
include(":capture-macos-coregraphics")
include(":capture-platform-api")
include(":capture-windows-jna")
include(":cli")
include(":compose-ui")
include(":desktop-app")
include(":encoder")
include(":export")
include(":hotkey-core")
include(":hotkey-linux-x11")
include(":hotkey-macos-carbon")
include(":hotkey-windows-jna")
include(":media-desktop-ffmpeg")
include(":replay-buffer")
include(":settings")
include(":utils")

rootProject.name = "mission-recorder"
