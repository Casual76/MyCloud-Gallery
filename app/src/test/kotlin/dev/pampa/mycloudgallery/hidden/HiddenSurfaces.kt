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
  ALBUM_VIEWER("Viewer aperto da un album"),
  ADD_TO_ALBUM("Aggiungi ad album"),
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

/** Il lettore dei Nascosti (la pagina stessa) e chi li mostra dietro il cancello: leggono i nascosti **apposta**, solo dopo lo sblocco. */
internal class GatedReader(val id: String, val title: String, val proofs: List<String>)

internal object HiddenSurfaces {

  private const val LIB = "core-library/src/main/kotlin/dev/pampa/mycloudgallery/core/library"

  // I nomi del widget di prova sono scritti in due pezzi: `Phase5BArchitectureTest` vieta i nomi "Field" + "Probe" fuori da app/src/debug e testDebug,
  // e questo file e' in app/src/test (il widget di prova sta solo in debug, qui lo si nomina soltanto).
  /** Il file del contenuto del widget di prova (solo debug): e' la superficie. */
  const val WIDGET_CONTENT_FILE: String = "app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/widget/Field" + "ProbeWidgetContent.kt"

  /** La classe di test del widget di prova (solo testDebug). */
  const val WIDGET_TEST: String = "Field" + "ProbeWidgetTest"
  private const val ALBUMS = "core-albums/src/main/kotlin/dev/pampa/mycloudgallery/core/albums"

  /** Le superfici. L'ordine e' quello del §2.1. */
  val entries: List<SurfaceEntry> = listOf(
    SurfaceEntry(
      id = "library", title = "Libreria, filtri, intestazioni e conteggi", row = SpecRow.LIBRARY, owner = "Fase 3", backing = Backing.PRODUCTION,
      probe = HiddenProbes.library,
      alsoProvedBy = listOf("CatalogIntegrationTest", "TimelineIndexTest", "LibraryContractTest"),
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
      probe = HiddenProbes.nasFolders, live = true, alsoProvedBy = listOf("NasHiddenFilesTest", "NasHiddenRowScreenTest"),
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
    SurfaceEntry(
      id = "add-to-album", title = "Aggiungi ad album: la scelta dell'album e dei media", row = SpecRow.ADD_TO_ALBUM, owner = "P10", backing = Backing.PENDING,
      landedWhen = listOf("feature-albums/src/main/kotlin/dev/pampa/mycloudgallery/feature/albums/ui"),
    ),
  )

  /** Il lettore dei Nascosti e gli Eliminati di recente: mostrano i nascosti apposta, dietro il cancello (credenziale a ogni ingresso). */
  val gated: List<GatedReader> = listOf(
    GatedReader("hidden-page", "Nascosti", listOf("HiddenControllerTest", "HiddenScreenTest", "HiddenAccessGateTest", "HiddenLiveViewTest")),
    GatedReader("recently-deleted", "Eliminati di recente (con il segno sui nascosti)", listOf("RecentlyDeletedControllerTest", "RecentlyDeletedScreenTest")),
  )

  /**
   * I file che **legano** i contratti (`VisibleMedia`, `HiddenMask`, `WidgetFeed`) al catalogo senza mostrare niente: non sono superfici. Il registro li
   * ammette fra i file che nominano quei contratti.
   */
  val wiring: List<String> = listOf("app/src/main/kotlin/dev/pampa/mycloudgallery/gallery/GalleryGraph.kt")

  /** I contratti da cui ogni superficie deve passare per i nascosti. */
  val contractNames: List<String> = listOf("VisibleMedia", "HiddenMask", "WidgetFeed")
}
