plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(project(":capture-core"))
            implementation(libs.kotlinxCoroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
