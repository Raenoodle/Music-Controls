import java.util.Properties

plugins {
    id("com.android.application") version "9.2.1"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Signing keys stay outside the repository; see keystore.properties (git-ignored).
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

android {
    namespace = "dev.dariuss.musicbridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.dariuss.musicbridge"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.2"
    }

    sourceSets {
        named("main") {
            kotlin.directories += "src/main/kotlin"
        }
    }

    if (signing.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // MediaControllerCompat reads and sets shuffle and repeat, which the platform MediaController cannot.
    implementation("androidx.media:media:1.7.1")
}
