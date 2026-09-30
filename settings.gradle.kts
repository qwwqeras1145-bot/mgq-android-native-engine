pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mgq-android"

// Pure-Kotlin/JVM engine core. Must stay free of any Android dependency so it can be
// built and regression-tested on a desktop JVM against the real 380k-line script.
include(":core")

// Android application shell (thin rendering / audio / input layer).
include(":android")
