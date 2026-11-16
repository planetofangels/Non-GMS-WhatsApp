import java.util.Properties

plugins {
    id("com.android.application")
}

val signing = Properties().apply {
    val local = rootProject.file("keystore.properties")
    if (local.exists()) local.inputStream().use { load(it) }
}

android {
    namespace = "lolz.planetofangels.whatspush"
    compileSdk = 37

    defaultConfig {
        applicationId = "lolz.planetofangels.whatspush"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            signing.getProperty("storeFile")?.let { storeFile = file(it) }
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword") ?: signing.getProperty("storePassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (signing.getProperty("storeFile") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.18.0")
    testImplementation("junit:junit:4.13.2")
}
