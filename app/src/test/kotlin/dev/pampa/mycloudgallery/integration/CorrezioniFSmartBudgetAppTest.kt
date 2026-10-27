package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.albums.AlbumsDiagnostics
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.albums.AlbumsWiring
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.upkeep.ContentBudget
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.search.contract.Today
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il budget delle ricerche per contenuto degli smart sul **grafo vero** (Fase 7, correzioni F, risposta dell'utente del 27/10 su R19 e L146): nessun tetto alla
 * creazione e nessun messaggio, ma `SmartUpkeep` non fa piu' di [ContentBudget.PER_ROUND] ricerche per giro, e la Diagnostica dice, in soli numeri, quanti
 * smart aspettano il loro turno (`SMART_SEARCH_WAITING`). Il giro dura 30 secondi veri: la prova guarda solo il primo, senza aspettare gli altri (la rotazione
 * e la fila sono provate con il tempo virtuale da `ContentBudgetTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CorrezioniFSmartBudgetAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val rigs = mutableListOf<BackupRig>()
  private val graphs = mutableListOf<AlbumsGraph>()
  private val today = Today { LocalDate.of(2026, 10, 27) }

  @After
  fun tearDown() {
    graphs.forEach { runBlocking { runCatching { it.stop() } } }
    rigs.forEach { rig ->
      runBlocking { runCatching { rig.gallery.shutdown() } }
      runCatching { rig.close() }
    }
  }

  @Test
  fun moltiSmartConDescrizioneNonSiTettano_MaAspettanoIlTurno_ELaDiagnosticaLoDice() = runBlocking<Unit> {
    val phone = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, backend))
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER).also { rigs += it }
    rig.open()
    phone.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val albums = AlbumsWiring.build(phone.context, phone.graph, rig.gallery, search = null, today = today).also { graphs += it }
    val live = checkNotNull(albums.ensureLive()) { "album non montati" }
    withTimeout(Attese.TETTO) { live.albums.status.first { it !is AlbumsStatus.Loading } }

    // L'utente non ha un tetto: dodici smart con una descrizione si creano tutti, senza un messaggio.
    val total = ContentBudget.PER_ROUND + 4
    val created = (1..total).map { n -> live.albums.create("Mare $n", AlbumKind.SMART, SmartRules(null, null, "mare $n")) }
    assertThat(created.all { it is CreateOutcome.Created }).isTrue()

    // Il primo giro ne valuta al piu' PER_ROUND; gli altri aspettano (e il numero e' quello della Diagnostica).
    until("gli smart oltre il budget aspettano il turno") { live.smartUpkeep.waitingForBudget >= 1 }
    withTimeout(Attese.TETTO) { live.albums.all.first { list -> list.albums.count { it is Album.Smart } == total } }
    val diagnostics = AlbumsDiagnostics(albums)
    val facts = diagnostics.facts()
    assertThat(facts.smart).isEqualTo(total)
    assertThat(facts.smartSearchWaiting).isAtLeast(1)
    assertThat(facts.smartSearchWaiting).isAtMost(total)
    val item = diagnostics.render(facts).single { it.code.id == "SMART_SEARCH_WAITING" }
    assertThat(item.export.keys).containsExactly("count")
  }
}
