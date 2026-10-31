package dev.pampa.mycloudgallery.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.DropReason
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.NotifySkip
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMusicSettings
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifySettings
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.feature.memories.testing.FakeNotificationAccess
import dev.pampa.mycloudgallery.feature.settings.memories.MemoriesEntryState
import dev.pampa.mycloudgallery.integration.Attese
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Le correzioni della Fase 9 del 31/10 che si provano senza Android e senza il grafo: i conti della Diagnostica su file (DG-1, DG-2), la riga di Impostazioni che non parte
 * dai valori di serie (IM-1) e il permesso della canzone che si rilascia a ogni cambio (I4).
 */
class MemoriesCorrectionsTest {

  @get:Rule
  val temp = TemporaryFolder()

  private var today = LocalDate.of(2026, 10, 20)
  private val file get() = File(temp.root, DayTally.FILE)

  /** Un "processo" dei ricordi: i contatori sui file di [temp], con l'oggi di [today]. */
  private fun process() = MemoriesCounters(LateDeliveries(File(temp.root, LateDeliveries.FILE)) { today }, DayTally(file) { today })

  private fun report(shelfReset: Boolean = false, withoutVectors: Boolean = false, dropped: Map<DropReason, Int> = emptyMap()) =
    RefreshReport(0, 0, 0, shelfReset, incomplete = false, withoutVectors = withoutVectors, dropped = dropped)

  // --- DG-1, DG-2 -----------------------------------------------------------------------------------------------------------------

  /** I conti sono di sempre e non dell'ultimo processo: il lavoro delle notifiche e la notte muoiono subito, e la Diagnostica li vede lo stesso. */
  @Test
  fun iContiSopravvivonoAlProcesso() {
    val first = process()
    first.skipped(NotifySkip.PERMISSION)
    first.skipped(NotifySkip.PERMISSION)
    first.skipped(NotifySkip.FREQUENCY)
    first.round(report(shelfReset = true, withoutVectors = true, dropped = mapOf(DropReason.SCREENSHOT to 3)))
    first.round(report(dropped = mapOf(DropReason.SCREENSHOT to 2)))
    first.delivered(late = true)
    first.delivered(late = false)
    first.revoked()

    // Un processo nuovo sullo stesso file ("riavvio"): i numeri ci sono tutti.
    val second = process()
    assertThat(second.skippedByReason()).containsExactly(NotifySkip.PERMISSION, 2, NotifySkip.FREQUENCY, 1)
    assertThat(second.droppedByReason()).containsExactly(DropReason.SCREENSHOT, 5)
    assertThat(second.shelfResets).isEqualTo(1)
    assertThat(second.withoutVectors).isEqualTo(1)
    assertThat(second.revoked).isEqualTo(1)
    // DG-2: `delivered` e `late` hanno la stessa base (i 14 giorni, su file): un 1 su 2 si legge anche dopo il riavvio.
    assertThat(second.delivered).isEqualTo(2)
    assertThat(second.late!!.count()).isEqualTo(1)
    // Il resto e' solo del processo di adesso.
    assertThat(second.rounds).isEqualTo(0)
    assertThat(first.rounds).isEqualTo(2)
  }

  /** La stessa finestra di `notify-late`: gli ultimi 14 giorni, il resto si butta. */
  @Test
  fun laFinestraEDiQuattordiciGiorni() {
    val counters = process()
    counters.skipped(NotifySkip.NOTHING)
    counters.delivered(late = true)
    today = today.plusDays(13)
    counters.skipped(NotifySkip.NOTHING)
    assertThat(counters.skippedByReason()).containsExactly(NotifySkip.NOTHING, 2)
    assertThat(counters.delivered).isEqualTo(1)
    assertThat(counters.late!!.count()).isEqualTo(1)
    // Il quattordicesimo giorno dopo il primo: il primo conto e' fuori, per tutti e due i file.
    today = today.plusDays(1)
    assertThat(counters.skippedByReason()).containsExactly(NotifySkip.NOTHING, 1)
    assertThat(counters.delivered).isEqualTo(0)
    assertThat(counters.late!!.count()).isEqualTo(0)
  }

  /** Solo numeri e codici: nessun'altra cosa si scrive (un codice che non e' un codice si scarta). */
  @Test
  fun ilFileHaSoloNumeriECodici() {
    val counters = process()
    counters.skipped(NotifySkip.OUTSIDE_WINDOW)
    counters.round(report(shelfReset = true, dropped = mapOf(DropReason.SCREENSHOT to 4)))
    val tally = DayTally(file) { today }
    tally.add("Titolo del ricordo")
    tally.add("12/03/2024")
    tally.add("skip.permission", 0)
    val lines = file.readText().lines().filter { it.isNotBlank() }
    assertThat(lines).isNotEmpty()
    for (line in lines) assertThat(line).matches("[0-9]{8} [a-z0-9._]+ [0-9]+")
    assertThat(file.readText()).doesNotContain("Titolo")
  }

  /** Un file illeggibile vale zero, e la scrittura dopo lo rimette a posto (atomica). */
  @Test
  fun unFileRovinatoValeZero() {
    file.writeText("boh\n20261020 skip.nothing\n2026 skip.nothing 3\n99999999 Skip 1 2\n")
    val counters = process()
    assertThat(counters.skippedByReason()).isEmpty()
    counters.skipped(NotifySkip.CHANNEL)
    assertThat(process().skippedByReason()).containsExactly(NotifySkip.CHANNEL, 1)
    assertThat(file.readText().lines().filter { it.isNotBlank() }).hasSize(1)
  }

  /** L'azzeramento toglie `memories/` intera: il file dei conti sta li' (e non ne ha altri fuori). */
  @Test
  fun ilFileDeiContiStaNellaCartellaDeiRicordi() {
    assertThat(MemoriesWiring.DIR).isEqualTo("memories")
    assertThat(DayTally.FILE).doesNotContain("/")
  }

  // --- IM-1 -----------------------------------------------------------------------------------------------------------------------

  /** La riga di Impostazioni non parte dai valori di serie: `null` finche' le preferenze non sono lette, poi il valore vero, che resta in memoria. */
  @Test
  fun laRigaDiImpostazioniNonParteDaiValoriDiSerie() = runBlocking<Unit> {
    val scope = CoroutineScope(Dispatchers.Default + Job())
    try {
      val entry = AppMemoriesEntryStatus({ InMemoryNotifySettings(NotifyPrefs(enabled = false)) }, FakeNotificationAccess(NotifyPermission.DENIED), scope)
      assertThat(entry.summary.value).isNull()
      val read = withTimeout(Attese.TETTO) { entry.summary.first { it != null } }
      assertThat(read!!.state).isEqualTo(MemoriesEntryState.OFF)
      assertThat(read.needsAttention).isFalse()
      // L'ultimo valore letto resta: chi riapre la pagina parte da quello.
      assertThat(entry.summary.value).isEqualTo(read)
    } finally {
      scope.cancel()
    }
  }

  // --- I4 -------------------------------------------------------------------------------------------------------------------------

  /** A ogni cambio di canzone si rilascia il permesso della vecchia; la stessa canzone scelta di nuovo lo tiene. */
  @Test
  fun aOgniCambioDiCanzoneSiRilasciaIlPermessoDellaVecchia() = runBlocking<Unit> {
    val released = ArrayList<String>()
    val settings = PermissionReleasingMusicSettings(InMemoryMusicSettings(MusicChoice.OwnSong("content://prova/1"))) { released += it }
    settings.set(MusicChoice.OwnSong("content://prova/1"))
    assertThat(released).isEmpty()
    settings.set(MusicChoice.OwnSong("content://prova/2"))
    assertThat(released).containsExactly("content://prova/1")
    assertThat(settings.choice.value).isEqualTo(MusicChoice.OwnSong("content://prova/2"))
    settings.set(MusicChoice.None)
    assertThat(released).containsExactly("content://prova/1", "content://prova/2").inOrder()
    settings.set(MusicChoice.None)
    assertThat(released).hasSize(2)
  }
}
