plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.qwwqeras1145.mgq"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.qwwqeras1145.mgq"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // The engine core is written in pure Kotlin with no ABI-specific code, so a single APK
        // covers every device. There is deliberately no NDK component: the port replaces Wine and
        // Box86/Box64 with an interpreter, not with a translation layer.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // The engine core: pure Kotlin, no Android dependency, unit-tested on the desktop JVM.
    implementation(project(":core"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation(kotlin("test"))
}
