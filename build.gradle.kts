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
    // Explicitly opt in; the normal desktop entrypoint has no experiment observer.
    systemProperty("terra.baseline", providers.gradleProperty("baseline").orElse("false").get())
    systemProperty("terra.baseline.output", providers.gradleProperty("baselineOutput").orElse("build/experiments/live").get())
    systemProperty("terra.baseline.warmup", providers.gradleProperty("baselineWarmup").orElse("15").get())
    systemProperty("terra.baseline.seconds", providers.gradleProperty("baselineSeconds").orElse("30").get())
    systemProperty("terra.baseline.trials", providers.gradleProperty("baselineTrials").orElse("3").get())
    systemProperty("terra.baseline.phases", providers.gradleProperty("baselinePhases").orElse("true").get())
    systemProperty("terra.baseline.speeds", providers.gradleProperty("baselineSpeeds").orElse("").get())
    systemProperty("terra.baseline.gradle", gradle.gradleVersion)
}

val experimentCompilation = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("main")
tasks.register<JavaExec>("baselineExperiment") {
    group = "verification"
    description = "Measure existing weather/hydrology without a game window. Pass -PbaselineArgs='--help'."
    mainClass.set("terra.experiments.BaselineExperimentKt")
    classpath(experimentCompilation.output.allOutputs, experimentCompilation.runtimeDependencyFiles)
    args(providers.gradleProperty("baselineArgs").orElse("").get().split(' ').filter { it.isNotBlank() })
}

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
