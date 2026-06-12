import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Reads a secret first from `local.properties` (key `kolco24.<name>`), then falls back to the
 * environment variable `KOLCO24_<ENV>`. The env fallback keeps `lintDebug`/`testDebugUnitTest`
 * (the merge gate) working in CI / clean checkouts without a `local.properties` file.
 */
fun secret(localPropsKey: String, envName: String): String? {
    val localProps = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { localProps.load(it) }
    }
    return localProps.getProperty(localPropsKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
}

val apiBaseUrl = secret("kolco24.apiBaseUrl", "KOLCO24_API_BASE_URL")
val appKeyId = secret("kolco24.appKeyId", "KOLCO24_APP_KEY_ID")
val appSecret = secret("kolco24.appSecret", "KOLCO24_APP_SECRET")

run {
    val missing = buildList {
        if (apiBaseUrl == null) add("kolco24.apiBaseUrl / KOLCO24_API_BASE_URL")
        if (appKeyId == null) add("kolco24.appKeyId / KOLCO24_APP_KEY_ID")
        if (appSecret == null) add("kolco24.appSecret / KOLCO24_APP_SECRET")
    }
    if (missing.isNotEmpty()) {
        error(
            "Missing required Kolco24 build config: ${missing.joinToString()}.\n" +
                "Set them either in local.properties (e.g. `kolco24.apiBaseUrl=...`) " +
                "or as environment variables (e.g. `KOLCO24_API_BASE_URL=...`)."
        )
    }
}

android {
    namespace = "ru.kolco24.kolco24"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "ru.kolco24.kolco24"
        minSdk = 24
        targetSdk = 36
        versionCode = 16
        versionName = "2.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "APP_KEY_ID", "\"$appKeyId\"")
        buildConfigField("String", "APP_SECRET", "\"$appSecret\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

base {
    archivesName.set("kolco24_v${android.defaultConfig.versionName}")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}