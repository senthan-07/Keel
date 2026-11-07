plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.keel.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.keel.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        debug { manifestPlaceholders["appLabel"] = "Keel" }
        // Optimised build. It's signed with the same local key as debug so it can update your installed
        // app without losing data, which suits personal use. Publishing would need a dedicated key.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            manifestPlaceholders["appLabel"] = "Keel"
        }
        // The optimised build under the test copy's identity, to try it without touching real data.
        create("checkRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".check"
            manifestPlaceholders["appLabel"] = "Keel check"
            matchingFallbacks += "release"
        }
        // A separate test copy with its own data, so tests can never touch the real Keel's data.
        create("check") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".check"
            manifestPlaceholders["appLabel"] = "Keel check"
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":domain"))
    implementation("net.zetetic:sqlcipher-android:4.20.0@aar")
    implementation("androidx.sqlite:sqlite:2.7.1")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
}
