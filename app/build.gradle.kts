plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.mycloudgallery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mycloudgallery"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../mycloudgallery.jks")
            storePassword = "MyCloudGallery2024!"
            keyAlias = "mycloudgallery_key"
            keyPassword = "MyCloudGallery2024!"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
        arg("room.generateKotlin", "true")
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Work around an AndroidX lifecycle lint crash with Kotlin 2.1 on release builds.
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)

    // Material 3 Expressive (alpha override del BOM)
    implementation(libs.material3)
    implementation(libs.material3.window)
    implementation(libs.material3.adaptive.nav)
    implementation(libs.material.icons.extended)

    // Activity & Navigation
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Core
    implementation(libs.core.ktx)

    // Security (EncryptedSharedPreferences)
    implementation(libs.security.crypto)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx)
    implementation(libs.kotlinx.serialization.json)

    // Image Loading (Coil 3 — HEIC + WebP)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Media3 / ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // DataStore Preferences
    implementation(libs.datastore.preferences)

    // SMBJ for local NAS access
    implementation(libs.smbj)

    // Paging 3
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    // Accompanist Permissions
    implementation(libs.accompanist.permissions)

    // ML Kit — unbundled via Play Services (Step 1: APK size reduction)
    implementation(libs.play.services.mlkit.text.recognition)
    implementation(libs.play.services.mlkit.face.detection)
    implementation(libs.play.services.mlkit.image.labeling)
    // TFLite — custom models (MobileFaceNet, future Gemma 4)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)
    // Coroutines Play Services bridge (.await() on Tasks)
    implementation(libs.kotlinx.coroutines.play.services)

    // MapLibre — vista mappa GPS (Fase 2)
    implementation(libs.maplibre.android)

    // ExifInterface — estrazione metadati EXIF
    implementation(libs.exifinterface)

    // Glance — widget homescreen (Fase 3)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // MediaPipe Tasks GenAI — Gemma 4 on-device inference
    implementation(libs.mediapipe.tasks.genai)

    // Shared domain layer (Fase 5)
    implementation(project(":shared"))

    // Testing — Unit
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    // Testing — UI
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // Debug
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
