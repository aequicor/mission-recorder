plugins {
    kotlin("multiplatform")
}

kotlin {
    explicitApi()
    jvm()
    jvmToolchain(21)

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
