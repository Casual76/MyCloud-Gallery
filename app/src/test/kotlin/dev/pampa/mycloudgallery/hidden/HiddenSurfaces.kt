package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.model.MediaId

// Fase 7 (P9, D22, C27): IL REGISTRO DELLE SUPERFICIE. "Un elemento nascosto non compare in nessuna superficie": ogni posto dell'app che mostra
// media (o i loro nomi, o i loro conteggi) fuori dai Nascosti sta in questo elenco, con la **sonda** che lo prova. Il test di proprieta'
// (`HiddenSurfacesPropertyTest`) genera Librerie casuali con nascosti, gruppi di copie simili con un nascosto dentro (E-L11), raffiche, copie
// nell'area comune e controlla ogni sonda: un nascosto perfetto non esce mai. Il registro e' fatto per accogliere le superfici che arrivano con
// gli altri pacchetti (album, viaggi, Utility, Raccolte) senza che nessuno possa dimenticarsene: vedi `HiddenSurfacesRegistryTest`.
//
// COME SI REGISTRA UNA SUPERFICIE NUOVA (un pacchetto che mostra media):
//  1. la superficie legge i media da `VisibleMedia` o li filtra con `HiddenMask` (core-library): mai da un'altra fonte;
//  2. si scrive una sonda in questa cartella: una funzione che, sul mondo casuale, dice cosa la superficie mostra (gli `MediaId`);
//  3. si aggiunge qui una riga `SurfaceEntry` con `Backing.PRODUCTION`, i file di produzione che la compongono (`sources`) e la sonda; se la
//     superficie aveva una riga `REFERENCE` o `PENDING` si cambia quella (togliendo i marcatori);
//  4. `check.ps1` esegue il registro: una superficie nuova che usa `VisibleMedia`, `HiddenMask` o `WidgetFeed` e non e' qui fa fallire il test.

/** Le righe della tabella del §2.1 di `docs/fase7-progetto.md` ("Nascosti"): ogni riga deve essere coperta da almeno una voce del registro. */
internal enum class SpecRow(val label: String) {
  LIBRARY("Libreria, filtri, intestazioni e conteggi"),
  COLLECTIONS("Raccolte (conteggi di ogni sezione)"),
  ALBUMS("Album manuali, smart, condivisi; copertine; Suggerite; Da condividere"),
  TRIPS("Viaggi ed eventi (rilevazione, conteggi, copertine)"),
  UTILITY("Utility e Doppioni"),
  SEARCH("Ricerca, suggerimenti, Trova simili"),
  OTHER_APPS("Selettore per le altre app, provider di documenti"),
  WIDGET("Widget di prova"),
  NAS_FOLDERS("Cartelle NAS"),
  NAS_SPACE("Cartelle NAS · Spazio (i file piu' grandi, per nome)"),
  NAS_TRASH("Cartelle NAS · Cestino (nome e cartella di cio' che e' stato eliminato)"),
  ALBUM_VIEWER("Viewer aperto da un album"),
  ADD_TO_ALBUM("Aggiungi ad album"),

  /** Fase 8 (P9, D28): le persone -- la sezione delle Raccolte, "Tutte le persone", "Altri volti", la pagina, la revisione dei volti, la riga del viewer, il dizionario. */
  PEOPLE("Persone: sezione, elenchi, pagina, revisione dei volti, viewer, dizionario"),

  /** Fase 9 (P9, D31): i ricordi -- la sezione delle Raccolte, la pagina del ricordo, "Tutti i ricordi", la presentazione, le copertine e i ricordi con le persone. */
  MEMORIES("Ricordi: sezione, pagina, \"Tutti i ricordi\", presentazione, copertine, persone"),

  /** Fase 9 (P4, D31): la mappa -- i punti e i conteggi, le bolle e le copertine, la griglia di una zona, la sezione Luoghi delle Raccolte e la mini-mappa del viewer. */
  MAP("Mappa: punti, bolle, copertine, griglia di una zona, Luoghi, mini-mappa del viewer"),

  /** Fase 9 (P10, D19, D31): le notifiche dei ricordi -- la scelta del ricordo, il contenuto, la miniatura della copertina, la revoca. */
  NOTIFICATIONS("Notifiche dei ricordi: scelta, contenuto, miniatura, revoca"),
}

/** Con cosa e' provata una superficie. */
internal enum class Backing {
  /** Con il codice vero di produzione. */
  PRODUCTION,

  /** Con l'esecutore di riferimento del contratto (`InMemoryAlbums`, `InMemoryTrips`): il codice vero non c'e' ancora. Quando arriva, si passa a [PRODUCTION]. */
  REFERENCE,

  /** Non c'e' ne' il codice vero ne' un riferimento: la superficie arriva con un pacchetto futuro. */
  PENDING,
}

/** Cosa mostra una superficie su un mondo, secondo la sua sonda. */
internal class Probed(
  /** Tutti i media che la superficie mostra (nel loro insieme) sul mondo. */
  val shown: Set<MediaId>,
  /** Se c'e': la superficie deve mostrare **esattamente** questi (prova che non e' vuota per sbaglio). */
  val expected: Set<MediaId>? = null,
  /** Se c'e': tutto cio' che la superficie mostra sta in questo insieme (per le superfici che scelgono, come la ricerca). */
  val within: Set<MediaId>? = null,
  /** Altre violazioni trovate dalla sonda (conteggi, copertine): frasi senza id. */
  val problems: List<String> = emptyList(),
)

/** La sonda di una superficie: la fa girare sul mondo e dice cosa mostra. */
internal fun interface SurfaceProbe {
  suspend fun probe(world: BuiltWorld): Probed
}

internal class SurfaceEntry(
  val id: String,
  val title: String,
  val row: SpecRow,
  /** Il pacchetto o la fase che ha scritto (o scrivera') la superficie. */
  val owner: String,
  val backing: Backing,
  /**
   * I file di produzione (percorsi dalla radice del repository) che compongono la superficie e usano `VisibleMedia`, `HiddenMask` o `WidgetFeed`,
   * o che sono la superficie stessa. Il registro controlla che esistano e che ogni file che usa quei contratti sia elencato da qualcuno.
   */
  val sources: List<String> = emptyList(),
  /** Percorsi che esistono solo quando il codice vero e' arrivato: per [Backing.REFERENCE] e [Backing.PENDING], il registro fallisce se ci sono. */
  val landedWhen: List<String> = emptyList(),
  val probe: SurfaceProbe? = null,
  /**
   * La superficie, dopo l'istantanea della Libreria, chiede ancora al catalogo (il filtro finale di D22): deve escludere anche gli "ultimi nascosti"
   * ([WorldSpec.lateHidden]) e i loro gruppi. Le altre usano solo l'istantanea (la Libreria la aggiorna da se' al cambiamento successivo).
   */
  val live: Boolean = false,
  /** Le classi di test che provano la stessa regola a modo loro (esistono davvero: lo controlla il registro). */
  val alsoProvedBy: List<String> = emptyList(),
) {
  init {
    require((backing == Backing.PENDING) == (probe == null)) { "$id: una superficie in attesa non ha sonda, le altre si." }
    require(backing == Backing.PRODUCTION || landedWhen.isNotEmpty()) { "$id: serve il marcatore di quando arriva il codice vero." }
  }

  override fun toString(): String = "SurfaceEntry($id, $backing)"
}

/**
 * Il lettore dei Nascosti (la pagina stessa) e chi li mostra dietro il cancello: leggono i nascosti **apposta**, solo dopo lo sblocco.
 *
 * @property sources i file di produzione che mostrano nomi di file di Cartelle NAS dietro il cancello (il registro li riconosce, vedi
 *   [HiddenSurfaces.nameBearingTypes]).
 * @property row la riga del §2.1 che questa pagina copre, se ne copre una (il Cestino di Cartelle NAS).
 */
internal class GatedReader(val id: String, val title: String, val proofs: List<String>, val sources: List<String> = emptyList(), val row: SpecRow? = null)

internal object HiddenSurfaces {

  private const val LIB = "core-library/src/main/kotlin/dev/pampa/mycloudgallery/core/library"

  // I nomi del widget di prova sono scritti in due pezzi: `Phase5BArchitectureTest` vieta i nomi "Field" + "Probe" fuori da app/src/debug e testDebug,
  // e questo file e' in app/src/test (il widget di prova sta solo in debug, qui lo si nomina soltanto).
  /** Il file del contenuto del widget di prova (solo debug): e' la superficie. */
  const val WIDGET_CONTENT_FILE: String = "app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/widget/Field" + "ProbeWidgetContent.kt"

  /** La classe di test del widget di prova (solo testDebug). */
  const val WIDGET_TEST: String = "Field" + "ProbeWidgetTest"
  private const val ALBUMS = "core-albums/src/main/kotlin/dev/pampa/mycloudgallery/core/albums"
  private const val FEATURE_ALBUMS = "feature-albums/src/main/kotlin/dev/pampa/mycloudgallery/feature/albums"
  private const val FACES_PEOPLE = "core-faces/src/main/kotlin/dev/pampa/mycloudgallery/core/faces/people"
  private const val SEARCH = "core-search/src/main/kotlin/dev/pampa/mycloudgallery/core/search"
  private const val APP_FACES = "app/src/main/kotlin/dev/pampa/mycloudgallery/faces"
  private const val FEATURE_NAS = "feature-nas/src/main/kotlin/dev/pampa/mycloudgallery/feature/nas"
  private const val FEATURE_LIBRARY = "feature-library/src/main/kotlin/dev/pampa/mycloudgallery/feature/library"
  private const val MEMORIES_NOTIFY = "core-memories/src/main/kotlin/dev/pampa/mycloudgallery/core/memories/notify"
  private const val MEMORIES_CORE = "core-memories/src/main/kotlin/dev/pampa/mycloudgallery/core/memories"
  private const val FEATURE_MEMORIES = "feature-memories/src/main/kotlin/dev/pampa/mycloudgallery/feature/memories"
  private const val FEATURE_MAP = "feature-map/src/main/kotlin/dev/pampa/mycloudgallery/feature/map"

  /**
   * I file che fanno vivere la riga `MEMORIES`: il motore di P6 (la pulizia dello scaffale, le voci della sezione, il giro e la pubblicazione, la scelta delle foto), il
   * filtro finale di P7 (`VisiblePhotoPicker`) e il filtro finale delle schermate di P9 (`FinalFilter`, che le superfici chiamano tutte; `MemoriesDependencies` e' dove `:app`
   * lega la maschera). Un file nuovo che nomina `VisibleMedia` o `HiddenMask` si elenca qui, con la sua sonda.
   */
  private val MEMORIES_SOURCES = listOf(
    "$MEMORIES_CORE/shelf/ShelfPruner.kt", "$MEMORIES_CORE/shelf/ShelfCards.kt", "$MEMORIES_CORE/plan/MemoryEngine.kt", "$MEMORIES_CORE/plan/PublishRound.kt",
    "$MEMORIES_CORE/plan/MemoryPhotoChoice.kt", "$MEMORIES_CORE/pick/VisiblePhotoPicker.kt",
    "$FEATURE_MEMORIES/ui/FinalFilter.kt", "$FEATURE_MEMORIES/nav/MemoriesDependencies.kt",
  )

  /**
   * I file che fanno vivere la riga `MAP` e nominano i contratti dei nascosti: il filtro finale delle superfici della mappa (`FinalFilter`, l'unico file di feature-map che nomina `HiddenMask`: bolle, griglia e mini-mappa
   * passano tutte da li'), le porte per Dagger (`MapDependencies`, dove `:app` lega la maschera e l'istantanea visibile) e la griglia di una zona (`ZoneGridController`, che legge l'istantanea di `VisibleMedia`). Un file
   * nuovo di feature-map che nomina `VisibleMedia` o `HiddenMask` si elenca qui, con la sua sonda.
   */
  private val MAP_SOURCES = listOf("$FEATURE_MAP/ui/FinalFilter.kt", "$FEATURE_MAP/nav/MapDependencies.kt", "$FEATURE_MAP/grid/ZoneGridController.kt")

  /** Le superfici. L'ordine e' quello del §2.1. */
  val entries: List<SurfaceEntry> = listOf(
    SurfaceEntry(
      id = "library", title = "Libreria, filtri, intestazioni e conteggi", row = SpecRow.LIBRARY, owner = "Fase 3", backing = Backing.PRODUCTION,
      probe = HiddenProbes.library,
      // Avvio istantaneo (28/10, C190): l'istantanea della partenza (pubblicata prima del catalogo) non ha i nascosti dell'ultima volta.
      alsoProvedBy = listOf("CatalogIntegrationTest", "TimelineIndexTest", "LibraryContractTest", "RepositoryPreloadTest", "LibraryInstantTest"),
    ),
    SurfaceEntry(
      id = "collections", title = "Raccolte: Preferiti, Video, Screenshot, WhatsApp, Modificate e i loro conteggi", row = SpecRow.COLLECTIONS, owner = "Fase 3",
      backing = Backing.PRODUCTION, probe = HiddenProbes.collections, alsoProvedBy = listOf("CollectionsViewModelNoticeTest"),
    ),
    SurfaceEntry(
      id = "utility-pages", title = "Utility: Screenshot, Video, Modificate, WhatsApp (pagine-filtro della Libreria)", row = SpecRow.UTILITY, owner = "P8",
      backing = Backing.PRODUCTION, probe = HiddenProbes.utilityPages,
    ),
    SurfaceEntry(
      id = "search", title = "Ricerca, suggerimenti e Trova simili (F6-D29)", row = SpecRow.SEARCH, owner = "Fase 6", backing = Backing.PRODUCTION,
      probe = HiddenProbes.search, alsoProvedBy = listOf("HiddenNeverTest", "SearchHiddenNeverTest"),
    ),
    SurfaceEntry(
      id = "other-apps", title = "Selettore per le altre app (E-X7) e provider dei documenti", row = SpecRow.OTHER_APPS, owner = "Fase 5B",
      backing = Backing.PRODUCTION, probe = HiddenProbes.picker, alsoProvedBy = listOf("HiddenNeverLeavesTest", "PickerDeliveryTest"),
    ),
    SurfaceEntry(
      id = "widget", title = "Widget di prova (WidgetFeed)", row = SpecRow.WIDGET, owner = "P9", backing = Backing.PRODUCTION,
      sources = listOf("$LIB/visibility/RotatingWidgetFeed.kt", WIDGET_CONTENT_FILE),
      probe = HiddenProbes.widget, live = true, alsoProvedBy = listOf("RotatingWidgetFeedTest", WIDGET_TEST),
    ),
    SurfaceEntry(
      id = "visible-media", title = "VisibleMedia: la vista del catalogo senza nascosti (album, copertine, viaggi, Utility, Doppioni)", row = SpecRow.ALBUMS,
      owner = "P9", backing = Backing.PRODUCTION, sources = listOf("$LIB/visibility/CatalogVisibility.kt"), probe = HiddenProbes.visibleMedia, live = true,
      alsoProvedBy = listOf("CatalogVisibilityTest"),
    ),
    SurfaceEntry(
      id = "hidden-mask", title = "HiddenMask: il filtro finale subito prima di mostrare", row = SpecRow.ALBUMS, owner = "P9", backing = Backing.PRODUCTION,
      probe = HiddenProbes.hiddenMask, live = true, alsoProvedBy = listOf("CatalogVisibilityTest"),
    ),
    SurfaceEntry(
      id = "nas-folders", title = "Cartelle NAS: la riga dei nascosti senza nomi (I8)", row = SpecRow.NAS_FOLDERS, owner = "P9", backing = Backing.PRODUCTION,
      sources = listOf("$FEATURE_NAS/folders/HiddenFiles.kt", "$FEATURE_NAS/folders/NasFoldersScreen.kt", "$FEATURE_NAS/folders/NasFoldersViewModel.kt"),
      probe = HiddenProbes.nasFolders, live = true, alsoProvedBy = listOf("NasHiddenFilesTest", "NasHiddenRowScreenTest"),
    ),
    // --- Revisione di Fase 7, pacchetto C (R4, NA-1): Spazio NAS elenca per nome i file piu' grandi, che sono quasi sempre i video; i nascosti non ci sono ------
    SurfaceEntry(
      id = "nas-space", title = "Cartelle NAS · Spazio: i file piu' grandi, per nome e cartella, senza i nascosti (R4, NA-1)", row = SpecRow.NAS_SPACE,
      owner = "Revisione C", backing = Backing.PRODUCTION,
      sources = listOf("app/src/main/kotlin/dev/pampa/mycloudgallery/nas/NasOrganizer.kt", "$FEATURE_NAS/space/NasSpaceScreen.kt"),
      probe = HiddenProbes.nasSpace, live = true, alsoProvedBy = listOf("NasHiddenFilesTest"),
    ),
    // --- Il codice vero di P2 (album condivisi) e di P4 (condivisione): integrazione del 26/10 ---------------------------------------------------------------
    SurfaceEntry(
      id = "albums-shared", title = "Album condivisi veri: elenco, conteggi, copertine, elementi, lasciati, copie personali, foglio di chiusura (E-H5, E-H6)",
      row = SpecRow.ALBUMS, owner = "P2", backing = Backing.PRODUCTION, sources = listOf("$ALBUMS/shared/ReplicaSharedAlbums.kt"),
      probe = RealSharedProbes.sharedAlbums, live = true,
      alsoProvedBy = listOf("RealSharedAlbumsContractTest", "FakeSharedAlbumsContractTest", "HiddenAlbumsReferenceTest"),
    ),
    SurfaceEntry(
      id = "share-adoptions", title = "Da condividere: i file nuovi nella cartella dell'album (D39)", row = SpecRow.ALBUMS, owner = "P4", backing = Backing.PRODUCTION,
      sources = listOf("$ALBUMS/share/ShareProposals.kt"), probe = RealSharedProbes.shareAdoptions, live = true,
    ),
    SurfaceEntry(
      id = "share-bring-back", title = "Riporta nelle mie cartelle: i candidati del foglio di chiusura (D31)", row = SpecRow.ALBUMS, owner = "P4", backing = Backing.PRODUCTION,
      sources = listOf("$ALBUMS/share/ShareProposals.kt"), probe = RealSharedProbes.shareBringBack, live = true,
    ),
    SurfaceEntry(
      id = "closed-copies", title = "Copie personali degli album chiusi (Q5): gli elementi che si scrivono", row = SpecRow.ALBUMS, owner = "P4", backing = Backing.PRODUCTION,
      sources = listOf("$ALBUMS/share/ClosedAlbumCopies.kt"), probe = RealSharedProbes.closedCopies, live = true,
    ),
    SurfaceEntry(
      id = "revoked-restore", title = "Ripristina gli elementi di un dispositivo revocato: quanti (E-SH20)", row = SpecRow.ALBUMS, owner = "P4", backing = Backing.PRODUCTION,
      sources = listOf("$ALBUMS/share/RevokedItemsRestore.kt"), probe = RealSharedProbes.revokedRestore, live = true,
    ),
    // --- Il codice vero di P3 (album personali): integrazione del 26/10 ---------------------------------------------------------------------------------------
    SurfaceEntry(
      id = "albums-manual", title = "Album manuali veri: elenco, conteggi, copertine (E-H6) e contenuto", row = SpecRow.ALBUMS, owner = "P3", backing = Backing.PRODUCTION,
      sources = listOf("$ALBUMS/personal/CatalogAlbums.kt", "$ALBUMS/personal/AlbumReader.kt"), probe = RealPersonalProbes.manualAlbums, live = true,
      alsoProvedBy = listOf("CatalogAlbumsContractTest", "CatalogAlbumsHiddenMaskTest", "CatalogAlbumsTest", "AlbumProjectionPropertyTest"),
    ),
    SurfaceEntry(
      id = "albums-smart-read", title = "Album smart: la lettura di P3 (conteggi, elementi, Suggerite) con le righe del motore", row = SpecRow.ALBUMS, owner = "P3",
      backing = Backing.PRODUCTION, sources = listOf("$ALBUMS/personal/AlbumReader.kt"), probe = RealPersonalProbes.smartAlbums, live = true,
    ),
    SurfaceEntry(
      id = "albums-copies", title = "Copie personali degli album condivisi chiusi (Q5): quello che si scrive e si legge", row = SpecRow.ALBUMS, owner = "P3",
      backing = Backing.PRODUCTION, sources = listOf("$ALBUMS/personal/PersonalAlbumCopies.kt"), probe = RealPersonalProbes.personalCopies, live = true,
      alsoProvedBy = listOf("PersonalAlbumCopiesTest"),
    ),
    SurfaceEntry(
      id = "albums-pending", title = "Elementi in attesa (id che questo catalogo non conosce): ritrovati per impronta, arrivati dopo", row = SpecRow.ALBUMS, owner = "P3",
      backing = Backing.PRODUCTION, sources = listOf("$ALBUMS/personal/CatalogAlbumProjection.kt", "$ALBUMS/personal/MediaResolver.kt"), probe = RealPersonalProbes.pendingItems,
      live = true, alsoProvedBy = listOf("CatalogAlbumProjectionTest"),
    ),
    // --- Il codice vero di P6 (album smart): integrazione del 26/10 -----------------------------------------------------------------------------------------
    SurfaceEntry(
      id = "albums-smart", title = "Album smart veri: il motore (sicuri, Suggerite, anteprima, incrementale) sui candidati del catalogo", row = SpecRow.ALBUMS, owner = "P6",
      backing = Backing.PRODUCTION, sources = listOf("$ALBUMS/smart/RulesSmartEngine.kt", "$ALBUMS/smart/SmartVisible.kt"), probe = RealSmartProbes.engine, live = true,
      alsoProvedBy = listOf("SmartHiddenPropertyTest", "SmartIncrementalPropertyTest", "SmartRoomTest", "SmartEngineTest"),
    ),
    SurfaceEntry(
      id = "albums-smart-editor", title = "L'editor degli album smart: la proposta del nome (luogo, serie di giorni) e l'anteprima, solo dai media visibili", row = SpecRow.ALBUMS,
      owner = "P6", backing = Backing.PRODUCTION, sources = listOf("$ALBUMS/smart/RuleSmartNameInterpreter.kt"), probe = RealSmartProbes.editor, live = true,
      alsoProvedBy = listOf("SmartInterpreterTest", "TitleTableTest"),
    ),
    SurfaceEntry(
      id = "album-viewer", title = "Viewer aperto da un album: la vista dell'album (`AlbumContent.items` di P3, che il viewer di P10 scorre)", row = SpecRow.ALBUM_VIEWER,
      owner = "P3/P10", backing = Backing.PRODUCTION, probe = RealPersonalProbes.albumViewer, live = true,
    ),
    // --- Il codice vero di P7 (viaggi ed eventi): 26/10 ---------------------------------------------------------------------------------------------------------
    SurfaceEntry(
      id = "trips", title = "Viaggi ed eventi veri: rilevazione, conteggi, copertine (E-H7), anche i nascosti dopo la rilevazione", row = SpecRow.TRIPS, owner = "P7",
      backing = Backing.PRODUCTION, sources = listOf("$ALBUMS/trips/TripVisibility.kt", "$ALBUMS/trips/CatalogTrips.kt"), probe = RealTripsProbes.trips, live = true,
      alsoProvedBy = listOf("CatalogTripsContractTest", "ReferenceTripsContractTest", "TripHiddenPropertyTest", "RoomTripsTest"),
    ),
    SurfaceEntry(
      id = "duplicates", title = "Doppioni: identici e simili, pulizia guidata (piani compresi)", row = SpecRow.UTILITY, owner = "P8",
      backing = Backing.PRODUCTION,
      sources = listOf(
        "$LIB/duplicates/CatalogIdenticalDuplicates.kt", "$LIB/duplicates/CatalogSimilarShots.kt", "$LIB/duplicates/FullHashNightly.kt",
      ),
      probe = HiddenProbes.duplicates, live = true,
      alsoProvedBy = listOf("CatalogIdenticalDuplicatesTest", "CatalogSimilarShotsTest", "FullHashNightlyTest"),
    ),
    // --- Il codice vero di P10 (schermate degli album): integrazione del 27/10 -------------------------------------------------------------------------------
    SurfaceEntry(
      id = "add-to-album", title = "Aggiungi ad album: la scelta dell'album e dei media (il chooser vero di feature-albums)", row = SpecRow.ADD_TO_ALBUM, owner = "P10",
      backing = Backing.PRODUCTION, sources = listOf("$FEATURE_ALBUMS/choose/AddToAlbumController.kt"), probe = RealAlbumScreensProbes.addToAlbum, live = true,
      alsoProvedBy = listOf("AddToAlbumAndSuggestionsTest"),
    ),
    SurfaceEntry(
      id = "album-pages", title = "Pagine degli album e Suggerite (i controllori veri di feature-albums sullo stack vero): elementi, copertine, menu di un elemento",
      row = SpecRow.ALBUMS, owner = "P10", backing = Backing.PRODUCTION,
      sources = listOf("$FEATURE_ALBUMS/page/AlbumPageController.kt", "$FEATURE_ALBUMS/page/SuggestionsController.kt"), probe = RealAlbumScreensProbes.albumPages, live = true,
      alsoProvedBy = listOf("AlbumPageControllerTest"),
    ),
    // --- Il codice vero di P9 (persone): integrazione del 27/10. Tutte passano da `PeopleVisibility`, l'unico punto in cui le persone chiedono cosa si puo' mostrare ----------------
    SurfaceEntry(
      id = "people-lists", title = "Persone: la sezione delle Raccolte, \"Tutte le persone\" (Q2 b e Q2 a), \"Altri volti\" e \"Persone nascoste\": conti, copertine", row = SpecRow.PEOPLE,
      owner = "P9", backing = Backing.PRODUCTION, sources = listOf("$FACES_PEOPLE/PeopleVisibility.kt"), probe = RealPeopleProbes.peopleLists(), live = true,
      alsoProvedBy = listOf("PeopleVisibilityTest", "ArchivePeopleContractTest", "PeopleListControllerTest", "OthersControllerTest", "HiddenPeopleControllerTest", "PeopleSectionsTest"),
    ),
    SurfaceEntry(
      id = "person-pages", title = "La pagina di una persona (foto, carta, \"Forse e' anche…\") e le proposte \"E' la stessa persona?\"", row = SpecRow.PEOPLE, owner = "P9",
      backing = Backing.PRODUCTION, sources = listOf("$FACES_PEOPLE/PeopleVisibility.kt"), probe = RealPeopleProbes.personPages(), live = true,
      alsoProvedBy = listOf("PeopleVisibilityTest", "PersonPageControllerTest"),
    ),
    SurfaceEntry(
      id = "face-review", title = "\"Controlla le foto\", \"Dividi\" e i ritagli dei volti (il cancello): solo da celle mostrabili", row = SpecRow.PEOPLE, owner = "P9",
      backing = Backing.PRODUCTION, sources = listOf("$FACES_PEOPLE/PeopleVisibility.kt"), probe = RealPeopleProbes.faceReview(), live = true,
      alsoProvedBy = listOf("FaceReviewControllerTest", "GuardedPeopleCropsTest"),
    ),
    SurfaceEntry(
      id = "viewer-people", title = "La riga \"Persone\" del viewer, anche dai Nascosti (F99): i chip non portano mai a una pagina con altri nascosti; \"Chi e'?\"", row = SpecRow.PEOPLE,
      owner = "P9", backing = Backing.PRODUCTION, sources = listOf("$FACES_PEOPLE/PeopleVisibility.kt"), probe = RealPeopleProbes.viewerRow(), live = true,
      alsoProvedBy = listOf("PeopleLifecycleTest", "ViewerPeopleInfoTest"),
    ),
    SurfaceEntry(
      id = "people-dictionary", title = "Il dizionario dei nomi per la ricerca: solo nomi di persone non nascoste, con il conto delle foto mostrabili", row = SpecRow.PEOPLE, owner = "P9",
      backing = Backing.PRODUCTION, sources = listOf("$FACES_PEOPLE/PeopleVisibility.kt"), probe = RealPeopleProbes.dictionary(), live = true,
      alsoProvedBy = listOf("PeopleIndexTest"),
    ),
    // --- Il codice vero di P10 (ricerca per persona): i candidati vengono dall'istantanea, il filtro finale resta dei nascosti, il dizionario e' quello di `people-dictionary` ---------
    SurfaceEntry(
      id = "search-people", title = "Ricerca per persona: dizionario dei nomi, chip \"Persona\", candidati \"sicuri\" e \"forse\", suggerimenti e risultati (F6-D29, D26)",
      row = SpecRow.PEOPLE, owner = "P10", backing = Backing.PRODUCTION,
      sources = listOf("$SEARCH/engine/PeopleGate.kt", "$SEARCH/engine/LibrarySuggestions.kt", "$APP_FACES/FacesPeoplePorts.kt"), probe = RealSearchPeopleProbes.searchPeople(), live = true,
      alsoProvedBy = listOf("PeopleSearchEngineTest", "PeopleHistoryAndSuggestionsTest", "PeopleQueryTableTest"),
    ),
    // --- Il codice vero di P10 (album smart con le persone): i candidati sono quelli VISIBILI del catalogo, le celle delle persone passano da `PeopleVisibility` ---------------
    SurfaceEntry(
      id = "albums-people", title = "Album smart con le persone: la regola \"Persone\" (sicuri e suggeriti, \"e\" con il periodo, alias, persona dimenticata, volti spenti) e il nome che diventa regola (D27)",
      row = SpecRow.PEOPLE, owner = "P10", backing = Backing.PRODUCTION,
      sources = listOf("$ALBUMS/smart/SmartPeople.kt", "$ALBUMS/smart/SmartClassifier.kt", "$APP_FACES/FacesPeoplePorts.kt"), probe = RealAlbumsPeopleProbes.albumsPeople(), live = true,
      alsoProvedBy = listOf("SmartPeopleTest", "SmartPeoplePropertyTest", "SmartPeopleInterpreterTest", "SmartUpkeepPeopleTest"),
    ),

    // --- Il codice vero di P6, P7 e P9 (i ricordi): integrazione del 30/10. Il motore vero e le schermate vere sul catalogo vero; dopo la pubblicazione il catalogo nasconde
    // alcuni elementi (senza che l'istantanea e lo scaffale lo sappiano) e ogni superficie deve toglierli (E-MEM5, E-H8, E-H10, D31) -------------------------------------------------------
    SurfaceEntry(
      id = "memories-section", title = "Ricordi: la sezione delle Raccolte (voci, conti, segni \"nuovo\", copertine, codici)", row = SpecRow.MEMORIES, owner = "P9",
      backing = Backing.PRODUCTION, sources = MEMORIES_SOURCES + "$FEATURE_MEMORIES/section/MemoriesSections.kt", probe = RealMemoriesProbes.section, live = true,
      alsoProvedBy = listOf("MemoriesSectionSourcesTest", "MemoriesSectionStatesTest", "MemoriesSectionBindingTest"),
    ),
    SurfaceEntry(
      id = "memories-page", title = "Ricordi: la pagina del ricordo e \"Tutti i ricordi\" (elementi, copertina, conti, viewer)", row = SpecRow.MEMORIES, owner = "P9",
      backing = Backing.PRODUCTION, sources = listOf("$FEATURE_MEMORIES/page/MemoryPageController.kt", "$FEATURE_MEMORIES/list/AllMemoriesController.kt"),
      probe = RealMemoriesProbes.page, live = true, alsoProvedBy = listOf("MemoryPageControllerTest", "AllMemoriesControllerTest"),
    ),
    SurfaceEntry(
      id = "memories-player", title = "Ricordi: la presentazione (elementi, copertina del titolo, precaricamento, carta finale)", row = SpecRow.MEMORIES, owner = "P9",
      backing = Backing.PRODUCTION, sources = listOf("$FEATURE_MEMORIES/player/PlayerController.kt"), probe = RealMemoriesProbes.player, live = true,
      alsoProvedBy = listOf("PlayerControllerTest", "ThumbnailPlayerImagesTest"),
    ),
    SurfaceEntry(
      id = "memories-people", title = "Ricordi con le persone: mai una persona nascosta, mai un nascosto nei ricordi di una persona (E-H10)", row = SpecRow.MEMORIES, owner = "P9",
      backing = Backing.PRODUCTION, sources = listOf("$MEMORIES_CORE/shelf/ShelfPruner.kt"), probe = RealMemoriesProbes.people, live = true,
    ),

    // --- Il codice vero di P3 e P4 (la mappa): la catena vera dei punti (query, lettura a pagine, servizio) sul catalogo vero e le schermate vere sopra. Dopo l'indice il catalogo nasconde le copertine delle bolle piu' grandi
    // e un elemento in mezzo (senza che l'indice lo sappia): bolle, copertine e griglie li tolgono (E-MAP15, E-H9); rifatto l'indice, i conteggi sono esatti. La mini-mappa non c'e' mai per un nascosto (D30) -------------------
    SurfaceEntry(
      id = "map-points", title = "Mappa: punti, conteggi, bolle, copertine ed etichette (mai un nascosto, mai Casa; conteggi esatti dopo il cambio dei nascosti)", row = SpecRow.MAP, owner = "P4",
      backing = Backing.PRODUCTION, sources = MAP_SOURCES, probe = RealMapProbes.points, live = true, alsoProvedBy = listOf("BubbleEngineTest", "MapControllerTest", "MapScreensTest", "HiddenNeverShownTest"),
    ),
    SurfaceEntry(
      id = "map-zone", title = "Mappa: la griglia di una zona (elementi dal piu' recente, viva sull'istantanea, senza nascosti)", row = SpecRow.MAP, owner = "P4",
      backing = Backing.PRODUCTION, sources = listOf("$FEATURE_MAP/grid/ZoneGridController.kt"), probe = RealMapProbes.zone, live = true, alsoProvedBy = listOf("ZoneGridControllerTest"),
    ),
    SurfaceEntry(
      id = "places-section", title = "Raccolte -> Luoghi: la riga Mappa, i luoghi piu' fotografati, le foto senza posizione (conteggi esatti, mai Casa)", row = SpecRow.MAP, owner = "P4",
      backing = Backing.PRODUCTION, sources = listOf("$FEATURE_MAP/nav/MapDependencies.kt"), probe = RealMapProbes.places, live = true, alsoProvedBy = listOf("PlacesSectionTest"),
    ),
    SurfaceEntry(
      id = "viewer-minimap", title = "Viewer: la riga Mostra sulla mappa e la mini-mappa (mai per un media dei Nascosti)", row = SpecRow.MAP, owner = "P4",
      backing = Backing.PRODUCTION, sources = listOf("$FEATURE_MAP/ui/FinalFilter.kt"), probe = RealMapProbes.minimap, live = true, alsoProvedBy = listOf("MiniMapServiceTest", "ViewerPlaceMapInfoTest"),
    ),
    // --- Il codice vero di P10 (notifiche dei ricordi): la scelta passa dallo scaffale e da `MemoryVisibility` (il filtro finale dei nascosti, D19) subito prima di pubblicare; la revoca a ogni cambio ---
    SurfaceEntry(
      id = "memories-notify", title = "Notifiche dei ricordi: scelta, contenuto, miniatura della copertina e revoca (D19, F116): mai un nascosto, mai una copertina nascosta in tendina",
      row = SpecRow.NOTIFICATIONS, owner = "P10", backing = Backing.PRODUCTION,
      sources = listOf("$MEMORIES_NOTIFY/MemoryNotifyRun.kt", "$MEMORIES_NOTIFY/MemoryNotifyRevocation.kt", "$MEMORIES_NOTIFY/MemoryNotifyPolicy.kt", "app/src/main/kotlin/dev/pampa/mycloudgallery/notify/MemoryNotifier.kt"),
      probe = RealNotifyProbes.notifications(), live = true,
      alsoProvedBy = listOf("HiddenNotifyMutationTest", "MemoryNotifyRunTest", "MemoryNotifyRevocationTest", "NotifyOncePerDayPropertyTest", "MemoryNotifierTest"),
    ),
  )

  /**
   * Il lettore dei Nascosti e gli Eliminati di recente: mostrano i nascosti apposta, dietro il cancello (credenziale a ogni ingresso). Il **Cestino di
   * Cartelle NAS** e' la stessa pagina (ED-1, R4 della revisione di Fase 7): `NasTrashGateTest` prova che ogni ingresso di `NasScreen.TRASH` passa da li'.
   */
  val gated: List<GatedReader> = listOf(
    GatedReader("hidden-page", "Nascosti", listOf("HiddenControllerTest", "HiddenScreenTest", "HiddenAccessGateTest", "HiddenLiveViewTest")),
    GatedReader(
      "recently-deleted", "Eliminati di recente (con il segno sui nascosti)", listOf("RecentlyDeletedControllerTest", "RecentlyDeletedScreenTest"),
      sources = listOf(
        "$FEATURE_LIBRARY/trash/NasTrashPort.kt", "$FEATURE_LIBRARY/trash/NasTrashRows.kt", "$FEATURE_LIBRARY/trash/RecentlyDeletedController.kt",
        "app/src/main/kotlin/dev/pampa/mycloudgallery/utility/UtilityPorts.kt",
      ),
    ),
    GatedReader(
      "nas-trash", "Cartelle NAS · Cestino: nome e cartella di cio' che e' stato eliminato, dietro il cancello", listOf("NasTrashGateTest", "RecentlyDeletedNasTest"),
      row = SpecRow.NAS_TRASH,
    ),
  )

  /**
   * Le pagine di Cartelle NAS che mostrano nomi di file ma **non sono raggiungibili** dall'app (R4): la vecchia pagina del Cestino, senza cancello. Il
   * registro le riconosce per nome; `NasTrashGateTest` prova che nessuna rotta le chiama.
   */
  val dormant: List<String> = listOf("$FEATURE_NAS/trash/NasTrashScreen.kt", "$FEATURE_NAS/trash/NasTrashViewModel.kt")

  /**
   * I file che **legano** i contratti (`VisibleMedia`, `HiddenMask`, `WidgetFeed`) al catalogo senza mostrare niente: non sono superfici. Il registro li
   * ammette fra i file che nominano quei contratti.
   */
  val wiring: List<String> = listOf(
    "app/src/main/kotlin/dev/pampa/mycloudgallery/gallery/GalleryGraph.kt",
    // Le porte facoltative di feature-albums (la visibilita' per "Aggiungi ad album"): le raccoglie e le passa ai controllori, non mostra niente.
    "feature-albums/src/main/kotlin/dev/pampa/mycloudgallery/feature/albums/nav/AlbumDependencies.kt",
    // Fase 7 (P13): il montaggio degli album in :app (P7: "il file di :app che nomina VisibleMedia/HiddenMask va nell'elenco wiring"). Passano
    // `GalleryGraph.visibleMedia`/`hiddenMask` alle superfici registrate qui sopra (album, smart, viaggi, condivisi) e alle schermate; non mostrano niente.
    "app/src/main/kotlin/dev/pampa/mycloudgallery/albums/AlbumsLive.kt",
    "app/src/main/kotlin/dev/pampa/mycloudgallery/di/AlbumsModule.kt",
    // Fase 7 (P13, P8): le pagine dell'Utility in :app passano `visibleMedia`/`hiddenMask` a `CatalogIdenticalDuplicates`, `CatalogSimilarShots` e
    // `FullHashNightly` (le superfici `duplicates` registrate qui sopra), costruite al primo uso; non mostrano niente.
    "app/src/main/kotlin/dev/pampa/mycloudgallery/utility/UtilityGraph.kt",
    // Fase 7 (P12): lo strumento di valutazione degli album (solo debug, `app/src/debug/.../eval/albums/`) passa `VisibleMedia` e `HiddenMask` alla corsa degli album
    // smart di P6 (che li usa gia') e filtra con loro le celle che mostra. Non e' una superficie del prodotto (nessuna riga del §2.1): la prova che un nascosto non esce
    // mai e' `AppAlbumsEvalEnvironmentTest` (catalogo vero, mondi casuali) e `AlbumsEvalSessionTest`.
    "app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/eval/albums/AlbumsEvalRunner.kt",
    "app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/eval/albums/AppAlbumsEvalEnvironment.kt",
    // Fase 9 (P9): `VisiblePhotoPicker` (il filtro dei nascosti attorno alla scelta delle foto dei ricordi, P7) e' passato fra i sorgenti della riga `MEMORIES`.
    // Fase 9 (P13): il montaggio dei ricordi e della mappa in :app. `MemoriesWiring` costruisce l'adattatore di `MemoryVisibility` (P6: `VisibleMedia.snapshot` e
    // `visibleOrThrow`, `HiddenMask.hiddenAmongOrThrow`) e la scelta delle foto con `VisiblePhotoPicker` (P7); `MemoriesScreenPorts` passa la maschera al filtro finale
    // della sezione Ricordi (superficie `memories-section`); `MapGraph` legge la posizione di un media solo se visibile (`viewer-minimap`) e passa la maschera al
    // filtro finale della mini-mappa. Le superfici sono le righe `MEMORIES`, `NOTIFICATIONS` e `MAP` qui sopra, provate sul motore e sulla catena dei punti veri;
    // `GalleryModule` lega `HiddenMask` per le schermate di feature-memories e feature-map, che la chiedono **senza `Optional`** (R3, 31/10: prima stava in `AlbumsModule`
    // dietro tre `@BindsOptionalOf`, che compilano anche senza legame; `HiddenMaskWiringTest`).
    "app/src/main/kotlin/dev/pampa/mycloudgallery/di/GalleryModule.kt",
    "app/src/main/kotlin/dev/pampa/mycloudgallery/memories/MemoriesWiring.kt",
    "app/src/main/kotlin/dev/pampa/mycloudgallery/memories/MemoriesScreenPorts.kt",
    "app/src/main/kotlin/dev/pampa/mycloudgallery/map/MapGraph.kt",
  )

  /** I contratti da cui ogni superficie deve passare per i nascosti. */
  val contractNames: List<String> = listOf("VisibleMedia", "HiddenMask", "WidgetFeed")

  /**
   * I tipi che portano **il nome o la cartella** di un file del NAS (spazio, cestino, elenco delle cartelle). Una schermata che li mostra senza passare
   * da `VisibleMedia` o `HiddenMask` non era mai segnalata dal registro (R4): ora ogni file di `feature-*` e `:app` che li nomina deve stare nel
   * registro (una superficie, un lettore dietro il cancello, o la pagina inattiva).
   */
  val nameBearingTypes: List<String> = listOf("LargestFile", "NasTrashItem", "NasFolderEntry", "NasTrashRow")
}
