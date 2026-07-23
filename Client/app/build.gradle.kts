plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.example"
    compileSdk = 35

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    defaultConfig {
        applicationId = "com.aistudio.kipotify.xqpzn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val kipotifyBaseUrl = providers.gradleProperty("KIPOTIFY_BASE_URL")
        .orElse("http://127.0.0.1:18080/")
        .get()
    val kipotifyFallbackBaseUrls = providers.gradleProperty("KIPOTIFY_FALLBACK_BASE_URLS")
        .orElse("http://10.0.2.2:18080/,http://localhost:18080/")
        .get()
    val kipotifyAllowInsecureLan = providers.gradleProperty("KIPOTIFY_ALLOW_INSECURE_LAN")
        .orElse("false")
        .get()

    buildTypes {
        debug {
            buildConfigField("String", "KIPOTIFY_BASE_URL", "\"$kipotifyBaseUrl\"")
            buildConfigField("String", "KIPOTIFY_FALLBACK_BASE_URLS", "\"$kipotifyFallbackBaseUrls\"")
            buildConfigField("boolean", "KIPOTIFY_ALLOW_INSECURE_LAN", kipotifyAllowInsecureLan)
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "KIPOTIFY_BASE_URL", "\"$kipotifyBaseUrl\"")
            buildConfigField("String", "KIPOTIFY_FALLBACK_BASE_URLS", "\"$kipotifyFallbackBaseUrls\"")
            buildConfigField("boolean", "KIPOTIFY_ALLOW_INSECURE_LAN", kipotifyAllowInsecureLan)
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
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Retrofit
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)

    // Media3 (ExoPlayer + MediaSession)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
