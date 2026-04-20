# MyCloud Gallery

## Project Overview
MyCloud Gallery is a Kotlin Multiplatform (KMP) application designed as a private multimedia gallery for WD MyCloud devices. It targets both Android and Desktop (Windows/JVM) platforms.

### Key Technologies & Libraries
*   **Core**: Kotlin (2.1.0), Kotlin Multiplatform.
*   **UI Framework**: Jetpack Compose (Android) and Compose Multiplatform (Desktop) using Material 3 Expressive.
*   **Dependency Injection**: Hilt (Android).
*   **Networking**: OkHttp, Retrofit, and `kotlinx.serialization` for JSON parsing.
*   **Image Loading**: Coil 3 (used across Android and Desktop).
*   **Local Storage**: Room (Android) and DataStore Preferences.
*   **Media Playback**: Media3 / ExoPlayer.
*   **Background Processing**: WorkManager.
*   **Advanced Features**: ML Kit for on-device AI indexing (image labeling, text recognition), MapLibre for GPS map views, and Glance for homescreen widgets.

## Directory Structure
*   `app/`: The Android application module containing Android-specific UI, features, and DI setup.
*   `desktop/`: The Compose Desktop application module targeting JVM/Windows.
*   `shared/`: The shared Kotlin Multiplatform module containing common business logic, domain models, and shared networking code.
*   `gradle/`: Gradle wrapper and version catalog (`libs.versions.toml`).

## Building and Running
This project uses Gradle as its build system. You can use the included wrapper (`gradlew` or `gradlew.bat`).

*   **Build everything**:
    ```bash
    ./gradlew build
    ```
*   **Run Android app (Debug)**:
    ```bash
    ./gradlew :app:installDebug
    ```
*   **Run Desktop app**:
    ```bash
    ./gradlew :desktop:run
    ```
*   **Package Desktop app (MSI/EXE)**:
    ```bash
    ./gradlew :desktop:packageDistributionForCurrentOS
    ```

## Development Conventions
*   **Target Compatibility**: Java 17 is used for compilation.
*   **Android SDK**: Minimum SDK 26, Target SDK 35.
*   **Testing**: JUnit 5, MockK, Coroutines Test, and Espresso are configured for unit and UI testing.
*   **UI Declarations**: The project heavily utilizes Jetpack Compose and Compose Multiplatform; avoid legacy View-based Android implementations unless strictly necessary.
