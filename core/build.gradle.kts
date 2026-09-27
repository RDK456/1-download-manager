plugins {
    // Applied by id, not by alias: the version comes from the root classpath.
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // The libtorrent4j JVM artifact carries no native code, so :core stays
    // platform neutral. Each app then adds the native library it needs:
    // libtorrent4j-android-arm64 for Android, libtorrent4j-windows for desktop.
    implementation(libs.libtorrent4j)
    testImplementation(libs.junit)
    // The engine is platform neutral, so :core itself has no native library and its
    // tests could not exercise libtorrent at all. Putting the Windows native on the
    // *test* classpath only lets the shared engine be verified on the build machine
    // while production stays free of any platform binding.
    testRuntimeOnly("org.libtorrent4j:libtorrent4j-windows:2.1.0-39")
}

tasks.withType<Test> {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}
