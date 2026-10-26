package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.index.FilteredTimelineView
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.duplicates.CatalogIdenticalDuplicates
import dev.pampa.mycloudgallery.core.library.duplicates.CatalogSimilarShots
import dev.pampa.mycloudgallery.core.library.duplicates.CleanupPlan
import dev.pampa.mycloudgallery.core.library.duplicates.RemovalPlaces
import dev.pampa.mycloudgallery.core.library.duplicates.ShotVectorSource
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.library.visibility.RotatingWidgetFeed
import dev.pampa.mycloudgallery.core.library.contract.WidgetFeed
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchEngine
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerAction
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerItems
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerRequest
import dev.pampa.mycloudgallery.nas.CatalogHidden
import dev.pampa.mycloudgallery.search.CatalogSearchData
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration.Companion.hours

// Fase 7 (P9, D22): le SONDE delle superfici che hanno gia' il codice vero. Ognuna fa girare la superficie sul mondo casuale (catalogo vero,
// istantanea vera) e dice cosa mostra: il test di proprieta' controlla che non mostri mai cio' che l'oracolo vieta e che mostri cio' che deve.

internal object HiddenProbes {

  private fun ids(view: TimelineView): Set<MediaId> {
    val snapshot = view.snapshot
    return (0 until view.size).mapTo(LinkedHashSet()) { snapshot.idAt(view.snapshotIndex(it)) }
  }

  private fun cellsWhere(w: BuiltWorld, predicate: (MediaSpec) -> Boolean): Set<MediaId> =
    w.oracle.libraryCells.filter { predicate(w.spec.media[it]) }.mapTo(LinkedHashSet()) { w.ids[it] }

  /** La Libreria: l'istantanea intera, ogni filtro, e i numeri sulle celle (quanti membri ha un gruppo). */
  val library = SurfaceProbe { w ->
    val snapshot = w.snapshot
    val all = (0 until snapshot.size).mapTo(LinkedHashSet()) { snapshot.idAt(it) }
    val problems = ArrayList<String>()
    if (snapshot.size != w.oracle.libraryCells.size) problems += "la Libreria ha ${snapshot.size} celle, ne attendeva ${w.oracle.libraryCells.size}"
    // Il numero sulla copertina di un gruppo conta i membri visibili: di un gruppo visibile sono tutti, di uno nascosto non c'e' nemmeno la cella.
    val groupSize = w.spec.groups.flatMap { g -> g.members.map { it to g.members.size } }.toMap()
    for (i in 0 until snapshot.size) {
      val index = w.indexOf(snapshot.idAt(i))
      val expected = groupSize[index] ?: 1
      if (snapshot.groupSizeAt(i) != expected) problems += "una cella dichiara ${snapshot.groupSizeAt(i)} membri, ne attendeva $expected"
    }
    val viaFilters = LibraryFilter.entries.flatMapTo(HashSet()) { filter -> ids(FilteredTimelineView(snapshot, filter)) }
    Probed(shown = all + viaFilters, expected = w.libraryCells, problems = problems)
  }

  /** Cio' che ogni raccolta-filtro deve contenere, dal solo spec. */
  private fun expectedFor(w: BuiltWorld, filter: LibraryFilter): Set<MediaId> = when (filter) {
    LibraryFilter.ALL -> cellsWhere(w) { true }
    LibraryFilter.WITHOUT_SCREENSHOTS_AND_WHATSAPP -> cellsWhere(w) { it.source != MediaSource.SCREENSHOT && it.source != MediaSource.WHATSAPP }
    LibraryFilter.SCREENSHOTS -> cellsWhere(w) { it.source == MediaSource.SCREENSHOT }
    LibraryFilter.WHATSAPP -> cellsWhere(w) { it.source == MediaSource.WHATSAPP }
    LibraryFilter.VIDEOS -> cellsWhere(w) { it.kind == MediaKind.VIDEO }
    LibraryFilter.FAVORITES -> cellsWhere(w) { it.index in w.spec.favorites }
    LibraryFilter.DOCUMENTS, LibraryFilter.EDITED -> emptySet()
  }

  private fun filterProbe(filters: List<LibraryFilter>) = SurfaceProbe { w ->
    val shown = LinkedHashSet<MediaId>()
    val problems = ArrayList<String>()
    for (filter in filters) {
      val view = FilteredTimelineView(w.snapshot, filter)
      val found = ids(view)
      shown += found
      // Il conteggio che la Raccolta scrive e' la dimensione della vista: deve essere quello giusto, senza nascosti.
      if (view.size != expectedFor(w, filter).size) problems += "il filtro ${filter.code} conta ${view.size}, ne attendeva ${expectedFor(w, filter).size}"
      if (found != expectedFor(w, filter)) problems += "il filtro ${filter.code} mostra altri media del previsto"
    }
    Probed(shown = shown, within = w.libraryCells, problems = problems)
  }

  /** Le Raccolte: le raccolte-filtro e i loro conteggi. */
  val collections = filterProbe(
    listOf(LibraryFilter.ALL, LibraryFilter.FAVORITES, LibraryFilter.VIDEOS, LibraryFilter.SCREENSHOTS, LibraryFilter.WHATSAPP, LibraryFilter.WITHOUT_SCREENSHOTS_AND_WHATSAPP),
  )

  /** Le pagine-filtro dell'Utility (D24): le stesse viste della Libreria, con "Modificate". */
  val utilityPages = filterProbe(listOf(LibraryFilter.SCREENSHOTS, LibraryFilter.VIDEOS, LibraryFilter.EDITED, LibraryFilter.WHATSAPP))

  /** La ricerca: filtri e testo sul motore di riferimento con il filtro finale vero del catalogo (`CatalogSearchData`), poi "Trova simili". */
  val search = SurfaceProbe { w ->
    val data = CatalogSearchData(MutableStateFlow(w.snapshot), w.db) { w.catalog }
    val engine = InMemorySearchEngine(data, null, ItalianQueryParser(), FixedToday(LocalDate.of(2026, 10, 21)))
    val shown = LinkedHashSet<MediaId>()
    fun collect(results: SearchResults) {
      shown += ids(results.confident)
      shown += ids(results.likely)
    }
    for (text in listOf("video", "foto", "screenshot", "whatsapp", "preferiti", "marzo 2026", "2026", "marzo")) {
      for (order in ResultOrder.entries) collect(engine.search(SearchRequest(text, showLikely = true, order = order)))
    }
    // "Trova simili" da ogni media, anche da uno nascosto: non deve mostrare nessun nascosto.
    for (id in w.ids) collect(engine.similar(id, ResultOrder.RECENT))
    Probed(shown = shown, within = w.libraryCells)
  }

  /** Il selettore per le altre app: gli elementi offerti per ogni forma di richiesta. */
  val picker = SurfaceProbe { w ->
    fun request(images: Boolean, videos: Boolean, localOnly: Boolean) =
      PickerRequest(PickerAction.GET_CONTENT, images, videos, allowMultiple = true, localOnly = localOnly, mimeFilters = listOf("*/*"))
    fun offered(request: PickerRequest): Set<MediaId> {
      val items = PickerItems.of(w.snapshot, request)
      return (0 until items.size).mapTo(LinkedHashSet()) { items.idAt(it) }
    }
    val everything = offered(request(images = true, videos = true, localOnly = false))
    val shown = LinkedHashSet(everything)
    shown += offered(request(images = true, videos = false, localOnly = false))
    shown += offered(request(images = false, videos = true, localOnly = false))
    shown += offered(request(images = true, videos = true, localOnly = true))
    Probed(shown = shown, expected = w.libraryCells)
  }

  /** Il widget: tante chiamate di fila, abbastanza da fare piu' di un giro della Libreria. */
  val widget = SurfaceProbe { w ->
    val feed: WidgetFeed = RotatingWidgetFeed(CatalogVisibleMedia(w.db, MutableStateFlow(w.snapshot)), CatalogHiddenMask(w.db))
    val shown = LinkedHashSet<MediaId>()
    repeat(w.snapshot.size / WidgetFeed.MAX_SIZE + 3) { shown += feed.next(WidgetFeed.MAX_SIZE) }
    repeat(3) { shown += feed.next(4) }
    // Le celle dell'istantanea, meno gli ultimi nascosti e i loro gruppi (il filtro finale li toglie).
    Probed(shown = shown, expected = w.liveLibraryCells)
  }

  /** `VisibleMedia`: dato l'elenco di tutti i media, quali si possono mostrare. */
  val visibleMedia = SurfaceProbe { w ->
    val visibility = CatalogVisibleMedia(w.db, MutableStateFlow(w.snapshot))
    Probed(shown = visibility.visible(w.ids), expected = w.liveShowable)
  }

  /** `HiddenMask`: dato l'elenco di tutti i media, quali il filtro finale lascia passare. */
  val hiddenMask = SurfaceProbe { w ->
    val hidden = CatalogHiddenMask(w.db).hiddenAmong(w.ids)
    val problems = ArrayList<String>()
    if (!hidden.containsAll(w.liveMustNeverShow)) problems += "il filtro finale non nasconde ${(w.liveMustNeverShow - hidden).size} media che devono restare nascosti"
    Probed(shown = w.allIds - hidden, expected = w.liveShowable, problems = problems)
  }

  /** Cartelle NAS: i media il cui file e' elencato per nome (quelli che la riga dei nascosti non copre). */
  val nasFolders = SurfaceProbe { w ->
    val me = AccountId.of(HiddenWorldBuilder.ME)
    val keyOf = w.nas.mapValues { (_, place) -> Location.Nas(me, place.share, NasPath.of(place.path)).key.value }
    val hiddenKeys = CatalogHidden.hiddenLocationKeys(w.db, keyOf.values)
    val listed = keyOf.filter { it.value !in hiddenKeys }.keys.mapTo(LinkedHashSet()) { w.ids[it] }
    Probed(shown = listed, expected = w.idsOf(w.nas.keys.filter { it in w.oracle.liveShowable }))
  }

  // --- Doppioni (Fase 7, P8, D25): il codice vero sul catalogo vero ---------------------------------------------------------------------

  /** Il media di un file di prova: l'indice dello spec ricavato dal nome o dall'`_ID` (sul telefono) con cui la costruzione del mondo li ha scritti. */
  private val NAME_INDEX = Regex("""IMG_\d+_(\d+)""")

  private fun indexOfPhone(ref: MediaRef.Phone): Int =
    (ref.mediaStoreId - if (ref.mediaStoreId >= HiddenWorldBuilder.PHONE_COPY_BASE) HiddenWorldBuilder.PHONE_COPY_BASE else HiddenWorldBuilder.PHONE_ORIGINAL_BASE).toInt()

  private fun indexOfNas(path: String): Int = NAME_INDEX.find(path)!!.groupValues[1].toInt()

  /** I media a cui un piano di pulizia (telefono e NAS) si riferisce, ricavati dai riferimenti: nessun id, solo i file che ha scritto il mondo. */
  private fun mediaOf(w: BuiltWorld, plan: CleanupPlan): Set<MediaId> =
    (plan.phone.map { indexOfPhone(it.ref) } + plan.nas.map { indexOfNas(it.path) }).mapTo(LinkedHashSet()) { w.ids[it] }

  private fun mediaOf(w: BuiltWorld, places: RemovalPlaces): Set<MediaId> =
    (places.phone.map { indexOfPhone(it.ref) } + (places.nasPrivate + places.nasCommon).map { indexOfNas(it.path) }).mapTo(LinkedHashSet()) { w.ids[it] }

  /** Una `VisibleMedia` che non toglie niente: simula una pagina che ha letto i gruppi prima che un media venisse nascosto. */
  internal fun everythingVisible(w: BuiltWorld): VisibleMedia = object : VisibleMedia {
    override val snapshot: StateFlow<TimelineSnapshot?> = MutableStateFlow(w.snapshot)

    override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = ids.toSet()
  }

  /** Lo stesso vettore per ogni foto: tutte quelle dello stesso giorno a meno di dieci minuti si somigliano, e il filtro dei nascosti decide cosa esce. */
  internal val sameVector = ShotVectorSource { media -> media.associateWith { floatArrayOf(1f, 0f, 0f, 0f) } }

  /**
   * I Doppioni: i gruppi identici (con la loro proposta), il piano di pulizia, i gruppi di scatti simili e i luoghi di un'eliminazione. Un nascosto non
   * compare in niente di tutto questo: ne' nei gruppi, ne' nei piani (i file da mandare nei cestini), nemmeno se i gruppi sono stati letti **prima**
   * che venisse nascosto (il piano filtra di nuovo, D22). Una seconda copia dalla stessa parte e' un doppione identico (`WorldSpec.sameSideCopies`).
   */
  val duplicates = SurfaceProbe { w ->
    val visible = CatalogVisibleMedia(w.db, MutableStateFlow(w.snapshot))
    val shown = LinkedHashSet<MediaId>()
    val problems = ArrayList<String>()

    val identical = CatalogIdenticalDuplicates(w.catalog, visible)
    val scan = identical.scan()
    if (scan.failed) problems += "la lettura dei doppioni identici e' fallita"
    scan.proposals.forEach { shown += it.group.media }
    shown += mediaOf(w, identical.plan(scan.cleanable))

    // Una pagina che aveva letto i gruppi senza sapere dei nascosti: il piano li lascia fuori lo stesso.
    val stale = CatalogIdenticalDuplicates(w.catalog, everythingVisible(w)).scan()
    val staleMedia = mediaOf(w, identical.plan(stale.proposals))
    shown += staleMedia
    if (w.liveMustNeverShow.isNotEmpty() && staleMedia.any { it in w.liveMustNeverShow }) problems += "il piano porta nei cestini un media nascosto"

    val similar = CatalogSimilarShots(w.catalog, visible, CatalogHiddenMask(w.db), vectors = sameVector)
    var before: Int? = null
    do {
      val batch = similar.scan(before, 1.hours)
      if (batch.failed) problems += "la ricerca degli scatti simili e' fallita"
      batch.clusters.forEach { cluster -> shown += cluster.media }
      before = batch.nextBeforeDay
    } while (before != null)
    // I luoghi di un'eliminazione (il foglio): per qualunque media, anche nascosto, nessun file di un nascosto.
    shown += mediaOf(w, similar.placesOf(w.ids))

    // Tutto cio' che esce sta fra i media che si possono mostrare (dopo gli ultimi nascosti): la pagina chiede ancora al catalogo (`live`).
    Probed(shown = shown, within = w.liveShowable, problems = problems)
  }
}
