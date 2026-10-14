package dev.pampa.mycloudgallery.core.rules

import dev.pampa.mycloudgallery.core.model.FreezeReason
import java.time.Duration
import java.time.LocalDateTime

/**
 * Lo stato termico del dispositivo. L'ordine e' quello di `PowerManager.THERMAL_STATUS_*` (0..6), cosi'
 * si confronta con `>=`.
 */
enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

/**
 * Alimentazione, batteria e temperatura.
 *
 * **"In carica" = [pluggedIn]**: alimentazione collegata (`EXTRA_PLUGGED != 0`), NON lo stato di
 * carica (docs/android.md, scoperta 1). Con la Protezione batteria Samsung la carica si ferma
 * all'80-90% con il cavo collegato (`status = NOT_CHARGING`): il telefono conta comunque come in
 * carica (E-P16). [chargingPaused] e' solo informativo, per dirlo nella UI.
 *
 * @property thermalHeadroom `PowerManager.getThermalHeadroom`: 0.0..1.0, dove 1.0 = SEVERE. Null se non
 *   disponibile (il sistema risponde NaN).
 * @property unpluggedFor da quanto l'alimentazione e' scollegata (zero se collegata). Serve a tollerare
 *   i falsi distacchi di pochi secondi visti sull'S25 (docs/android.md, 2.2 bis) mentre un lavoro gira.
 * @property timeSinceThermalSevere da quanto il telefono e' stato l'ultima volta in stato termico
 *   severo (null = mai visto): serve all'isteresi, per non ripartire appena la temperatura scende.
 */
data class PowerState(
  val pluggedIn: Boolean,
  val levelPercent: Int,
  val chargingPaused: Boolean = false,
  val powerSaveMode: Boolean = false,
  val thermalStatus: ThermalStatus = ThermalStatus.NONE,
  val thermalHeadroom: Float? = null,
  val unpluggedFor: Duration = Duration.ZERO,
  val timeSinceThermalSevere: Duration? = null,
) {
  init {
    require(levelPercent in 0..100) { "Il livello della batteria e' una percentuale." }
  }
}

/** Il trasporto della rete in uso. La VPN non e' un trasporto: e' il flag [NetworkState.viaVpn]. */
enum class NetworkTransport { NONE, WIFI, CELLULAR, ETHERNET }

/**
 * La rete effettivamente usata dal traffico (E-R12: si segue la rete predefinita, non un'altra
 * contemporanea).
 *
 * @property viaVpn il traffico passa dalla VPN (la rete sottostante e' [transport]).
 * @property isHome la rete e' quella di casa (segnale della Fase 2, `HomeNetworkSignal`).
 * @property metered a consumo (Wi-Fi marcato a consumo o hotspot di un altro telefono, E-R13).
 * @property roaming dati mobili in roaming.
 * @property validated la rete e' stata verificata da Android: falso con pagina di accesso (captive
 *   portal) o senza internet (E-R13).
 * @property poor la rete e' scadente (E-R4: VPN su una rete lenta o che si blocca): le attivita'
 *   automatiche stanno ferme, l'uso interattivo no. Lo misura la Fase 2 (`NetworkQualitySignal`).
 */
data class NetworkState(
  val transport: NetworkTransport,
  val viaVpn: Boolean = false,
  val isHome: Boolean = false,
  val metered: Boolean = false,
  val roaming: Boolean = false,
  val validated: Boolean = true,
  val poor: Boolean = false,
) {
  companion object {
    val OFFLINE = NetworkState(transport = NetworkTransport.NONE, validated = false)
  }
}

/** La rete vista dalle regole. */
enum class NetworkClass {
  /** Nessuna rete, o rete non verificata / dietro pagina di accesso. */
  OFFLINE,

  /** Wi-Fi (o Ethernet) di casa, con o senza VPN. */
  HOME_WIFI,

  /** Un altro Wi-Fi, raggiunto attraverso la VPN, non a consumo. */
  EXTERNAL_WIFI,

  /** Dati mobili via VPN, o un Wi-Fi a consumo via VPN (E-R13). */
  MOBILE,

  /** Dati mobili in roaming. */
  ROAMING,

  /** Una rete da cui il NAS non si contatta: non e' casa e la VPN e' spenta (piano 2.1). */
  UNTRUSTED;

  /** Vero se su questa rete il traffico verso il NAS e' possibile. */
  val carriesTraffic: Boolean get() = this != OFFLINE && this != UNTRUSTED

  /** La voce di [AllowedNetwork] che questa rete richiede (null se non porta traffico). */
  fun asAllowedNetwork(): AllowedNetwork? =
    when (this) {
      HOME_WIFI -> AllowedNetwork.HOME_WIFI
      EXTERNAL_WIFI -> AllowedNetwork.ANY_WIFI_VPN
      MOBILE -> AllowedNetwork.MOBILE_DATA
      ROAMING -> AllowedNetwork.ROAMING
      OFFLINE, UNTRUSTED -> null
    }
}

/** Da [NetworkState] a [NetworkClass]: l'unico posto che decide cosa e' "casa", "mobile" e "roaming". */
object NetworkClassifier {

  fun classify(network: NetworkState): NetworkClass {
    if (network.transport == NetworkTransport.NONE || !network.validated) return NetworkClass.OFFLINE
    return when (network.transport) {
      NetworkTransport.CELLULAR ->
        when {
          // Il roaming vince su tutto: e' la rete che costa di piu'.
          network.roaming -> NetworkClass.ROAMING
          network.viaVpn -> NetworkClass.MOBILE
          else -> NetworkClass.UNTRUSTED
        }
      NetworkTransport.WIFI,
      NetworkTransport.ETHERNET ->
        when {
          // Il traffico verso il NAS di casa e' locale: anche se l'utente ha marcato la rete di casa
          // "a consumo", non consuma dati dell'operatore.
          network.isHome -> NetworkClass.HOME_WIFI
          !network.viaVpn -> NetworkClass.UNTRUSTED
          // Hotspot di un altro telefono o Wi-Fi a consumo: come dati mobili (E-R13).
          network.metered -> NetworkClass.MOBILE
          else -> NetworkClass.EXTERNAL_WIFI
        }
      NetworkTransport.NONE -> NetworkClass.OFFLINE
    }
  }
}

/** I ruoli del dispositivo (piano, glossario). */
data class DeviceRoles(val indexer: Boolean = false, val preparer: Boolean = false) {
  fun has(role: DeviceRole): Boolean =
    when (role) {
      DeviceRole.INDEXER -> indexer
      DeviceRole.PREPARER -> preparer
    }
}

/**
 * I limiti di Android raggiunti (E-P14): la quota di 6 h su 24 h dei servizi in primo piano per tipo e
 * la quota dei job (bucket, salvaguardie). Li compila chi esegue i lavori (Fase 6.2), qui sono un dato.
 */
data class AndroidLimits(
  val dataSyncBudgetExhausted: Boolean = false,
  val mediaProcessingBudgetExhausted: Boolean = false,
  val jobQuotaExhausted: Boolean = false,
) {
  fun blocks(activity: BackgroundActivity): Boolean =
    jobQuotaExhausted ||
      when (activity.budget) {
        ForegroundBudget.DATA_SYNC -> dataSyncBudgetExhausted
        ForegroundBudget.MEDIA_PROCESSING -> mediaProcessingBudgetExhausted
      }
}

/**
 * Il contesto dell'app.
 *
 * @property inForeground l'app e' aperta e visibile.
 * @property profileActive il profilo Android (utente) e' quello attivo (E-P19). Falso su un profilo
 *   del tablet rimasto in background.
 */
data class AppContext(val inForeground: Boolean = false, val profileActive: Boolean = true)

/**
 * Tutto cio' che il valutatore deve sapere di questo momento (piano 1.6).
 *
 * @property now l'ora locale del dispositivo (data e ora): fa da riferimento per la fascia notte e per
 *   il cambio mese dei contatori.
 * @property nasReachable il NAS risponde (segnale della Fase 2, `NasReachabilitySignal`).
 * @property nasFrozen almeno una share del NAS e' congelata per cambiamenti di massa (D10, Fase 4; segnale
 *   `NasFreezeSignal`): perche', o null se non lo e'. Vale solo per i lavori che scrivono sul NAS
 *   ([WorkRequest.writesToNas]); la granularita' per share sta in `IndexWriteGate`, qui basta "qualcuna".
 */
data class SystemState(
  val now: LocalDateTime,
  val power: PowerState,
  val network: NetworkState,
  val nasReachable: Boolean,
  val roles: DeviceRoles = DeviceRoles(),
  val androidLimits: AndroidLimits = AndroidLimits(),
  val appInForeground: Boolean = false,
  val profileActive: Boolean = true,
  val nasFrozen: FreezeReason? = null,
)
