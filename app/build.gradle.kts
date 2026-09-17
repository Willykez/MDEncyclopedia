import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing material is pulled from environment variables so nothing secret
// ever needs to live in this file or in git. The CI workflow (see
// .github/workflows/release.yml) decodes KEYSTORE_B64 to a file on disk and
// exports these four variables before invoking Gradle. For local signed
// builds, export the same variables in your shell before running Gradle.
val keystorePathEnv = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/app/release.keystore"
val storePasswordEnv = System.getenv("STORE_PASSWORD")
val keyAliasEnv = System.getenv("KEY_ALIAS")
val keyPasswordEnv = System.getenv("KEY_PASSWORD")
val hasSigningEnv = storePasswordEnv != null && keyAliasEnv != null &&
        keyPasswordEnv != null && file(keystorePathEnv).exists()

android {
    namespace = "com.willykez.md"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.willykez.md"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0"

        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasSigningEnv) {
            create("release") {
                storeFile = file(keystorePathEnv)
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningEnv) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
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
        viewBinding = false
    }

    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
}
