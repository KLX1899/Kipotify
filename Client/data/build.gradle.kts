plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.example.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        buildConfig = true
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

    defaultConfig {
        buildConfigField("String", "KIPOTIFY_BASE_URL", "\"$kipotifyBaseUrl\"")
        buildConfigField("String", "KIPOTIFY_FALLBACK_BASE_URLS", "\"$kipotifyFallbackBaseUrls\"")
        buildConfigField("boolean", "KIPOTIFY_ALLOW_INSECURE_LAN", kipotifyAllowInsecureLan)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
