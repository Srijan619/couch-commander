import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin itself, so no separate kotlin-android plugin is needed.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.couchcommander"
    compileSdk = 37

    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val traktClientId = localProps.getProperty("trakt.clientId") ?: ""

    defaultConfig {
        applicationId = "app.couchcommander"
        // Android 9: still common on TVs and sticks.
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // TMDB's API Read Access Token (not the short v3 API key), for the Movies and TV pages; see media-core's README.
        buildConfigField("String", "TMDB_API_KEY", "\"${localProps.getProperty("tmdb.apiKey") ?: ""}\"")
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"$traktClientId\"")
    }

    buildTypes {
        release {
            // Personal sideloaded build: signed with the debug key so `assembleRelease` installs directly.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation("app.football:football-core")
    // The Movies and TV pages: TMDB lists, details, seasons and where to watch.
    implementation("app.media:media-core")
    implementation(libs.ktor.client.okhttp)
    // The QR code for signing in to TMDB from a phone.
    implementation(libs.zxing.core)
    // The IPTV player: raw MPEG-TS and HLS live streams.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kxml2)
    testImplementation(libs.ktor.client.mock)
}
