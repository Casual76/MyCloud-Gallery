import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// NPU Qualcomm. Il runtime LiteRT di LiteRT-LM v0.18.0 e compilato contro QAIRT 2.50.0.260828
// (LiteRT commit 26895c9f, third_party/qairt/workspace.bzl): qnn-runtime 2.50.0 e la versione allineata.
// La guida LiteRT-LM cita ancora 2.42.0 come esempio: per provarla `-PqnnVersion=2.42.0`.
val qnnVersion: String = providers.gradleProperty("qnnVersion").getOrElse("2.50.0")
// libQnnHtpPrepare.so (85 MB scompattati) serve alla compilazione JIT sul dispositivo; con i file AOT
// probabilmente no. Inclusa di default per sicurezza: `-PqnnPrepare=false` per toglierla.
val qnnPrepare: Boolean = providers.gradleProperty("qnnPrepare").getOrElse("true").toBoolean()
// Il compiler plugin Qualcomm di LiteRT NON va nell'APK per i file .litertlm NPU precompilati: il modello di
// "embedding lookup" del testo non e precompilato, il plugin lo compila JIT (gather non supportato -> 7 op su 8 all'NPU),
// lo riserializza perdendo i pesi esterni e l'init fallisce con "Input tensor 2 lacks data". Senza plugin LiteRT salta il
// JIT (errore recuperabile in compiled_model.cc) e il lookup gira su CPU. `-PqnnCompilerPlugin=true` per rimetterlo.
val qnnCompilerPlugin: Boolean = providers.gradleProperty("qnnCompilerPlugin").getOrElse("false").toBoolean()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.pampa.mycloudgallery.spike.embedprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pampa.mycloudgallery.spike.embedprobe"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spike"

        // liblitertlm_jni.so esiste solo per arm64-v8a (e x86_64, che non ci serve).
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // Richiesto dalla guida NPU di LiteRT per le librerie Qualcomm (estratte in nativeLibraryDir).
            useLegacyPackaging = true
            // Dell'AAR QNN teniamo solo Hexagon v73 (SM8550, Tab S9) e v79 (SM8750, S25).
            excludes += setOf(
                "**/libQnnDsp*",
                "**/libQnnGpu.so",
                "**/libQnnHtpV68*",
                "**/libQnnHtpV69*",
                "**/libQnnHtpV75*",
                "**/libQnnHtpV81*",
            )
            if (!qnnPrepare) excludes += "**/libQnnHtpPrepare.so"
            if (!qnnCompilerPlugin) excludes += "**/libLiteRtCompilerPlugin_Qualcomm.so"
        }
        resources {
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

// Solo debug: la variante release non serve a uno spike.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // litertlm-android 0.18.0 e le sue dipendenze transitive (kotlin-reflect 2.4.0, coroutines 1.11.0)
        // portano metadati Kotlin 2.4: Edge Gallery fa lo stesso salto con questo flag.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    // Versione indicata dalla ricerca (docs/research/embeddinggemma2.md, 6/10/2026).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    // Solo per il download dei modelli (ripresa con Range, redirect di Hugging Face).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // NPU Qualcomm (doc ricerca par. 3.2): dispatch + compiler plugin LiteRT (Apache 2.0, unica versione: 2.3.0)
    // e librerie QNN di Qualcomm (licenza Qualcomm AI Hub Model License, vedi POM).
    implementation("com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.0")
    implementation("com.qualcomm.qti:qnn-runtime:$qnnVersion")

    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-alpha16")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
}
