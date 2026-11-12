package dev.pampa.mycloudgallery.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiLive
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.library.testing.FakeTimelineRepository
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
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
  ) {
    /** Tutte le anteprime del nastro. */
    fun thumbs(flow: AnalysisFlow): List<AnalysisThumb> = listOfNotNull(flow.current) + flow.recent + flow.next

    fun shown(flow: AnalysisFlow): Set<MediaId> = thumbs(flow).filterIsInstance<AnalysisThumb.Shown>().mapTo(HashSet()) { it.id }

    suspend fun next(predicate: (AnalysisFlow) -> Boolean): AnalysisFlow = withTimeout(20_000) { page.live.first(predicate) }
  }

  private suspend fun rig(spec: WorldSpec, scope: CoroutineScope): Rig {
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
    val visible = CatalogVisibleMedia(world.db, visibleTimeline.snapshot)
    val mask = CatalogHiddenMask(world.db)
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val cellIds = world.libraryCells.toList()
    val target = cellIds.first()
    val others = cellIds.drop(1).take(3)
    val shownKeys = (listOf(target) + others).map { keyOf.getValue(it) }
    val engine = AiLive(current = shownKeys.first(), recent = shownKeys.drop(1), next = emptyList())
    val page = AppAnalysisLive(MutableStateFlow(engine), visible, mask, keys, facts, scope, changes)
    return Rig(world, base, access, selection, changes, page, target, others, scope)
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
      val flow = withTimeout(30_000) { page.live.first { it.recent.size == every.size - 1 } }
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
}
