import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Signing secrets are read from local.properties (git-ignored) instead of being hardcoded here.
// Keys: TVASSIST_STORE_FILE (optional), TVASSIST_STORE_PASSWORD, TVASSIST_KEY_ALIAS, TVASSIST_KEY_PASSWORD.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.tvassist"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvassist"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"
        // Only package ARM native libs (drop x86/x86_64 — emulator-only) so the universal APK
        // stays as small as possible while still covering 32-bit + 64-bit Android TV devices.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Per-ABI APKs (armeabi-v7a for the 32-bit BRAVIAs/budget sticks, arm64-v8a for modern boxes)
    // plus one universal APK. x86/x86_64 dropped (emulator-only) to keep size down.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val storeFileName = keystoreProperties.getProperty("TVASSIST_STORE_FILE") ?: "tv-assist-release.jks"
            storeFile = rootProject.file(storeFileName)
            storePassword = keystoreProperties.getProperty("TVASSIST_STORE_PASSWORD")
            keyAlias = keystoreProperties.getProperty("TVASSIST_KEY_ALIAS")
            keyPassword = keystoreProperties.getProperty("TVASSIST_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Shrink + obfuscate with R8. Keep rules live in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            // Only sign if the secrets are present locally; otherwise leave unsigned so the build
            // still configures (e.g. on a machine without the keystore).
            signingConfig = if (keystoreProperties.getProperty("TVASSIST_STORE_PASSWORD") != null) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }

    lint {
        // Acknowledged pre-existing/deliberate/third-party findings, so lint stays green. Each is a
        // conscious choice, not an oversight:
        disable += setOf(
            // Remaining "newer version available" nags target media3 1.8+/Compose 2026/AGP 9, which
            // need compileSdk 36 and/or a Kotlin bump — a deliberate, on-BRAVIA-retested migration,
            // not a lint action. (This project is on compileSdk 35 / AGP 8.7 / media3 1.5.)
            "GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi", "NewerVersionAvailable",
            // Intentional for talking to a local Home Assistant / LAN onboarding over http, and
            // legacy external storage on Android 10 for backup/restore (see AndroidManifest.xml).
            "InsecureBaseConfiguration", "ScopedStorage",
            // "Credentials" here are the setup console's own token shown to the user on their LAN.
            "AuthLeak",
            // Inside the bundled BouncyCastle jar (mints the self-signed cert for the setup console).
            "TrustAllX509TrustManager",
            // 32-bit + 64-bit ARM only by design (Android TV); x86 is emulator-only.
            "ChromeOsAbiSupport",
            // Cosmetic / informational; not worth churn in this TV-only app.
            "VectorRaster", "MonochromeLauncherIcon", "UnusedResources",
            "ObsoleteSdkInt", "ViewConstructor",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tv.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    // Mint the self-signed TLS cert for the on-demand HTTPS setup console (Android can't build an
    // X.509 cert on its own). jdk15to18 variant is the Android-compatible one.
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Icon rendering: Coil + SVG decoder for faithful Iconify/MDI rendering (replaces the
    // hand-rolled SVG→ImageVector parser, which mis-tessellated complex icons).
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    // libVLC — alternative player for streams ExoPlayer struggles with (HEVC, quirky RTSP,
    // software-decode fallback). Native .so libs are why we split by ABI.
    implementation(libs.libvlc.all)

    // Camera streaming + notification video via ExoPlayer (Media3 1.5.x pairs with compileSdk 35).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)

    // Unit tests (pure-JVM: field parsing, serialization round-trips, notification-store timing).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
