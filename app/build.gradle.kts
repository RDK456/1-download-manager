import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing material lives outside version control. When keystore/keystore.properties
// exists the release build is signed with that key; otherwise it falls back to the debug key
// so the project still assembles on a fresh clone or a CI machine.
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseStorePath = keystoreProperties.getProperty("storeFile")
val hasReleaseKeystore = !releaseStorePath.isNullOrBlank() && rootProject.file(releaseStorePath).exists()

android {
    namespace = "com.downloadhub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.downloadhub.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "1.4.22"

        // Single source of truth for the About page and the in-app updater.
        buildConfigField("String", "GITHUB_OWNER", "\"RDK456\"")
        buildConfigField("String", "GITHUB_REPO", "\"1-download-manager\"")
        buildConfigField("String", "GITHUB_URL", "\"https://github.com/RDK456/1-download-manager\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Obfuscation is intentionally off: the JNI engines (libtorrent4j,
            // yt-dlp/FFmpeg wrapper) and Room reflection are easier to keep correct
            // without a device to test on.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "No keystore/keystore.properties found - the release build is signed with " +
                        "the debug key and cannot replace an installed release-signed build."
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }

    lint {
        // Lint's UAST/Kotlin frontend intermittently crashes on this project's unit
        // test sources (it resolves org.json against both the android.jar stub and the
        // real org.json test dependency). App sources are still linted in full.
        checkTestSources = false
        abortOnError = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Shared engine code, compiled into the Windows build from the same sources.
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.android.arm64)
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.2")

    testImplementation(libs.junit)
    // Real org.json on the unit-test classpath (the Android stub only throws).
    testImplementation("org.json:json:20231013")
    // The same databind the yt-dlp wrapper maps with, so the format-mapping tests
    // read JSON the way the app does rather than the way a mock would.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.11.1")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
