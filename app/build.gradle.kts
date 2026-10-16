import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.pesu.tamil"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pesu.tamil"
        minSdk = 26            // LiteRT-LM needs Android 8.0+
        targetSdk = 34
        versionCode = 10
        versionName = "3.0.3"
        ndk { abiFilters += listOf("arm64-v8a") }   // phones only; keeps the APK small
    }

    // Same key as every earlier Pesu APK, so updates install over the old app and keep your words.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("pesu-release.keystore")
            storePassword = "pesu-tamil"
            keyAlias = "pesu"
            keyPassword = "pesu-tamil"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs { useLegacyPackaging = true }   // extract native libs so the GPU accelerators can be found
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Google's on-device LLM runtime (runs Gemma 4 E2B on the phone).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")   // keep in sync with the accelerator tag in build.yml
}
