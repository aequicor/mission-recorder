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
        jvmTest.dependencies {
            implementation(project(":encoder"))
            implementation(kotlin("test"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
