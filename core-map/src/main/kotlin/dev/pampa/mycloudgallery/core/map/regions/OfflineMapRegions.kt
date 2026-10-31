package dev.pampa.mycloudgallery.core.map.regions

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.map.contract.BudgetLimit
import dev.pampa.mycloudgallery.core.map.contract.DownloadBudget
import dev.pampa.mycloudgallery.core.map.contract.DownloadStart
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapNetworkState
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.contract.OpenFreeMapLimits
import dev.pampa.mycloudgallery.core.map.contract.PlaceCenters
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadConfirmation
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadScope
import dev.pampa.mycloudgallery.core.map.contract.RegionEngine
import dev.pampa.mycloudgallery.core.map.contract.RegionEngineFailure
import dev.pampa.mycloudgallery.core.map.contract.RegionEngineListener
import dev.pampa.mycloudgallery.core.map.contract.RegionEstimate
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionLimits
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.core.map.contract.RegionStop
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import dev.pampa.mycloudgallery.core.map.contract.TileMath
import java.io.IOException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * LE REGIONI OFFLINE (Fase 9, P5; docs/fase9-progetto.md §2.9, D26, E-MAP11, E-MAP12, E-MAP20; decisione dell'utente del 31/10: **dentro i limiti di OpenFreeMap**). Il gestore: catalogo, stima,
 * regole di rete, spazio e tetti, **un solo scaricamento alla volta**, stato su disco ([RegionStateStore]), ripresa all'apertura, copertura per il cancello. Lo scaricamento vero e' del
 * [RegionEngine] (`OfflineManager` di MapLibre in feature-map); le richieste passano dal cancello con il conto dello scaricamento ([DownloadBudget]: 3.000 tessere, 50 MB sul filo, al piu' 4
 * richieste insieme) che qui si crea e qui si ascolta.
 *
 *  - **Prima di partire** ([download]): conferma del foglio valida (10 minuti, della stessa regione), nessun altro scaricamento in corso, il piano nei limiti ([RegionPlanner.plan]: non offerta =
 *    [DownloadStart.TooLarge]), il tetto totale (1 GB), lo spazio (stima + 1 GiB libero), la rete (Wi-Fi; dati mobili solo con "Solo sul Wi-Fi" spento **e** la seconda conferma; mai roaming).
 *  - **Durante**: se la rete non e' piu' permessa (roaming, nessuna rete, dati mobili senza conferma o con "Solo sul Wi-Fi" acceso) lo scaricamento si ferma ([RegionStop.NETWORK]); se la mappa
 *    dettagliata si spegne si ferma ([RegionStop.MAP_OFF], E-MAP20, riprende solo con un tocco); se il conto raggiunge un tetto si ferma ([RegionStop.LIMIT_TILES], [RegionStop.LIMIT_BYTES]),
 *    resta parziale con il motivo e **non riparte** (ne' da sola ne' con un tocco: una regione e' uno scaricamento, mai spezzato in pezzi piccoli per aggirare il limite).
 *  - **Dopo la morte del processo** ([resumeInterrupted], all'apertura dell'app): le regioni "in scaricamento" nello stato su disco sono parziali ([RegionStop.INTERRUPTED]) e riprendono dal conto di
 *    prima, sul Wi-Fi, una alla volta. Nessun lavoro in background.
 *  - **Aggiornamento solo a mano**: dopo 6 mesi una regione completa diventa [RegionStatus.NEEDS_UPDATE] (la pagina lo propone); "Aggiorna" e' un nuovo [download] con una nuova conferma.
 *  - Una regione **completa** copre le sue tessere ([covers]) anche da "da aggiornare"; una parziale no.
 * Nei log solo numeri e tipi: mai zone, riquadri o tessere. [today] e' il giorno (`aaaammgg`) dalla porta di :app: qui nessun orologio del telefono.
 */
class OfflineMapRegions(
  private val engine: RegionEngine,
  private val store: RegionStateStore,
  private val network: MapNetworkState,
  private val settings: MapSettings,
  private val places: PlaceCenters,
  private val freeBytes: () -> Long,
  private val nowMs: () -> Long,
  private val today: () -> Int,
  private val scope: CoroutineScope,
  private val planOf: (RegionSpec) -> RegionEstimate = RegionPlanner::plan,
  private val catalog: List<RegionSpec> = RegionCatalog.specs(),
) : MapRegions {

  /** Lo scaricamento in corso: la regione, il conto, l'ambito delle tessere per il cancello e chi guarda la rete. */
  private class Active(val id: RegionId, val box: GeoBox, val maxZoom: Int, val mobile: Boolean, val budget: DownloadBudget) {
    val scope = RegionDownloadScope(
      includes = { tile -> tile.z in 0..maxZoom && TileMath.touches(tile, box, 0, maxZoom) },
      mobileConfirmed = mobile,
      budget = budget,
    )
    var watcher: Job? = null
  }

  private val lock = Any()
  private val state = MutableStateFlow(loadState())
  override val regions: StateFlow<List<RegionState>> = state

  @Volatile private var active: Active? = null

  @Volatile private var closed = false

  @Volatile private var chain = false

  private var lastSavedMs = Long.MIN_VALUE

  // --- il catalogo e la stima -------------------------------------------------------------------------------------------------

  override fun catalog(): List<RegionSpec> = catalog

  override fun around(placeId: Int, radius: RegionRadius): RegionSpec? =
    places.center(placeId)?.let { RegionSpec.AroundPlace(placeId, radius, it, places.urban(placeId), places.name(placeId).orEmpty()) }

  override suspend fun estimate(spec: RegionSpec): RegionEstimate = planOf(spec)

  // --- la copertura e il cancello ---------------------------------------------------------------------------------------------

  /** Solo una regione **completa** (anche "da aggiornare") copre le sue tessere, ai livelli che ha scaricato: mai una parziale (le sue tessere arrivate le serve il database di MapLibre). */
  override fun covers(tile: TileKey): Boolean = state.value.any {
    (it.status == RegionStatus.READY || it.status == RegionStatus.NEEDS_UPDATE) && TileMath.touches(tile, it.spec.box, 0, it.maxZoom)
  }

  override fun downloadScope(): RegionDownloadScope? = active?.scope

  // --- scaricare, riprendere, fermare, togliere -------------------------------------------------------------------------------

  override suspend fun download(spec: RegionSpec, confirmation: RegionDownloadConfirmation): DownloadStart {
    if (closed || confirmation.region != spec.id || !confirmation.validAt(nowMs())) return DownloadStart.Expired
    if (active != null) return DownloadStart.Busy
    val existing = state.value.firstOrNull { it.spec.id == spec.id }
    if (existing?.stop?.terminal == true) return DownloadStart.TooLarge
    // Una regione parziale riprende dalle tessere arrivate (stesso conto); una completa si rifa da capo ("Aggiorna", a mano, con questa nuova conferma).
    val resumes = existing?.status == RegionStatus.PARTIAL
    val plan = planOf(spec)
    if (!resumes && !plan.offered) return DownloadStart.TooLarge
    val used = if (resumes) existing!!.bytes else 0L
    val others = state.value.filter { it.spec.id != spec.id }.sumOf { it.bytes }
    if (others + plan.bytes > RegionLimits.TOTAL_BYTES) return DownloadStart.TooLarge
    val toDownload = (plan.bytes - used).coerceAtLeast(0)
    val needed = toDownload + RegionLimits.FREE_RESERVE_BYTES
    if (freeBytes() < needed) return DownloadStart.NoSpace(needed)
    networkVerdict(confirmation.mobile, toDownload)?.let { return it }
    return begin(spec, existing?.takeIf { resumes }, replacing = existing != null && !resumes, plan, confirmation.mobile)
  }

  override suspend fun resume(id: RegionId): DownloadStart {
    if (closed) return DownloadStart.Expired
    val region = state.value.firstOrNull { it.spec.id == id } ?: return DownloadStart.Expired
    if (region.status != RegionStatus.PARTIAL) return DownloadStart.Started
    if (region.stop?.terminal == true) return DownloadStart.TooLarge
    if (active != null) return DownloadStart.Busy
    val remaining = (planOf(region.spec).bytes - region.bytes).coerceAtLeast(0)
    val needed = remaining + RegionLimits.FREE_RESERVE_BYTES
    if (freeBytes() < needed) return DownloadStart.NoSpace(needed)
    // Con un tocco sui dati mobili serve la seconda conferma con la dimensione: la riprende `download` con la conferma del foglio.
    networkVerdict(mobileConfirmed = false, bytes = remaining)?.let { return it }
    return begin(region.spec, region, replacing = false, planOf(region.spec), mobile = false)
  }

  override suspend fun resumeInterrupted() {
    if (closed) return
    refreshAges()
    chain = true
    resumeNext()
  }

  override suspend fun pauseAll() {
    val a = active ?: return
    stopActive(a, RegionStop.MAP_OFF, clearScope = true)
  }

  override suspend fun delete(id: RegionId) {
    val a = active
    if (a != null && a.id == id) {
      synchronized(lock) { if (active === a) active = null }
      a.watcher?.cancel()
    }
    try {
      engine.delete(id.code)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "regione non tolta dal database (${e.javaClass.simpleName})" }
    }
    update { list -> list.filter { it.spec.id != id } }
    save(force = true)
  }

  /** L'azzeramento (D37): ferma lo scaricamento, toglie tutte le regioni dal database e dallo stato, e da qui **non scrive piu'** niente. */
  override suspend fun clear() {
    closed = true
    chain = false
    val a = active
    synchronized(lock) { active = null }
    a?.watcher?.cancel()
    try {
      engine.deleteAll()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "regioni non tolte dal database (${e.javaClass.simpleName})" }
    }
    state.value = emptyList()
    store.delete()
  }

  // --- dentro: partenza, eventi del motore, arresti ---------------------------------------------------------------------------

  /** Il verdetto di rete per partire: Wi-Fi si'; dati mobili solo con "Solo sul Wi-Fi" spento e la seconda conferma; mai roaming o senza rete. Null = si puo'. */
  private fun networkVerdict(mobileConfirmed: Boolean, bytes: Long): DownloadStart? = when (network.current.value) {
    MapNetwork.WIFI -> null
    MapNetwork.MOBILE -> when {
      settings.wifiOnly.value -> DownloadStart.NeedsWifi
      !mobileConfirmed -> DownloadStart.NeedsMobileConfirmation(bytes)
      else -> null
    }
    MapNetwork.ROAMING, MapNetwork.NONE -> DownloadStart.NeedsWifi
  }

  private fun networkAllowed(net: MapNetwork, mobile: Boolean, wifiOnly: Boolean): Boolean = when (net) {
    MapNetwork.WIFI -> true
    MapNetwork.MOBILE -> mobile && !wifiOnly
    MapNetwork.ROAMING, MapNetwork.NONE -> false
  }

  private fun begin(spec: RegionSpec, existing: RegionState?, replacing: Boolean, plan: RegionEstimate, mobile: Boolean): DownloadStart {
    val maxZoom = existing?.maxZoom ?: plan.maxZoom
    val budget = DownloadBudget(
      startTiles = (existing?.completedTiles ?: 0L).coerceAtMost(OpenFreeMapLimits.MAX_TILES.toLong() - 1).toInt(),
      startBytes = existing?.bytes ?: 0L,
    )
    if (budget.limit != null) return DownloadStart.TooLarge
    val a = Active(spec.id, spec.box, maxZoom, mobile, budget)
    synchronized(lock) {
      if (active != null) return DownloadStart.Busy
      active = a
    }
    val fresh = existing == null
    val base = RegionState(
      spec = spec,
      status = RegionStatus.DOWNLOADING,
      completedTiles = if (fresh) 0 else existing!!.completedTiles,
      totalTiles = if (fresh) plan.tiles else existing!!.totalTiles,
      bytes = if (fresh) 0 else existing!!.bytes,
      completedDay = null,
      maxZoom = maxZoom,
      stop = null,
    )
    update { list -> list.filter { it.spec.id != spec.id } + base }
    save(force = true)
    budget.onExhausted = { limit -> onExhausted(a, limit) }
    a.watcher = scope.launch { watch(a) }
    scope.launch {
      try {
        // "Aggiorna": la regione completa si rifa da capo (nuova conferma, nuovo conto); una parziale riprende dalle tessere arrivate.
        if (replacing) engine.delete(spec.id.code)
        engine.start(spec.id.code, spec.box, RegionLimits.MIN_ZOOM, maxZoom, listenerOf(a))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "scaricamento non partito (${e.javaClass.simpleName})" }
        stopActive(a, RegionStop.INTERRUPTED, clearScope = true)
      }
    }
    return DownloadStart.Started
  }

  /** Guarda la rete, "Solo sul Wi-Fi" e la mappa dettagliata finche' lo scaricamento dura: se la rete non e' piu' permessa o la mappa si spegne, si ferma. */
  private suspend fun watch(a: Active): Unit = coroutineScope {
    var wasOn = settings.online.value is MapOnline.On
    launch {
      combine(network.current, settings.wifiOnly) { net, wifiOnly -> networkAllowed(net, a.mobile, wifiOnly) }.collect { ok ->
        if (!ok && active === a) stopActive(a, RegionStop.NETWORK, clearScope = true)
      }
    }
    settings.online.collect { online ->
      val on = online is MapOnline.On
      if (wasOn && !on && active === a) stopActive(a, RegionStop.MAP_OFF, clearScope = true)
      wasOn = on
    }
  }

  private fun listenerOf(a: Active) = object : RegionEngineListener {
    override fun onProgress(completedTiles: Long, requiredTiles: Long) {
      if (active !== a) return
      update { list ->
        list.map {
          if (it.spec.id != a.id) it else it.copy(
            completedTiles = maxOf(it.completedTiles, completedTiles),
            totalTiles = if (requiredTiles > it.totalTiles) requiredTiles else it.totalTiles,
            bytes = maxOf(it.bytes, a.budget.bytes),
          )
        }
      }
      save(force = false)
    }

    override fun onComplete() {
      // La regione e' completa: vince su ogni altro motivo (anche un tetto raggiunto sull'ultima tessera, o una pausa un attimo prima). Se nel frattempo e' stata tolta, niente.
      synchronized(lock) { if (active === a) active = null }
      a.watcher?.cancel()
      var known = false
      update { list ->
        list.map {
          if (it.spec.id != a.id) it else {
            known = true
            it.copy(status = RegionStatus.READY, stop = null, completedTiles = it.totalTiles, bytes = maxOf(it.bytes, a.budget.bytes), completedDay = today())
          }
        }
      }
      if (!known) return
      save(force = true)
      if (chain) scope.launch { resumeNext() }
    }

    override fun onFailure(failure: RegionEngineFailure) {
      if (active !== a) return
      val stop = if (failure == RegionEngineFailure.SERVER) RegionStop.SERVER else RegionStop.INTERRUPTED
      scope.launch { stopActive(a, stop, clearScope = true) }
    }
  }

  /** Il conto ha raggiunto un tetto di OpenFreeMap: lo scaricamento si ferma (la regione resta parziale con il motivo) e il cancello rifiuta le tessere (`LIMIT`) finche' il motore non si e' fermato. */
  private fun onExhausted(a: Active, limit: BudgetLimit) {
    if (active !== a) return
    val stop = if (limit == BudgetLimit.BYTES) RegionStop.LIMIT_BYTES else RegionStop.LIMIT_TILES
    scope.launch { stopActive(a, stop, clearScope = false) }
  }

  private suspend fun stopActive(a: Active, stop: RegionStop, clearScope: Boolean) {
    synchronized(lock) {
      if (active !== a) return
      if (clearScope) active = null
    }
    a.watcher?.cancel()
    update { list ->
      list.map {
        if (it.spec.id != a.id || it.status == RegionStatus.READY) {
          it
        } else {
          // Un tetto di OpenFreeMap gia' raggiunto resta il motivo (una pausa dopo non lo cambia).
          it.copy(status = RegionStatus.PARTIAL, stop = if (it.stop?.terminal == true) it.stop else stop, bytes = maxOf(it.bytes, a.budget.bytes))
        }
      }
    }
    save(force = true)
    try {
      engine.stop(a.id.code)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "scaricamento non fermato (${e.javaClass.simpleName})" }
    } finally {
      synchronized(lock) { if (active === a) active = null }
    }
  }

  /** Riprende la prima regione interrotta che riprende da sola (Wi-Fi, spazio), se nessuna e' in corso. */
  private suspend fun resumeNext() {
    if (closed || active != null || network.current.value != MapNetwork.WIFI) return
    val next = state.value.firstOrNull { it.status == RegionStatus.PARTIAL && it.stop?.resumesAtOpen == true } ?: return
    val remaining = (planOf(next.spec).bytes - next.bytes).coerceAtLeast(0)
    if (freeBytes() < remaining + RegionLimits.FREE_RESERVE_BYTES) {
      update { list -> list.map { if (it.spec.id == next.spec.id) it.copy(stop = RegionStop.SPACE) else it } }
      save(force = true)
      return
    }
    begin(next.spec, next, replacing = false, planOf(next.spec), mobile = false)
  }

  // --- stato su disco ---------------------------------------------------------------------------------------------------------

  /** L'elenco salvato, con le regioni che erano "in scaricamento" ormai parziali (il processo e' morto: E-MAP11) e le completate da oltre 6 mesi "da aggiornare". */
  private fun loadState(): List<RegionState> = ageOf(
    store.load().map { if (it.status == RegionStatus.DOWNLOADING) it.copy(status = RegionStatus.PARTIAL, stop = RegionStop.INTERRUPTED) else it },
  )

  /** Le regioni complete da oltre 6 mesi diventano "da aggiornare" (la pagina lo propone; solo a mano). Si fa al caricamento e a ogni apertura dell'app ([resumeInterrupted]). */
  fun refreshAges() = update { ageOf(it) }

  private fun ageOf(list: List<RegionState>): List<RegionState> = list.map {
    if (it.status == RegionStatus.READY && olderThanUpdateAge(it.completedDay)) it.copy(status = RegionStatus.NEEDS_UPDATE) else it
  }

  private fun olderThanUpdateAge(day: Int?): Boolean {
    if (day == null) return false
    return try {
      val done = LocalDate.of(day / 10_000, (day / 100) % 100, day % 100)
      val now = today().let { LocalDate.of(it / 10_000, (it / 100) % 100, it % 100) }
      ChronoUnit.DAYS.between(done, now) > RegionLimits.UPDATE_AFTER_DAYS
    } catch (_: RuntimeException) {
      false
    }
  }

  private fun update(change: (List<RegionState>) -> List<RegionState>) {
    synchronized(lock) { if (!closed) state.value = change(state.value) }
  }

  /** Salva lo stato; i progressi al piu' una volta al secondo, i cambi di stato sempre. Un guasto di scrittura non ferma niente (lo stato resta in memoria). */
  private fun save(force: Boolean) {
    if (closed) return
    val now = nowMs()
    if (!force && lastSavedMs != Long.MIN_VALUE && now - lastSavedMs < SAVE_EVERY_MS) return
    lastSavedMs = now
    try {
      store.save(state.value)
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "stato delle regioni non salvato (${e.javaClass.simpleName})" }
    }
  }

  private companion object {
    const val TAG = "MapRegions"
    const val SAVE_EVERY_MS = 1_000L
  }
}
