// Embed Probe: app usa e getta per la Fase 0.3 (EmbeddingGemma 2 sul dispositivo reale).
// Progetto Gradle autonomo, non collegato al repo MyCloud-Gallery.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "embed-probe"
include(":app")
