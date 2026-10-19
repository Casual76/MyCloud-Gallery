package dev.pampa.mycloudgallery.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.nasmedia.contract.FreezeAcceptance
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanDiff
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanReport
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop
import dev.pampa.mycloudgallery.core.nasops.contract.RunResult
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.work.NasStages
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La verifica funzionale delle fasi 1-5A (docs/review/funzionale-fasi-1-5a.md), il NAS nella Libreria: i pezzi di `:app` che si provano
 * senza il grafo intero (NA-4: la franchigia su file; NA-8: il battito della scansione).
 */
class VerificaFunzionaleNasAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val me = AccountId("test")
  private val diff = ScanDiff(knownPresent = 1_000, vanished = 500, changed = 0, notMedia = 500)

  private fun file() = File(temp.root, "replica/nas-freeze-accept")

  // --- NA-4 ----------------------------------------------------------------------------------------------------------------------

  /** NA-4: la franchigia del gesto sta su un file, quindi la trova il processo nuovo che fa il giro dopo. */
  @Test
  fun na4_laFranchigiaSopravvivePiuProcessi() = runBlocking<Unit> {
    FileFreezeAcceptances(file()).save(me, "Test-Comune", FreezeAcceptance(diff, atNasMillis = 123_456L))

    val reborn = FileFreezeAcceptances(file())
    assertThat(reborn.load(me, "test-comune")).isEqualTo(FreezeAcceptance(diff, 123_456L))
    // Un'altra share o un altro account non la vedono.
    assertThat(reborn.load(me, "test")).isNull()
    assertThat(reborn.load(AccountId("altro"), "test-comune")).isNull()
  }

  @Test
  fun na4_usataLaFranchigiaSiTogliePerQuellaShareSoltanto() = runBlocking<Unit> {
    val acceptances = FileFreezeAcceptances(file())
    acceptances.save(me, "test", FreezeAcceptance(diff, 1L))
    acceptances.save(me, "test-comune", FreezeAcceptance(diff, 2L))

    acceptances.save(me, "test", null)

    assertThat(FileFreezeAcceptances(file()).load(me, "test")).isNull()
    assertThat(FileFreezeAcceptances(file()).load(me, "test-comune")).isNotNull()
    // Tolta l'ultima, il file sparisce.
    acceptances.save(me, "test-comune", null)
    assertThat(file().exists()).isFalse()
  }

  /** Un file rovinato non da' sconti: e' il lato prudente (il giro dopo ricongela e l'utente conferma di nuovo). */
  @Test
  fun na4_unFileRovinatoNonDaSconti() = runBlocking<Unit> {
    file().parentFile!!.mkdirs()
    file().writeText("test\ttest-comune\tabc\t1\t2\t3\t4\nrotto\ntest\ttest\t-5\t1\t2\t3\t4")

    val acceptances = FileFreezeAcceptances(file())

    assertThat(acceptances.load(me, "test-comune")).isNull()
    assertThat(acceptances.load(me, "test")).isNull()
  }

  // --- NA-12 ---------------------------------------------------------------------------------------------------------------------

  private val ran = RunResult(1, 1, 0, 0, RunStop.COMPLETE)

  private suspend fun cold(
    start: Boolean,
    attaches: Boolean = false,
    result: RunResult? = null,
    stopped: Boolean = false,
    open: Boolean = false,
  ): RunResult? = resumeCold(
    start = { start },
    awaitAttached = { attaches },
    resume = { result },
    stopped = { stopped },
    hasOpenOperations = { open },
  )

  /**
   * NA-12: il servizio delle operazioni, in un processo appena nato, girava prima che la galleria avesse agganciato il NAS:
   * `resumeOperations` rispondeva null e il lavoro finiva "fatto" senza fare niente.
   */
  @Test
  fun na12_unProcessoAppenaNatoAspettaIlNasEPoiRiprende() = runBlocking<Unit> {
    // Il NAS e' gia' pronto: si riprende.
    assertThat(cold(start = true, result = ran)).isEqualTo(ran)
    // Non e' ancora pronto ma la galleria lo aggancia da sola entro l'attesa: si riprende (prima: null, cioe' "fatto").
    assertThat(cold(start = false, attaches = true, result = ran)).isEqualTo(ran)
  }

  @Test
  fun na12_seIlNasNonArrivaMaNelDiarioCeLavoroNonEFinito() = runBlocking<Unit> {
    val answer = cold(start = false, attaches = false, open = true)

    assertThat(answer).isNotNull()
    assertThat(answer!!.stoppedBy).isEqualTo(RunStop.NAS_UNREACHABLE)
    // Anche se il motore c'e' ma non ha niente da dire, il lavoro aperto non si da' per finito.
    assertThat(cold(start = true, result = null, open = true)?.stoppedBy).isEqualTo(RunStop.NAS_UNREACHABLE)
  }

  @Test
  fun na12_senzaNasONasFermoPerLAzzeramentoNonCeNienteDaRiprendere() = runBlocking<Unit> {
    assertThat(cold(start = false, attaches = false, open = false)).isNull()
    // L'azzeramento ferma tutto: anche con operazioni nel diario, il lavoro non riparte (le toglie l'azzeramento).
    assertThat(cold(start = false, attaches = false, stopped = true, open = true)).isNull()
  }

  // --- NA-8 ----------------------------------------------------------------------------------------------------------------------

  private fun report(folders: Int, stoppedBy: ScanStop? = null) = ScanReport(
    mode = ScanMode.Verify, folders = folders, files = 0, unchanged = 0, adopted = 0, detected = 0, arriving = 0, skippedNames = 0,
    skippedSystem = 0, albumCovers = 0, notMedia = 0, busy = 0, vanished = 0, bytesRead = 0, complete = false, resumed = false, stoppedBy = stoppedBy,
  )

  /**
   * NA-8: il battito della scansione batteva anche quando non si era letto niente (share negata, NAS spento), quindi "fermo da N giorni"
   * non compariva mai nel riepilogo giornaliero. Adesso batte solo se almeno una cartella e' stata letta.
   */
  @Test
  fun na8_senzaCartelleLetteNonCeBattito() {
    assertThat(NasStages.readSomething(listOf(report(folders = 0, stoppedBy = ScanStop.UNAVAILABLE)))).isFalse()
    assertThat(NasStages.readSomething(emptyList())).isFalse()
    assertThat(NasStages.readSomething(listOf(report(folders = 0), report(folders = 3)))).isTrue()
  }
}
