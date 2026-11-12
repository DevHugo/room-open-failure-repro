plugins {
    kotlin("jvm") version "2.4.10"
    id("com.google.devtools.ksp") version "2.3.11"
}

val roomVersion = "2.8.4"
val sqliteVersion = "2.6.2"

dependencies {
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.sqlite:sqlite-bundled:$sqliteVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation(kotlin("test-junit"))
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
