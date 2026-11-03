package dev.pampa.mycloudgallery.feature.widget

import android.app.Application
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.ledger.FileLedgerStorage
import dev.pampa.mycloudgallery.core.widget.ledger.FileWidgetLedger
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.feature.widget.contract.WidgetUiState
import dev.pampa.mycloudgallery.feature.widget.glance.WidgetComposer
import dev.pampa.mycloudgallery.feature.widget.host.WidgetThumbnails
import dev.pampa.mycloudgallery.feature.widget.refresh.RefreshResult
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetEngine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il motore del ridisegno sulla JVM (Fase 10, P3, docs/fase10-progetto.md §2.1, D5, D7, D9, D42, E-W4): porte finte e composizione finta, **l'ordine** delle cose (il registro
 * prima del disegno, lo svuotamento prima del giro), cio' che **non** si disegna (Libreria non pronta, grafo non montato) e la gara con un nascosto che arriva a meta' giro.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetEngineTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  @get:Rule
  val folder = TemporaryFolder()

  private val small = WidgetSlot(7)
  private val wide = WidgetSlot(8)

  private class Rig(
    val events: MutableList<String>,
    val targets: RecordingTargets,
    val composer: RecordingComposer,
    val ledger: OrderedLedger,
    val planner: ScriptedPlanner,
    val services: FakeServices,
    val engine: WidgetEngine,
  )

  /** Un motore con le porte finte; [mounted] false = il grafo di `:app` non c'e'. */
  private fun rig(
    mounted: Boolean = true,
    prewarm: Boolean = false,
    thumbnails: WidgetThumbnails = WidgetThumbnails { _, _ -> thumb() },
    gate: CompletableDeferred<Unit>? = null,
    plan: (suspend (WidgetSlot, WidgetSize, Int) -> WidgetPlan)? = null,
    backing: WidgetLedger = InMemoryWidgetLedger(),
  ): Rig {
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(small to (160 to 160), wide to (320 to 160)), events)
    val composer = RecordingComposer(context, events)
    val ledger = OrderedLedger(backing, events)
    // Come il pianificatore vero: l'epoca di un piano riparte da quella registrata (uno svuotamento ne alza una, e un piano piu' vecchio il registro lo ignorerebbe).
    val planner = ScriptedPlanner(events, gate, plan ?: { slot, size, _ -> photoPlan(slot, size, size.cells, epoch = ledger.epochOf(slot) + 1) })
    val services = FakeServices(planner, ledger, thumbnails)
    val engine = WidgetEngine(services = { if (mounted) services else null }, targets = targets, composer = composer, resources = { context.resources }, prewarmBlanks = prewarm)
    return Rig(events, targets, composer, ledger, planner, services, engine)
  }

  private fun Rig.frameKinds() = composer.frames.map { it.state.javaClass.simpleName }

  @Test
  fun ilRegistroSiScrivePrimaDelDisegno() = runBlocking {
    val r = rig()
    assertThat(r.engine.refresh(small)).isEqualTo(RefreshResult.DRAWN)
    val record = r.events.indexOfFirst { it.startsWith("record:7:") }
    val compose = r.events.indexOfFirst { it.startsWith("compose:7:") }
    val push = r.events.indexOf("push:7")
    assertThat(record).isAtLeast(0)
    assertThat(record).isLessThan(compose)
    assertThat(compose).isLessThan(push)
    // La misura vien dai dp del widget: 160x160 = piccola, una sola cella.
    assertThat(r.composer.frames.single().cells).hasSize(1)
    // Il registro e il disegno dicono la stessa cosa: un tocco trova il media che si vede.
    assertThat(r.ledger.cell(small, 0)?.media).isEqualTo(mediaId(0))
    assertThat(r.ledger.cell(small, 1)).isNull()
  }

  @Test
  fun laMisuraVieneDallaGrandezzaDelWidget() = runBlocking {
    val r = rig()
    r.engine.refresh(small)
    r.engine.refresh(wide)
    assertThat(r.composer.frames.map { it.state.size }).containsExactly(WidgetSize.SMALL, WidgetSize.WIDE).inOrder()
    assertThat(r.composer.frames.map { it.cells.size }).containsExactly(1, 4).inOrder()
    // Il launcher non da' le misure: vale la piccola.
    r.targets.dims[small] = 0 to 0
    r.engine.refresh(small)
    assertThat(r.composer.frames.last().state.size).isEqualTo(WidgetSize.SMALL)
  }

  @Test
  fun unaLibreriaNonPronta_NonDisegnaNienteERestaQuelloCheC() = runBlocking {
    val r = rig(plan = { slot, size, _ -> WidgetPlan(slot, size, WidgetMode.Message(WidgetEmpty.NOT_READY), emptyList(), 1) })
    assertThat(r.engine.refresh(small)).isEqualTo(RefreshResult.KEPT)
    assertThat(r.targets.pushed).isEmpty()
    assertThat(r.composer.frames).isEmpty()
    // Nemmeno il registro cambia: il contenuto di prima (verificato) resta com'e'.
    assertThat(r.events.none { it.startsWith("record:") }).isTrue()
  }

  /** La lezione di NT-1: un processo freddo senza il grafo montato non si fida di niente e non disegna niente. */
  @Test
  fun ilGrafoNonMontato_NonDisegnaMaiUnContenutoNonVerificato() = runBlocking {
    val r = rig(mounted = false)
    assertThat(r.engine.refresh(small)).isEqualTo(RefreshResult.KEPT)
    r.engine.refreshAll()
    r.engine.settingsChanged()
    assertThat(r.targets.pushed).isEmpty()
    assertThat(r.planner.calls).isEqualTo(0)
  }

  @Test
  fun senzaIlGrafoUnNascostoSvuotaTuttiIWidget() = runBlocking {
    val r = rig(mounted = false)
    r.engine.hiddenChanged(setOf(mediaId(3)))
    // Non si sa cosa mostra ogni widget: si svuotano tutti (fallire chiusi), con il testo "Apri MyCloud Gallery" e senza foto.
    assertThat(r.targets.pushed).containsExactly(small, wide)
    assertThat(r.composer.frames.map { it.state }).containsExactly(WidgetUiState.Message(WidgetSize.SMALL, WidgetEmpty.NO_MEDIA), WidgetUiState.Message(WidgetSize.WIDE, WidgetEmpty.NO_MEDIA))
    assertThat(r.composer.frames.all { it.cells.isEmpty() && it.message == "Apri MyCloud Gallery" }).isTrue()
  }

  @Test
  fun senzaRegistroNienteFoto() = runBlocking {
    val r = rig()
    r.ledger.failWrites = true
    assertThat(r.engine.refresh(small)).isEqualTo(RefreshResult.BLANKED)
    val frame = r.composer.frames.single()
    assertThat(frame.state).isEqualTo(WidgetUiState.Message(WidgetSize.SMALL, WidgetEmpty.ERROR))
    assertThat(frame.cells).isEmpty()
  }

  @Test
  fun iTettiDiD7_UnPrefissoDiCelle() = runBlocking {
    // Sette miniature da 700 x 700 sono 13,7 MiB: ne stanno 1 (1,87 MiB) e basta, e il disegno e il registro hanno quella sola cella.
    val big = rig(thumbnails = WidgetThumbnails { _, _ -> thumb(700) }, plan = { slot, size, _ -> photoPlan(slot, size, 7, epoch = 1) })
    big.targets.dims[small] = 320 to 320
    assertThat(big.engine.refresh(small)).isEqualTo(RefreshResult.DRAWN)
    assertThat(big.composer.frames.single().cells).hasSize(1)
    assertThat(big.ledger.cell(small, 0)).isNotNull()
    assertThat(big.ledger.cell(small, 1)).isNull()
    // Miniature piccole: tutte e sette.
    val ok = rig(plan = { slot, size, _ -> photoPlan(slot, size, 7, epoch = 1) })
    ok.targets.dims[small] = 320 to 320
    ok.engine.refresh(small)
    assertThat(ok.composer.frames.single().cells).hasSize(7)
  }

  @Test
  fun unaMiniaturaCheNonSiDecodificaFermaIlPrefisso_NienteSegnaposto() = runBlocking {
    var n = 0
    val r = rig(thumbnails = WidgetThumbnails { _, _ -> if (n++ == 2) null else thumb() }, plan = { slot, size, _ -> photoPlan(slot, size, 4, epoch = 1) })
    r.targets.dims[small] = 320 to 160
    r.engine.refresh(small)
    assertThat(r.composer.frames.single().cells).hasSize(2)
    // Nessuna miniatura: "Apri MyCloud Gallery", mai una cella vuota.
    val none = rig(thumbnails = WidgetThumbnails { _, _ -> null }, plan = { slot, size, _ -> photoPlan(slot, size, 4, epoch = 1) })
    none.targets.dims[small] = 320 to 160
    none.engine.refresh(small)
    assertThat(none.composer.frames.single().state).isEqualTo(WidgetUiState.Message(WidgetSize.WIDE, WidgetEmpty.NO_LOCAL_THUMBS))
  }

  @Test
  fun unaMiniaturaCheLanciaNonFermaIlWidget() = runBlocking {
    val r = rig(thumbnails = WidgetThumbnails { _, _ -> throw IllegalStateException("decodifica") })
    assertThat(r.engine.refresh(small)).isEqualTo(RefreshResult.DRAWN)
    assertThat(r.composer.frames.single().state).isEqualTo(WidgetUiState.Message(WidgetSize.SMALL, WidgetEmpty.NO_LOCAL_THUMBS))
  }

  /** D5: lo svuotamento viene **prima** di ogni piano, non decodifica niente e non chiama il pianificatore. */
  @Test
  fun unNascostoSvuotaPrimaEPoiRidisegna() = runBlocking {
    val r = rig()
    r.engine.refresh(small)
    r.engine.refresh(wide)
    r.events.clear()
    r.composer.frames.clear()
    // Il piano dopo il nascosto non ha piu' quella foto: una cella con un altro media.
    r.engine.hiddenChanged(setOf(mediaId(0)))
    // Solo i widget che mostravano mediaId(0): entrambi (il piano di prova parte da 0 in tutte le misure).
    val firstPlan = r.events.indexOfFirst { it.startsWith("plan:") }
    val firstBlank = r.events.indexOfFirst { it.startsWith("compose:") }
    assertThat(firstBlank).isAtLeast(0)
    assertThat(firstBlank).isLessThan(firstPlan)
    // I disegni dello svuotamento non hanno foto e il registro li conosce.
    val blanks = r.composer.frames.takeWhile { it.state is WidgetUiState.Message }
    assertThat(blanks.map { it.slot }).containsExactly(small, wide)
    assertThat(blanks.all { it.cells.isEmpty() }).isTrue()
  }

  /** D5, punto 5: un registro che non si legge non dice cosa mostra ogni widget, quindi si svuotano tutti (e si ridisegnano subito dopo). */
  @Test
  fun unRegistroCheNonSiLeggeSvuotaTuttiIWidget() = runBlocking<Unit> {
    val r = rig()
    r.engine.refresh(small)
    r.engine.refresh(wide)
    r.events.clear()
    r.composer.frames.clear()
    r.ledger.failReads = true
    r.engine.hiddenChanged(setOf(mediaId(0)))
    val blanks = r.composer.frames.takeWhile { it.state is WidgetUiState.Message }
    assertThat(blanks.map { it.slot }).containsExactly(small, wide)
    // Il secondo svuotamento usa i disegni gia' composti dal primo (nessuna composizione): si guardano le consegne, e il primo disegno di ogni widget e' lo svuotamento.
    r.targets.pushed.clear()
    r.engine.memoryDropped(memoryId(2))
    assertThat(r.targets.pushed.take(2)).containsExactly(small, wide)
  }

  /** D5, < 100 ms: dopo un disegno con le foto lo svuotamento di quel widget e' gia' composto, e uno svuotamento **consegna soltanto** (nessuna composizione, e prima del registro). */
  @Test
  fun loSvuotamentoGiaComposto_SiConsegnaSenzaComporre() = runBlocking<Unit> {
    val r = rig(prewarm = true)
    r.engine.refresh(small)
    r.engine.refresh(wide)
    // Il disegno con le foto e quello senza immagini composto in anticipo, per ogni widget.
    assertThat(r.composer.frames.map { it.state.javaClass.simpleName }).containsExactly("Photos", "Message", "Photos", "Message").inOrder()
    r.events.clear()
    r.composer.frames.clear()
    r.engine.hiddenChanged(setOf(mediaId(0)))
    val firstPlan = r.events.indexOfFirst { it.startsWith("plan:") }
    val head = r.events.take(firstPlan)
    // Prima del primo piano: solo consegne e registro, nessuna composizione (e la consegna prima del registro).
    assertThat(head.none { it.startsWith("compose:") }).isTrue()
    assertThat(head.filter { it.startsWith("push:") }).containsExactly("push:7", "push:8").inOrder()
    assertThat(head.indexOf("push:7")).isLessThan(head.indexOfFirst { it.startsWith("record:7:Message") })
    // I due svuotamenti sono diversi per misura (160x160 e 320x160): due disegni in memoria, non uno.
    r.composer.frames.clear()
    r.engine.clearBlankCache()
    r.engine.hiddenChanged(setOf(mediaId(0)))
    assertThat(r.composer.frames.takeWhile { it.state is WidgetUiState.Message }.map { it.slot }).containsExactly(small, wide)
  }

  @Test
  fun unNascostoCheNessunWidgetMostraNonCambiaNiente() = runBlocking {
    val r = rig()
    // Tutti e due i widget sono stati disegnati (il registro li conosce): un widget non registrato si svuoterebbe comunque (R4).
    r.engine.refresh(small)
    r.engine.refresh(wide)
    r.events.clear()
    r.composer.frames.clear()
    r.engine.hiddenChanged(setOf(mediaId(99)))
    assertThat(r.events).isEmpty()
  }

  @Test
  fun unRicordoScartatoSvuotaIWidgetCheLoMostrano() = runBlocking {
    val memory = memoryId(3)
    val r = rig(plan = { slot, size, _ -> photoPlan(slot, size, size.cells, epoch = 1, mode = WidgetMode.Memory(memory, WidgetTestKit.memoryTitle)) })
    // Un solo widget sulla Home (un secondo non registrato si svuoterebbe comunque, R4).
    r.targets.dims.remove(wide)
    r.engine.refresh(small)
    r.events.clear()
    r.composer.frames.clear()
    r.engine.memoryDropped(memoryId(5))
    assertThat(r.events).isEmpty()
    r.engine.memoryDropped(memory)
    assertThat(r.events.first { it.startsWith("record:") }).startsWith("record:7:Message")
    assertThat(r.composer.frames.first().state).isInstanceOf(WidgetUiState.Message::class.java)
    assertThat(r.events.count { it.startsWith("plan:") }).isEqualTo(1)
  }

  /** D5, la gara: un "Nascondi" arriva mentre il piano si legge; il giro in corso **non consegna** e ricomincia con il piano nuovo. */
  @Test
  fun unNascostoCheArrivaAMetaGiroScartaIlGiroInCorso() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val r = rig(gate = gate, plan = { slot, size, call -> photoPlan(slot, size, size.cells, epoch = call.toLong(), from = if (call == 1) 0 else 50) })
    // I due widget sono gia' nel registro (con altre foto): il nascosto non ne tocca nessuno e non ridisegna niente mentre il giro in corso tiene il suo turno.
    r.ledger.record(photoPlan(small, WidgetSize.SMALL, 1, epoch = 0, from = 90))
    r.ledger.record(photoPlan(wide, WidgetSize.WIDE, 1, epoch = 0, from = 90))
    r.events.clear()
    val running = async { r.engine.refresh(small) }
    withTimeout(5_000) { while (r.planner.calls == 0) yield() }
    // Il primo piano (con mediaId(0)) e' fermo nella lettura; arriva il nascosto di mediaId(0).
    r.engine.hiddenChanged(setOf(mediaId(0)))
    gate.complete(Unit)
    assertThat(withTimeout(5_000) { running.await() }).isEqualTo(RefreshResult.DRAWN)
    // Il disegno finale e' del secondo piano (media da 50): il primo non e' mai stato registrato ne' consegnato.
    assertThat(r.ledger.cell(small, 0)?.media).isEqualTo(mediaId(50))
    assertThat(r.events.filter { it.startsWith("record:7:Photos") }).hasSize(1)
    assertThat(r.targets.pushed).hasSize(1)
    assertThat(r.planner.calls).isEqualTo(2)
  }

  /** Una causa nuova a ogni piano (un nascosto dopo l'altro): al terzo tentativo il giro si ferma, non consegna e non gira all'infinito. */
  @Test
  fun unGiroCheTrovaSempreUnaCausaNuovaSiFermaEDiceKept() = runBlocking {
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(small to (160 to 160)), events)
    val composer = RecordingComposer(context, events)
    lateinit var engine: WidgetEngine
    val planner = ScriptedPlanner(events) { slot, size, _ ->
      engine.hiddenChanged(emptySet())
      photoPlan(slot, size, size.cells, epoch = 1)
    }
    val ledger = OrderedLedger(InMemoryWidgetLedger(), events)
    // Il widget e' gia' registrato: la causa nuova (che sposta solo la generazione) non ha un widget "sconosciuto" da svuotare e ridisegnare dentro il giro (R4).
    ledger.record(photoPlan(small, WidgetSize.SMALL, 1, epoch = 0))
    val services = FakeServices(planner, ledger, WidgetThumbnails { _, _ -> thumb() })
    engine = WidgetEngine({ services }, targets, composer, { context.resources })
    assertThat(engine.refresh(small)).isEqualTo(RefreshResult.KEPT)
    assertThat(planner.calls).isEqualTo(WidgetEngine.MAX_ATTEMPTS)
    assertThat(targets.pushed).isEmpty()
  }

  /** D42: l'azzeramento svuota **prima** tutti i widget e poi il registro dimentica ogni cella. */
  @Test
  fun blankAllSvuotaPrimaEPoiIlRegistroDimentica() = runBlocking {
    val r = rig()
    r.engine.refresh(small)
    r.engine.refresh(wide)
    r.events.clear()
    r.composer.frames.clear()
    r.engine.blankAll(WidgetEmpty.NOT_CONFIGURED)
    val clear = r.events.indexOf("clear")
    val pushes = r.events.withIndex().filter { it.value.startsWith("push:") }.map { it.index }
    assertThat(pushes).hasSize(2)
    assertThat(pushes.max()).isLessThan(clear)
    assertThat(r.composer.frames.map { it.message }.toSet()).containsExactly("Configura MyCloud Gallery")
    assertThat(r.ledger.slots()).isEmpty()
    assertThat(r.ledger.cell(small, 0)).isNull()
  }

  @Test
  fun unWidgetCheNonSiRiesceASvuotareNonFermaGliAltri() = runBlocking<Unit> {
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(small to (160 to 160), wide to (320 to 160)), events)
    var first = true
    val composer = WidgetComposer { _, _, _ ->
      if (first) {
        first = false
        throw IllegalArgumentException("widget tolto dalla Home")
      }
      RemoteViews(context.packageName, R.layout.widget_initial)
    }
    val planner = ScriptedPlanner(events) { slot, size, _ -> photoPlan(slot, size, 1, epoch = 1) }
    val services = FakeServices(planner, InMemoryWidgetLedger(), WidgetThumbnails { _, _ -> thumb() })
    val engine = WidgetEngine({ services }, targets, composer, { context.resources })
    engine.blankAll(WidgetEmpty.NOT_CONFIGURED)
    // Il primo non c'e' piu', il secondo e' svuotato lo stesso.
    assertThat(targets.pushed).containsExactly(wide)
  }

  @Test
  fun refreshAllContinuaDopoUnErrore() = runBlocking<Unit> {
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(small to (160 to 160), wide to (320 to 160)), events)
    val composer = RecordingComposer(context, events)
    val planner = ScriptedPlanner(events) { slot, size, _ ->
      if (slot == small) throw IllegalStateException("lettura") else photoPlan(slot, size, 1, epoch = 1)
    }
    val services = FakeServices(planner, InMemoryWidgetLedger(), WidgetThumbnails { _, _ -> thumb() })
    WidgetEngine({ services }, targets, composer, { context.resources }).refreshAll()
    assertThat(targets.pushed).containsExactly(wide)
  }

  // --- Correzioni della Fase 10, pacchetto B: R5 (azzeramento) --------------------------------------------------------------------------

  /**
   * R5: il widget si dice fermo (il fornitore risponde null) mentre un giro e' nella decodifica, **senza** che nessuna causa sposti la generazione: il giro riprende, trova le porte
   * tolte nel cancello e non registra ne' consegna niente (prima rimetteva le foto sulla Home dopo lo svuotamento).
   */
  @Test
  fun unGiroGiaPartitoNonConsegnaSeLePorteSonoTolte() = runBlocking<Unit> {
    val open = AtomicBoolean(true)
    val gate = CompletableDeferred<Unit>()
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(small to (160 to 160)), events)
    val composer = RecordingComposer(context, events)
    val ledger = OrderedLedger(InMemoryWidgetLedger(), events)
    val planner = ScriptedPlanner(events, gate) { slot, size, _ -> photoPlan(slot, size, size.cells) }
    val services = FakeServices(planner, ledger, WidgetThumbnails { _, _ -> thumb() })
    val engine = WidgetEngine({ if (open.get()) services else null }, targets, composer, { context.resources })
    val running = async { engine.refresh(small) }
    withTimeout(5_000) { while (planner.calls == 0) yield() }
    // Il giro e' fermo nel piano; il processo toglie le porte (azzeramento: il widget si dice fermo prima di svuotare).
    open.set(false)
    gate.complete(Unit)
    assertThat(withTimeout(5_000) { running.await() }).isEqualTo(RefreshResult.KEPT)
    assertThat(targets.pushed).isEmpty()
    assertThat(events.none { it.startsWith("record:") || it.startsWith("compose:") }).isTrue()
  }

  /** R5, il caso intero: giro sospeso nella decodifica, il widget si dice fermo, `blankAll`, il giro riprende: dopo lo svuotamento non arriva nessuna foto. */
  @Test
  fun dopoLoSvuotamentoDiUnAzzeramentoNonArrivaPiuUnaFoto() = runBlocking<Unit> {
    val open = AtomicBoolean(true)
    val gate = CompletableDeferred<Unit>()
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(small to (160 to 160), wide to (320 to 160)), events)
    val composer = RecordingComposer(context, events)
    val ledger = OrderedLedger(InMemoryWidgetLedger(), events)
    val planner = ScriptedPlanner(events, gate) { slot, size, _ -> photoPlan(slot, size, size.cells) }
    val services = FakeServices(planner, ledger, WidgetThumbnails { _, _ -> thumb() })
    val engine = WidgetEngine({ if (open.get()) services else null }, targets, composer, { context.resources })
    val running = async { engine.refresh(small) }
    withTimeout(5_000) { while (planner.calls == 0) yield() }
    open.set(false)
    engine.blankAll(WidgetEmpty.NOT_CONFIGURED)
    val blanksDone = events.size
    gate.complete(Unit)
    assertThat(withTimeout(5_000) { running.await() }).isEqualTo(RefreshResult.KEPT)
    assertThat(composer.frames.map { it.state.javaClass.simpleName }.toSet()).containsExactly("Message")
    assertThat(events.drop(blanksDone).none { it.startsWith("push:") || it.startsWith("record:") }).isTrue()
    assertThat(targets.pushed).containsExactly(small, wide)
  }

  // --- R4: un registro perso non dice "nessuno" ------------------------------------------------------------------------------------------

  /** R4, sul registro vero: un file rovinato riparte vuoto, ma i widget sulla Home mostrano ancora le foto di prima: un nascosto li svuota **tutti** (prima: nessuno). */
  @Test
  fun conIlRegistroRovinatoSuDiscoUnNascostoSvuotaTuttiIWidget() = runBlocking<Unit> {
    val dir = folder.newFolder("widget")
    val before = FileWidgetLedger(dir)
    before.record(photoPlan(small, WidgetSize.SMALL, 1, epoch = 1))
    before.record(photoPlan(wide, WidgetSize.WIDE, 4, epoch = 1))
    val file = File(dir, FileLedgerStorage.FILE_NAME)
    val bytes = file.readBytes()
    bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5A).toByte()
    file.writeBytes(bytes)

    val r = rig(backing = FileWidgetLedger(dir))
    r.engine.hiddenChanged(setOf(mediaId(0)))
    val blanks = r.composer.frames.takeWhile { it.state is WidgetUiState.Message }
    assertThat(blanks.map { it.slot }).containsExactly(small, wide)
    assertThat(blanks.all { it.cells.isEmpty() }).isTrue()

    // Anche per un ricordo scartato, con un file che non si legge per niente.
    file.writeBytes(ByteArray(40) { 0x7F })
    val again = rig(backing = FileWidgetLedger(dir))
    again.engine.memoryDropped(memoryId(2))
    assertThat(again.targets.pushed.take(2)).containsExactly(small, wide)
  }

  /** R4: il registro conosce due widget e un terzo sta sulla Home senza esserci: un nascosto svuota chi lo mostra **e** chi non e' registrato, e non il resto. */
  @Test
  fun unWidgetCheIlRegistroNonConoscePerUnNascostoSiSvuotaComunque() = runBlocking<Unit> {
    val third = WidgetSlot(9)
    val r = rig()
    r.engine.refresh(small)
    r.engine.refresh(wide)
    r.targets.dims[third] = 160 to 160
    r.events.clear()
    r.composer.frames.clear()
    r.targets.pushed.clear()
    // Un nascosto che nessuno mostra: solo il widget non registrato si svuota (e si ridisegna, ora il registro lo conosce).
    r.engine.hiddenChanged(setOf(mediaId(99)))
    assertThat(r.targets.pushed.first()).isEqualTo(third)
    assertThat(r.composer.frames.first().slot).isEqualTo(third)
    assertThat(r.composer.frames.first().state).isInstanceOf(WidgetUiState.Message::class.java)
    assertThat(r.targets.pushed.filter { it != third }).isEmpty()
    // Un nascosto che due widget mostrano: loro due, e il terzo ora e' registrato.
    r.targets.pushed.clear()
    r.engine.hiddenChanged(setOf(mediaId(0)))
    assertThat(r.targets.pushed.take(3)).containsExactly(small, wide, third)
  }

  // --- WG-5: il budget a freddo ----------------------------------------------------------------------------------------------------------

  /** WG-5: il lavoro periodico aspetta le porte piu' del ricevitore, **una volta sola** per tutto il giro; il ricevitore e il nascosto restano al tetto di serie. */
  @Test
  fun ilGiroPeriodicoAspettaLePorteUnaVoltaSolaEPiuALungo() = runBlocking<Unit> {
    val budgets = mutableListOf<Long>()
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(small to (160 to 160), wide to (320 to 160)), events)
    val planner = ScriptedPlanner(events) { slot, size, _ -> photoPlan(slot, size, size.cells) }
    val services = FakeServices(planner, OrderedLedger(InMemoryWidgetLedger(), events), WidgetThumbnails { _, _ -> thumb() })
    // Un processo freddo che monta le porte in 5 s: chi aspetta meno non le ha, chi aspetta di piu' le trova (e poi restano montate).
    var mounted = false
    val engine = WidgetEngine(
      services = { budget ->
        budgets += budget
        if (budget >= 5_000) mounted = true
        if (mounted) services else null
      },
      targets = targets,
      composer = RecordingComposer(context, events),
      resources = { context.resources },
    )
    // Il ricevitore (e `refresh` da solo): 1,5 s, le porte non ci sono.
    assertThat(engine.refresh(small)).isEqualTo(RefreshResult.KEPT)
    assertThat(budgets).containsExactly(WidgetRules.COLD_START_BUDGET_MS)
    budgets.clear()
    // Il lavoro periodico: un'attesa lunga per il giro, poi i due widget con il tetto di serie.
    engine.refreshAll(WidgetRules.BUDGET_MS, WidgetRules.PERIODIC_COLD_START_BUDGET_MS)
    assertThat(budgets.first()).isEqualTo(WidgetRules.PERIODIC_COLD_START_BUDGET_MS)
    assertThat(budgets.count { it > WidgetRules.COLD_START_BUDGET_MS }).isEqualTo(1)
    assertThat(targets.pushed).containsExactly(small, wide)
  }

  /** WG-5: se le porte non arrivano nemmeno nell'attesa lunga il giro finisce subito (un'attesa sola, non una per widget). */
  @Test
  fun ilGiroPeriodicoSenzaPorteNonAspettaUnaVoltaPerWidget() = runBlocking<Unit> {
    val budgets = mutableListOf<Long>()
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(small to (160 to 160), wide to (320 to 160)), events)
    val engine = WidgetEngine(
      services = { budget ->
        budgets += budget
        null
      },
      targets = targets,
      composer = RecordingComposer(context, events),
      resources = { context.resources },
    )
    engine.refreshAll(WidgetRules.BUDGET_MS, WidgetRules.PERIODIC_COLD_START_BUDGET_MS)
    assertThat(budgets).containsExactly(WidgetRules.PERIODIC_COLD_START_BUDGET_MS)
    assertThat(targets.pushed).isEmpty()
  }
}
