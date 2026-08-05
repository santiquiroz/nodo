plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.santiquiroz.nodo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.santiquiroz.nodo"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // La keystore vive fuera del repo. Sin ella el release sale sin firmar, que es
    // mejor que fallar el build de quien clone el proyecto.
    val keystore = file(System.getProperty("user.home") + "/.android/nodo-release.jks")
    signingConfigs {
        if (keystore.exists()) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("NODO_KEYSTORE_PASS") ?: "nodo-release"
                keyAlias = "nodo"
                keyPassword = System.getenv("NODO_KEY_PASS") ?: "nodo-release"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:inference"))
    implementation(project(":core:serving"))
    implementation(project(":core:capability"))
    implementation(project(":feature:chat"))
    implementation(project(":core:models"))
    implementation(project(":core:settings"))
    implementation(project(":core:tools"))
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":feature:settings"))
    implementation(project(":feature:explore"))
    implementation(project(":feature:models"))
    implementation(project(":feature:server"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
