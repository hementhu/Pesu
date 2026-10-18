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
        versionCode = 11
        versionName = "3.0.4"
        ndk { abiFilters += listOf("arm64-v8a") }   // phones only; keeps the APK small
    }

    // Release signing key, kept OUT of the repo. CI reads it from GitHub secrets (env vars);
    // a local build reads ~/.gradle/gradle.properties (pesu.keystore, pesu.keystorePassword).
    // The key was rotated on 2026-10-18 after the old one was published here, so APKs from
    // build 3.0.4 on do not install over earlier ones: uninstall the old Pesu first.
    val keystorePath = System.getenv("PESU_KEYSTORE") ?: (findProperty("pesu.keystore") as String?)
    val keystorePassword = System.getenv("PESU_KEYSTORE_PASSWORD")
        ?: (findProperty("pesu.keystorePassword") as String?)
    val releaseSigning = keystorePath != null && keystorePassword != null
    signingConfigs {
        if (releaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storeType = "pkcs12"
                storePassword = keystorePassword
                keyAlias = "pesu"
                keyPassword = keystorePassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigning) signingConfig = signingConfigs.getByName("release")
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
