package dev.pampa.mycloudgallery.feature.settings.about

import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.feature.settings.R

/**
 * Le licenze che le librerie dell'APK di release dichiarano, con il nome breve (la riga della libreria) e l'indirizzo (la
 * sezione "Licenze"). Gli indirizzi sono risorse e **testo**: nessun tocco li apre, la galleria non fa rete (D18, D24).
 */
internal enum class LibraryLicense(@StringRes val shortName: Int, @StringRes val fullName: Int, @StringRes val url: Int) {
  APACHE_2(R.string.about_license_apache, R.string.about_license_apache_full, R.string.about_license_apache_url),
  MIT(R.string.about_license_mit, R.string.about_license_mit_full, R.string.about_license_mit_url),
  BOUNCY_CASTLE(R.string.about_license_bc, R.string.about_license_bc_full, R.string.about_license_bc_url),
  SIL_OFL(R.string.about_license_ofl, R.string.about_license_ofl_full, R.string.about_license_ofl_url),

  /** Fase 9 (P13): MapLibre Native Android e le sue librerie dei gesti. */
  BSD_2(R.string.about_license_bsd2, R.string.about_license_bsd2_full, R.string.about_license_bsd2_url),
}

/**
 * Una libreria di altri autori dentro l'APK di release.
 *
 * @property name il nome com'e' noto (non si traduce).
 * @property author chi la scrive; per le licenze che chiedono la riga di copyright (MIT, Bouncy Castle) e' la riga stessa.
 * @property detail cosa comprende e, dove la versione e' fissata dall'app (`versions-app.gradle`), la versione.
 * @property versionKey la chiave di `appVersions` in `versions-app.gradle` da cui viene [version], se c'e': un test controlla
 *   che la pagina non resti indietro quando una libreria si aggiorna.
 * @property version la versione che compare in [detail], se e' una sola.
 */
internal class AboutLibrary(
  val name: String,
  val author: String,
  val detail: String,
  val license: LibraryLicense,
  val versionKey: String? = null,
  val version: String? = null,
) {
  /** La riga sotto il nome: chi la scrive e cosa comprende. */
  val subtitle: String get() = "$author · $detail"
}

/**
 * **Tutte** le librerie di terze parti incluse nell'APK di release, con la loro licenza. L'elenco viene dalle dipendenze di
 * runtime della release (`gradlew :app:dependencies --configuration releaseRuntimeClasspath`, 229 artefatti), non da una
 * lista a memoria; licenze e autori vengono dai POM e dai file di licenza dentro i jar. Gli artefatti della stessa
 * famiglia stanno in una riga sola.
 *
 * **Quando si aggiunge o si toglie una dipendenza si aggiorna questo elenco**: `AboutLibrariesTest` controlla le versioni
 * fissate in `versions-app.gradle` e che ogni libreria dichiarata dal build ci sia.
 *
 * Fuori da qui: GeoNames (dati, CC BY 4.0, ha la sua sezione) e le opere dentro il Fluid Engine (`FluidEngineLicenses`,
 * un'altra sezione).
 */
internal val ABOUT_LIBRARIES: List<AboutLibrary> = listOf(
  // --- Jetpack ---------------------------------------------------------------------------------------------------------
  AboutLibrary(
    name = "AndroidX",
    author = "The Android Open Source Project",
    detail = "Activity, AppCompat, Biometric 1.1.0, Collection, Core, Fragment, Hilt 1.3.0, Lifecycle, Navigation, " +
      "Profile Installer 1.4.1, SavedState, SQLite, Startup, Tracing, Window e altre librerie Jetpack",
    license = LibraryLicense.APACHE_2,
  ),
  AboutLibrary(
    name = "Jetpack Compose e Material 3",
    author = "The Android Open Source Project",
    detail = "interfaccia, animazioni, componenti Material e icone Material",
    license = LibraryLicense.APACHE_2,
  ),
  AboutLibrary(
    name = "Room",
    author = "The Android Open Source Project",
    detail = "database locale, versione 2.8.5",
    license = LibraryLicense.APACHE_2,
    versionKey = "room",
    version = "2.8.5",
  ),
  AboutLibrary(
    name = "WorkManager",
    author = "The Android Open Source Project",
    detail = "lavori in background, versione 2.11.2",
    license = LibraryLicense.APACHE_2,
    versionKey = "work",
    version = "2.11.2",
  ),
  AboutLibrary(
    name = "DataStore",
    author = "The Android Open Source Project",
    detail = "preferenze, versione 1.1.1",
    license = LibraryLicense.APACHE_2,
  ),
  AboutLibrary(
    name = "AndroidX ExifInterface",
    author = "The Android Open Source Project",
    detail = "metadati delle immagini, versione 1.4.2",
    license = LibraryLicense.APACHE_2,
    versionKey = "exifinterface",
    version = "1.4.2",
  ),
  AboutLibrary(
    name = "AndroidX Media3",
    author = "The Android Open Source Project",
    detail = "ExoPlayer e componenti per i video, versione 1.11.1",
    license = LibraryLicense.APACHE_2,
    versionKey = "media3",
    version = "1.11.1",
  ),
  AboutLibrary(
    name = "CameraX",
    author = "The Android Open Source Project",
    detail = "fotocamera per leggere i QR, versione 1.5.3; include libyuv (licenza BSD)",
    license = LibraryLicense.APACHE_2,
    versionKey = "camerax",
    version = "1.5.3",
  ),
  // --- Linguaggio e iniezione ------------------------------------------------------------------------------------------
  AboutLibrary(
    name = "Kotlin",
    author = "JetBrains",
    detail = "libreria standard, versione 2.2.20",
    license = LibraryLicense.APACHE_2,
  ),
  AboutLibrary(
    name = "kotlinx",
    author = "JetBrains",
    detail = "coroutines 1.10.2, serialization 1.9.0, collections immutabili 0.4.0, atomicfu 0.27.0",
    license = LibraryLicense.APACHE_2,
    versionKey = "coroutines",
    version = "1.10.2",
  ),
  AboutLibrary(
    name = "Compose Multiplatform",
    author = "JetBrains",
    detail = "moduli runtime, foundation, animation e ui portati da telephoto",
    license = LibraryLicense.APACHE_2,
  ),
  AboutLibrary(
    name = "Dagger e Hilt",
    author = "Google",
    detail = "iniezione delle dipendenze, versione 2.58; javax.inject 1 e jakarta.inject 2.0.1",
    license = LibraryLicense.APACHE_2,
    versionKey = "hilt",
    version = "2.58",
  ),
  AboutLibrary(
    name = "Guava",
    author = "Google",
    detail = "versione 33.3.1-android, con failureaccess",
    license = LibraryLicense.APACHE_2,
  ),
  // --- NAS e sicurezza -------------------------------------------------------------------------------------------------
  AboutLibrary(
    name = "smbj",
    author = "Jeroen van Erp",
    detail = "SMB 3 verso il NAS, versione 0.15.0",
    license = LibraryLicense.APACHE_2,
    versionKey = "smbj",
    version = "0.15.0",
  ),
  AboutLibrary(
    name = "asn-one",
    author = "Jeroen van Erp",
    detail = "ASN.1 per smbj, versione 0.6.0",
    license = LibraryLicense.APACHE_2,
  ),
  AboutLibrary(
    name = "Bouncy Castle Provider (bcprov)",
    author = "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc.",
    detail = "crittografia per smbj, versione 1.85.2",
    license = LibraryLicense.BOUNCY_CASTLE,
  ),
  AboutLibrary(
    name = "mbassador",
    author = "Benjamin Diedrichsen",
    detail = "bus di eventi per smbj, versione 1.3.2",
    license = LibraryLicense.MIT,
  ),
  AboutLibrary(
    name = "SLF4J API",
    author = "Copyright (c) 2004-2022 QOS.ch Sarl",
    detail = "interfaccia dei log di smbj, versione 2.0.18; l'app non ha nessun provider, quindi non scrive nulla",
    license = LibraryLicense.MIT,
  ),
  AboutLibrary(
    name = "Okio",
    author = "Square, Inc.",
    detail = "versione 3.11.0",
    license = LibraryLicense.APACHE_2,
  ),
  AboutLibrary(
    name = "ZXing core",
    author = "Gli autori di ZXing",
    detail = "lettura e scrittura dei QR, versione 3.5.4",
    license = LibraryLicense.APACHE_2,
    versionKey = "zxing",
    version = "3.5.4",
  ),
  // --- Mappa (Fase 9, P13: da quando `:app` collega `:feature-map`; versioni da versions-app.gradle e dai POM, "Fase 9 P1" in docs/decisioni.md) ---------
  AboutLibrary(
    name = "MapLibre Native Android",
    author = "Copyright (c) 2021 MapLibre contributors; (c) 2018-2021 MapTiler.com; (c) 2014-2020 Mapbox",
    detail = "la mappa e la mini-mappa, con la libreria dei gesti 0.0.4, versione 13.7.1",
    license = LibraryLicense.BSD_2,
    versionKey = "maplibre",
    version = "13.7.1",
  ),
  AboutLibrary(
    name = "MapLibre GeoJSON e Turf",
    author = "MapLibre contributors",
    detail = "geometrie per la mappa, versione 6.0.1",
    license = LibraryLicense.APACHE_2,
  ),
  AboutLibrary(
    name = "OkHttp",
    author = "Square, Inc.",
    detail = "il client delle tessere della mappa dettagliata (un solo indirizzo, dietro il cancello dell'app), versione 4.12.0",
    license = LibraryLicense.APACHE_2,
    versionKey = "okhttp",
    version = "4.12.0",
  ),
  AboutLibrary(
    name = "Timber",
    author = "Jake Wharton",
    detail = "log di MapLibre, versione 5.0.1; l'app non pianta nessun albero, quindi non scrive niente",
    license = LibraryLicense.APACHE_2,
  ),
  // --- Immagini --------------------------------------------------------------------------------------------------------
  AboutLibrary(
    name = "telephoto",
    author = "Saket Narayan",
    detail = "zoomable, zoomable-image e sub-sampling-image, versione 0.19.0",
    license = LibraryLicense.APACHE_2,
    versionKey = "telephoto",
    version = "0.19.0",
  ),
  AboutLibrary(
    name = "Poko",
    author = "Drew Hamilton",
    detail = "annotazioni, versione 0.18.7 (portate da telephoto)",
    license = LibraryLicense.APACHE_2,
  ),
  AboutLibrary(
    name = "Annotazioni",
    author = "JetBrains, JSpecify, Google",
    detail = "JetBrains annotations 23.0.0, JSpecify 1.0.0, JSR 305 3.0.2, AutoValue annotations 1.6.3",
    license = LibraryLicense.APACHE_2,
  ),
)

/** Le licenze usate dall'elenco, nell'ordine dell'enum: la sezione "Licenze" scrive nome e indirizzo di ciascuna una volta. */
internal val ABOUT_LICENSES_IN_USE: List<LibraryLicense> =
  LibraryLicense.entries.filter { license -> ABOUT_LIBRARIES.any { it.license == license } || license == LibraryLicense.SIL_OFL }
