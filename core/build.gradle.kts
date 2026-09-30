plugins {
    kotlin("jvm")
    application
}

dependencies {
    // Deliberately zero runtime dependencies: the engine core must be auditable and
    // must not drag a scripting runtime onto Android. JUnit only for tests.
    testImplementation(kotlin("test"))
}

// Compile with whatever JDK Gradle itself runs on. We deliberately avoid `jvmToolchain(17)`
// because that makes the build require a *second* local JDK (17) which many Windows machines do
// not have; `gradle.properties` pins the single installed JDK instead.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    // `main` lives inside a Kotlin object, so the generated class is `Main`, not `MainKt`.
    mainClass.set("mgq.core.cli.Main")
}

tasks.test {
    useJUnitPlatform()
}

// Diagnostic harness for isolating parser bugs against real script snippets.
// Run with: gradlew :core:scratch
tasks.register<JavaExec>("scratch") {
    group = "verification"
    description = "Run the parser scratch harness (real-snippet diagnostics)"
    mainClass.set("mgq.core.script.Scratch")
    classpath = sourceSets["test"].runtimeClasspath
}
