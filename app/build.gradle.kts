import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
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
        buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", "\"${escapeBuildConfig(resolveFirebaseWebClientId())}\"")
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
            // On, and kept green by CI rather than by hope. R8 is the one step that can turn a
            // build that works into an APK that dies on the phone: it renames classes and strips
            // fields it cannot prove are used, and Filament reaches its own Java classes and
            // their native pointers by name from C++. app/proguard-rules.pro keeps that bridge
            // intact, and the build workflow assembles this exact variant on every push and reads
            // the DEX back to prove the rules held.
            isMinifyEnabled = true
            // Unused drawables and strings go too. Assets are untouched by this: the compiled
            // shaders are read from assets/materials/ by file name.
            isShrinkResources = true
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
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    androidResources {
        // Material packages and textures are read as raw blobs and must not be recompressed.
        noCompress += listOf("filamat", "ktx")
    }

}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // The full icon set: the nook uses Explore, LibraryBooks and AutoAwesome, none of which are
    // in icons-core. R8 strips the two thousand icons that are not referenced, so the release
    // cost is measured in kilobytes, not megabytes.
    implementation("androidx.compose.material:material-icons-extended")
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
    // Filament renders the revolving bookcase (see com.lantern.library.shelf3d).
    //
    // - filament-android: the engine itself. Its Java classes are JNI wrappers, so
    //   Filament.init() must be called before anything else, or the first native call fails.
    // - filamat-android: the material compiler as a library. Filament ships no built-in
    //   material and matc is a host tool Gradle cannot run, so the PBR shaders are compiled on
    //   the device once and cached in filesDir.
    //
    // Pinned to 1.72.0 deliberately: 1.73+ is built against a newer SDK and its AAR metadata
    // demands a compileSdk this project does not use.
    implementation("com.google.android.filament:filament-android:1.72.0")
    implementation("com.google.android.filament:filamat-android:1.72.0")
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

/**
 * Firebase Auth needs the Web OAuth client ID from Firebase/Google Cloud, not the Android one.
 * Empty is a valid answer: the app then says so instead of failing to sign in silently.
 */
fun resolveFirebaseWebClientId(): String =
    signingValue("FIREBASE_WEB_CLIENT_ID")
        ?: Properties().also { props ->
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { props.load(it) }
        }.getProperty("FIREBASE_WEB_CLIENT_ID")?.trim().takeIf { !it.isNullOrEmpty() }
        ?: ""
