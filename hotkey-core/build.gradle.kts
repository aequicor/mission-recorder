plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinxCoroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
