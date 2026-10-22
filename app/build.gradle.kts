plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.sanskarpal.receiptsnap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sanskarpal.receiptsnap"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // phones and Apple-silicon/Intel emulators; drops 32-bit ML Kit libraries from the APK
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // the Flask mock as seen from the Android emulator; override with -PapiBaseUrl=http://<lan-ip>:8000/
        buildConfigField("String", "API_BASE_URL", "\"${providers.gradleProperty("apiBaseUrl").getOrElse("http://10.0.2.2:8000/")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // demo builds are debug-signed so the APK on the GitHub release installs without a keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.work.runtime.ktx)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
}
