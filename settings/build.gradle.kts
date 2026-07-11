plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    jvm()
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(project(":capture-core"))
            implementation(libs.kotlinxSerialization)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
