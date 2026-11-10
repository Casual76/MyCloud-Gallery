package dev.pampa.mycloudgallery.feature.albums.tab

import dev.pampa.mycloudgallery.core.common.files.QuietWrites
import dev.pampa.mycloudgallery.core.albums.contract.AlbumList
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumOpenings
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbum
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbums
import dev.pampa.mycloudgallery.feature.albums.list.CreateAlbumResult
import dev.pampa.mycloudgallery.feature.albums.list.ManualAlbumCreator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// LA SCHEDA ALBUM (fase grafica, G2b): mette insieme gli album (`Albums.all`: i miei, i condivisi, gli smart, mai quelli lasciati), le voci speciali
// (le viste dell'indice delle raccolte-filtro) e l'ultima apertura, e ne fa l'elenco nell'ordine di [AlbumsTabOrder]. Non sa niente di Compose.
// Scrive solo l'ultima apertura e gli avvisi letti; la creazione di un album passa da `Albums.create` come nell'elenco. **Nessuna azione sui file.**

class AlbumsTabController(
  private val albums: Albums,
  private val shared: SharedAlbums,
  private val specials: SpecialAlbums,
  private val openings: AlbumOpenings,
  private val scope: CoroutineScope,
  worker: CoroutineDispatcher = Dispatchers.Default,
) {
  private val dismissed = MutableStateFlow<Set<SharedAlbumNotice>>(emptySet())

  private val summaries: Flow<Map<SpecialAlbum, SpecialSummary>> =
    combine(SpecialAlbum.entries.map { kind -> specials.view(kind).map { view -> kind to SpecialSummary.of(view) } }) { pairs -> pairs.toMap() }
      .distinctUntilChanged()

  private val notices: Flow<List<SharedAlbumNotice>> = combine(shared.notices, dismissed) { all, gone -> all.filter { it !in gone } }

  /**
   * Lo stato di adesso. Il primo valore si calcola subito dai valori correnti (la scheda si disegna al primo fotogramma con le voci vere, niente
   * salta dopo): costa la lettura delle cinque viste speciali, cioe' le loro mappe se nessuno le aveva ancora costruite (circa un millisecondo per
   * 100 mila elementi l'una, come `countsNow` delle Raccolte). I successivi fuori dal thread principale ([worker]).
   */
  val state: StateFlow<AlbumsTabState> =
    combine(albums.all, albums.status, summaries, openings.order, notices) { list, status, specials, order, shown ->
      compute(list, status, specials, order, shown)
    }
      .flowOn(worker)
      .stateIn(scope, SharingStarted.Eagerly, initial())

  private fun initial(): AlbumsTabState = compute(
    albums.all.value,
    albums.status.value,
    SpecialAlbum.entries.associateWith { SpecialSummary.of(specials.view(it).value) },
    openings.order.value,
    shared.notices.value,
  )

  /**
   * Finche' gli album non sono letti, o l'ultima apertura non e' letta ([order] null), la scheda mostra solo i fissi (che non dipendono da nessuna
   * delle due) e al posto del resto le copertine ferme: un ordine provvisorio si rimescolerebbe sotto gli occhi un attimo dopo.
   */
  private fun compute(
    list: AlbumList,
    status: AlbumsStatus,
    specials: Map<SpecialAlbum, SpecialSummary>,
    order: Map<String, Long>?,
    shown: List<SharedAlbumNotice>,
  ): AlbumsTabState {
    val (entries, pinned) = AlbumsTabOrder.arrange(list.albums, specials, order.orEmpty())
    val waiting = order == null || (list.albums.isEmpty() && status == AlbumsStatus.Loading)
    return AlbumsTabState.Ready(
      entries = if (waiting) entries.take(pinned) else entries,
      pinned = pinned,
      notices = shown,
      albumsPending = waiting,
    )
  }

  /**
   * L'utente ha aperto [entry] dalla scheda: si ricorda (l'ultima apertura). Un album si ricorda anche dalla sua pagina, da qualunque parte ci si
   * arrivi (`AlbumPageViewModel`): qui conta soprattutto per le voci speciali, la cui griglia sta in un altro modulo. Si scrive dopo [OPEN_SETTLE_MS],
   * a pagina gia' entrata: la scheda, che resta disegnata sotto la pagina che arriva, non si rimescola durante la transizione.
   */
  fun opened(entry: AlbumsTabEntry) {
    scope.launch {
      delay(OPEN_SETTLE_MS)
      QuietWrites.attempt("ultima apertura") { openings.opened(entry.key) }
    }
  }

  private val creator = ManualAlbumCreator(albums)

  /** Vero mentre "Crea" sta salvando (SOL-11): il foglio spegne il tasto. */
  val creating: StateFlow<Boolean> get() = creator.saving

  /**
   * Crea un album personale (come l'elenco): per uno smart le regole le porta l'editor, che parte dal nome. Un invio alla volta (SOL-11), e con la memoria piena [CreateAlbumResult.NotSaved]
   * invece della chiusura dell'app (SOL-10).
   */
  suspend fun createManual(title: String): CreateAlbumResult = creator.create(title)

  /** L'avviso e' stato letto: non torna. */
  fun dismissNotice(notice: SharedAlbumNotice) {
    dismissed.update { it + notice }
    scope.launch { QuietWrites.attempt("avviso letto") { shared.dismissNotice(notice) } }
  }

  companion object {
    /** Il tempo dopo cui un'apertura conta: la transizione della pagina e' finita (e un tocco sbagliato, chiuso subito, non conta). */
    const val OPEN_SETTLE_MS: Long = 700L
  }
}
