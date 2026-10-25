import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Declared before the google/android blocks: script properties initialise in order, so a
// value used inside android {} must be assigned above it.
// 10 is the first code that can update over the old 3.5.0 (versionCode 9) builds.
val baseVersionCode = 10

android {
    namespace = "com.lantern.library"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lantern.library"
        minSdk = 26
        targetSdk = 34
        // CI passes LORE_VERSION_CODE (base + run number) so every build is newer than the
        // last one and Android accepts it as an in-place update. Local builds fall back here.
        versionCode = resolveVersionCode()
        versionName = "3.5.1"
        vectorDrawables.useSupportLibrary = true
        // Only the two ABIs phones actually use. The native renderer ships for both;
        // emulator ABIs would triple the download for nobody's benefit.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        buildConfigField("String", "GOOGLE_BOOKS_API_KEY", "\"${escapeBuildConfig(resolveGoogleBooksApiKey())}\"")
    }

    // A stable signing key is what makes "download the new APK and install it" update the
    // existing app instead of failing with a signature mismatch. Supplied by CI secrets or
    // by tools/make-update-key.sh locally. Without it we fall back to the throwaway debug
    // key, which Android will refuse to install over a previous build.
    signingConfigs {
        val keyFile = resolveSigningFile()
        val storePass = signingValue("LORE_KEYSTORE_PASSWORD")
        val alias = signingValue("LORE_KEY_ALIAS")
        val keyPass = signingValue("LORE_KEY_PASSWORD")
        if (keyFile != null && storePass != null && alias != null && keyPass != null) {
            create("stableUpdate") {
                storeFile = keyFile
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            if (signingConfigs.findByName("stableUpdate") != null) {
                signingConfig = signingConfigs.getByName("stableUpdate")
            }
        }
        release {
            if (signingConfigs.findByName("stableUpdate") != null) {
                signingConfig = signingConfigs.getByName("stableUpdate")
            }
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
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("io.github.oothp:pdfium-android:1.9.5-beta01")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

/** Dev: GOOGLE_BOOKS_API_KEY or google.books.api.key in local.properties (gitignored), or env/Gradle property. CI: GitHub secret GOOGLE_BOOKS_API_KEY. Never commit the key. */
fun resolveGoogleBooksApiKey(): String {
    val fromEnv = System.getenv("GOOGLE_BOOKS_API_KEY")?.trim().orEmpty()
    if (fromEnv.isNotEmpty()) return fromEnv
    val fromProp = (findProperty("GOOGLE_BOOKS_API_KEY") as? String)?.trim().orEmpty()
    if (fromProp.isNotEmpty()) return fromProp
    val file = rootProject.file("local.properties")
    if (!file.exists()) return ""
    val props = Properties()
    file.inputStream().use { props.load(it) }
    for (name in listOf("GOOGLE_BOOKS_API_KEY", "google.books.api.key")) {
        val value = props.getProperty(name)?.trim().orEmpty()
        if (value.isNotEmpty()) return value
    }
    return ""
}

fun escapeBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

fun signingValue(name: String): String? =
    System.getenv(name)?.trim().orEmpty()
        .ifEmpty { (project.findProperty(name) as? String)?.trim().orEmpty() }
        .takeIf { it.isNotEmpty() }

fun resolveVersionCode(): Int {
    // Absolute override wins (used by a release built for a specific version).
    signingValue("LORE_VERSION_CODE")?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
    // Otherwise CI adds the workflow run number, so every build is newer than the last.
    val build = signingValue("LORE_BUILD_NUMBER")?.toIntOrNull() ?: 0
    return baseVersionCode + build.coerceAtLeast(0)
}

fun resolveSigningFile(): File? {
    val path = signingValue("LORE_KEYSTORE_PATH") ?: return null
    val candidate = File(path)
    return if (candidate.exists()) candidate else null
}
