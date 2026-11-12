package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.ai.AppAnalysisLive
import dev.pampa.mycloudgallery.ai.ContentFact
import dev.pampa.mycloudgallery.ai.ContentFacts
import dev.pampa.mycloudgallery.core.ai.contract.AiLive
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisThumb
import dev.pampa.mycloudgallery.search.CatalogCellKeySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

// Pacchetto XF (XS-1): la sonda della pagina "Analisi delle foto". Il nastro vero (`AppAnalysisLive`) sul catalogo vero e sull'istantanea vera
// del mondo casuale, con le **chiavi di tutti i media** del mondo come se il motore le avesse appena lavorate: la pagina puo' disegnare
// un'anteprima (con ThumbHash e riferimento) solo per le celle che la Libreria mostra adesso, mai un nascosto, mai un membro di un gruppo con un
// nascosto, nemmeno uno nascosto un attimo fa (il filtro finale chiede al catalogo, non all'istantanea).

internal object RealAnalysisProbes {

  val analysis = SurfaceProbe { w ->
    val cells = w.catalog.mediaContentKeysOf(w.ids)
    val keyOf = cells.associate { it.mediaId to it.own.first() }
    val facts = ContentFacts()
    keyOf.forEach { (id, key) -> facts.put(key, ContentFact(id, VersionId(java.util.UUID(0L, 99L).toString()), size = 1, source = MediaSource.CAMERA)) }
    val keys = CellKeys(CatalogCellKeySource { w.catalog })
    keys.sync()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val everyKey = keyOf.values.toList()
      val page = AppAnalysisLive(
        engineLive = MutableStateFlow(AiLive(everyKey.first(), everyKey.drop(1), emptyList())),
        visible = CatalogVisibleMedia(w.db, MutableStateFlow(w.snapshot)),
        mask = CatalogHiddenMask(w.db),
        cells = keys,
        facts = facts,
        scope = scope,
      )
      val flow = withTimeout(30_000) { page.live.first { it.recent.size == everyKey.size - 1 } }
      val thumbs = listOfNotNull(flow.current) + flow.recent + flow.next
      val shown = thumbs.filterIsInstance<AnalysisThumb.Shown>().mapTo(LinkedHashSet()) { it.id }
      Probed(shown = shown, expected = w.liveLibraryCells, within = w.liveShowable)
    } finally {
      scope.cancel()
    }
  }
}
