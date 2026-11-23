// Standalone payload template. Build once, drop the APK into
// app/src/main/assets/payload/template.apk, and OVERSIGHT repacks it per target.
//
// Zero external dependencies on purpose: no AndroidX, no Play Services. The artifact stays
// small, builds with nothing but the SDK, and runs on de-Googled devices where Play Services
// location is absent.

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "com.mobilesvc.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mobilesvc.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Debug signing keeps the template rebuildable without a keystore.
            // PayloadPackager re-signs with a fresh key regardless.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}
