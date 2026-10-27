package dev.pampa.mycloudgallery.work

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJobResult
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagnosticsRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * I lavori degli album e della manutenzione notturna lasciano traccia in "Ultimi esiti ed errori" (Fase 7, revisione di fine fase, DG-2). Prima giravano
 * senza scrivere niente: dopo un blocco la Diagnostica non diceva nemmeno se erano partiti. Solo codici e nomi di lavoro: mai il messaggio di un errore.
 */
class AlbumJobReportsTest {

  private val log = RingDiagnosticsLog()
  private val events = DiagnosticsRecorder(log) { 1_000_000L }

  private fun job(index: Int = 0) = log.snapshot()[index].values["job"]
  private fun result(index: Int = 0) = log.snapshot()[index].values["result"]

  @Test
  fun unGiroDellaCondivisioneConQualcosaDiApertoSiScriveComeFattoInParte() = runBlocking<Unit> {
    val open = AlbumJobReports.share(events) { true }

    assertThat(open).isTrue()
    assertThat(log.snapshot().single().code).isEqualTo(DiagCodes.EVT_JOB_RESULT)
    assertThat(job()).isEqualTo(ExportValue.Enum.of(DiagJob.ALBUM_SHARE))
    assertThat(result()).isEqualTo(ExportValue.Enum.of(DiagJobResult.YIELDED))
  }

  @Test
  fun unGiroDellaCondivisioneFinitoSiScriveFinito() = runBlocking<Unit> {
    assertThat(AlbumJobReports.share(events) { false }).isFalse()

    assertThat(result()).isEqualTo(ExportValue.Enum.of(DiagJobResult.FINISHED))
  }

  @Test
  fun conLaGalleriaFermaNonSiScriveNiente() = runBlocking<Unit> {
    assertThat(AlbumJobReports.share(events) { null }).isNull()

    assertThat(log.snapshot()).isEmpty()
  }

  @Test
  fun unErroreDelGiroSiScriveComeFallitoSenzaIlMessaggioENonSiRilancia() = runBlocking<Unit> {
    val out = AlbumJobReports.share(events) { error("titolo: Compleanno di Anna") }

    assertThat(out).isNull()
    val event = log.snapshot().single()
    assertThat(event.code).isEqualTo(DiagCodes.EVT_JOB_FAILED)
    assertThat(job()).isEqualTo(ExportValue.Enum.of(DiagJob.ALBUM_SHARE))
    assertThat(result()).isEqualTo(ExportValue.Enum.of(DiagJobResult.FAILED))
    // Mai il messaggio, che puo' avere dati dell'utente.
    assertThat(event.toString()).doesNotContain("Anna")
    assertThat(event.values.values.map { it.toString() }.joinToString()).doesNotContain("Anna")
  }

  @Test
  fun unaCancellazioneNonEUnErrore() = runBlocking<Unit> {
    try {
      AlbumJobReports.share(events) { throw CancellationException("fermato") }
      throw AssertionError("la cancellazione doveva uscire")
    } catch (_: CancellationException) {
      // Come tutte le coroutine: si lascia passare.
    }
    assertThat(log.snapshot()).isEmpty()
  }

  @Test
  fun laManutenzioneFinitaESiScriveFinitaEUnBudgetFinitoComeFattaInParte() = runBlocking<Unit> {
    AlbumJobReports.maintenance(events) { true }
    AlbumJobReports.maintenance(events) { false }

    val written = log.snapshot()
    assertThat(written.map { it.values["job"] }.toSet()).containsExactly(ExportValue.Enum.of(DiagJob.LIBRARY_MAINTENANCE))
    assertThat(written.mapNotNull { it.values["result"] })
      .containsExactly(ExportValue.Enum.of(DiagJobResult.FINISHED), ExportValue.Enum.of(DiagJobResult.YIELDED))
  }

  @Test
  fun unErroreDellaManutenzioneSiScriveEPoiSiRilancia() = runBlocking<Unit> {
    val thrown = try {
      AlbumJobReports.maintenance(events) { error("cartella: Foto/Segrete") }
      null
    } catch (e: IllegalStateException) {
      e
    }

    // WorkManager lo vede come prima.
    assertThat(thrown).isNotNull()
    val event = log.snapshot().single()
    assertThat(event.code).isEqualTo(DiagCodes.EVT_JOB_FAILED)
    assertThat(job()).isEqualTo(ExportValue.Enum.of(DiagJob.LIBRARY_MAINTENANCE))
    assertThat(event.values.values.map { it.toString() }.joinToString()).doesNotContain("Segrete")
  }
}
