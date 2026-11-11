package dev.pampa.mycloudgallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumRefs
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbum
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabOrder
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabScreen
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabState
import dev.pampa.mycloudgallery.feature.albums.tab.NoAlbumsTabHandlers
import dev.pampa.mycloudgallery.feature.albums.tab.SpecialSummary
import dev.pampa.mycloudgallery.feature.albums.testing.AlbumSamples
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsLayoutViewModel
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsSectionProvider
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsTab
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.library.testing.M1SyntheticSizes
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import androidx.compose.ui.graphics.asImageBitmap
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.feature.search.FixedSearchSource
import dev.pampa.mycloudgallery.feature.search.SuggestionArt
import dev.pampa.mycloudgallery.feature.search.SuggestionItem
import dev.pampa.mycloudgallery.feature.search.NoSearchHandlers
import dev.pampa.mycloudgallery.feature.search.SearchHandlers
import dev.pampa.mycloudgallery.feature.search.SearchSource
import dev.pampa.mycloudgallery.feature.search.contract.SearchRefs
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.noop.NoSearchActions
import dev.pampa.mycloudgallery.main.MainScaffold
import dev.pampa.mycloudgallery.main.ScaffoldSearch
import dev.pampa.mycloudgallery.status.GlobalStatus

import java.util.Optional

// LO SCAFFOLD DELLE PROVE (K3a, GF-4 e ORC-1). Uno solo, per gli screenshot e le prove di :app che mostrano la schermata principale: prima ogni prova
// ricostruiva lo scaffold a mano, e cosi' la pillola della scheda Album mostrava l'elenco degli album di prima, Cerca e Raccolte erano segnaposto
// ("arriveranno qui") e le Raccolte non avevano la capsula della rete ne' il fondo della pagina.

/**
 * La Libreria sintetica **con il NAS configurato e il backup al sicuro** (GF-23 c): e' lo stato di chi ha il NAS di casa, e in produzione la nuvola di stato sta
 * nella capsula in alto a destra. Prima lo stato di prova non aveva un NAS (`LibraryNasStatus.Idle`, backup `Idle`) e la Libreria dello scaffold non mostrava la
 * nuvola, che invece si vede negli screenshot di `G1/`.
 */
internal fun nasLibraryTabState(): LibraryTabState = syntheticLibraryState(
  debugSizes = M1SyntheticSizes,
  nas = LibraryNasStatus.UpToDate(atNasMillis = NAS_MOMENT),
  backup = LibraryBackupStatus.AllSafe(atNasMillis = NAS_MOMENT),
)

private const val NAS_MOMENT = 1_000L

/**
 * Gli stati dati delle schede vere: la Libreria (la griglia sintetica), la scheda Album di G2b (Preferiti, Fotocamera e Video in testa, poi album
 * miei, smart e condivisi), le Raccolte (il controllore vero sui fornitori di prova, che toglie le sezioni passate alla scheda Album) e la ricerca dentro
 * la Libreria (H4: i suggerimenti, i risultati, "nessun risultato", "Trova simili").
 *
 * Va creato sul thread della prova, fuori dalla composizione: il ViewModel delle Raccolte parte li'.
 *
 * @param library la Libreria; di serie quella di chi ha il NAS configurato ([nasLibraryTabState]: la nuvola c'e'); `emptyLibraryTabState()` per la Libreria vuota.
 * @param albumCount quanti album ci sono oltre alle voci speciali (le prove dello scorrimento ne vogliono abbastanza per scorrere).
 * @param collections le Raccolte: di serie il controllore vero sui fornitori di prova; una prova con i fornitori di :app (ricordi, luoghi) passa il suo.
 */
internal class ScaffoldScenes(
  val library: LibraryTabState = nasLibraryTabState(),
  albumCount: Int = DEFAULT_ALBUMS,
  collections: CollectionsLayoutViewModel? = null,
) {
  private val covers = SyntheticLibrary(seed = COVER_SEED, count = COVER_POOL)

  private fun cover(index: Int): MediaId = covers.snapshot.idAt(index % covers.snapshot.size)

  /** La scheda Album: le voci speciali con la loro copertina, gli album di prova (i quattro di sempre e altri manuali) con la copertina automatica. */
  val albums: AlbumsTabState.Ready = run {
    val samples: List<Album> = listOf(AlbumSamples.manual, AlbumSamples.smart, AlbumSamples.sharedMine, AlbumSamples.sharedByOther)
    val extra = (samples.size until albumCount).map { n ->
      AlbumSamples.manual.copy(id = albumId(n), title = EXTRA_TITLES[n % EXTRA_TITLES.size] + " " + (2026 - n / EXTRA_TITLES.size), count = 12 + n * 7)
    }
    val all = (samples + extra).take(albumCount).mapIndexed { index, album -> album.withCover(CoverRef.Auto(cover(index + 10))) }
    val specials = SpecialAlbum.entries.mapIndexed { index, kind -> kind to SpecialSummary(SPECIAL_COUNTS[index], cover(index), NEWEST_DAY - index) }.toMap()
    val (entries, pinned) = AlbumsTabOrder.arrange(all, specials, emptyMap())
    AlbumsTabState.Ready(entries, pinned)
  }

  /** Le miniature delle copertine: il caricatore della Libreria (come in produzione) con sorgenti sintetiche. */
  val albumThumbnails: AlbumThumbnails = AlbumThumbnails(library.grid.loader, library.grid.placeholders, SyntheticCovers)

  /**
   * Le Raccolte come le compone il controllore vero, con le sezioni **come in produzione** (CU-9: l'Utility vera, le Cartelle NAS vere, "Da tenere al
   * sicuro" vuota come la lega `:app`) e le copertine dalla cache delle miniature (qui quella finta).
   */
  val collections: CollectionsLayoutViewModel = collections ?: run {
    val world = FakeCollectionsWorld()
    val resources = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>().resources
    val providers: Set<CollectionsSectionProvider> = CollectionsSectionSamples.production(resources, world.visibleIds.toList(), emptyList()).toSet()
    CollectionsLayoutViewModel(
      world.source, FakePhoneMediaIntents(), providers, Optional.empty(),
      dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader(), Optional.empty(),
    )
  }

  /** L'istantanea della Libreria delle scene: le anteprime dei suggerimenti si disegnano solo se il loro media c'e' (D29). */
  private val shown get() = library.grid.view.snapshot

  /** I2: la foto del media alla posizione [index] dell'istantanea (il primo risultato di un suggerimento). */
  // Con la Libreria vuota (scena `libreria-vuota`) non c'e' nessun media da mostrare: il suggerimento resta senza anteprima.
  private fun photo(index: Int): SuggestionArt? = if (shown.size == 0) null else SuggestionArt.Photo(shown.idAt(index % shown.size))

  /** I2: un volto sintetico (pixel finti, nessun volto vero) con la sua foto d'origine nella Libreria. */
  private fun face(seed: Long, origin: Int): SuggestionArt? = if (shown.size == 0) null else SuggestionArt.Face(
    PersonId("00000000-0000-4000-8000-0000000000%02d".format(seed)),
    dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels.bitmap(seed, 96, 96).asImageBitmap(),
    setOf(shown.idAt(origin % shown.size)),
  )

  private fun person(seed: Long, name: String) = Suggestion.PersonSuggestion(
    PersonOption(listOf(PersonId("00000000-0000-4000-8000-0000000000%02d".format(seed))), PeopleMode.ANY, name),
  )

  /**
   * La ricerca aperta dal cerchio, a campo vuoto (I2): in alto due esempi con la foto del primo risultato e la ricerca recente piu' nuova; gli esempi, scelti fra
   * quelli con tanti risultati e a rotazione (qui: un luogo e un mese pieno).
   */
  val searchIdle: SearchSource = FixedSearchSource(
    items = listOf(
      SuggestionItem(Suggestion.PlaceSuggestion(PlaceOption(setOf(1), "Roma", PlaceLevel.PLACE)), photo(11)),
      SuggestionItem(Suggestion.Term("Agosto 2025"), photo(17)),
      SuggestionItem(Suggestion.Recent(HistoryEntry(1, "mare ad agosto", listOf("Agosto"))), photo(3)),
    ),
    library = shown,
  )

  /** I2: la stessa barra all'apertura successiva: la terna e' girata (una persona con il suo volto e un tipo che c'e' davvero). */
  val searchIdleRotated: SearchSource = FixedSearchSource(
    items = listOf(
      SuggestionItem(person(7, "Anna"), face(7, 5)),
      SuggestionItem(Suggestion.Type(SearchMediaType.VIDEO, "Video"), photo(2)),
      SuggestionItem(Suggestion.Recent(HistoryEntry(1, "mare ad agosto", listOf("Agosto"))), photo(3)),
    ),
    library = shown,
  )

  /** I2: un esempio solo (la libreria di chi ha appena cominciato): una capsula con il suo tondo, non un titolo. */
  val searchIdleSingle: SearchSource = FixedSearchSource(
    items = listOf(SuggestionItem(Suggestion.Term("Mare"), photo(9))),
    library = shown,
  )

  /** Mentre si scrive «mare»: i tre completamenti migliori in alto, i risultati per pertinenza al posto della griglia, i meno probabili in coda. */
  val searchTyping: SearchSource = FixedSearchSource(
    query = "mare",
    items = listOf(
      SuggestionItem(Suggestion.Recent(HistoryEntry(1, "mare ad agosto", listOf("Agosto"))), photo(3)),
      SuggestionItem(Suggestion.PlaceSuggestion(PlaceOption(setOf(6), "Marettimo", PlaceLevel.PLACE)), photo(23)),
      SuggestionItem(Suggestion.Term("Marzo"), photo(29)),
    ),
    state = results(confident = (0 until 34).toList(), likely = (120 until 128).toList()),
    library = shown,
  )

  /**
   * Nessun risultato per «zebre in montagna»: la Libreria attenuata dietro (CU-11), la riga con il segno e cio' che si e' cercato (I2) e, in alto, le
   * ultime ricerche da cui ripartire con la loro foto (CS-12 del giro 2; I2).
   */
  val searchNothing: SearchSource = FixedSearchSource(
    query = "zebre in montagna",
    items = listOf(
      SuggestionItem(Suggestion.Recent(HistoryEntry(1, "mare ad agosto", listOf("Agosto"))), photo(3)),
      SuggestionItem(Suggestion.Recent(HistoryEntry(2, "Natale", emptyList())), photo(31)),
      SuggestionItem(Suggestion.Recent(HistoryEntry(3, "cane", emptyList())), photo(37)),
    ),
    state = SearchScreenState.NoResults("zebre in montagna", emptyList()),
    library = shown,
  )

  /** "Trova simili" da una foto: il campo vuoto, i simili per pertinenza, la riga con la foto da cui vengono (I2). */
  val searchSimilar: SearchSource = FixedSearchSource(
    items = (searchIdle as FixedSearchSource).items,
    state = results(confident = (40 until 61).toList(), likely = emptyList(), similar = 1, similarTo = if (shown.size > 40) listOf(shown.idAt(40)) else emptyList()),
    library = shown,
  )

  /**
   * I2: la ricerca non conosce ancora tutte le foto (nessun dispositivo analizza l'area comune, la copertura sotto il 98%): **una riga sola** con il segno,
   * la barra che avanza e la percentuale. Niente piu' schede «Nessun dispositivo analizza…» (stanno in Impostazioni).
   */
  val searchCoverage: SearchSource = FixedSearchSource(
    query = "cibo",
    items = listOf(SuggestionItem(Suggestion.Recent(HistoryEntry(4, "cibo a Napoli", emptyList())), photo(43))),
    state = results(
      confident = (60 until 84).toList(),
      likely = emptyList(),
      coverage = SearchCoverage.Partial(covered = 5_029, total = 13_608, reasons = mapOf(dev.pampa.mycloudgallery.core.search.contract.CoverageReason.NO_INDEXER_FOR_AREA to 8_579)),
      notices = listOf(dev.pampa.mycloudgallery.core.search.contract.SearchNotice.NoIndexerForArea(true), dev.pampa.mycloudgallery.core.search.contract.SearchNotice.NoIndexerForArea(false)),
    ),
    library = shown,
  )

  /** I risultati come li da' il motore: posizioni dell'istantanea della Libreria, nell'ordine di pertinenza. */
  fun results(
    confident: List<Int>,
    likely: List<Int>,
    similar: Int? = null,
    coverage: SearchCoverage = SearchCoverage.Complete,
    notices: List<dev.pampa.mycloudgallery.core.search.contract.SearchNotice> = emptyList(),
    similarTo: List<dev.pampa.mycloudgallery.core.model.MediaId> = emptyList(),
  ): SearchScreenState.Results {
    val snapshot = library.grid.view.snapshot
    return SearchScreenState.Results(
      chips = emptyList(),
      confident = IndexOrderedTimelineView(snapshot, confident.filter { it < snapshot.size }.toIntArray()),
      likely = IndexOrderedTimelineView(snapshot, likely.filter { it < snapshot.size }.toIntArray()),
      coverage = coverage,
      notices = notices,
      similar = similar,
      similarTo = similarTo,
    )
  }

  /** Le miniature della ricerca: il caricatore della Libreria (la stessa cache), con le sorgenti della Libreria sintetica. */
  val searchThumbnails: SearchThumbnails = SearchThumbnails(library.grid.loader, library.grid.placeholders, SearchRefs { snapshot, index -> library.grid.refAt(snapshot, index) })

  /** La ricerca dello scaffold su [source]. */
  fun search(source: SearchSource, handlers: SearchHandlers = NoSearchHandlers): ScaffoldSearch = ScaffoldSearch(source, handlers, searchThumbnails, NoSearchActions)

  private companion object {
    const val DEFAULT_ALBUMS = 10
    const val COVER_SEED = 21L
    const val COVER_POOL = 200
    const val NEWEST_DAY = 20_400
    val SPECIAL_COUNTS = listOf(86, 4_212, 318, 1_204, 2_577)
    val EXTRA_TITLES = listOf("Montagna", "Mare", "Nonni", "Scuola", "Cene", "Gite", "Natale", "Concerti")

    fun albumId(n: Int): AlbumId = AlbumId("00000000-0000-4000-8000-1" + n.toString().padStart(11, '0'))

    fun Album.withCover(cover: CoverRef): Album = when (this) {
      is Album.Manual -> copy(cover = cover)
      is Album.Smart -> copy(cover = cover)
      is Album.Shared -> copy(cover = cover)
    }
  }
}

/** Le sorgenti sintetiche delle copertine: il caricatore della Libreria disegna un'immagine per ogni media. */
private object SyntheticCovers : AlbumRefs {
  override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef = refOf(snapshot.idAt(index))

  override fun request(media: MediaId): ThumbRequest = ThumbRequest(ThumbKey(media, 0L, ThumbBucket.M), refOf(media))

  private fun refOf(media: MediaId): MediaRef = MediaRef.Synthetic(media.value.hashCode().toLong(), 1_200, 900)
}

/**
 * La schermata principale come in produzione (`AppNavHost`, `MainDestination`), sugli stati di [scenes]: `MainScaffold` con le quattro pagine vere e,
 * sotto, il fondo della pagina che in produzione dipinge `FluidNavHost` (`colorScheme.background`).
 */
@Composable
internal fun GalleryScaffold(
  scenes: ScaffoldScenes,
  tab: GalleryTab,
  status: GlobalStatus = GlobalStatus.HOME_CONNECTED,
  searchOpen: Boolean = false,
  /** H4: che cosa mostra la ricerca aperta (di serie: a campo vuoto, con i tre suggerimenti). */
  search: SearchSource = scenes.searchIdle,
  onSelectTab: (GalleryTab) -> Unit = {},
  onOpenSearch: () -> Unit = {},
  onCloseSearch: () -> Unit = {},
  /** Sul tablet, la pagina di una voce delle Raccolte nel pannello di dettaglio (come `entryDetail` in produzione); null = la voce apre la rotta. */
  collectionsEntryDetail: (@Composable (CollectionEntry) -> Unit)? = null,
) {
  Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    MainScaffold(
      status = status,
      selectedTab = tab,
      onSelectTab = onSelectTab,
      onStatusClick = {},
      onOpenSettings = {},
      libraryState = scenes.library,
      collections = { slot ->
        CollectionsTab(
          contentPadding = slot.contentPadding,
          backdropOut = slot.backdropOut,
          onOpenViewer = {},
          onOpenCollection = {},
          onOpenSettings = {},
          statusHeader = slot.statusHeader,
          viewModel = scenes.collections,
          entryDetail = collectionsEntryDetail,
        )
      },
      albums = { slot ->
        AlbumsTabScreen(
          state = scenes.albums,
          thumbnails = scenes.albumThumbnails,
          handlers = NoAlbumsTabHandlers,
          contentPadding = slot.contentPadding,
          backdropOut = slot.backdropOut,
        )
      },
      search = remember(search) { scenes.search(search) },
      searchOpen = searchOpen,
      onOpenSearch = onOpenSearch,
      onCloseSearch = onCloseSearch,
    )
  }
}
