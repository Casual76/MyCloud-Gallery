package dev.pampa.mycloudgallery.gallery

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.CatalogChange
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.entity.IndexRow
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.data.TimelineSource
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.FilteredTimelineView
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexReader
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * La sorgente dell'indice della timeline sopra il catalogo, che pero' nasce dopo (serve l'account): ogni lettura aspetta
 * il catalogo. Il repository parte solo quando il catalogo c'e' ([GalleryGraph.startWhenReady]), quindi in pratica non
 * aspetta mai; l'iscrizione ai cambiamenti precede comunque la lettura del `change_seq`, come chiede P5.
 */
internal class DeferredTimelineSource(private val source: suspend () -> CatalogTimelineSource) : TimelineSource {

  override val changes: Flow<CatalogChange> = flow { emitAll(source().changes) }

  override suspend fun changeSeq(): Long = source().changeSeq()

  override suspend fun buildIndex(): ColumnarTimelineSnapshot = source().buildIndex()

  override suspend fun indexRows(ids: Collection<MediaId>): List<IndexRow> = source().indexRows(ids)

  override suspend fun groupSizes(): Map<String, Int> = source().groupSizes()

  override suspend fun phoneRefs(ids: Collection<MediaId>): Map<String, MediaRef.Phone> = source().phoneRefs(ids)

  override suspend fun withGroupMates(ids: Collection<MediaId>): Set<MediaId> = source().withGroupMates(ids)
}

/**
 * Preferiti e nascosti: un errore del catalogo si registra (solo il tipo dell'eccezione, mai identita' o percorsi) e
 * **arriva a chi ha chiesto**, che lo dice all'utente (revisione di sicurezza, R8). Prima l'errore era ingoiato per non
 * far cadere l'app (la UI lancia queste azioni nello scope della composizione, dove un'eccezione arrivava al thread
 * principale e la chiudeva, visto sull'emulatore, P11): ma cosi' "Nascondi" che falliva sembrava riuscito, la selezione
 * si chiudeva e il viewer annunciava "Nascosto" mentre le foto tornavano in griglia. Ora ogni punto che le lancia
 * (selezione della Libreria, viewer, Raccolte, Nascosti) cattura l'errore e mostra un avviso: l'app non cade e l'utente
 * sa com'e' andata. Solo l'annullamento passa come tale.
 */
internal class GuardedMediaActions(private val inner: LocalMediaActions) : LocalMediaActions {

  override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = guarded("preferiti") { inner.setFavorite(ids, on) }

  override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) = guarded("nascosti") { inner.setHidden(ids, on) }

  private suspend fun guarded(what: String, block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.e(TAG) { "Modifica dei $what non salvata: ${e.javaClass.simpleName}" }
      throw e
    }
  }

  private companion object {
    const val TAG = "MediaActions"
  }
}

/**
 * I nascosti letti dal catalogo (E-H1). **Il solo punto dell'app** che li legge: l'indice della timeline, la sua
 * istantanea, le raccolte e i conteggi non li contengono mai. Si chiama solo dopo lo sblocco (lo garantisce
 * `HiddenController`, P10).
 */
internal object CatalogHidden {

  /** I nascosti ancora presenti in qualche posto, dal piu' recente, con i riferimenti ai byte del telefono. */
  suspend fun read(database: GalleryDatabase, catalog: MediaCatalog): ViewerItems {
    val rows = database.timelineIndexDao().hiddenRows()
    val refs = if (rows.isEmpty()) emptyMap() else catalog.phoneRefs(rows.map { MediaId(it.mediaId) }).mapKeys { it.key.value }
    // I membri di un gruppo nascosto si vedono uno per uno: qui ogni cella e' un media (niente copertine).
    val snapshot = TimelineIndexReader.fromRows(rows, seq = catalog.changeSeq(), groupSizes = emptyMap(), refs = refs)
    val view: TimelineView = FilteredTimelineView(snapshot, LibraryFilter.ALL)
    return SnapshotItems(MutableStateFlow(view), snapshot)
  }

  /**
   * Quali fra [refs] (elementi del cestino di sistema) sono di media nascosti nell'app. Un `_ID` del cestino si cerca fra
   * le posizioni in cache del catalogo (anche assenti: un elemento cestinato e' sparito dal MediaStore visibile). Nel
   * dubbio (un `_ID` che punta a piu' posizioni) basta che una sia di un nascosto: il segno "Nascosto" in piu' non espone
   * niente, uno in meno si'.
   */
  suspend fun hiddenAmong(database: GalleryDatabase, catalog: MediaCatalog, refs: List<MediaRef.Phone>): Set<MediaRef.Phone> {
    val locations = database.locationDao()
    val personal = database.personalDao()
    val hidden = HashSet<MediaRef.Phone>()
    for (ref in refs) {
      val candidates = locations.byMediaStoreId(ref.volume, ref.mediaStoreId)
      for (location in candidates) {
        val mediaId = locations.mediaIdOf(location.locationId)?.let(::MediaId) ?: continue
        val resolved = catalog.resolve(mediaId) ?: continue
        if (personal.get(resolved.value)?.hidden == true) {
          hidden += ref
          break
        }
      }
    }
    return hidden
  }

  private class SnapshotItems(
    override val view: StateFlow<TimelineView>,
    private val snapshot: ColumnarTimelineSnapshot,
  ) : ViewerItems {
    override fun ref(id: MediaId): MediaRef = refOrNull(id) ?: throw NoSuchElementException("Nessuna copia leggibile adesso.")

    override fun refOrNull(id: MediaId): MediaRef? = snapshot.indexOf(id).takeIf { it >= 0 }?.let { snapshot.refAt(it) }
  }
}
