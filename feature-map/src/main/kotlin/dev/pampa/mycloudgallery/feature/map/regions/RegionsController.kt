package dev.pampa.mycloudgallery.feature.map.regions

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.map.contract.DownloadStart
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.PlaceHit
import dev.pampa.mycloudgallery.core.map.contract.PlaceSearch
import dev.pampa.mycloudgallery.core.map.contract.RegionEstimate
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionKind
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ZONE SENZA INTERNET: IL CONTROLLORE (Fase 9, P5; docs/fase9-progetto.md §2.9, D26, E-MAP12; decisione dell'utente del 31/10: dentro i limiti di OpenFreeMap). Lega la pagina a `MapRegions`: il
// catalogo con le stime (calcolate fuori dal thread principale), le zone scaricate con l'avanzamento (dal flusso delle regioni), la ricerca di un luogo per nome (con un attimo di calma fra due
// lettere), i fogli. **Uno scaricamento parte solo da un tocco sul foglio**: la conferma (`RegionDownloadConfirmation`) nasce in `RegionDownloadSheet`, dentro `onConfirm`, e `MapRegions` rifiuta
// la conferma scaduta o di un'altra zona. Le regole di rete, di spazio e dei limiti sono di `MapRegions`: qui si traduce l'esito in un foglio o in una riga sopra la pagina.

internal class RegionsController(
  private val regions: MapRegions,
  private val search: PlaceSearch,
  private val scope: CoroutineScope,
  /** L'ora **monotona** in millisecondi (mai l'orologio del telefono): la conferma vale 10 minuti da qui. */
  private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
  private val compute: CoroutineDispatcher = Dispatchers.Default,
  /** La calma fra due lettere della ricerca (tempo del `CoroutineScope`: virtuale nei test). */
  private val searchCalmMs: Long = SEARCH_CALM_MS,
) {

  private class Local(
    val estimates: Map<RegionId, RegionEstimate> = emptyMap(),
    val sheet: RegionSheet? = null,
    val message: RegionMessage? = null,
    val around: AroundState? = null,
  )

  private val local = MutableStateFlow(Local())
  private var searchJob: Job? = null

  val ui: StateFlow<RegionsUi> = combine(regions.regions, local) { mine, l -> build(mine, l) }.stateIn(scope, SharingStarted.Eagerly, build(regions.regions.value, local.value))

  init {
    // Le stime del catalogo si calcolano fuori dal thread principale (una ventina di voci, qualche livello l'una).
    scope.launch(compute) {
      val all = regions.catalog().associate { it.id to regions.estimate(it) }
      local.update { Local(all, it.sheet, it.message, it.around) }
    }
  }

  private fun build(mine: List<RegionState>, l: Local): RegionsUi {
    val byId = mine.associateBy { it.spec.id }
    fun offer(spec: RegionSpec) = RegionOffer(spec, l.estimates[spec.id], byId[spec.id])
    val catalog = regions.catalog()
    val around = l.around?.let { a ->
      a.copy(radii = a.radii.map { it.copy(present = byId[it.spec.id]) })
    }
    return RegionsUi(
      downloaded = mine.sortedWith(compareBy({ it.status == RegionStatus.READY || it.status == RegionStatus.NEEDS_UPDATE }, { it.spec.kind != RegionKind.AROUND_PLACE })),
      italian = catalog.filter { it.kind == RegionKind.ITALIAN_REGION }.map(::offer),
      countries = catalog.filter { it.kind == RegionKind.COUNTRY }.map(::offer),
      usedBytes = mine.sumOf { it.bytes },
      sheet = l.sheet?.let { refreshed(it, byId) },
      message = l.message,
      around = around,
    )
  }

  /** Il foglio di gestione segue lo stato vivo della zona (avanzamento, completa); se la zona non c'e' piu', il foglio si chiude. */
  private fun refreshed(sheet: RegionSheet, byId: Map<RegionId, RegionState>): RegionSheet? = when (sheet) {
    is RegionSheet.Manage -> byId[sheet.state.spec.id]?.let { sheet.copy(state = it) }
    is RegionSheet.Confirm -> sheet
  }

  // --- il catalogo e le zone scaricate ----------------------------------------------------------------------------------------

  /** Un tocco su una voce del catalogo: una zona gia' scaricata si gestisce; una troppo grande dice perche'; altrimenti il foglio di conferma. */
  fun onCatalog(spec: RegionSpec) {
    val present = regions.regions.value.firstOrNull { it.spec.id == spec.id }
    if (present != null) {
      local.update { Local(it.estimates, RegionSheet.Manage(present), null, it.around) }
      return
    }
    openConfirm(spec, SheetPurpose.DOWNLOAD)
  }

  fun onDownloaded(id: RegionId) {
    val state = regions.regions.value.firstOrNull { it.spec.id == id } ?: return
    local.update { Local(it.estimates, RegionSheet.Manage(state), null, it.around) }
  }

  private fun openConfirm(spec: RegionSpec, purpose: SheetPurpose) {
    scope.launch {
      val estimate = local.value.estimates[spec.id] ?: withContext(compute) { regions.estimate(spec) }
      local.update {
        if (!estimate.offered) Local(it.estimates, null, RegionMessage.TooLarge, it.around) else Local(it.estimates, RegionSheet.Confirm(spec, estimate, purpose), null, it.around)
      }
    }
  }

  fun onDismiss() {
    local.update { Local(it.estimates, null, it.message, it.around) }
  }

  fun onDismissMessage() {
    local.update { Local(it.estimates, it.sheet, null, it.around) }
  }

  /** Il tocco su "Scarica", "Aggiorna", "Riprendi" o "Scarica con i dati mobili": qui nasce la conferma del foglio, con la stima mostrata e l'ora monotona. */
  fun onConfirm() {
    val sheet = local.value.sheet as? RegionSheet.Confirm ?: return
    scope.launch {
      val confirmation = RegionDownloadSheet.confirmation(sheet.spec.id, sheet.estimate.bytes, mobile = sheet.mobileBytes != null, nowMs = nowMs())
      val result = guarded { regions.download(sheet.spec, confirmation) } ?: return@launch
      handle(result, sheet)
    }
  }

  private fun handle(result: DownloadStart, sheet: RegionSheet.Confirm?) {
    when (result) {
      DownloadStart.Started -> local.update { Local(it.estimates, null, null, it.around) }
      // Sui dati mobili serve la seconda conferma, con la dimensione: lo stesso foglio, al secondo passo.
      is DownloadStart.NeedsMobileConfirmation -> local.update { l ->
        val base = sheet ?: (l.sheet as? RegionSheet.Manage)?.let { m -> RegionSheet.Confirm(m.state.spec, RegionEstimate(m.state.totalTiles, result.bytes, m.state.maxZoom), SheetPurpose.RESUME) }
        Local(l.estimates, base?.copy(mobileBytes = result.bytes), null, l.around)
      }
      DownloadStart.NeedsWifi -> message(RegionMessage.NeedsWifi)
      is DownloadStart.NoSpace -> message(RegionMessage.NoSpace(result.neededBytes))
      DownloadStart.TooLarge -> message(RegionMessage.TooLarge)
      DownloadStart.Busy -> message(RegionMessage.Busy)
      DownloadStart.Expired -> message(RegionMessage.Expired)
    }
  }

  private fun message(m: RegionMessage) {
    local.update { Local(it.estimates, null, m, it.around) }
  }

  // --- gestire una zona scaricata ---------------------------------------------------------------------------------------------

  /** "Riprendi": una zona interrotta riprende (sul Wi-Fi subito; sui dati mobili con la seconda conferma). */
  fun onResume() {
    val sheet = local.value.sheet as? RegionSheet.Manage ?: return
    scope.launch {
      val result = guarded { regions.resume(sheet.state.spec.id) } ?: return@launch
      handle(result, null)
    }
  }

  /** "Aggiorna" (solo a mano): il foglio di conferma, che dice che si scarica di nuovo da capo. */
  fun onUpdate() {
    val sheet = local.value.sheet as? RegionSheet.Manage ?: return
    openConfirm(sheet.state.spec, SheetPurpose.UPDATE)
  }

  fun onAskDelete() {
    local.update { l -> Local(l.estimates, (l.sheet as? RegionSheet.Manage)?.copy(confirmDelete = true) ?: l.sheet, null, l.around) }
  }

  fun onDelete() {
    val sheet = local.value.sheet as? RegionSheet.Manage ?: return
    scope.launch {
      guarded { regions.delete(sheet.state.spec.id) }
      local.update { Local(it.estimates, null, null, it.around) }
    }
  }

  // --- la zona attorno a un luogo ---------------------------------------------------------------------------------------------

  fun onOpenAround() {
    local.update { Local(it.estimates, null, null, AroundState(available = search.available)) }
  }

  fun onCloseAround() {
    searchJob?.cancel()
    local.update { Local(it.estimates, null, null, null) }
  }

  /** Il testo cambia: dopo un attimo di calma si cerca per nome (fuori dal thread principale); due lettere almeno. */
  fun onQuery(text: String) {
    val current = local.value.around ?: return
    local.update { Local(it.estimates, it.sheet, it.message, current.copy(query = text, picked = null, radii = emptyList(), searched = false)) }
    searchJob?.cancel()
    if (text.trim().length < MIN_QUERY) {
      local.update { Local(it.estimates, it.sheet, it.message, it.around?.copy(hits = emptyList(), searched = false)) }
      return
    }
    searchJob = scope.launch {
      delay(searchCalmMs)
      val hits = guarded { withContext(compute) { search.search(text.trim(), MAX_HITS) } }.orEmpty()
      local.update { Local(it.estimates, it.sheet, it.message, it.around?.takeIf { a -> a.query == text }?.copy(hits = hits, searched = true) ?: it.around) }
    }
  }

  /** Un luogo scelto: i suoi tre raggi con la stima. Il centro e' quello del luogo (mai la casa, mai i punti delle foto). */
  fun onPick(hit: PlaceHit) {
    scope.launch {
      val specs = RegionRadius.entries.map { RegionSpec.AroundPlace(hit.placeId, it, hit.center, hit.urban, hit.name) }
      val offers = withContext(compute) { specs.map { RadiusOffer(it, regions.estimate(it), null) } }
      local.update { Local(it.estimates, null, null, it.around?.copy(picked = hit, radii = offers)) }
    }
  }

  fun onChangePlace() {
    local.update { Local(it.estimates, null, null, it.around?.copy(picked = null, radii = emptyList())) }
  }

  /** Un raggio scelto: il foglio di conferma di quella zona (o "troppo grande"). */
  fun onRadius(offer: RadiusOffer) {
    val present = regions.regions.value.firstOrNull { it.spec.id == offer.spec.id }
    if (present != null) {
      local.update { Local(it.estimates, RegionSheet.Manage(present), null, it.around) }
      return
    }
    val estimate = offer.estimate
    if (estimate == null || !estimate.offered) {
      message(RegionMessage.TooLarge)
      return
    }
    local.update { Local(it.estimates, RegionSheet.Confirm(offer.spec, estimate, SheetPurpose.DOWNLOAD), null, it.around) }
  }

  /** Un guasto non fa crollare l'app: nei log solo il tipo. */
  private suspend fun <T> guarded(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    GalleryLog.w(TAG) { "operazione sulle zone non riuscita (${e.javaClass.simpleName})" }
    null
  }

  companion object {
    const val SEARCH_CALM_MS = 250L
    const val MIN_QUERY = 2
    const val MAX_HITS = 8
    private const val NANOS_PER_MS = 1_000_000L
    private const val TAG = "MapRegionsUi"
  }
}
