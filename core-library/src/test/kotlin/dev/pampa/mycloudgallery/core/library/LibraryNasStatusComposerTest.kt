package dev.pampa.mycloudgallery.core.library

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.status.FrozenInfo
import dev.pampa.mycloudgallery.core.library.status.LibraryNasStatusComposer
import dev.pampa.mycloudgallery.core.library.status.NasAreaProgress
import dev.pampa.mycloudgallery.core.library.status.NasStatusInputs
import dev.pampa.mycloudgallery.core.library.status.ScanProgress
import dev.pampa.mycloudgallery.core.library.status.SyncProgress
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.NasArea
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Cosa dice il piede della Libreria (§1.5): la precedenza fra gli stati, le somme fra le aree e il tempo stimato. */
class LibraryNasStatusComposerTest {

  private var clock = 0L
  private val composer = LibraryNasStatusComposer { clock }

  private val private0 = NasAreaProgress(NasArea.PRIVATE)
  private val common0 = NasAreaProgress(NasArea.COMMON)

  private fun inputs(
    vararg areas: NasAreaProgress,
    sync: SyncProgress = SyncProgress.Idle,
    localOnly: Boolean = false,
    last: Long? = null,
    configured: Boolean = true,
  ) = NasStatusInputs(configured, areas.toList(), sync, localOnly, last)

  @Test
  fun senzaNasIlPiedeNonDiceNiente() {
    assertThat(composer.compose(inputs(configured = false))).isEqualTo(LibraryNasStatus.Idle)
    assertThat(composer.compose(inputs())).isEqualTo(LibraryNasStatus.Idle)
    assertThat(composer.compose(inputs(NasAreaProgress(NasArea.PRIVATE, unavailable = true), configured = false))).isEqualTo(LibraryNasStatus.Idle)
  }

  @Test
  fun tuttoFermoEAggiornato_sonoLUltimaOraDelNas() {
    assertThat(composer.compose(inputs(private0, common0, sync = SyncProgress.UpToDate(1_000L)))).isEqualTo(LibraryNasStatus.UpToDate(1_000L))
    // Se l'allineamento non lo dice, vale l'ultima verifica; se non lo dice nessuno, il piede tace.
    assertThat(composer.compose(inputs(private0, last = 777L))).isEqualTo(LibraryNasStatus.UpToDate(777L))
    assertThat(composer.compose(inputs(private0))).isEqualTo(LibraryNasStatus.Idle)
  }

  @Test
  fun laPrimaScansioneSommaLeAree_eIlTotaleSoloSeLoSannoTutte() {
    val a = NasAreaProgress(NasArea.PRIVATE, scan = ScanProgress.First(done = 300, total = 12_000))
    val b = NasAreaProgress(NasArea.COMMON, scan = ScanProgress.First(done = 20, total = null))
    // Una delle due non conosce ancora il totale: niente totale (meglio "300 file" che "300 su 12.000" sbagliato).
    assertThat(composer.compose(inputs(a, b))).isEqualTo(LibraryNasStatus.FirstScan(done = 320, total = null, etaMillis = null))
    val b2 = b.copy(scan = ScanProgress.First(done = 20, total = 800))
    assertThat(composer.compose(inputs(a, b2))).isEqualTo(LibraryNasStatus.FirstScan(done = 320, total = 12_800, etaMillis = null))
    // Una verifica di routine non e' un avanzamento da mostrare.
    assertThat(composer.compose(inputs(NasAreaProgress(NasArea.PRIVATE, scan = ScanProgress.Verifying), sync = SyncProgress.UpToDate(5)))).isEqualTo(LibraryNasStatus.UpToDate(5))
  }

  @Test
  fun congelataVinceSuTutto_laPiuVecchiaPrima() {
    val frozenOld = NasAreaProgress(NasArea.PRIVATE, frozen = FrozenInfo(FreezeReason.NOT_MEDIA, sinceNasMillis = 100))
    val frozenNew = NasAreaProgress(NasArea.COMMON, frozen = FrozenInfo(FreezeReason.VANISHED, sinceNasMillis = 900))
    val scanning = NasAreaProgress(NasArea.COMMON, scan = ScanProgress.First(1, 10), unavailable = true)
    assertThat(composer.compose(inputs(frozenNew, frozenOld, sync = SyncProgress.Aligning(1, 2), localOnly = true)))
      .isEqualTo(LibraryNasStatus.Frozen(FreezeReason.NOT_MEDIA, 100))
    assertThat(composer.compose(inputs(frozenOld, scanning))).isEqualTo(LibraryNasStatus.Frozen(FreezeReason.NOT_MEDIA, 100))
  }

  @Test
  fun lAvanzamentoVieneDellaNonRaggiungibilita_ilSoloLocaleViene_dopo() {
    val down = NasAreaProgress(NasArea.COMMON, unavailable = true)
    val scanning = NasAreaProgress(NasArea.PRIVATE, scan = ScanProgress.First(5, 50))
    assertThat(composer.compose(inputs(scanning, down))).isInstanceOf(LibraryNasStatus.FirstScan::class.java)
    assertThat(composer.compose(inputs(private0, down, sync = SyncProgress.Aligning(10, 100)))).isEqualTo(LibraryNasStatus.Aligning(10, 100, null))
    // Finito il lavoro il piede dice cosa non va.
    assertThat(composer.compose(inputs(private0, down, sync = SyncProgress.UpToDate(9), localOnly = true)))
      .isEqualTo(LibraryNasStatus.AreasUnavailable(setOf(NasArea.COMMON)))
    assertThat(composer.compose(inputs(down, down.copy(area = NasArea.PRIVATE)))).isEqualTo(LibraryNasStatus.AreasUnavailable(setOf(NasArea.COMMON, NasArea.PRIVATE)))
    assertThat(composer.compose(inputs(private0, sync = SyncProgress.UpToDate(9), localOnly = true))).isEqualTo(LibraryNasStatus.LocalOnly)
  }

  @Test
  fun unAllineamentoFinitoOSenzaByteNonEUnAvanzamento() {
    assertThat(composer.compose(inputs(private0, sync = SyncProgress.Aligning(100, 100)))).isEqualTo(LibraryNasStatus.Idle)
    assertThat(composer.compose(inputs(private0, sync = SyncProgress.Aligning(0, 0)))).isEqualTo(LibraryNasStatus.Idle)
  }

  // --- Il tempo stimato ----------------------------------------------------------------------------------------------

  @Test
  fun ilTempoStimatoArrivaSoloQuandoCeAbbastanza_eSegueLaVelocitaRecente() {
    var done = 0
    fun eta() = (composer.compose(inputs(NasAreaProgress(NasArea.PRIVATE, scan = ScanProgress.First(done, 120_000)))) as LibraryNasStatus.FirstScan).etaMillis
    clock = 1_000
    done = 100
    assertThat(eta()).isNull() // una sola osservazione
    clock = 3_000
    done = 500
    assertThat(eta()).isNull() // troppo presto (2 s)
    clock = 11_000
    done = 2_100 // 2.000 file in 10 s = 200 al secondo
    // Mancano 117.900 file a 200 al secondo.
    assertThat(eta()).isEqualTo(589_500L)
    // Va il doppio piu' veloce: conta la finestra recente (30 s), non l'inizio.
    clock = 41_000
    done = 2_100 + 30 * 400
    assertThat(eta()).isEqualTo(((120_000 - done) / 400.0 * 1_000).toLong())
  }

  @Test
  fun senzaProgressoONonSaleNonCeStima_eUnaScansioneRipartitaRicomincia() {
    fun first(done: Int) = inputs(NasAreaProgress(NasArea.PRIVATE, scan = ScanProgress.First(done, 1_000)))
    clock = 0
    composer.compose(first(100))
    clock = 20_000
    assertThat((composer.compose(first(100)) as LibraryNasStatus.FirstScan).etaMillis).isNull()
    // Ripartita da zero: le osservazioni di prima non valgono.
    clock = 21_000
    composer.compose(first(10))
    clock = 22_000
    assertThat((composer.compose(first(20)) as LibraryNasStatus.FirstScan).etaMillis).isNull()
    clock = 30_000
    assertThat((composer.compose(first(100)) as LibraryNasStatus.FirstScan).etaMillis).isNotNull()
  }

  @Test
  fun l_allineamentoHaLaSuaStima() {
    fun aligning(done: Long) = inputs(private0, sync = SyncProgress.Aligning(done, 1_000_000_000L))
    clock = 0
    composer.compose(aligning(0))
    clock = 10_000
    // 100 MB in 10 s: mancano 900 MB, cioe' 90 s.
    val status = composer.compose(aligning(100_000_000L)) as LibraryNasStatus.Aligning
    assertThat(status.bytesDone).isEqualTo(100_000_000L)
    assertThat(status.etaMillis).isEqualTo(90_000L)
  }

  @Test
  fun ilFlussoNonRipeteLoStessoStato() = runTest {
    val source = MutableSharedFlow<NasStatusInputs>(extraBufferCapacity = 8)
    composer.statusFlow(source).test {
      source.emit(inputs(private0, sync = SyncProgress.UpToDate(1)))
      assertThat(awaitItem()).isEqualTo(LibraryNasStatus.UpToDate(1))
      source.emit(inputs(private0, common0, sync = SyncProgress.UpToDate(1)))
      source.emit(inputs(private0, sync = SyncProgress.UpToDate(2)))
      assertThat(awaitItem()).isEqualTo(LibraryNasStatus.UpToDate(2))
      source.emit(inputs(NasAreaProgress(NasArea.PRIVATE, frozen = FrozenInfo(FreezeReason.CHANGED, 3))))
      assertThat(awaitItem()).isEqualTo(LibraryNasStatus.Frozen(FreezeReason.CHANGED, 3))
      cancelAndIgnoreRemainingEvents()
    }
  }
}
