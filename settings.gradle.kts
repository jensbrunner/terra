pluginManagement {
    repositories {
        maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
        mavenLocal()
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

rootProject.name = "terra"
