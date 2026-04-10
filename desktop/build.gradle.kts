import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.components.resources)

            implementation(project(":shared"))

            // Rete (stesso OkHttp dell'app Android — funziona su JVM)
            implementation(libs.okhttp)
            implementation(libs.kotlinx.serialization.json)

            // Coroutines con dispatcher Swing per il main thread desktop
            implementation(libs.kotlinx.coroutines.swing)

            // Caricamento immagini (Coil 3 supporta Compose Desktop)
            implementation(libs.coil.core)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.mycloudgallery.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MyCloudGallery"
            packageVersion = "1.0.0"
            description = "Galleria multimediale privata per WD MyCloud"
            copyright = "© 2026 Pampa"
            vendor = "Pampa"

            windows {
                menuGroup = "MyCloud Gallery"
                upgradeUuid = "9c3b4e2a-1f5d-4a8b-b3c7-e2f1a9d8c6b5"
                shortcut = true
                dirChooser = true
            }
        }

        buildTypes.release.proguard {
            isEnabled = false
        }
    }
}
