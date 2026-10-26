package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidates
import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.albums.contract.TripProposals
import dev.pampa.mycloudgallery.core.albums.contract.TripReport
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.search.contract.Today
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// VIAGGI ED EVENTI RILEVATI (docs/fase7-progetto.md §2.3, D18-D20; P7). Il `Trips` vero: dalle foto **proprie** e **visibili** deduce la casa
// (solo in locale, `home_estimate`), i viaggi (giorni lontani da casa) e gli eventi (molte foto in poche ore), li propone nella sezione Viaggi
// e con un tocco ("Crea album") ne fa un album smart; "Non mi interessa" e' un dato personale sincronizzato (`trip_dismissed`).
//
// **Cosa NON fa e cosa NON espone**:
//  - nessuna coordinata fuori da trips/: le API pubbliche sono solo `Trips` e `AwayFromHome` ("questo giorno e' lontano da casa si'/no" e la
//    distanza arrotondata a 10 km); i log hanno solo numeri e codici (`TripLog`); la casa non entra mai in un titolo, in un luogo, in un album;
//  - nessun nascosto (D22, E-H7): la rilevazione parte da `VisibleMedia`, conteggi e copertine ripassano dal filtro finale (`TripVisibility`);
//  - nessuna azione sui file: non sposta, non copia, non cancella e non scrive sul NAS (`core-albums` non vede `core-nasops` ne' `core-nas`).

/**
 * L'unica cosa che di "casa" esce da trips/ (D18, §1.1): per un giorno, se e' lontano da casa e a che distanza (arrotondata a 10 km). Mai le
 * coordinate della casa, mai quelle delle foto. Si risponde con la casa **di oggi** (gli ultimi 12 mesi): per i giorni di una casa lasciata da
 * tempo la risposta puo' non essere quella di allora (la rilevazione dei viaggi, invece, usa la casa di ogni epoca).
 */
interface AwayFromHome {
  /** Il giorno locale [day] e' lontano da casa? Null se non si sa: nessuna casa stimata o nessuna foto propria geolocalizzata quel giorno. */
  suspend fun isAway(day: LocalDate): Boolean?

  /** La distanza mediana da casa (o dal luogo abituale piu' vicino) delle foto di quel giorno, arrotondata a 10 km; null come sopra. */
  suspend fun distanceKm(day: LocalDate): Int?
}

class CatalogTrips internal constructor(
  candidates: AlbumCandidates,
  private val visibility: TripVisibility,
  private val store: TripStore,
  private val dismissals: DismissedTrips,
  private val albums: Albums,
  private val titles: TripTitles,
  private val today: Today,
  private val clocks: AlbumClocks,
  scope: CoroutineScope,
  private val refreshDelayMs: Long = REFRESH_DELAY_MS,
) : Trips, AwayFromHome {

  constructor(
    database: GalleryDatabase,
    candidates: AlbumCandidates,
    visible: VisibleMedia,
    mask: HiddenMask,
    personal: PersonalStore,
    albums: Albums,
    places: PlaceNames,
    today: Today,
    clocks: AlbumClocks,
    scope: CoroutineScope,
  ) : this(
    candidates, TripVisibility(visible, mask), RoomTripStore(database), PersonalDismissedTrips(personal, scope), albums, TripTitles(places),
    today, clocks, scope,
  )

  private val reader = TripReader(candidates, visibility)
  private val lock = Mutex()

  /** Le proposte conservate (anche quelle gia' diventate album) e quelle ricontrollate con la visibilita' di adesso. */
  private var rows: List<StoredProposal> = emptyList()
  @Volatile private var checked: List<TripProposal> = emptyList()
  @Volatile private var homeKnown = false

  private val state = MutableStateFlow(TripProposals(emptyList(), homeKnown = false))

  override val proposals: StateFlow<TripProposals> = state.asStateFlow()

  init {
    scope.launch { loadQuietly() }
    scope.launch { dismissals.dismissed.collect { publish() } }
    scope.launch {
      // Si nasconde o si aggiunge qualcosa: conteggi e copertine si ricontrollano (D22). Un attimo di calma per i gesti a raffica.
      visibility.snapshot.collectLatest {
        delay(refreshDelayMs)
        refreshQuietly()
      }
    }
  }

  /** Rilegge le proposte conservate (all'avvio) e le ricontrolla con la visibilita' di adesso. */
  suspend fun load() {
    lock.withLock {
      rows = store.proposals()
      homeKnown = store.anchors() != null
      recheck()
    }
  }

  /** Ricontrolla conteggi e copertine con la visibilita' di adesso: un media nascosto un attimo fa non compare ne' si conta (D22). */
  suspend fun refresh() {
    lock.withLock { recheck() }
  }

  // --- Trips --------------------------------------------------------------------------------------------------------------------------

  override suspend fun detect(budget: Duration): TripReport = lock.withLock {
    val started = clocks.monotonicMs()
    val deadline = started + budget.inWholeMilliseconds
    try {
      val shots = reader.mine(expired = { clocks.monotonicMs() > deadline })
      if (shots == null) {
        TripLog.interrupted()
        return@withLock TripReport(rows.count { it.kind == TripKind.TRIP }, rows.count { it.kind == TripKind.EVENT }, homeKnown, incomplete = true)
      }
      // Il conto e' tutto CPU (qualche centinaio di millisecondi su una Libreria vera): fuori dal thread di chi chiama.
      val day = today.date()
      val detection = withContext(Dispatchers.Default) { TripDetector.detect(shots, day) }
      val now = clocks.nasNowMs() ?: 0L
      store.saveAnchors(detection.current, now)
      rows = merged(detection, rows, now)
      store.replaceProposals(rows)
      homeKnown = detection.current != null
      if (!homeKnown && shots.isNotEmpty()) TripLog.noGps()
      recheck()
      TripLog.detected(detection.trips, detection.events, homeKnown, clocks.monotonicMs() - started)
      TripReport(detection.trips, detection.events, homeKnown, incomplete = false)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      TripLog.failed(e)
      throw e
    }
  }

  /** Le proposte nuove al posto di quelle di prima; l'album nato da una proposta si conserva (stesso tipo e stessi giorni) e non si ripropone. */
  private fun merged(detection: Detection, before: List<StoredProposal>, now: Long): List<StoredProposal> {
    val accepted = before.filter { it.album != null }
    val fresh = detection.items.map { item ->
      val title = if (item.kind == TripKind.TRIP) titles.trip(item.range, item.places) else titles.event(item.range, item.places)
      val album = accepted.firstOrNull { it.kind == item.kind && it.days == item.range }?.album
      StoredProposal(item.id, item.kind, item.range, title, item.members.size, CoverPicker.pick(item.coverPool).media, item.places.ids, album, now)
    }
    val kept = accepted.filter { a -> fresh.none { it.kind == a.kind && it.days == a.days } }
    return fresh + kept
  }

  override suspend fun accept(id: TripId): TripAcceptOutcome = lock.withLock {
    val row = rows.firstOrNull { it.id == id } ?: return@withLock TripAcceptOutcome.NotFound
    row.album?.let { return@withLock TripAcceptOutcome.AlreadyAlbum(it) }
    if (id in dismissals.dismissed.value) return@withLock TripAcceptOutcome.NotFound
    val pool = reader.mine(days = row.days, finalMask = true).orEmpty()
    if (pool.isEmpty()) return@withLock TripAcceptOutcome.NotFound
    val rules = SmartRules(SmartPeriod.Ranges(listOf(row.days)), placeRuleOf(row, pool), text = null)
    val created = albums.create(row.label, AlbumKind.SMART, rules) as? CreateOutcome.Created ?: return@withLock TripAcceptOutcome.NotFound
    store.markAccepted(id, created.id)
    rows = rows.map { if (it.id == id) it.copy(album = created.id) else it }
    checked = checked.filter { it.id != id }
    publish()
    TripAcceptOutcome.Created(created.id)
  }

  override suspend fun dismiss(id: TripId) {
    dismissals.dismiss(id)
    publish()
  }

  /**
   * Il luogo dell'album di un viaggio: il cerchio che copre le foto lontane da casa, con i luoghi della proposta. Un **evento** non ha luogo:
   * potrebbe essere a casa, e le coordinate di casa non devono finire in un album (che viaggia nei dati personali). Un viaggio senza luoghi
   * noti al dataset, o senza foto geolocalizzate lontane da casa, ha solo il periodo.
   */
  private suspend fun placeRuleOf(row: StoredProposal, pool: List<AlbumCandidate>): PlaceRule? {
    if (row.kind != TripKind.TRIP || row.placeIds.isEmpty()) return null
    val anchors = store.anchors() ?: return null
    val area = TripDetector.areaOf(pool, anchors) ?: return null
    val away = pool.filter { c -> c.gps?.let { TripGeometry.usable(it.latitude, it.longitude) && !anchors.isNear(it.latitude, it.longitude) } == true }
    val label = titles.placeLabel(PlaceStats.of(away)).ifEmpty { VIAGGIO }
    return PlaceRule(row.placeIds, GeoPoint(area.lat, area.lon), area.radiusKm, label, PlaceScope.AROUND)
  }

  // --- cio' che si mostra -------------------------------------------------------------------------------------------------------------

  private suspend fun recheck() {
    val shown = ArrayList<TripProposal>()
    for (row in rows) {
      if (row.album != null) continue
      val pool = reader.mine(days = row.days, finalMask = true).orEmpty()
      if (pool.isEmpty()) continue
      val cover = pool.firstOrNull { it.media == row.cover } ?: CoverPicker.pick(pool)
      shown += TripProposal(row.id, row.kind, row.days, row.label, pool.size, cover.media, row.placeIds)
    }
    checked = shown
    publish()
    TripLog.shown(shown.size)
  }

  private fun publish() {
    val dismissed = dismissals.dismissed.value
    state.value = TripProposals(checked.filter { it.id !in dismissed }, homeKnown)
  }

  private suspend fun loadQuietly() {
    try {
      load()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      TripLog.failed(e)
    }
  }

  private suspend fun refreshQuietly() {
    try {
      refresh()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      TripLog.failed(e)
    }
  }

  // --- AwayFromHome ---------------------------------------------------------------------------------------------------------------------

  override suspend fun isAway(day: LocalDate): Boolean? = awayOn(day)?.let { (anchors, km) -> km > anchors.farKm }

  override suspend fun distanceKm(day: LocalDate): Int? = awayOn(day)?.let { (_, km) -> TripGeometry.roundedKm(km) }

  /** La casa di oggi e la distanza mediana delle foto proprie geolocalizzate del giorno; null se manca l'una o le altre. */
  private suspend fun awayOn(day: LocalDate): Pair<Anchors, Double>? {
    val anchors = store.anchors() ?: return null
    val shots = reader.mine(days = LocalDayRange(day, day), finalMask = true).orEmpty()
    val km = shots.mapNotNull { c -> c.gps?.takeIf { TripGeometry.usable(it.latitude, it.longitude) } }
      .map { anchors.nearestKm(it.latitude, it.longitude) }.toDoubleArray().also { it.sort() }
    return if (km.isEmpty()) null else anchors to TripGeometry.median(km)
  }
}

/** Un attimo di calma prima di ricontrollare le proposte dopo un cambiamento della Libreria (i gesti a raffica), e il nome di un luogo che non c'e'. */
private const val REFRESH_DELAY_MS = 1_000L
private const val VIAGGIO = "Viaggio"
