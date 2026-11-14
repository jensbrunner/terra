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
    systemProperty("terra.profile", providers.gradleProperty("performanceProfile").orElse("false").get())
    systemProperty("terra.profile.seconds", providers.gradleProperty("profileSeconds").orElse("2.0").get())
    systemProperty("terra.profile.warmup", providers.gradleProperty("profileWarmup").orElse("0.5").get())
    systemProperty("terra.profile.speeds", providers.gradleProperty("profileSpeeds").orElse("").get())
    systemProperty("terra.profile.weather", providers.gradleProperty("profileWeather").orElse("false").get())
    systemProperty("terra.profile.wind", providers.gradleProperty("profileWind").orElse("false").get())
    systemProperty("terra.profile.dayNight", providers.gradleProperty("profileDayNight").orElse("false").get())
    systemProperty("terra.profile.output", providers.gradleProperty("profileOutput").orElse("build/experiments/desktop-profile").get())
}

val experimentCompilation = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("main")
tasks.register<JavaExec>("atlasUiPreview") {
    group = "application"
    description = "Export actual game UI views for a short visual review."
    mainClass.set("terra.experiments.AtlasUiPreviewKt")
    classpath(experimentCompilation.output.allOutputs, experimentCompilation.runtimeDependencyFiles)
    jvmArgs((tasks.named("runJvm").get() as JavaExec).jvmArgs)
    systemProperty("terra.uiReviewStates", providers.gradleProperty("uiReviewStates")
        .orElse("overview,inspection,details,speed,settings,advanced-settings,settings-edited,help").get())
}

tasks.register<JavaExec>("climateAtlas") {
    group = "verification"
    description = "Export seasonal climate maps for three seeds and controlled geographical comparisons."
    mainClass.set("terra.experiments.ClimateAtlasKt")
    classpath(experimentCompilation.output.allOutputs, experimentCompilation.runtimeDependencyFiles)
    args(providers.gradleProperty("climateAtlasArgs").orElse("").get().split(' ').filter { it.isNotBlank() })
}

tasks.register<JavaExec>("climateBenchmark") {
    group = "verification"
    description = "Compare climate atlas generation time and retained heap at coarse and native resolution."
    mainClass.set("terra.experiments.ClimateBenchmarkKt")
    classpath(experimentCompilation.output.allOutputs, experimentCompilation.runtimeDependencyFiles)
    args(providers.gradleProperty("climateBenchmarkArgs").orElse("").get().split(' ').filter { it.isNotBlank() })
}

// Opt-in GPU regression for transparent raster interpolation; no numerical simulation.
tasks.register<JavaExec>("rasterBlendCheck") {
    group = "verification"
    description = "Check raster crossfade brightness and interruption with 16 tiny native-GPU samples."
    jvmArgs((tasks.named("runJvm").get() as JavaExec).jvmArgs)
    mainClass.set("terra.experiments.RasterBlendCheckKt")
    classpath(experimentCompilation.output.allOutputs, experimentCompilation.runtimeDependencyFiles)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("java.awt.headless", "false")
}
