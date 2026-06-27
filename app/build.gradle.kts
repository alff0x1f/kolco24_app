import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Export Room schemas to a committed directory so the schema history is version-controlled and
// MigrationTestHelper can validate migrations against the generated schema (see schemas/).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * Escapes a raw string value so it is safe to embed inside a Java/Kotlin string literal
 * (i.e. inside `"..."` in generated BuildConfig source). Handles backslash and double-quote.
 */
fun String.escapeJavaLiteral(): String = replace("\\", "\\\\").replace("\"", "\\\"")

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

// Local LAN upload target for GPS tracks (second, cleartext host). Unlike the secrets above this
// has a default so the build never fails when the key is absent (it is optional infrastructure).
//
// HOST ↔ CLEARTEXT COUPLING (by design): res/xml/network_security_config.xml permits cleartext
// traffic ONLY for the literal host 192.168.1.5. This config key may freely change the port, path,
// or scheme, but changing the *host* requires a synchronous edit to network_security_config.xml
// (Android cannot select a cleartext domain-config at runtime). A future arbitrary-LAN-host
// feature would be a separate task (debug-only broad cleartext, or runtime host entry with a
// dynamic security config — not supported out of the box).
val localApiBaseUrl = secret("kolco24.localApiBaseUrl", "KOLCO24_LOCAL_API_BASE_URL")
    ?: "http://192.168.1.5/"

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
        versionCode = 19
        versionName = "2.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl!!.escapeJavaLiteral()}\"")
        buildConfigField("String", "APP_KEY_ID", "\"${appKeyId!!.escapeJavaLiteral()}\"")
        buildConfigField("String", "APP_SECRET", "\"${appSecret!!.escapeJavaLiteral()}\"")
        buildConfigField("String", "LOCAL_API_BASE_URL", "\"${localApiBaseUrl.escapeJavaLiteral()}\"")
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // MigrationTestHelper reads the exported schema JSONs from the androidTest assets.
    sourceSets["androidTest"].assets.srcDirs("$projectDir/schemas")
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
    implementation(libs.play.services.location)
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
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
