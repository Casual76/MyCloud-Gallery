package dev.pampa.mycloudgallery.hidden

import android.net.Uri
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.DragFlags
import dev.pampa.mycloudgallery.core.library.contract.DragOrigin
import dev.pampa.mycloudgallery.core.library.contract.DragPrepared
import dev.pampa.mycloudgallery.core.library.contract.DragRefusal
import dev.pampa.mycloudgallery.core.library.contract.DragRequest
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.index.FilteredTimelineView
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.drag.DocumentDragPayloads
import dev.pampa.mycloudgallery.feature.integrations.drag.DragLeases
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIdTable
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentLabels
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentResolution
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentTarget
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.provider.LibraryMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.provider.MediaFileInfo
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import dev.pampa.mycloudgallery.feature.integrations.provider.NoIntegrationLibrary
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 10 (P6, D20, D41, C436, C448, E-H11, E-T18): la SONDA del trascinamento verso un'altra app sul codice VERO (`DocumentDragPayloads`), sul mondo casuale con nascosti, gruppi di copie simili con un nascosto
// dentro, nascosti dell'ultimo momento e raffiche. La porta della Libreria che la sonda da' al gestore e' **una porta che sbaglia apposta**: conosce ogni media del mondo, nascosti compresi (riferimento, nome,
// dimensione), e dice "raggiungibile" per il NAS. Cio' che la ferma e' la **vista della Libreria** (la sola che dice "questo si vede"), e la sonda prova che:
//  - un media trascinato da solo esce **se e solo se** e' una cella della Libreria (mai un nascosto, mai il membro non copertina di un gruppo, mai un membro di un gruppo nascosto);
//  - una richiesta con **anche un solo** non-cella e' rifiutata per intero (`HIDDEN`) e non nasce nessun id;
//  - una richiesta di sole celle esce con un URI per elemento, nell'ordine chiesto, tutti del provider (mai un `file://`, mai il MediaStore) e ognuno porta proprio a quel media;
//  - i permessi sono sempre e solo `DragFlags.ALLOWED`;
//  - chiuso il prestito non resta nessun id;
//  - **i nascosti dell'ultimo momento** (I4 della revisione di sicurezza, riga `live`): la vista della Libreria della sonda e' l'istantanea, che li ha ancora; il filtro finale (`CatalogHiddenMask` sul
//    catalogo, che li conosce anche con i loro gruppi) li ferma: il trascinamento li rifiuta per intero, e il provider non descrive ne' apre un id consegnato di un media nascosto dopo la sua nascita.
// Dai Nascosti non si trascina: nessuna origine li nomina (`DragOrigin`), e le loro celle non hanno il modificatore (lo prova `HiddenGridsHaveNoDragTest`).

/** La Libreria per le altre app costruita sul mondo: la vista e' quella del mondo, tutto il resto sa di tutti i media (anche dei nascosti) e non dice mai di no. */
private class WorldDragLibrary(private val world: BuiltWorld) : IntegrationLibrary by NoIntegrationLibrary {

  private val view = MutableStateFlow<TimelineView>(FilteredTimelineView(world.snapshot, LibraryFilter.ALL))

  override val items: ViewerItems = object : ViewerItems {
    override val view: StateFlow<TimelineView> = this@WorldDragLibrary.view

    // Un riferimento per **ogni** media, nascosti compresi: la porta "sbaglia", la vista decide.
    override fun ref(id: MediaId): MediaRef = refOrNull(id) ?: throw NoSuchElementException("Nessun riferimento.")

    override fun refOrNull(id: MediaId): MediaRef? = world.ids.indexOf(id).takeIf { it >= 0 }?.let { MediaRef.Phone("external_primary", 1_000L + it, "image/jpeg") }
  }

  override val thumbnails: ThumbnailLoader get() = NoIntegrationLibrary.thumbnails

  override val placeholders: PlaceholderCache get() = NoIntegrationLibrary.placeholders

  override fun phoneUri(ref: MediaRef.Phone): Uri = Uri.parse("content://media/${ref.volume}/${ref.mediaStoreId}")

  override suspend fun fileInfos(ids: List<MediaId>): Map<MediaId, MediaFileInfo> = ids.associateWith { MediaFileInfo("IMG_${it.value.takeLast(6)}.jpg", "image/jpeg", 40_000L, null) }

  override suspend fun nasReach(): NasReach = NasReach.Reachable
}

internal object RealDragProbes {

  val drag = SurfaceProbe { w ->
    val library = WorldDragLibrary(w)
    val table = DocumentIdTable()
    val mask = CatalogHiddenMask(w.db)
    val documents = LibraryMediaDocuments(library, DocumentLabels("MyCloud Gallery", "Recenti", "Cartelle NAS"), table, hiddenMask = mask)
    val scope = CoroutineScope(Dispatchers.Unconfined)
    try {
      val leases = DragLeases(scope)
      val handler = DocumentDragPayloads(library, documents, AUTHORITY, { EntryDecision.ALLOWED }, leases, hiddenMask = mask)
      // Le celle dell'istantanea meno gli ultimi nascosti e i loro gruppi: il filtro finale li toglie (la vista della sonda e' l'istantanea).
      val cells = w.liveLibraryCells
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()

      fun mediaOf(uri: Uri): MediaId? {
        if (uri.scheme != "content" || uri.authority != AUTHORITY) problems += "un URI trascinato non e' del provider dei documenti"
        val resolved = table.resolve(DocumentId(uri.lastPathSegment ?: return null)) as? DocumentResolution.Found ?: return null
        return (resolved.target as? DocumentTarget.Media)?.id
      }

      // 1. Ogni media, da solo: esce se e solo se e' una cella della Libreria, e porta a se stesso.
      for (id in w.ids) {
        val origin = DragOrigin.entries[w.indexOf(id) % DragOrigin.entries.size]
        when (val result = handler.prepare(DragRequest(origin, listOf(id), removeLocation = false))) {
          is DragPrepared.Ready -> {
            if (result.flags != DragFlags.ALLOWED) problems += "un trascinamento ha permessi diversi da sola lettura sul singolo URI"
            val uri = result.clip.getItemAt(0).uri
            val media = mediaOf(uri)
            if (media != id) problems += "l'URI di un trascinamento non porta al media chiesto"
            shown += id
            result.lease.release()
          }
          is DragPrepared.Refused -> if (result.reason != DragRefusal.HIDDEN) problems += "un media fuori dalla vista e' stato rifiutato per un altro motivo"
        }
        if (table.size != 0) problems += "dopo un trascinamento rilasciato restano id"
      }

      // 2. Raffiche: una richiesta con anche un solo non-cella e' rifiutata per intero e non lascia id; una di sole celle esce intera e nell'ordine.
      val random = Random(w.ids.size.toLong() * 31 + 7)
      val visible = cells.toList()
      val others = w.ids.filter { it !in cells }
      repeat(60) {
        val pure = visible.shuffled(random).take(1 + random.nextInt(minOf(8, visible.size.coerceAtLeast(1)))).takeIf { visible.isNotEmpty() }
        if (pure != null) {
          val ready = handler.prepare(DragRequest(DragOrigin.LIBRARY, pure, removeLocation = false)) as? DragPrepared.Ready
          if (ready == null) {
            problems += "una richiesta di sole celle della Libreria non e' uscita"
          } else {
            val back = (0 until ready.clip.itemCount).map { mediaOf(ready.clip.getItemAt(it).uri) }
            if (back != pure) problems += "gli URI di una raffica non portano ai media chiesti nell'ordine chiesto"
            shown += pure
            ready.lease.release()
          }
          if (others.isNotEmpty()) {
            val mixed = (pure.take(3) + others.shuffled(random).take(1 + random.nextInt(3))).shuffled(random)
            val refused = handler.prepare(DragRequest(DragOrigin.SEARCH, mixed, removeLocation = false))
            if (refused !is DragPrepared.Refused) problems += "una richiesta con un media fuori dalla vista e' uscita"
          }
        }
        if (table.size != 0) problems += "dopo una raffica restano id"
      }

      // 3. Il provider: un id consegnato (nato dalla vista, che ha ancora l'ultimo nascosto) non descrive ne' apre un media che il catalogo dice nascosto adesso.
      val providerTable = DocumentIdTable()
      val provider = LibraryMediaDocuments(library, DocumentLabels("MyCloud Gallery", "Recenti", "Cartelle NAS"), providerTable, hiddenMask = mask)
      for (id in w.libraryCells) {
        val token = provider.issue(id) ?: continue
        val described = provider.entry(token) != null
        if (described != (id in cells)) problems += if (described) "il provider descrive un media nascosto dell'ultimo momento" else "il provider non descrive un media che si vede"
      }
      Probed(shown = shown, expected = cells, problems = problems.distinct())
    } finally {
      scope.cancel()
    }
  }

  private const val AUTHORITY = "dev.pampa.mycloudgallery.test.integrations.documents"
}
