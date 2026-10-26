package dev.pampa.mycloudgallery.debugtools.eval.albums

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidates
import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.CandidatePage
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.noop.NoAlbums
import dev.pampa.mycloudgallery.core.albums.smart.CatalogSmartCandidates
import dev.pampa.mycloudgallery.core.albums.smart.DeviceNormalizer
import dev.pampa.mycloudgallery.core.albums.smart.SmartCandidate
import dev.pampa.mycloudgallery.core.albums.smart.SmartContentSearch
import dev.pampa.mycloudgallery.core.albums.trips.CatalogTrips
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.personal.noop.NoPersonalStore
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.debugtools.eval.EvalThumbnails
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.search.GeocoderPlaceLookup
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SOLO debug (Fase 7, P12): la porta dello strumento di valutazione degli album sopra la Libreria vera ([GalleryGraph]): gli stessi candidati del catalogo
 * (`CatalogSmartCandidates`), la stessa visibilita' dei nascosti (`VisibleMedia`, `HiddenMask`), gli stessi luoghi e la stessa ricerca che userebbero gli album
 * veri. **Sola lettura**: l'album della corsa sta in memoria ([EvalSmartRows]) e la rilevazione dei viaggi scrive in un **database di lavoro in memoria**, mai in
 * quello della Libreria. Le miniature sono quelle locali della Libreria ([EvalThumbnails], come per la ricerca).
 *
 * [ownBackupFolder]: il nome della cartella `Backup/<dispositivo>/` di questo dispositivo (per la regola B: le foto del tuo backup sono "dello stesso dispositivo"
 * del telefono). Oggi l'app non la espone a questo punto: finche' l'integrazione degli album (P13) non la passa, vale `null` e una foto che sta solo nel tuo
 * backup conta come di un altro dispositivo (suggerita invece che sicura).
 */
internal class AppAlbumsEvalEnvironment(
  private val catalog: suspend () -> MediaCatalog,
  /** La Libreria: SOLO per leggere i nomi dei file (la prova generale). Mai una scrittura. */
  private val database: GalleryDatabase,
  private val visible: VisibleMedia,
  private val mask: HiddenMask,
  private val geocoder: ReverseGeocoder,
  private val names: PlaceNames,
  private val thumbnail: suspend (MediaId) -> ImageBitmap?,
  private val context: Context,
  private val search: SmartContentSearch,
  private val todayPort: Today,
  private val ownBackupFolder: () -> String? = { null },
) : AlbumsEvalEnvironment {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val lock = Mutex()
  private val clocks = object : AlbumClocks {
    override fun monotonicMs(): Long = SystemClock.elapsedRealtime()

    override fun nasNowMs(): Long? = null
  }
  private var smart: CatalogSmartCandidates? = null
  private var ports: AlbumsEvalPorts? = null
  private var trips: CatalogTrips? = null

  override val today: LocalDate get() = todayPort.date()

  override suspend fun ports(): AlbumsEvalPorts = lock.withLock {
    ports ?: AlbumsEvalPorts(
      candidates = candidates(),
      visible = visible,
      mask = mask,
      parser = ItalianQueryParser(),
      lookup = GeocoderPlaceLookup(geocoder),
      search = search,
      devices = DeviceNormalizer(ownBackupFolder()),
      clocks = clocks,
      geocoder = geocoder,
      today = { today },
    ).also { ports = it }
  }

  /** I candidati del catalogo (il catalogo aspetta la famiglia pronta: si costruisce alla prima richiesta). */
  private suspend fun candidates(): CatalogSmartCandidates = smart ?: CatalogSmartCandidates(catalog()).also { smart = it }

  override suspend fun cellsOfDays(from: LocalDate, to: LocalDate): List<AlbumsEvalCell> {
    val source = candidates()
    val found = ArrayList<SmartCandidate>()
    var after: CandidateCursor? = null
    do {
      val page = source.page(CandidateFilter(days = LocalDayRange(from, to)), after, PAGE)
      found += page.items
      after = page.next
    } while (after != null)
    return visibleCells(found)
  }

  override suspend fun cellsOf(ids: Collection<String>): List<AlbumsEvalCell> {
    if (ids.isEmpty()) return emptyList()
    return visibleCells(candidates().candidatesOf(ids.map { MediaId(it) }))
  }

  /** Le celle dei candidati che si possono mostrare adesso: `VisibleMedia` e il filtro finale `HiddenMask` (D22: mai un nascosto, nemmeno in uno strumento di debug). */
  private suspend fun visibleCells(items: List<SmartCandidate>): List<AlbumsEvalCell> {
    if (items.isEmpty()) return emptyList()
    val ids = items.map { it.media }
    val shown = visible.visible(ids)
    val hidden = mask.hiddenAmong(shown)
    return items.filter { it.media in shown && it.media !in hidden }.map { AlbumsEvalCell(it.media.value, it.c.day) }
  }

  override suspend fun thumbnail(id: MediaId): ImageBitmap? = thumbnail.invoke(id)

  override suspend fun tripProposals(): TripsView {
    val detector = tripsInstance()
    val report = detector.detect(DETECT_BUDGET)
    check(!report.incomplete) { "La rilevazione dei viaggi non e' arrivata in fondo nel tempo concesso: riprova." }
    val shown = detector.proposals.value
    return TripsView(shown.proposals.map { ProposedDays(it.days.from, it.days.to, it.kind == TripKind.TRIP) }, shown.homeKnown)
  }

  /** `CatalogTrips` vero sopra la Libreria vera, ma con un database di lavoro in memoria (la casa e le proposte non finiscono nella Libreria) e senza dati personali. */
  private suspend fun tripsInstance(): CatalogTrips = lock.withLock {
    trips ?: CatalogTrips(
      scratchDatabase(),
      CatalogAlbumCandidates(candidates()),
      visible,
      mask,
      NoPersonalStore,
      NoAlbums,
      names,
      todayPort,
      clocks,
      scope,
    ).also { trips = it }
  }

  /**
   * Il database di lavoro: vuoto, in memoria, solo per la casa e le proposte. Non ha i media della Libreria, mentre le proposte nominano la copertina: i vincoli di
   * chiave esterna dello schema non possono valere qui (si spengono su questa connessione, l'unica di un database in memoria). Non tocca mai la Libreria.
   */
  private fun scratchDatabase(): GalleryDatabase {
    val scratch = GalleryDatabase.build(context, null)
    scratch.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
    return scratch
  }

  override suspend fun mediaByFileName(names: Set<String>): Map<String, MediaId> {
    if (names.isEmpty()) return emptyMap()
    val db = database.openHelper.readableDatabase
    val out = HashMap<String, MediaId>()
    for (chunk in names.chunked(CHUNK)) {
      val marks = chunk.joinToString(",") { "?" }
      db.query(
        "SELECT v.media_id, l.display_name FROM media_location l JOIN media_version v ON v.version_id = l.version_id WHERE l.display_name IN ($marks)",
        arrayOf<Any?>(*chunk.toTypedArray()),
      ).use { cursor ->
        while (cursor.moveToNext()) out[cursor.getString(1)] = MediaId(cursor.getString(0))
      }
    }
    return out
  }

  companion object {
    private const val PAGE = 2_000
    private const val CHUNK = 300
    private val DETECT_BUDGET = 5.minutes

    /** La porta sopra la Libreria vera: il catalogo, la visibilita' dei nascosti, i luoghi e le miniature locali di [gallery]. */
    fun over(
      gallery: GalleryGraph,
      context: Context,
      search: SmartContentSearch,
      todayPort: Today,
      ownBackupFolder: () -> String? = { null },
    ): AppAlbumsEvalEnvironment {
      val thumbnails = EvalThumbnails(
        loader = gallery.thumbnails,
        snapshot = { gallery.repository.snapshot.value },
        refAt = { snapshot, index -> (snapshot as? ColumnarTimelineSnapshot)?.thumbRefAt(index) },
      )
      return AppAlbumsEvalEnvironment(
        catalog = { gallery.core().catalog },
        database = gallery.database,
        visible = gallery.visibleMedia,
        mask = gallery.hiddenMask,
        geocoder = gallery.places,
        names = gallery.places,
        thumbnail = { thumbnails.of(it) },
        context = context,
        search = search,
        todayPort = todayPort,
        ownBackupFolder = ownBackupFolder,
      )
    }
  }
}

/** I candidati del catalogo nella forma di `AlbumCandidates` per `CatalogTrips`: la traduzione senza logica che P13 lega per gli album veri. */
private class CatalogAlbumCandidates(private val source: CatalogSmartCandidates) : AlbumCandidates {
  override suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): CandidatePage {
    val page = source.page(filter, after, limit)
    return CandidatePage(page.items.map<SmartCandidate, AlbumCandidate> { it.c }, page.next)
  }

  override suspend fun visibleTotal(): Int = source.visibleTotal()
}
