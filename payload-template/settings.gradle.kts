pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Single module: sources live in src/main, the APK lands in build/outputs/apk/release/.
rootProject.name = "payload-template"
