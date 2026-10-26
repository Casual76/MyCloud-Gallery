package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.personal.contract.MotionPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.SearchOrderSetting
import dev.pampa.mycloudgallery.core.personal.contract.SearchPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import dev.pampa.mycloudgallery.core.personal.settings.SyncedMotionSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedSearchSettings
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Le scelte personali fatte su questo dispositivo che **non sono ancora nel registro dei dati personali** (CE-3 e VM-2 della verifica
 * funzionale della Fase 6, stessa radice di LT-1): il registro nasce con il motore del NAS, e se l'utente sceglie prima (app appena
 * avviata, nessun NAS) la scelta si perdeva, o tornava al valore di prima. Qui restano, in un file di `noBackupFilesDir`, finche'
 * [PersonalSettingsHub] non le consegna al registro; poi si tolgono.
 *
 * Due righe: `order=` (`recent` o `relevant`, vuoto = niente in sospeso) e `motion=` (`1`, `0` o vuoto). [file] null = solo in memoria.
 */
class PersonalLocalSettings(private val file: File?) {
  private val lock = Any()

  private val orderFlow = MutableStateFlow<SearchOrderSetting?>(null)
  private val motionFlow = MutableStateFlow<Boolean?>(null)

  /** L'ordine dei risultati scelto qui e non ancora nel registro. */
  val searchOrder: StateFlow<SearchOrderSetting?> get() = orderFlow

  /** "Animazione delle foto in movimento" scelta qui e non ancora nel registro. */
  val motionAnimation: StateFlow<Boolean?> get() = motionFlow

  init {
    read()
  }

  fun chooseOrder(order: SearchOrderSetting) = update { orderFlow.value = order }

  /** L'ordine [delivered] e' nel registro: si toglie, se nel frattempo l'utente non ne ha scelto un altro. */
  fun orderDelivered(delivered: SearchOrderSetting) = update { if (orderFlow.value == delivered) orderFlow.value = null }

  fun chooseMotion(on: Boolean) = update { motionFlow.value = on }

  fun motionDelivered(delivered: Boolean) = update { if (motionFlow.value == delivered) motionFlow.value = null }

  /** Azzeramento dei dati dell'account (AZ-2): nessuna scelta in sospeso, nessun file. */
  fun reset() {
    synchronized(lock) {
      orderFlow.value = null
      motionFlow.value = null
      file?.delete()
    }
  }

  private fun update(block: () -> Unit) = synchronized(lock) {
    val before = orderFlow.value to motionFlow.value
    block()
    if (orderFlow.value to motionFlow.value != before) write()
  }

  private fun write() {
    val target = file ?: return
    val order = orderFlow.value
    val motion = motionFlow.value
    if (order == null && motion == null) {
      target.delete()
      return
    }
    try {
      target.parentFile?.mkdirs()
      val tmp = File(target.parentFile, target.name + ".tmp")
      tmp.writeText("order=${order?.code.orEmpty()}\nmotion=${motion?.let { if (it) "1" else "0" }.orEmpty()}\n")
      if (!tmp.renameTo(target)) {
        target.delete()
        tmp.renameTo(target)
      }
    } catch (e: IOException) {
      // Resta in memoria: e' solo una preferenza.
    }
  }

  private fun read() {
    val source = file ?: return
    val lines = try {
      if (source.isFile) source.readLines() else return
    } catch (e: IOException) {
      return
    }
    for (line in lines) {
      val value = line.substringAfter('=', "")
      when {
        line.startsWith("order=") -> orderFlow.value = SearchOrderSetting.entries.firstOrNull { it.code == value }
        line.startsWith("motion=") -> motionFlow.value = when (value) {
          "1" -> true
          "0" -> false
          else -> null
        }
      }
    }
  }

  companion object {
    const val FILE = "personal-local"
  }
}

/**
 * Le impostazioni personali della Fase 6 (`text_reading`, `search_order`, `motion_animation`) sopra il registro dei dati personali del
 * NAS (4B), che c'e' solo quando l'organizzazione del NAS e' agganciata: prima, null (vale l'ultima scelta ricordata o il default).
 *
 * **Le scelte dell'utente valgono subito** (CE-3, VM-2): l'ordine e l'animazione si leggono dal registro (con le modifiche ancora da datare:
 * `SyncedSearchSettings` e `SyncedMotionSettings` leggono `effectiveEntries`) e, se il registro non c'e' ancora, da [local]. [setSearchOrder] e
 * [setMotionAnimation] salvano la scelta in [local] e la consegnano al registro appena c'e' (subito, se c'e' gia'): la sincronizzazione con
 * gli altri dispositivi viene dopo e non perde l'ordine delle modifiche.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonalSettingsHub(
  stores: StateFlow<LedgerPersonalStore?>,
  scope: CoroutineScope,
  private val local: PersonalLocalSettings = PersonalLocalSettings(null),
) {
  val search: StateFlow<SearchPersonalSettings?> = stores.map { s -> s?.let { SyncedSearchSettings(it, scope) } }
    .stateIn(scope, SharingStarted.Eagerly, null)

  val motion: StateFlow<MotionPersonalSettings?> = stores.map { s -> s?.let { SyncedMotionSettings(it, scope) } }
    .stateIn(scope, SharingStarted.Eagerly, null)

  val textReading: StateFlow<TextReadingChoice?> = search.flatMapLatest { s -> s?.textReading ?: flowOf(null) }
    .stateIn(scope, SharingStarted.Eagerly, null)

  /** L'ordine dei risultati: la scelta fatta qui non ancora nel registro, altrimenti il registro, altrimenti "Recenti". */
  val searchOrder: StateFlow<SearchOrderSetting> = combine(
    search.flatMapLatest { s -> s?.searchOrder ?: flowOf(null) },
    local.searchOrder,
  ) { synced, chosen -> chosen ?: synced ?: SearchOrderSetting.RECENT }
    .stateIn(scope, SharingStarted.Eagerly, local.searchOrder.value ?: SearchOrderSetting.RECENT)

  /** L'animazione delle foto in movimento: come [searchOrder]; acceso di serie. */
  val motionAnimation: StateFlow<Boolean> = combine(
    motion.flatMapLatest { m -> m?.motionAnimation ?: flowOf(null) },
    local.motionAnimation,
  ) { synced, chosen -> chosen ?: synced ?: true }
    .stateIn(scope, SharingStarted.Eagerly, local.motionAnimation.value ?: true)

  private val delivery = Mutex()

  init {
    // Appena il registro c'e', le scelte fatte prima vi entrano (e con loro la sincronizzazione).
    scope.launch {
      search.filterNotNull().collect { deliverOrder() }
    }
    scope.launch {
      motion.filterNotNull().collect { deliverMotion() }
    }
  }

  suspend fun setSearchOrder(order: SearchOrderSetting) {
    local.chooseOrder(order)
    deliverOrder()
  }

  suspend fun setMotionAnimation(on: Boolean) {
    local.chooseMotion(on)
    deliverMotion()
  }

  private suspend fun deliverOrder() {
    delivery.withLock {
      val settings = search.value ?: return
      val wanted = local.searchOrder.value ?: return
      settings.setSearchOrder(wanted)
      local.orderDelivered(wanted)
    }
  }

  private suspend fun deliverMotion() {
    delivery.withLock {
      val settings = motion.value ?: return
      val wanted = local.motionAnimation.value ?: return
      settings.setMotionAnimation(wanted)
      local.motionDelivered(wanted)
    }
  }

  /** L'azzeramento dei dati dell'account (AZ-2): le scelte in sospeso si dimenticano. */
  fun forget() = local.reset()
}
