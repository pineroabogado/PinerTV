import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(FileInputStream(file))
}

fun localProp(name: String, default: String = ""): String =
    localProperties.getProperty(name)?.trim().orEmpty().ifEmpty { default }

fun quoteForBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.piner.pinertv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.piner.pinertv"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Fire Stick 4K Max (arm64) + sticks que reportan v7a (AFTKRT, etc.)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        buildConfigField("String", "TMDB_API_KEY", "\"${quoteForBuildConfig(localProp("TMDB_API_KEY"))}\"")
        buildConfigField("String", "DEFAULT_NAS_URL", "\"${quoteForBuildConfig(localProp("NAS_URL", "http://192.168.18.74:5000"))}\"")
        buildConfigField("String", "DEFAULT_NAS_HOST", "\"${quoteForBuildConfig(localProp("NAS_HOST", "192.168.18.74"))}\"")
        buildConfigField("String", "DEFAULT_NAS_USER", "\"${quoteForBuildConfig(localProp("NAS_USER", "piner"))}\"")
        buildConfigField("String", "DEFAULT_NAS_PASS", "\"${quoteForBuildConfig(localProp("NAS_PASS"))}\"")
        buildConfigField("String", "DEFAULT_NAS_MAC", "\"${quoteForBuildConfig(localProp("NAS_MAC", "90:09:D0:65:AB:F1"))}\"")
        buildConfigField("String", "DEFAULT_NAS_BROADCAST", "\"${quoteForBuildConfig(localProp("NAS_BROADCAST", "192.168.18.255"))}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("io.coil-kt:coil:2.6.0")

    implementation("androidx.media3:media3-exoplayer:1.3.0")
    implementation("androidx.media3:media3-ui:1.3.0")
    implementation("androidx.media3:media3-common:1.3.0")
    implementation("androidx.media3:media3-extractor:1.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
