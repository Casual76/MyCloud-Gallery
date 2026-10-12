// NAS Probe: app usa e getta per la Fase 0.2 (misure SMB sul tablet reale).
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

rootProject.name = "nas-probe"
include(":app")
