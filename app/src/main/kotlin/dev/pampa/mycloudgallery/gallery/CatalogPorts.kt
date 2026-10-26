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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

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

  override suspend fun nasRefs(ids: Collection<MediaId>): Map<String, MediaRef.Nas> = source().nasRefs(ids)

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

  // Fase 7 (P9, E-H2): l'avviso "i familiari continuano a vederlo" attraversa il guardiano; senza questa riga tornerebbe sempre 0.
  override suspend fun familyStillSees(ids: Set<MediaId>): Int = inner.familyStillSees(ids)

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

  /**
   * I nascosti ancora presenti in qualche posto, dal piu' recente, con i riferimenti ai byte del telefono.
   *
   * **La vista e' viva** (GA-1): finche' qualcuno la guarda (la griglia dei Nascosti, il viewer aperto da li') si rilegge a ogni
   * cambiamento del catalogo, cosi' "Mostra nella Libreria", "Preferito" ed "Elimina" fatti dal viewer si vedono subito: l'elemento
   * che torna in Libreria esce dalla vista e il viewer passa al vicino con il suo avviso. Quando nessuno la guarda piu' (la pagina e'
   * uscita e [HiddenController] ha dimenticato l'oggetto) la rilettura si ferma da sola: niente resta in ascolto, e niente dei nascosti
   * viene letto fuori da una schermata sbloccata.
   *
   * @param scope dove vive la rilettura: la sua durata la decide chi guarda (`WhileSubscribed`), non lo scope.
   */
  suspend fun read(database: GalleryDatabase, catalog: MediaCatalog, scope: CoroutineScope): ViewerItems {
    val first = load(database, catalog)
    return LiveHiddenItems(first, scope, catalog.changes.map { }) { load(database, catalog) }
  }

  /** Una lettura dei nascosti: l'istantanea (per arrivare ai byte) e la vista che la mostra. */
  private class Loaded(val snapshot: ColumnarTimelineSnapshot, val view: TimelineView)

  private suspend fun load(database: GalleryDatabase, catalog: MediaCatalog): Loaded {
    val rows = database.timelineIndexDao().hiddenRows()
    val refs = if (rows.isEmpty()) emptyMap() else catalog.phoneRefs(rows.map { MediaId(it.mediaId) }).mapKeys { it.key.value }
    // I membri di un gruppo nascosto si vedono uno per uno: qui ogni cella e' un media (niente copertine).
    val snapshot = TimelineIndexReader.fromRows(rows, seq = catalog.changeSeq(), groupSizes = emptyMap(), refs = refs)
    return Loaded(snapshot, FilteredTimelineView(snapshot, LibraryFilter.ALL))
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

  /**
   * Gli elementi nascosti con la vista che segue il catalogo. [view] rilegge all'inizio di ogni ascolto e a ogni cambiamento
   * ([changes]); una lettura che fallisce (il catalogo si sta chiudendo) lascia la vista com'e'. L'istantanea dei riferimenti si
   * aggiorna **prima** di pubblicare la vista nuova, cosi' ogni elemento che la vista mostra ha il suo riferimento.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private class LiveHiddenItems(
    first: Loaded,
    scope: CoroutineScope,
    changes: Flow<Unit>,
    reload: suspend () -> Loaded,
  ) : ViewerItems {

    @Volatile
    private var snapshot: ColumnarTimelineSnapshot = first.snapshot

    override val view: StateFlow<TimelineView> = changes
      .onStart { emit(Unit) }
      .mapLatest {
        try {
          reload()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          GalleryLog.w(TAG) { "nascosti non riletti: ${e.javaClass.simpleName}" }
          null
        }
      }
      .filterNotNull()
      .onEach { snapshot = it.snapshot }
      .map { it.view }
      .stateIn(scope, SharingStarted.WhileSubscribed(), first.view)

    override fun ref(id: MediaId): MediaRef = refOrNull(id) ?: throw NoSuchElementException("Nessuna copia leggibile adesso.")

    override fun refOrNull(id: MediaId): MediaRef? = snapshot.let { current -> current.indexOf(id).takeIf { it >= 0 }?.let { current.refAt(it) } }
  }

  private const val TAG = "CatalogHidden"
}
