package dev.pampa.mycloudgallery.ai

import android.app.Application
import dev.pampa.mycloudgallery.gallery.AnalysisVisibilityBoundary
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidPhoneAccessReader
import dev.pampa.mycloudgallery.integration.Attese
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiLive
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.library.testing.FakeTimelineRepository
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.TimelinePatcher
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
import kotlinx.coroutines.flow.StateFlow
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
    val changes: TestVisibilitySignal,
    val page: AppAnalysisLive,
    val target: MediaId,
    val others: List<MediaId>,
    val scope: CoroutineScope,
    val engine: MutableStateFlow<AiLive>,
    val keyOf: Map<MediaId, ContentKey>,
    /** La barriera di produzione (con `withBoundary` o `realAccess`), null se il nastro ascolta il segnale di prova. */
    val boundary: AnalysisVisibilityBoundary?,
    /** L'istantanea che la Libreria pubblica (`PartialAccessTimeline`): quella che la barriera confronta. */
    val visibleSnapshot: StateFlow<TimelineSnapshot?>,
  ) {
    /** Tutte le anteprime del nastro. */
    fun thumbs(flow: AnalysisFlow): List<AnalysisThumb> = listOfNotNull(flow.current) + flow.recent + flow.next

    fun shown(flow: AnalysisFlow): Set<MediaId> = thumbs(flow).filterIsInstance<AnalysisThumb.Shown>().mapTo(HashSet()) { it.id }

    suspend fun next(predicate: (AnalysisFlow) -> Boolean): AnalysisFlow = withTimeout(Attese.TETTO) { page.live.first(predicate) }
  }

  private suspend fun rig(
    spec: WorldSpec,
    scope: CoroutineScope,
    stopTimeoutMillis: Long = AppAnalysisLive.STOP_AFTER_MILLIS,
    realAccess: AndroidPhoneAccessReader? = null,
    timelineScope: CoroutineScope = scope,
    boundaryReady: (AnalysisVisibilityBoundary) -> Unit = {},
    /** XL: la barriera di produzione anche senza il lettore vero dei permessi (accesso e selezione sono quelli della prova). */
    withBoundary: Boolean = false,
    wrapMask: (HiddenMask) -> HiddenMask = { it },
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
    val derivedAccess = realAccess?.state?.map { it.media }?.stateIn(timelineScope, SharingStarted.Eagerly, realAccess.state.value.media) ?: access
    val visibleTimeline = PartialAccessTimeline(base, selection, derivedAccess, timelineScope)
    val visible = wrapVisible(CatalogVisibleMedia(world.db, visibleTimeline.snapshot))
    val mask = wrapMask(CatalogHiddenMask(world.db))
    val changes = TestVisibilitySignal()

    val cellIds = world.libraryCells.toList()
    val target = cellIds.first()
    val others = cellIds.drop(1).take(3)
    val shownKeys = (listOf(target) + others).map { keyOf.getValue(it) }
    val engine = AiLive(current = shownKeys.first(), recent = shownKeys.drop(1), next = emptyList())
    val engineFlow = MutableStateFlow(engine)
    val boundary = realAccess?.let { reader ->
      AnalysisVisibilityBoundary({ reader.state.value.media == MediaAccess.FULL }, { visibleTimeline.snapshot.value }).also { boundary ->
        reader.onChange { boundary.changed() }
        boundaryReady(boundary)
      }
    } ?: if (withBoundary) {
      AnalysisVisibilityBoundary({ access.value == MediaAccess.FULL && selection.value == null }, { visibleTimeline.snapshot.value }).also(boundaryReady)
    } else {
      null
    }
    val page = AppAnalysisLive(engineFlow, visible, mask, keys, facts, scope, boundary ?: changes, stopTimeoutMillis)
    return Rig(world, base, access, selection, changes, page, target, others, scope, engineFlow, keyOf, boundary, visibleTimeline.snapshot)
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
      rig.changes.fire()

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
      withTimeout(Attese.TETTO) { entered.await() }
      log += REVOKED
      rig.access.value = MediaAccess.NONE
      // La revoca si vede **subito**, anche con una risoluzione ferma: esce lo stato neutro prima che la risoluzione di prima si sblocchi.
      withTimeout(Attese.TETTO) {
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
      val ahead = AiLive(current = rig.keyOf.getValue(rig.others.first()), recent = listOf(rig.keyOf.getValue(rig.target)), next = emptyList(), finished = 7)
      rig.engine.value = ahead
      val advanced = rig.next { f -> rig.shown(f).isNotEmpty() && f.recent.size == 1 }
      assertThat(advanced.visibilityGeneration).isEqualTo(start.visibilityGeneration)
      // XU-9: il contatore del motore arriva a chi disegna.
      assertThat(advanced.finished).isEqualTo(7)
      val between = log.dropWhile { it !== ENGINE }.drop(1).filterIsInstance<AnalysisFlow>().filter { it.recent.size == 1 }
      assertThat(between.filter { rig.shown(it).isEmpty() }).isEmpty()

      // Un "Nascondi" appena fatto: la generazione sale e il primo stato dopo e' tutto neutro.
      log += CHANGED
      rig.changes.fire()
      val afterChange = rig.next { f -> f.visibilityGeneration > advanced.visibilityGeneration }
      assertThat(afterChange.visibilityGeneration).isGreaterThan(advanced.visibilityGeneration)
      val firstAfter = log.dropWhile { it !== CHANGED }.drop(1).filterIsInstance<AnalysisFlow>().first()
      assertThat(firstAfter.visibilityGeneration).isGreaterThan(advanced.visibilityGeneration)
      assertThat(rig.shown(firstAfter)).isEmpty()
      // XU-9: anche lo stato neutro porta il contatore, e una generazione nuova non lo azzera.
      assertThat(firstAfter.finished).isEqualTo(7)
      collector.cancel()
    } finally {
      scope.cancel()
      rig.world.close()
    }
  }

  // ---- XS3-2: l'invalidazione e' sincrona, anche con una risoluzione lenta che non coopera -----------------------------------------------------

  /**
   * Il filtro finale che **si blocca su un latch** (un'attesa che non si puo' annullare, non una sospensione) dopo aver risposto con l'accesso di prima: la risoluzione
   * resta ferma e non coopera con la cancellazione. [armed] la accende.
   */
  private class StuckVisible(private val real: VisibleMedia) : VisibleMedia by real {
    val armed = java.util.concurrent.atomic.AtomicBoolean(false)
    val latch = java.util.concurrent.CountDownLatch(1)
    val entered = kotlinx.coroutines.CompletableDeferred<Unit>()

    override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> {
      val answer = real.visible(ids)
      if (armed.get()) {
        entered.complete(Unit)
        latch.await()
      }
      return answer
    }
  }

  /**
   * Il segnale del proprietario della visibilita' (un "Nascondi" della Libreria vera, una revoca): **quando il callback torna**, `live.value` non ha nessuna `Shown` della
   * generazione vecchia, mentre una risoluzione lenta e non cooperativa e' ancora ferma; chi rientra entro lo stop legge gia' il neutro; e quando la risoluzione vecchia
   * si sblocca il suo risultato non arriva mai al valore condiviso (le uniche `Shown` che compaiono dopo sono della generazione nuova). Il nastro gira su un thread vero e chi
   * osserva e' `Unconfined` in un altro scope (ogni emissione si vede). Mutazioni: il callback che non scrive il neutro (si limita a chiedere un ricalcolo); il controllo di
   * generazione tolto dalla scrittura.
   */
  @Test
  fun ilSegnaleInvalidaSubitoIlValoreCondivisoAncheConUnaRisoluzioneNonCooperativa() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val watcher = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    lateinit var stuck: StuckVisible
    val rig = rig(WorldSpec.nothingHidden(), scope) { real -> StuckVisible(real).also { stuck = it } }
    try {
      val log = java.util.concurrent.CopyOnWriteArrayList<Any>()
      val watching = watcher.launch { rig.page.live.collect { log += it } }
      val resolved = rig.next { f -> rig.target in rig.shown(f) }

      // Una risoluzione nuova (il motore avanza) si ferma dentro il filtro finale, sul latch.
      stuck.armed.set(true)
      rig.engine.value = AiLive(current = rig.keyOf.getValue(rig.others.first()), recent = listOf(rig.keyOf.getValue(rig.target)), next = emptyList())
      withTimeout(Attese.TETTO) { stuck.entered.await() }

      // Il proprietario segnala il cambio: al ritorno del callback il valore condiviso e' gia' neutro e di una generazione nuova.
      log += FIRED
      rig.changes.fire()
      val now = rig.page.live.value
      assertThat(rig.shown(now)).isEmpty()
      assertThat(now.visibilityGeneration).isGreaterThan(resolved.visibilityGeneration)
      assertThat(stuck.latch.count).isEqualTo(1L) // la risoluzione vecchia e' ancora ferma: non si e' aspettata

      // Chi rientra entro lo stop (nessun osservatore per un attimo, il produttore e' ancora attivo) legge gia' il neutro.
      watching.cancel()
      assertThat(rig.shown(rig.page.live.value)).isEmpty()
      val again = watcher.launch { rig.page.live.collect { log += it } }

      // Si sblocca la risoluzione vecchia: il suo risultato non arriva al valore condiviso. Le `Shown` che compaiono dopo sono solo della generazione nuova.
      stuck.armed.set(false)
      stuck.latch.countDown()
      kotlinx.coroutines.delay(700)
      val after = log.dropWhile { it !== FIRED }.drop(1).filterIsInstance<AnalysisFlow>()
      assertThat(after).isNotEmpty()
      assertThat(after.filter { rig.shown(it).isNotEmpty() }.map { it.visibilityGeneration }.filter { it <= resolved.visibilityGeneration }).isEmpty()
      again.cancel()
    } finally {
      stuck.latch.countDown()
      watcher.cancel()
      scope.cancel()
      rig.world.close()
    }
  }

  /** La stessa cosa per la **revoca dei permessi** (l'istantanea autorizzata sparisce): il neutro arriva mentre la risoluzione non cooperativa e' ancora ferma, e dopo non esce nessuna `Shown`. */
  @Test
  fun laRevocaInvalidaIlValoreCondivisoMentreUnaRisoluzioneNonCooperativaEFerma() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val watcher = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    lateinit var stuck: StuckVisible
    val rig = rig(WorldSpec.nothingHidden(), scope) { real -> StuckVisible(real).also { stuck = it } }
    try {
      val log = java.util.concurrent.CopyOnWriteArrayList<Any>()
      watcher.launch { rig.page.live.collect { log += it } }
      val resolved = rig.next { f -> rig.target in rig.shown(f) }
      stuck.armed.set(true)
      rig.engine.value = AiLive(current = rig.keyOf.getValue(rig.others.first()), recent = listOf(rig.keyOf.getValue(rig.target)), next = emptyList())
      withTimeout(Attese.TETTO) { stuck.entered.await() }

      log += REVOKED
      rig.access.value = MediaAccess.NONE
      // Il neutro arriva **senza** aspettare la risoluzione ferma (il latch e' ancora chiuso).
      withTimeout(Attese.TETTO) {
        while (rig.shown(rig.page.live.value).isNotEmpty() || rig.page.live.value.visibilityGeneration <= resolved.visibilityGeneration) kotlinx.coroutines.delay(10)
      }
      assertThat(stuck.latch.count).isEqualTo(1L)

      stuck.armed.set(false)
      stuck.latch.countDown()
      kotlinx.coroutines.delay(700)
      val after = log.dropWhile { it !== REVOKED }.drop(1).filterIsInstance<AnalysisFlow>()
      assertThat(after).isNotEmpty()
      assertThat(after.filter { rig.shown(it).isNotEmpty() }).isEmpty()
    } finally {
      stuck.latch.countDown()
      watcher.cancel()
      scope.cancel()
      rig.world.close()
    }
  }

  /**
   * Il blocco **all'ultimo passo**: il filtro finale e' `visible.visible(ids) - mask.hiddenAmong(ids)`; qui e' la maschera a restare ferma sul latch, dopo aver risposto, e dopo
   * di lei la risoluzione non ha piu' nessun punto di sospensione. Lo scopo e' provare la guardia **di scrittura**: quando il latch si sblocca la cancellazione non puo'
   * piu' fermare nulla (nessuna sospensione dopo) e solo il controllo sotto lucchetto (o l'`ensureActive` prima) impedisce di scrivere il risultato vecchio.
   */
  private class StuckMask(private val real: HiddenMask) : HiddenMask by real {
    val armed = java.util.concurrent.atomic.AtomicBoolean(false)
    val latch = java.util.concurrent.CountDownLatch(1)
    val entered = kotlinx.coroutines.CompletableDeferred<Unit>()

    override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> {
      val answer = real.hiddenAmong(ids)
      if (armed.get()) {
        entered.complete(Unit)
        latch.await()
      }
      return answer
    }
  }

  /**
   * La risoluzione ferma **all'ultimo passo** (nessuna sospensione dopo) non scrive il suo risultato vecchio quando si sblocca: lo impedisce la guardia di scrittura. Mutazione:
   * togliere insieme il controllo di generazione sotto lucchetto e l'`ensureActive` (ognuno da solo e' ridondante con l'altro: l'`ensureActive` vede la cancellazione che il segnale
   * ha chiesto, il controllo vede la generazione cambiata).
   */
  @Test
  fun unaRisoluzioneFermaAllUltimoPassoNonScriveIlRisultatoVecchioQuandoSiSblocca() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val watcher = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    lateinit var stuck: StuckMask
    val rig = rig(WorldSpec.nothingHidden(), scope, wrapMask = { real -> StuckMask(real).also { stuck = it } })
    try {
      val log = java.util.concurrent.CopyOnWriteArrayList<Any>()
      watcher.launch { rig.page.live.collect { log += it } }
      val resolved = rig.next { f -> rig.target in rig.shown(f) }

      stuck.armed.set(true)
      rig.engine.value = AiLive(current = rig.keyOf.getValue(rig.others.first()), recent = listOf(rig.keyOf.getValue(rig.target)), next = emptyList())
      withTimeout(Attese.TETTO) { stuck.entered.await() }

      log += FIRED
      rig.changes.fire()
      assertThat(rig.shown(rig.page.live.value)).isEmpty()

      stuck.armed.set(false)
      stuck.latch.countDown()
      kotlinx.coroutines.delay(700)
      val after = log.dropWhile { it !== FIRED }.drop(1).filterIsInstance<AnalysisFlow>()
      assertThat(after).isNotEmpty()
      assertThat(after.filter { rig.shown(it).isNotEmpty() }.map { it.visibilityGeneration }.filter { it <= resolved.visibilityGeneration }).isEmpty()
    } finally {
      stuck.latch.countDown()
      watcher.cancel()
      scope.cancel()
      rig.world.close()
    }
  }

  private companion object {
    val FIRED = Any()
    val REVOKED = Any()
    val ENGINE = Any()
    val CHANGED = Any()
    val NOTICED = Any()
  }
  @Test
  @Config(sdk = [34])
  fun permessoRealeDaFullAParzialeNeutralizzaPrimaDelleDerivazioni() = realPermissionBoundary(partial = true)

  @Test
  @Config(sdk = [34])
  fun permessoRealeDaFullANessunoNeutralizzaPrimaDelleDerivazioni() = realPermissionBoundary(partial = false)

  @Test
  @Config(sdk = [34])
  fun contenutoRevocatoNeutralizzaAncheConIstantaneaDerivataFerma() = realPermissionBoundary(false, "content")

  @Test
  @Config(sdk = [34])
  fun ritiroNeutralizzaAncheConIstantaneaDerivataFerma() = realPermissionBoundary(false, "retire")

  private fun realPermissionBoundary(partial: Boolean, action: String = "permission") = runBlocking {
    val app = context.applicationContext as Application
    val shadow = shadowOf(app)
    val images = "android.permission.READ_MEDIA_IMAGES"
    val videos = "android.permission.READ_MEDIA_VIDEO"
    shadow.grantPermissions(images, videos)
    val reader = AndroidPhoneAccessReader(app, volumeNames = { emptySet() })
    val producer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val derivedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val derived = CoroutineScope(SupervisorJob() + derivedDispatcher)
    val unblock = CountDownLatch(1)
    var world: BuiltWorld? = null
    try {
      lateinit var boundary: AnalysisVisibilityBoundary
      val rig = rig(WorldSpec.nothingHidden(), producer, realAccess = reader, timelineScope = derived, boundaryReady = { boundary = it })
      world = rig.world
      val observer = producer.launch { rig.page.live.collect {} }
      val shown = rig.next { rig.shown(it).isNotEmpty() }
      val blocked = CountDownLatch(1)
      derived.launch { blocked.countDown(); unblock.await(20, TimeUnit.SECONDS) }
      check(blocked.await(20, TimeUnit.SECONDS))
      when (action) {
        "content" -> boundary.contentChanged()
        "retire" -> boundary.retire()
        else -> {
          shadow.denyPermissions(images, videos)
          if (partial) shadow.grantPermissions("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
          reader.refresh()
        }
      }
      val immediate = rig.page.live.value
      assertThat(immediate.visibilityGeneration).isGreaterThan(shown.visibilityGeneration)
      assertThat(rig.thumbs(immediate).filterIsInstance<AnalysisThumb.Shown>()).isEmpty()
      // Il produttore resta Default: neppure un suo nuovo tentativo puo' pubblicare l'istantanea derivata vecchia.
      rig.engine.value = rig.engine.value.copy(finished = 99)
      kotlinx.coroutines.delay(100)
      assertThat(rig.shown(rig.page.live.value)).isEmpty()
      observer.cancel()
    } finally {
      unblock.countDown()
      producer.cancel()
      derived.cancel()
      derivedDispatcher.close()
      world?.db?.close()
    }
  }

  // ---- XL: avviso di Android -> rilettura finita -> istantanea aggiornata -> il nastro riapre (XS5-1, XS5-2) ----------------------------------------

  /**
   * XS5-1. Un avviso di Android toglie la foto P dal telefono: il nastro chiude **subito**. Un'istantanea gia' in viaggio, **precedente alla rilettura** e con P ancora dentro (e' un
   * oggetto diverso da quello catturato all'avviso), arriva **dopo** l'avviso: il nastro **resta neutro**. La rilettura (partita dopo l'avviso) finisce e dichiara il suo `change_seq`;
   * finche' l'istantanea pubblicata e' piu' indietro il nastro resta chiuso; quando arriva quella che tiene conto della rilettura (senza P) il nastro riapre e P non c'e'.
   * Produttore su `Default`, la vista autorizzata su un altro thread, un osservatore separato su `Unconfined`; i controlli negativi leggono `canResolve()` e `.value` (nessuna attesa).
   * Mutazioni: riaprire all'arrivo di un'istantanea qualunque (la barriera di prima); dichiarare una rilettura senza aspettare l'istantanea.
   */
  @Test
  fun unIstantaneaVecchiaInViaggioDopoUnAvvisoNonRiapreEPoiLaFotoToltaNonCe() = runBlocking<Unit> {
    val producer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val derivedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val derived = CoroutineScope(SupervisorJob() + derivedDispatcher)
    val watcher = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    var world: BuiltWorld? = null
    try {
      val rig = rig(WorldSpec.nothingHidden(), producer, timelineScope = derived, withBoundary = true)
      world = rig.world
      val boundary = checkNotNull(rig.boundary)
      val log = java.util.concurrent.CopyOnWriteArrayList<Any>()
      watcher.launch { rig.page.live.collect { log += it } }
      val before = rig.next { rig.target in rig.shown(it) }
      val s0 = rig.world.snapshot
      val seq = s0.seq

      // Android avvisa: la foto P e' stata tolta dal telefono. Il nastro e' neutro **nello stesso istante**.
      log += NOTICED
      boundary.contentChanged()
      val neutral = rig.page.live.value
      assertThat(rig.shown(neutral)).isEmpty()
      assertThat(neutral.visibilityGeneration).isGreaterThan(before.visibilityGeneration)

      // S1, in viaggio da prima della rilettura, ha ancora P: arriva dopo l'avviso ed e' la nuova istantanea della Libreria. Non riapre.
      val s1 = TimelinePatcher.withSeq(s0, seq + 1)
      assertThat(s1.indexOf(rig.target)).isAtLeast(0)
      rig.base.publish(s1)
      withTimeout(Attese.TETTO) { rig.visibleSnapshot.first { it === s1 } }
      rig.engine.value = rig.engine.value.copy(finished = 11)
      assertThat(boundary.canResolve()).isFalse()
      assertThat(rig.shown(rig.page.live.value)).isEmpty()

      // La rilettura (partita dopo l'avviso) e' finita e ha scritto il catalogo fino a seq + 2; l'istantanea pubblicata e' ancora a seq + 1: il nastro resta chiuso.
      boundary.rereadFinished(boundary.noticeOrder(), catalogSeq = seq + 2)
      assertThat(boundary.canResolve()).isFalse()
      rig.engine.value = rig.engine.value.copy(finished = 12)
      assertThat(rig.shown(rig.page.live.value)).isEmpty()

      // Arriva l'istantanea che tiene conto della rilettura: senza P. Il nastro riapre e P non c'e'.
      val s2 = TimelinePatcher.withSeq(TimelinePatcher.remove(s0, setOf(rig.target)), seq + 2)
      assertThat(s2.indexOf(rig.target)).isLessThan(0)
      rig.base.publish(s2)
      val reopened = rig.next { rig.shown(it).isNotEmpty() }
      assertThat(boundary.canResolve()).isTrue()
      assertThat(rig.shown(reopened)).doesNotContain(rig.target)
      assertThat(rig.shown(reopened)).containsAtLeastElementsIn(rig.others)
      // E in nessun momento dopo l'avviso un'emissione ha mostrato P.
      val after = log.dropWhile { it !== NOTICED }.drop(1).filterIsInstance<AnalysisFlow>()
      assertThat(after.filter { rig.target in rig.shown(it) }).isEmpty()
    } finally {
      watcher.cancel()
      producer.cancel()
      derived.cancel()
      derivedDispatcher.close()
      world?.db?.close()
    }
  }

  /**
   * XS5-2, il caso piu' comune: l'avviso e' ridondante (o la foto e' un file in sospeso), la rilettura finisce bene **senza cambiare niente** e non arriva nessuna istantanea nuova. Il
   * nastro deve riaprire lo stesso, con le stesse foto: la riapertura dipende dalla rilettura finita, non da un oggetto nuovo. Produttore su `Default`, osservatore separato.
   * Mutazione: la rilettura che riapre ma non avvisa chi ascolta (nessun ricalcolo: il nastro resta vuoto finche' il motore non avanza).
   */
  @Test
  fun unaRiletturaFinitaSenzaCambiamentiRiapreIlNastroConLeStesseFoto() = runBlocking<Unit> {
    val producer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val derivedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val derived = CoroutineScope(SupervisorJob() + derivedDispatcher)
    var world: BuiltWorld? = null
    try {
      val rig = rig(WorldSpec.nothingHidden(), producer, timelineScope = derived, withBoundary = true)
      world = rig.world
      val boundary = checkNotNull(rig.boundary)
      val observer = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      observer.launch { rig.page.live.collect {} }
      val before = rig.next { rig.target in rig.shown(it) }
      val snapshot = rig.visibleSnapshot.value

      boundary.contentChanged()
      assertThat(rig.shown(rig.page.live.value)).isEmpty()
      assertThat(boundary.canResolve()).isFalse()

      // La rilettura (partita dopo l'avviso) finisce senza toccare il catalogo: change_seq e istantanea sono quelli di prima.
      boundary.rereadFinished(boundary.noticeOrder(), catalogSeq = rig.world.snapshot.seq)
      val back = rig.next { rig.target in rig.shown(it) }
      assertThat(rig.visibleSnapshot.value).isSameInstanceAs(snapshot)
      assertThat(rig.shown(back)).containsAtLeastElementsIn(rig.shown(before))
      assertThat(back.visibilityGeneration).isGreaterThan(before.visibilityGeneration)
      observer.cancel()
    } finally {
      producer.cancel()
      derived.cancel()
      derivedDispatcher.close()
      world?.db?.close()
    }
  }

}
