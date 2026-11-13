package dev.pampa.mycloudgallery.feature.library.library

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import org.junit.Test

/**
 * Pacchetto XN (decisione dell'utente, 13/11/2026): **dalla Libreria l'analisi non si vede**. Non e' una voce della nuvola (non ne cambia lo stato,
 * il titolo, i problemi, lo spazio; non la crea da sola): ha la sua sezione in fondo al pannello, presente **sempre quando l'analisi e' accesa** su
 * questo telefono. Le prove sul pannello disegnato sono in `LibraryCloudAnalysisSectionTest`.
 */
class LibraryCloudAnalysisTest {

  @Test
  fun laSezioneCeConLAnalisiAccesa() {
    val on = listOf(
      LibraryAnalysisStatus.Running(remaining = 300, etaMinutes = 40, ready = 700, total = 1_000),
      LibraryAnalysisStatus.Running(remaining = null, etaMinutes = null, ready = null, total = null),
      LibraryAnalysisStatus.Queued(remaining = 3, ready = 1, total = 4),
      LibraryAnalysisStatus.AllReady,
      LibraryAnalysisStatus.Problem("AI_STORAGE_LOW"),
      LibraryAnalysisStatus.ModelMissing,
    ) + LibraryAnalysisWait.entries.map { LibraryAnalysisStatus.Paused(it, remaining = 9, ready = 1, total = 10) }
    for (status in on) assertWithMessage(status.toString()).that(analysisInPanel(status)).isTrue()
  }

  @Test
  fun spentaOFattaAltroveLaSezioneNonCe() {
    assertThat(analysisInPanel(LibraryAnalysisStatus.Idle)).isFalse()
    assertThat(analysisInPanel(LibraryAnalysisStatus.Disabled)).isFalse()
    assertThat(analysisInPanel(LibraryAnalysisStatus.NotHere(ready = 3, total = 4))).isFalse()
  }

  @Test
  fun laNuvolaDaSolaNonNasceDallAnalisi() {
    // Senza rete da dire, NAS e backup la nuvola non c'e': l'analisi non la crea (prima la teneva accesa da sola).
    assertThat(libraryCloud(null, LibraryNasStatus.Idle, LibraryBackupStatus.Idle)).isNull()
  }
}
