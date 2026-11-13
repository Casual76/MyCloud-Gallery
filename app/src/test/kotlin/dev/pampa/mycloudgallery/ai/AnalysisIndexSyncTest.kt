package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryIndexSync
import org.junit.Test

/** Pacchetto XN: da dove viene la riga «Sul NAS» della sezione dell'analisi nel pannello della nuvola (mai un istante inventato). */
class AnalysisIndexSyncTest {

  @Test
  fun senzaNasLaRigaNonCe() {
    assertThat(AnalysisIndexSync.of(nasConfigured = false, aiOnNas = false, lastPublishedAtNas = null)).isEqualTo(LibraryIndexSync.NotApplicable)
    // Una testa rimasta da un NAS tolto non si mostra come se l'indice ci andasse ancora.
    assertThat(AnalysisIndexSync.of(nasConfigured = false, aiOnNas = false, lastPublishedAtNas = 7_000L)).isEqualTo(LibraryIndexSync.NotApplicable)
  }

  @Test
  fun unaTestaPubblicataDiceIlSuoIstante() {
    assertThat(AnalysisIndexSync.of(nasConfigured = true, aiOnNas = true, lastPublishedAtNas = 7_000L)).isEqualTo(LibraryIndexSync.At(7_000L))
    // Il motore del NAS non ancora partito non cancella cio' che e' gia' successo.
    assertThat(AnalysisIndexSync.of(nasConfigured = true, aiOnNas = false, lastPublishedAtNas = 7_000L)).isEqualTo(LibraryIndexSync.At(7_000L))
  }

  @Test
  fun maiPubblicataConIlNasPartitoENonAncora() {
    assertThat(AnalysisIndexSync.of(nasConfigured = true, aiOnNas = true, lastPublishedAtNas = null)).isEqualTo(LibraryIndexSync.Never)
  }

  @Test
  fun maiPubblicataEMotoreFermoNonSiDiceNiente() {
    assertThat(AnalysisIndexSync.of(nasConfigured = true, aiOnNas = false, lastPublishedAtNas = null)).isEqualTo(LibraryIndexSync.NotApplicable)
  }
}
