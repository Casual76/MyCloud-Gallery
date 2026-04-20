# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MyCloud Gallery is a Kotlin Multiplatform (KMP) Android + Desktop gallery app for WD MyCloud NAS devices. It provides private, offline-first media browsing with SMB/WebDAV connectivity, on-device AI indexing (ML Kit), GPS map views, and home screen widgets.

## Modules

- `app/` — Android application (Hilt DI, Room, Compose UI, WorkManager, all features)
- `shared/` — KMP shared module (common domain models and business logic)
- `desktop/` — Compose Desktop (Windows/JVM) application

## Build Commands

```bash
./gradlew build                                      # Build all modules
./gradlew :app:installDebug                          # Install debug APK on device/emulator
./gradlew :app:assembleRelease                       # Build release APK
./gradlew :desktop:run                               # Run Desktop app
./gradlew :desktop:packageDistributionForCurrentOS  # Package Desktop (MSI/EXE)

# Testing
./gradlew :app:test                                  # Unit tests
./gradlew :app:connectedAndroidTest                  # Instrumented tests (requires device)
./gradlew :app:test --tests "*.ClassName.methodName" # Single test

# Lint
./gradlew :app:lint                                  # Android lint (NullSafeMutableLiveData is suppressed)
```

**Targets:** Java 17, minSdk 26, targetSdk 35.

## Architecture

Clean Architecture + MVVM with unidirectional data flow:

```
domain/model/          ← Pure Kotlin data classes (MediaItem, Album, NetworkMode)
domain/repository/     ← Repository interfaces (no Android deps)
data/repository/       ← Implementations: Room (local) + SMB/WebDAV/REST (remote)
core/database/         ← Room entities, DAOs, AppDatabase (version 2, FTS4)
core/network/          ← SmbClientImpl, WebDavClientImpl, WdRestApiService, NetworkDetector
core/di/               ← Hilt modules (NetworkModule, RepositoryModule, CoilModule, etc.)
core/security/         ← TokenManager using Android Keystore
presentation/          ← Compose screens + ViewModels (one folder per feature)
worker/                ← WorkManager: SyncWorker, IndexingWorker, TrashCleanupWorker
widget/                ← Glance home screen widget
```

## Key Architectural Patterns

**Network mode detection** (`NetworkDetector`): Probes LOCAL (direct IP, every 30s) → RELAY (wdmycloud.com) → OFFLINE. Exposed as `StateFlow<NetworkMode>`.

**SMB streaming to Coil**: `SmbClientImpl` streams files directly from NAS into Coil 3 via a custom fetcher — no intermediate buffering of full files. Persistent SMB sessions avoid repeated handshakes.

**Paging 3**: All media lists use `PagingSource` → `Pager` → `collectAsLazyPagingItems()` to avoid loading the full dataset.

**Room + FTS**: `AppDatabase` (version 2) includes `MediaFtsEntity` (virtual FTS4 table) for full-text search over `fileName`, `aiLabels`, `aiOcrText`, `aiScenes`.

**WorkManager jobs**: `SyncWorker` (NAS scan in batches of 100), `IndexingWorker` (ML Kit, runs only on charging), `SharedAlbumSyncWorker`, `TrashCleanupWorker` (30-day expiry). All scheduled in `MyCloudGalleryApplication` via `WorkScheduler`.

**Hilt scoping**: All repositories and network clients are `@Singleton`. ViewModels are `@HiltViewModel`.

## Navigation

Type-safe Compose Navigation with `NavHost`. Four bottom-nav destinations: Gallery, Albums, Search, Settings. Deep routes:

```
LoginRoute → GalleryRoute / AlbumsRoute / SearchRoute / SettingsRoute
AlbumsRoute → AlbumDetailRoute(albumId, isFavorites)
SettingsRoute → TrashRoute | DuplicatesRoute | MapRoute
Any list → ViewerRoute(mediaId)
```

Routes are defined as Kotlin `@Serializable` data classes/objects in `presentation/navigation/Routes.kt`.

## Key Dependencies (via `gradle/libs.versions.toml`)

| Area | Library |
|------|---------|
| UI | Compose BOM 2025.01.00, Material3 Expressive 1.5.0-alpha11 |
| DI | Hilt 2.53 |
| DB | Room 2.7.0 |
| Paging | Paging 3.3.4 |
| Network | OkHttp 4.12.0, Retrofit 2.11.0, smbj 0.13.0 |
| Images | Coil 3.0.4 |
| Media | Media3/ExoPlayer 1.5.1 |
| AI | ML Kit image-labeling 17.0.9, text-recognition 16.0.1 |
| Maps | MapLibre Android 11.8.1 |
| Widgets | Glance 1.1.1 |
| Prefs | DataStore Preferences 1.1.1 |
| Serialization | kotlinx.serialization 1.7.3 |
