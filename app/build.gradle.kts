import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is loaded from a gitignored keystore.properties (see keystore.properties.example),
// so no signing secrets live in the repo. When it's absent, debug builds and an unsigned release
// still work.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasKeystore) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.example.deskpad"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.deskpad"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.2-a11y"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    // No Shizuku / no root: privilege comes from a user-enabled AccessibilityService.
    testImplementation("junit:junit:4.13.2")
}
