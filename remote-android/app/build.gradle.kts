import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing: CI passes a keystore through the NUVIO_REMOTE_KEYSTORE_B64 / _PASSWORD secrets so every
// build is signed the same way and installs over the last one. Without them the build falls back to
// the machine's debug key, which works but means uninstalling before each update.
val releaseKeystore: File? = System.getenv("NUVIO_REMOTE_KEYSTORE_B64")?.takeIf { it.isNotBlank() }?.let { encoded ->
    layout.buildDirectory.file("signing/nuvio-remote.jks").get().asFile.apply {
        parentFile.mkdirs()
        writeBytes(Base64.getMimeDecoder().decode(encoded))
    }
}

android {
    namespace = "com.spydermahn.nuvioremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spydermahn.nuvioremote"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("NUVIO_REMOTE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NUVIO_REMOTE_KEY_ALIAS") ?: "nuvio-remote"
                keyPassword = System.getenv("NUVIO_REMOTE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    // Scanning the QR code in Nuvio's Settings to pair; Google's scanner brings its own camera screen.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    testImplementation("junit:junit:4.13.2")
}
