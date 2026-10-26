package dev.pampa.mycloudgallery.ai

import android.net.Network
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelGateReason
import dev.pampa.mycloudgallery.core.models.contract.ModelHttpsRoute
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelShelf
import dev.pampa.mycloudgallery.core.models.contract.ModelShelfDevices
import dev.pampa.mycloudgallery.core.models.contract.ModelShelfGate
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.ModelUsage
import dev.pampa.mycloudgallery.core.models.contract.ShelfCopy
import dev.pampa.mycloudgallery.core.models.contract.ShelfResult
import dev.pampa.mycloudgallery.core.models.shelf.NasModelShelf
import dev.pampa.mycloudgallery.core.models.shelf.NasShelfWriter
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.ops.models.NasModelShelfOps
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.env.NetworkLookup
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork

// Le PORTE dei modelli (P3) legate da :app (Fase 6, P11): la rete per l'HTTPS, la rete per il traffico, il cancello dello scaffale.

/** La rete di adesso per il registro del traffico: roaming, dati mobili (o Wi-Fi a consumo), Wi-Fi di casa o un altro Wi-Fi. */
fun trafficNetworkOf(snapshot: NetworkSnapshot): TrafficNetwork {
  val net = snapshot.underlying ?: return TrafficNetwork.MOBILE
  return when {
    net.roaming -> TrafficNetwork.ROAMING
    net.transport == NetTransport.CELLULAR || net.metered -> TrafficNetwork.MOBILE
    snapshot.place == NetPlace.HOME -> TrafficNetwork.HOME_WIFI
    else -> TrafficNetwork.EXTERNAL_WIFI
  }
}

/**
 * Il cancello di rete per lo scaricamento dei modelli (§1.4, §2.7, D17, R11): vale per **ogni** sorgente che usa la rete (HTTPS, copie sul NAS,
 * cartella dal PC), non solo per HTTPS. **Mai in roaming.** In background ([ModelTrigger.RULES], [ModelTrigger.ROLE_CHANGED]) solo una rete non a
 * consumo (Wi-Fi), mai i dati mobili; con "Scarica ora" ([ModelTrigger.USER_NOW]) i dati mobili chiedono la conferma con la dimensione
 * ([ModelNetworkVerdict.NeedsConfirmation]), e dopo il "Si'" la richiesta ripetuta ([ModelTrigger.USER_CONFIRMED]) passa. La conferma e' parte
 * della richiesta: niente stato condiviso da ricordarsi di spegnere (MO-2).
 *
 * Per HTTPS la rete deve essere **validata** (Internet raggiungibile); per le sorgenti del NAS no: il NAS e' in LAN, e un Wi-Fi di casa senza
 * Internet (router senza WAN) deve bastare (F48: il tablet scarica dal NAS senza Internet). Con la VPN accesa sui dati mobili il NAS conta
 * come rete a consumo: come HTTPS sui dati mobili.
 */
class AppModelNetworkGate(private val snapshot: () -> NetworkSnapshot) : ModelNetworkGate {
  override suspend fun verdict(trigger: ModelTrigger, bytes: Long, source: ModelSourceKind): ModelNetworkVerdict {
    val s = snapshot()
    val net = s.underlying ?: return ModelNetworkVerdict.Refused(ModelGateReason.OFFLINE)
    val needsInternet = source == ModelSourceKind.HTTPS
    if (needsInternet && (!net.validated || net.captivePortal)) return ModelNetworkVerdict.Refused(ModelGateReason.NOT_VALIDATED)
    if (net.roaming) return ModelNetworkVerdict.Refused(ModelGateReason.ROAMING)
    val mobile = net.transport == NetTransport.CELLULAR || net.metered
    if (!mobile) return ModelNetworkVerdict.Allowed
    return when (trigger) {
      ModelTrigger.USER_CONFIRMED -> ModelNetworkVerdict.Allowed
      ModelTrigger.USER_NOW -> ModelNetworkVerdict.NeedsConfirmation(bytes)
      ModelTrigger.RULES, ModelTrigger.ROLE_CHANGED -> ModelNetworkVerdict.Refused(ModelGateReason.METERED)
    }
  }
}

/**
 * R12 (revisione di sicurezza della Fase 6): le connessioni HTTPS dei modelli escono dalla rete che il cancello ([AppModelNetworkGate]) ha
 * giudicato, cioe' la rete sottostante dell'istantanea di adesso, **non** da quella predefinita del sistema. A casa, con il Wi-Fi riconosciuto,
 * One UI puo' aver reso predefiniti i dati mobili: senza questo legame "Scarica ora" avrebbe portato fino a 370 MB sui dati mobili senza la
 * conferma, segnati come Wi-Fi di casa. Si rilegge a ogni connessione (ogni fetta di 30 s). Senza una rete approvata, o se Android non ne conosce
 * piu' la `Network`, null: la connessione non si apre.
 */
class AppModelHttpsRoute(
  private val snapshot: () -> NetworkSnapshot,
  private val lookup: NetworkLookup,
) : ModelHttpsRoute {
  override fun network(): Network? {
    val net = snapshot().underlying ?: return null
    return lookup.network(net.networkId)
  }
}

/**
 * Il cancello delle copie dei modelli sul NAS (D18) da `IndexWriteRules` (P11): gli stessi blocchi dell'indice (F4-D31 sulle share vere,
 * emergenza, congelamento, salto dell'ora, fiducia). Senza il motore del NAS, "no_nas": niente si scrive.
 */
class RulesModelShelfGate(private val rules: () -> IndexWriteRules?) : ModelShelfGate {
  override suspend fun blockFor(share: String): String? {
    val r = rules() ?: return NO_NAS
    return r.blockFor(share)?.name
  }

  private companion object {
    const val NO_NAS = "no_nas"
  }
}

/**
 * I dispositivi da cui si accettano copie dei modelli (I5): quelli del registro firmato **approvati** (non revocati, persi o dismessi). Senza
 * registro, null: nessuna copia dal NAS.
 */
class TrustedShelfDevices(private val registry: () -> RegistryState?) : ModelShelfDevices {
  override suspend fun trusted(): Set<String>? =
    registry()?.registry?.devices?.filter { it.status == DeviceStatus.APPROVED }?.mapTo(HashSet()) { it.id }
}

/**
 * Lo scaffale dei modelli sul NAS (MO-4, D18) sopra il motore del NAS di **adesso**: nasce quando c'e' (il dispositivo ha il suo id solo allora) e
 * si rifa' se il NAS riparte. Senza NAS e' il null-object (niente si scrive, niente si legge). Le copie: solo di dispositivi approvati e non
 * dismessi (I5); la scrittura: a flusso, nella propria cartella, senza sostituire (core-nas `NasModelShelfOps`), dietro il cancello F4-D31.
 */
class AppModelShelf(
  private val files: ModelFiles,
  private val deviceId: () -> String?,
  private val reader: () -> NasMediaReader,
  private val ops: (String) -> NasModelShelfOps,
  private val gate: ModelShelfGate,
  private val devices: ModelShelfDevices,
) : ModelShelf {

  private var built: Pair<String, ModelShelf>? = null

  private fun shelf(): ModelShelf? {
    val id = deviceId() ?: return null
    synchronized(this) {
      built?.takeIf { it.first == id }?.let { return it.second }
      val created = NasModelShelf(id, files, reader(), NasShelfWriter(ops(id)), gate, devices)
      built = id to created
      return created
    }
  }

  override suspend fun publish(spec: ModelSpec, share: String): ShelfResult = shelf()?.publish(spec, share) ?: ShelfResult.Blocked("no_nas")

  override suspend fun copies(share: String, sha256: Sha256): List<ShelfCopy> = shelf()?.copies(share, sha256).orEmpty()

  override suspend fun pruneOwn(share: String, keep: Set<Sha256>): Int = shelf()?.pruneOwn(share, keep) ?: 0
}

/**
 * Il gestore dei modelli che il resto dell'app vede (MO-1, MO-2): "Scarica" della pagina, di Cerca e l'accensione del ruolo **non scaricano nel
 * loro scope** (che muore con la pagina: uscendo lo scaricamento si fermava e riprendeva 6 ore dopo): chiedono un lavoro di WorkManager accelerato
 * ([request], `AiWorkScheduling.fetchNow`) e tornano subito con lo stato. La pagina osserva soltanto [states]. Le richieste in background
 * ([ModelTrigger.RULES]) e `remove`, `usage` vanno diretti al gestore vero.
 *
 * Fino a quando il lavoro non parte, [states] mostra il modello "in coda" (la richiesta c'e', anche se la rete manca): l'indicazione sparisce
 * appena il gestore vero cambia stato (scaricamento, verifica, pronto, in coda con il suo motivo, errore).
 */
class RequestingModelManager(
  private val inner: ModelManager,
  scope: CoroutineScope,
  private val request: (ModelId, ModelTrigger) -> Unit,
) : ModelManager {

  private val requested = MutableStateFlow<Map<ModelId, ModelState>>(emptyMap())

  override val states: StateFlow<Map<ModelId, ModelState>> = combine(inner.states, requested) { real, asked ->
    if (asked.isEmpty()) real else real + asked.filter { (id, _) -> canShowRequest(real[id]) }
  }.stateIn(scope, SharingStarted.Eagerly, inner.states.value)

  init {
    // La richiesta e' stata presa in carico dal gestore vero: l'indicazione "in coda" non serve piu'.
    scope.launch {
      inner.states.collect { real ->
        requested.update { asked -> asked.filterKeys { id -> canShowRequest(real[id]) } }
      }
    }
  }

  /** La richiesta si mostra solo finche' il gestore vero non ha niente di meglio da dire. */
  private fun canShowRequest(real: ModelState?): Boolean =
    real == null || real is ModelState.Absent || real is ModelState.Failed || real is ModelState.NeedsConfirmation

  override suspend fun ensure(id: ModelId, trigger: ModelTrigger, budget: Duration): EnsureResult {
    if (trigger == ModelTrigger.RULES) return inner.ensure(id, trigger, budget)
    if (inner.states.value[id] is ModelState.Ready) return EnsureResult.Ready
    val state = ModelState.Queued(trigger)
    requested.update { it + (id to state) }
    request(id, trigger)
    return EnsureResult.Deferred(state)
  }

  override suspend fun remove(id: ModelId): Boolean = inner.remove(id)

  override suspend fun usage(): ModelUsage = inner.usage()
}
