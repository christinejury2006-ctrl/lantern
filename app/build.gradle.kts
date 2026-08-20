plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lantern.library"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lantern.library"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "3.5.0"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "GOOGLE_BOOKS_API_KEY", "\"${escapeBuildConfig(resolveGoogleBooksApiKey())}\"")
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
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.4.8" }
    packagingOptions {
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

fun resolveGoogleBooksApiKey(): String {
    val fromEnv = System.getenv("GOOGLE_BOOKS_API_KEY")?.trim().orEmpty()
    if (fromEnv.isNotEmpty()) return fromEnv
    val fromProp = (findProperty("GOOGLE_BOOKS_API_KEY") as? String)?.trim().orEmpty()
    if (fromProp.isNotEmpty()) return fromProp
    val file = rootProject.file("local.properties")
    if (!file.exists()) return ""
    val props = java.util.Properties()
    file.inputStream().use { props.load(it) }
    for (name in listOf("GOOGLE_BOOKS_API_KEY", "google.books.api.key")) {
        val value = props.getProperty(name)?.trim().orEmpty()
        if (value.isNotEmpty()) return value
    }
    return ""
}

fun escapeBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
