package dev.pampa.mycloudgallery.feature.diagnostics.export

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Le chiavi di esportazione della Fase 3: ogni chiave che i contributori della «Libreria del telefono» scrivono sta
 * nell'elenco chiuso (`ExportKeys`), e nessuna chiave di un dato personale ci e' finita per sbaglio.
 *
 * Le chiavi qui sotto sono scritte a mano (questo modulo non vede ne' core-mediastore ne' `:app`): quelle di
 * `PhoneLibraryDiagnostics` sono lette dal suo sorgente, e `DiagnosticsExportKeysTest` di `:app` verifica che i
 * contributori veri non ne usino altre.
 */
class ExportKeysFase3Test {

  /** Le chiavi di `PhoneLibraryDiagnostics` (core-mediastore). */
  private val mediastoreKeys = listOf(
    "media", "mediaLocation", "volumes", "granted", "pendingPhotos", "metaWaiting", "state", "pauseReason", "lastReason",
    "lastFailure", "seen", "added", "updated", "vanished", "fullScan", "completed", "deletedFiles", "fullScansCompleted",
    "reconcileVanished", "purgedAbsences", "metaDone", "metaFailed", "mounted", "known", "unavailable",
    "fullScanInProgress", "fullScanPending", "zeroBytes", "notMedia", "invalidName", "unreadable", "times",
  )

  /** Le chiavi del contributore di `:app` (`LibraryDiagnostics`). */
  private val appKeys = listOf(
    "places", "placesFailure", "dataset", "items", "rebuilds", "patches", "snapshotWrites", "reconciled",
    "cachedBytes", "budgetBytes", "started", "cancelled", "failed", "memoryHits", "misses", "inFlight",
  )

  @Test
  fun leChiaviDellaLibreriaDelTelefonoSonoNellElencoChiuso() {
    val missing = (mediastoreKeys + appKeys).filterNot(ExportKeys::isAllowed)

    assertWithMessage("Chiavi della Fase 3 fuori da ExportKeys: $missing").that(missing).isEmpty()
  }

  @Test
  fun nessunaChiaveDellaFase3SembraUnDatoPersonale() {
    // Una chiave e' un nome fisso, mai il dato: niente di quello che segue puo' essere una chiave della Fase 3.
    val suspicious = listOf("name", "path", "uri", "file", "folder", "volumeName", "latitude", "longitude", "coordinates")
    val offenders = (mediastoreKeys + appKeys).filter { key -> suspicious.any { it.equals(key, ignoreCase = true) } }

    assertThat(offenders).isEmpty()
  }

  @Test
  fun ilTestoLiberoENonAmmesso() {
    // La forma della chiave non basta: "places.Oslo" o "volume.1234-ABCD" non sono nell'elenco ne' nelle famiglie.
    listOf("places.Oslo", "volume.1234-ABCD", "folder.DCIM", "coordinates", "latitude").forEach {
      assertWithMessage(it).that(ExportKeys.isAllowed(it)).isFalse()
    }
  }
}
