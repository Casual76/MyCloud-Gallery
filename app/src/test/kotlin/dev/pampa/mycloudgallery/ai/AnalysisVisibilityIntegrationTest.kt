package dev.pampa.mycloudgallery.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiLive
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.library.testing.FakeTimelineRepository
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisThumb
import dev.pampa.mycloudgallery.gallery.PartialAccessTimeline
import dev.pampa.mycloudgallery.hidden.BuiltWorld
import dev.pampa.mycloudgallery.hidden.HiddenWorldBuilder
import dev.pampa.mycloudgallery.hidden.WorldSpec
import dev.pampa.mycloudgallery.search.CatalogCellKeySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * XS-1 (Pacchetto XF): la pagina "Analisi delle foto" **non aggira il filtro dei permessi della Libreria**. La prova monta il collegamento vero:
 * l'istantanea di base della Libreria (che **non cambia mai**: conserva tutto, come in produzione con un accesso ridotto), `PartialAccessTimeline`
 * (la vista autorizzata: con l'accesso parziale o revocato non c'e'), `CatalogVisibleMedia` e `CatalogHiddenMask` sul catalogo vero, e il nastro
 * vero (`AppAnalysisLive`). Si passa da accesso COMPLETO a PARZIALE e a NESSUNO **senza toccare l'istantanea di base**: nessuna anteprima
 * (`Shown`), quindi nessun ThumbHash e nessun riferimento del media escluso. Mutazioni eseguite (vedi il resoconto): usare un'istantanea fissa al posto di
 * `visible.snapshot`, togliere il filtro finale (`visible` e `hiddenAmong`), ignorare `changes`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class AnalysisVisibilityIntegrationTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private class Rig(
    val world: BuiltWorld,
    val base: FakeTimelineRepository,
    val access: MutableStateFlow<MediaAccess>,
    val selection: MutableStateFlow<Set<MediaId>?>,
    val changes: MutableSharedFlow<Unit>,
    val page: AppAnalysisLive,
    val target: MediaId,
    val others: List<MediaId>,
    val scope: CoroutineScope,
    val engine: MutableStateFlow<AiLive>,
    val keyOf: Map<MediaId, ContentKey>,
  ) {
    /** Tutte le anteprime del nastro. */
    fun thumbs(flow: AnalysisFlow): List<AnalysisThumb> = listOfNotNull(flow.current) + flow.recent + flow.next

    fun shown(flow: AnalysisFlow): Set<MediaId> = thumbs(flow).filterIsInstance<AnalysisThumb.Shown>().mapTo(HashSet()) { it.id }

    suspend fun next(predicate: (AnalysisFlow) -> Boolean): AnalysisFlow = withTimeout(20_000) { page.live.first(predicate) }
  }

  private suspend fun rig(
    spec: WorldSpec,
    scope: CoroutineScope,
    stopTimeoutMillis: Long = AppAnalysisLive.STOP_AFTER_MILLIS,
    wrapVisible: (VisibleMedia) -> VisibleMedia = { it },
  ): Rig {
    val world = HiddenWorldBuilder.build(context, spec)
    val cells = world.catalog.mediaContentKeysOf(world.ids)
    val keyOf: Map<MediaId, ContentKey> = cells.associate { it.mediaId to it.own.first() }
    val facts = ContentFacts()
    keyOf.forEach { (id, key) -> facts.put(key, ContentFact(id, VersionId(java.util.UUID(0L, 99L).toString()), size = 1, source = MediaSource.CAMERA)) }
    val keys = CellKeys(CatalogCellKeySource { world.catalog })
    keys.sync()

    // Il collegamento di produzione (GalleryGraph): la base resta quella; la vista autorizzata e' `PartialAccessTimeline` e `VisibleMedia` ci sta sopra.
    val base = FakeTimelineRepository(world.snapshot)
    val access = MutableStateFlow(MediaAccess.FULL)
    val selection = MutableStateFlow<Set<MediaId>?>(null)
    val visibleTimeline = PartialAccessTimeline(base, selection, access, scope)
    val visible = wrapVisible(CatalogVisibleMedia(world.db, visibleTimeline.snapshot))
    val mask = CatalogHiddenMask(world.db)
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val cellIds = world.libraryCells.toList()
    val target = cellIds.first()
    val others = cellIds.drop(1).take(3)
    val shownKeys = (listOf(target) + others).map { keyOf.getValue(it) }
    val engine = AiLive(current = shownKeys.first(), recent = shownKeys.drop(1), next = emptyList())
    val engineFlow = MutableStateFlow(engine)
    val page = AppAnalysisLive(engineFlow, visible, mask, keys, facts, scope, changes, stopTimeoutMillis)
    return Rig(world, base, access, selection, changes, page, target, others, scope, engineFlow, keyOf)
  }

  @Test
  fun passandoDaCompletoAParzialeENessunoSenzaToccareLaBaseNienteSiMostra() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val rig = rig(WorldSpec.nothingHidden(), scope)
    try {
      val baseBefore = rig.base.snapshot.value
      val collector = scope.launch { rig.page.live.collect {} }

      // Accesso completo: il media analizzato e le altre celle che la Libreria mostra hanno la loro anteprima.
      val full = rig.next { f -> rig.target in rig.shown(f) }
      assertThat(rig.shown(full)).containsAtLeastElementsIn(listOf(rig.target) + rig.others)

      // L'utente revoca l'accesso a quel media scegliendo altre foto: la Libreria lo esclude. L'istantanea di base **non cambia**.
      rig.selection.value = setOf(rig.others.first())
      rig.access.value = MediaAccess.PARTIAL
      val partial = rig.next { f -> rig.thumbs(f).all { it is AnalysisThumb.Quiet } }
      assertThat(rig.base.snapshot.value).isSameInstanceAs(baseBefore)
      assertThat(rig.shown(partial)).isEmpty()
      // `Quiet` non porta niente: ne' un ThumbHash, ne' un riferimento, ne' un id.
      assertThat(rig.thumbs(partial)).isNotEmpty()

      // Nessun accesso: lo stesso, con la base ancora intera.
      rig.access.value = MediaAccess.NONE
      rig.selection.value = null
      val none = rig.next { f -> rig.thumbs(f).all { it is AnalysisThumb.Quiet } }
      assertThat(rig.base.snapshot.value).isSameInstanceAs(baseBefore)
      assertThat(rig.shown(none)).isEmpty()

      // E il permesso che torna riapre il nastro (si osservano i due versi).
      rig.access.value = MediaAccess.FULL
      val back = rig.next { f -> rig.target in rig.shown(f) }
      assertThat(rig.shown(back)).contains(rig.target)
      collector.cancel()
    } finally {
      scope.cancel()
      rig.world.close()
    }
  }

  @Test
  fun unNascostoUnAttimoFaNonSiMostraAncheSeLIstantaneaLoHaAncora() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val world = HiddenWorldBuilder.build(context, WorldSpec.onlyLateHidden())
    val cells = world.catalog.mediaContentKeysOf(world.ids)
    val facts = ContentFacts()
    val keyOf = cells.associate { it.mediaId to it.own.first() }
    keyOf.forEach { (id, key) -> facts.put(key, ContentFact(id, VersionId(java.util.UUID(0L, 99L).toString()), size = 1, source = MediaSource.CAMERA)) }
    val keys = CellKeys(CatalogCellKeySource { world.catalog })
    keys.sync()
    try {
      val every = keyOf.values.toList()
      val page = AppAnalysisLive(
        MutableStateFlow(AiLive(every.first(), every.drop(1), emptyList())),
        CatalogVisibleMedia(world.db, MutableStateFlow(world.snapshot)), CatalogHiddenMask(world.db), keys, facts, scope,
      )
      val flow = page.live.settled(every.size - 1)
      val shown = (listOfNotNull(flow.current) + flow.recent).filterIsInstance<AnalysisThumb.Shown>().mapTo(HashSet()) { it.id }
      // L'istantanea ha ancora i nascosti di un attimo fa (e i loro gruppi): il filtro finale chiede al catalogo e li toglie.
      assertThat(world.liveMustNeverShow).isNotEmpty()
      assertThat(shown.intersect(world.liveMustNeverShow)).isEmpty()
      assertThat(shown).isEqualTo(world.liveLibraryCells)
    } finally {
      scope.cancel()
      world.close()
    }
  }

  @Test
  fun unNascondiFattoOraRifaIlNastroSenzaAspettareLIstantanea() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val rig = rig(WorldSpec.nothingHidden(), scope)
    try {
      val collector = scope.launch { rig.page.live.collect {} }
      rig.next { f -> rig.target in rig.shown(f) }

      // Nascondo il media scrivendo nel catalogo (come un "Nascondi" o un dato arrivato dagli altri): l'istantanea non cambia ancora.
      rig.world.db.personalDao().upsert(MediaPersonalEntity(rig.target.value, favorite = false, hidden = true, updatedAt = 9_000_000_000_000L))
      rig.changes.emit(Unit)

      val after = rig.next { f -> rig.target !in rig.shown(f) }
      assertThat(rig.shown(after)).doesNotContain(rig.target)
      collector.cancel()
    } finally {
      scope.cancel()
      rig.world.close()
    }
  }

  // ---- XS2-1, parte 1: niente replay oltre la superficie osservata, niente emissioni intermedie con una Shown non piu' autorizzata -----------------

  /**
   * Pagina aperta con accesso pieno, uscita oltre lo stop del flusso, **revoca/nascondi a flusso fermo**, rientro: il **primo** valore che chi rientra riceve
   * non contiene il media (e nemmeno il successivo). Mutazione: lasciare il replay dell'ultimo valore dopo lo stop (`replayExpirationMillis` di serie).
   */
  @Test
  fun dopoLoStopDelFlussoLaRevocaELNascondiNonRiconsegnanoIlValoreVecchio() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val rig = rig(WorldSpec.nothingHidden(), scope, stopTimeoutMillis = 50)
    try {
      // Pagina aperta, accesso pieno: il media e' sul nastro.
      val open = scope.launch { rig.page.live.collect {} }
      rig.next { f -> rig.target in rig.shown(f) }
      open.cancel()
      // Uscita oltre lo stop del flusso (50 ms in questa prova, 5 s in produzione).
      kotlinx.coroutines.delay(500)

      // A flusso fermo: revoca (per il media: accesso parziale con altre foto) e, per un altro media, "Nascondi" scritto nel catalogo.
      rig.selection.value = setOf(rig.others.first())
      rig.access.value = MediaAccess.PARTIAL
      val hiddenToo = rig.others.last()
      rig.world.db.personalDao().upsert(MediaPersonalEntity(hiddenToo.value, favorite = false, hidden = true, updatedAt = 9_000_000_000_000L))

      // Il valore che resta **a flusso fermo** (quello che un nuovo osservatore leggerebbe per primo, prima di qualunque calcolo) e' gia' neutro: nessun replay del
      // vecchio. Si legge `value` senza osservare: con il rientro in linea (`Unconfined`) il flusso riparte e sovrascrive la cache prima che `first()` la legga,
      // e la prova non vedrebbe il replay. Mutazione: `replayExpirationMillis` di serie (la cache resta).
      assertThat(rig.shown(rig.page.live.value)).isEmpty()

      // Rientro: il PRIMO valore non ha anteprime.
      val first = rig.page.live.first()
      assertThat(rig.shown(first)).isEmpty()
      // Con l'accesso che torna pieno, il "Nascondi" fatto a flusso fermo vale ancora: il calcolo di adesso non lo mostra.
      rig.access.value = MediaAccess.FULL
      rig.selection.value = null
      val again = scope.launch { rig.page.live.collect {} }
      val settled = rig.next { f -> rig.target in rig.shown(f) }
      assertThat(rig.shown(settled)).doesNotContain(hiddenToo)
      again.cancel()
    } finally {
      scope.cancel()
      rig.world.close()
    }
  }

  /**
   * La prova che **vieta le emissioni intermedie**, non solo quella che aspetta il risultato finale: una risoluzione partita con l'accesso pieno e ferma nel
   * filtro finale, una revoca, poi la risoluzione che si sblocca: dopo la revoca **nessun** valore emesso ha una `Shown`. Mutazione: risolvere in sequenza senza
   * annullare (`transform` al posto di `transformLatest`): la risoluzione in corso non si annulla e consegna la sua `Shown` dopo la revoca.
   *
   * Il nastro gira su un **thread vero** (`Dispatchers.Default`) e chi osserva e' `Unconfined` in un altro scope: cosi' ogni emissione e' vista nel momento in cui
   * esce. Tutto su `Unconfined` in questa prova non basterebbe: l'emissione vecchia e quella neutra di dopo escono una dentro l'altra e lo `StateFlow` ne conserva
   * una sola, e la prova non vedrebbe il difetto.
   */
  @Test
  fun dopoLaRevocaNessunaEmissioneIntermediaHaUnaShown() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val watcher = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    val rig = rig(WorldSpec.nothingHidden(), scope) { real ->
      object : VisibleMedia by real {
        override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> {
          // Il filtro finale risponde **con l'accesso pieno** (risultato calcolato prima della revoca) e poi la risoluzione resta ferma: se si sbloccasse dopo la
          // revoca consegnerebbe una `Shown` che nessuno piu' autorizza. (Se il blocco stesse prima della lettura, il filtro leggerebbe la vista di adesso e
          // risponderebbe vuoto da solo: la prova non vedrebbe niente.)
          val answer = real.visible(ids)
          entered.complete(Unit)
          gate.await()
          return answer
        }
      }
    }
    try {
      val log = java.util.concurrent.CopyOnWriteArrayList<Any>()
      val collector = watcher.launch { rig.page.live.collect { log += it } }
      // La prima risoluzione (accesso pieno) e' ferma dentro il filtro finale.
      withTimeout(20_000) { entered.await() }
      log += REVOKED
      rig.access.value = MediaAccess.NONE
      // La revoca si vede **subito**, anche con una risoluzione ferma: esce lo stato neutro prima che la risoluzione di prima si sblocchi.
      withTimeout(5_000) {
        while (log.dropWhile { it !== REVOKED }.drop(1).none { it is AnalysisFlow }) kotlinx.coroutines.delay(10)
      }
      // Si sblocca la risoluzione di prima: la sua Shown non e' piu' autorizzata e non deve uscire.
      gate.complete(Unit)
      kotlinx.coroutines.delay(500)
      val after = log.dropWhile { it !== REVOKED }.drop(1).filterIsInstance<AnalysisFlow>()
      assertThat(after).isNotEmpty()
      assertThat(after.filter { rig.shown(it).isNotEmpty() }).isEmpty()
      // E lo stato neutro di dopo e' della generazione nuova.
      val before = log.takeWhile { it !== REVOKED }.filterIsInstance<AnalysisFlow>().maxOfOrNull { it.visibilityGeneration } ?: 0L
      assertThat(after.last().visibilityGeneration).isGreaterThan(before)
      collector.cancel()
    } finally {
      watcher.cancel()
      scope.cancel()
      rig.world.close()
    }
  }

  /** La generazione sale a ogni cambio di visibilita' e subito dopo c'e' uno stato **tutto neutro**; il solo avanzare del motore non la alza e non lampeggia. */
  @Test
  fun laGenerazioneSaleAlCambioDiVisibilitaESubitoNeutraNonConIlMotore() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val rig = rig(WorldSpec.nothingHidden(), scope)
    try {
      val log = java.util.concurrent.CopyOnWriteArrayList<Any>()
      val collector = scope.launch { rig.page.live.collect { log += it } }
      val start = rig.next { f -> rig.target in rig.shown(f) }

      // Il motore avanza (un media finito): stessa generazione e nessun fotogramma neutro in mezzo.
      log += ENGINE
      val ahead = AiLive(current = rig.keyOf.getValue(rig.others.first()), recent = listOf(rig.keyOf.getValue(rig.target)), next = emptyList())
      rig.engine.value = ahead
      val advanced = rig.next { f -> rig.shown(f).isNotEmpty() && f.recent.size == 1 }
      assertThat(advanced.visibilityGeneration).isEqualTo(start.visibilityGeneration)
      val between = log.dropWhile { it !== ENGINE }.drop(1).filterIsInstance<AnalysisFlow>().filter { it.recent.size == 1 }
      assertThat(between.filter { rig.shown(it).isEmpty() }).isEmpty()

      // Un "Nascondi" appena fatto: la generazione sale e il primo stato dopo e' tutto neutro.
      log += CHANGED
      rig.changes.emit(Unit)
      val afterChange = rig.next { f -> f.visibilityGeneration > advanced.visibilityGeneration }
      assertThat(afterChange.visibilityGeneration).isGreaterThan(advanced.visibilityGeneration)
      val firstAfter = log.dropWhile { it !== CHANGED }.drop(1).filterIsInstance<AnalysisFlow>().first()
      assertThat(firstAfter.visibilityGeneration).isGreaterThan(advanced.visibilityGeneration)
      assertThat(rig.shown(firstAfter)).isEmpty()
      collector.cancel()
    } finally {
      scope.cancel()
      rig.world.close()
    }
  }

  private companion object {
    val REVOKED = Any()
    val ENGINE = Any()
    val CHANGED = Any()
  }
}
