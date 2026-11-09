import korlibs.korge.gradle.*

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.korge)
}

korge {
    id = "com.terra.world"
    name = "Terra"
    version = "0.1.0"
    jvmMainClassName = "terra.DesktopMainKt"
    targetJvm()
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.korge.engine)
        }
    }
}

// JNA uses System.load for the native windowing libraries. Newer JDKs require
// applications to explicitly allow native access for unnamed modules.
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
