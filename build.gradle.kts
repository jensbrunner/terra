import korlibs.korge.gradle.*
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

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
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// JNA uses System.load for the native windowing libraries. Newer JDKs require
// applications to explicitly allow native access for unnamed modules.
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("terra.weatherModel", providers.gradleProperty("weatherModel").orElse("prescribed").get())
    systemProperty("terra.profile", providers.gradleProperty("performanceProfile").orElse("false").get())
    systemProperty("terra.profile.seconds", providers.gradleProperty("profileSeconds").orElse("2.0").get())
    systemProperty("terra.profile.warmup", providers.gradleProperty("profileWarmup").orElse("0.5").get())
    systemProperty("terra.profile.speeds", providers.gradleProperty("profileSpeeds").orElse("").get())
    systemProperty("terra.profile.dayNight", providers.gradleProperty("profileDayNight").orElse("false").get())
    systemProperty("terra.profile.clouds", providers.gradleProperty("profileClouds").orElse("").get())
    systemProperty("terra.profile.output", providers.gradleProperty("profileOutput").orElse("build/experiments/phase-7/baseline").get())
    systemProperty("terra.atmosphereGeometry", providers.gradleProperty("atmosphereGeometry").orElse("LATITUDE_CHANNEL").get())
}

val experimentCompilation = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("main")
tasks.register<JavaExec>("dryAtmospherePreview") {
    group = "application"
    description = "Open the dry rotating atmosphere's interactive 2D preview."
    mainClass.set("terra.experiments.DryAtmospherePreviewKt")
    classpath(experimentCompilation.output.allOutputs, experimentCompilation.runtimeDependencyFiles)
    systemProperty("java.awt.headless", "false")
    systemProperty("terra.atmospherePreview.seconds", providers.gradleProperty("previewSeconds")
        .orElse(providers.gradleProperty("dryPreviewSeconds")).orElse("0").get())
}

tasks.register<JavaExec>("dryAtmosphereExperiment") {
    group = "application"
    description = "Advance the dry atmosphere and export PNG/GIF previews. Pass -PdryAtmosphereArgs='--help'."
    mainClass.set("terra.experiments.DryExperimentMainKt")
    classpath(experimentCompilation.output.allOutputs, experimentCompilation.runtimeDependencyFiles)
    systemProperty("java.awt.headless", "true")
    args(providers.gradleProperty("dryAtmosphereArgs").orElse("").get().split(' ').filter { it.isNotBlank() })
}

tasks.register<JavaExec>("moistAtmospherePreview") {
    group = "application"
    description = "Open the moist atmosphere's cloud, rainfall and thermal preview."
    mainClass.set("terra.experiments.DryAtmospherePreviewKt")
    classpath(experimentCompilation.output.allOutputs, experimentCompilation.runtimeDependencyFiles)
    systemProperty("java.awt.headless", "false")
    systemProperty("terra.moistPreview", "true")
    systemProperty("terra.atmospherePreview.seconds", providers.gradleProperty("previewSeconds").orElse("0").get())
}

tasks.register<JavaExec>("moistAtmosphereExperiment") {
    group = "application"
    description = "Advance moist thermal dynamics and export a cloud/rain animation."
    mainClass.set("terra.experiments.DryExperimentMainKt")
    classpath(experimentCompilation.output.allOutputs, experimentCompilation.runtimeDependencyFiles)
    systemProperty("java.awt.headless", "true")
    systemProperty("terra.moistExperiment", "true")
    args(providers.gradleProperty("moistAtmosphereArgs").orElse("").get().split(' ').filter { it.isNotBlank() })
}
