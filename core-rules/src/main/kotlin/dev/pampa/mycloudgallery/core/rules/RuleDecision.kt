package dev.pampa.mycloudgallery.core.rules

import dev.pampa.mycloudgallery.core.model.FreezeReason
import java.time.LocalDateTime

/** Quale tetto mensile. */
enum class CapKind { MOBILE, WIFI }

/** Cosa chiede conferma all'utente prima di partire con "Fai ora". */
enum class ConfirmationKind { MOBILE_DATA, ROAMING, OVER_CAP }

/**
 * Perche' un'attivita' non gira (o si deve fermare), in forma tipizzata e con il testo italiano da
 * mostrare all'utente in [message].
 *
 * I tipi servono alla UI e alla Diagnostica (icone, azioni suggerite); [message] e' il testo di
 * riferimento finche' le stringhe non passano nelle risorse della UI.
 */
sealed interface WaitReason {

  /** Il motivo, in italiano. */
  val message: String

  /**
   * Il motivo riguarda la raggiungibilita' o l'uso del NAS: se e' l'UNICO ostacolo, il Calcolo AI
   * lavora comunque sul suo buffer locale (E-A6, piano 4.6).
   */
  val isNetworkRelated: Boolean get() = false

  data object ProfileInactive : WaitReason {
    override val message = "Profilo non attivo: il lavoro riprende quando torni su questo profilo"
  }

  data class RoleDisabled(val role: DeviceRole) : WaitReason {
    override val message =
      when (role) {
        DeviceRole.INDEXER -> "Questo dispositivo non è un indicizzatore"
        DeviceRole.PREPARER -> "Questo dispositivo non è un preparatore"
      }
  }

  data object ActivityDisabled : WaitReason {
    override val message = "Attività disattivata nelle impostazioni"
  }

  /**
   * Una share del NAS e' **congelata** per cambiamenti di massa (D10, C11 di docs/fase4-progetto.md): troppi file spariti,
   * cambiati o diventati "non piu' media" in una scansione (possibile ransomware o errore). Finche' l'utente non ha
   * controllato ("Ho controllato, continua") o la situazione non torna normale, **nessun lavoro scrive sul NAS**
   * (indice, prenotazioni, compattazione, pulizie, operazioni in coda, backup): lo vedono i lavori valutati con
   * [WorkRequest.writesToNas]. La lettura (anche la scansione che serve a scongelare) non e' toccata.
   *
   * E' "relativo al NAS" ([isNetworkRelated]): se e' l'unico ostacolo, il Calcolo AI lavora comunque sul suo buffer
   * locale (E-A6). Da risposta Q6 dell'utente, l'avviso va nel riepilogo giornaliero, non in una notifica a se'.
   */
  data class Frozen(val reason: FreezeReason) : WaitReason {
    override val message =
      when (reason) {
        FreezeReason.VANISHED ->
          "Scritture sul NAS ferme: sono spariti molti file. Controlla, poi tocca «Ho controllato, continua»"
        FreezeReason.CHANGED ->
          "Scritture sul NAS ferme: sono cambiati molti file. Controlla, poi tocca «Ho controllato, continua»"
        FreezeReason.NOT_MEDIA ->
          "Scritture sul NAS ferme: molti file non sono più foto o video. Controlla, poi tocca «Ho controllato, continua»"
      }
    override val isNetworkRelated = true
  }

  /** Una richiesta "solo con l'app aperta" (miniature visibili) arrivata con l'app in background. */
  data object AppNotVisible : WaitReason {
    override val message = "Si carica solo con l'app aperta"
  }

  data object Roaming : WaitReason {
    override val message = "Roaming: attività sospese"
  }

  data object Offline : WaitReason {
    override val message = "Nessuna connessione: in attesa di una rete"
    override val isNetworkRelated = true
  }

  data object NetworkNotTrusted : WaitReason {
    override val message = "Rete non riconosciuta: serve il Wi-Fi di casa o la VPN"
    override val isNetworkRelated = true
  }

  /** Rete lenta o instabile (E-R4): il lavoro automatico aspetta, l'uso interattivo no. */
  data object PoorNetwork : WaitReason {
    override val message = "Rete lenta o instabile: attività sospese"
    override val isNetworkRelated = true
  }

  data class OutsideWindow(val window: TimeWindow, val nextStart: LocalDateTime) : WaitReason {
    override val message = "In attesa della fascia oraria ($window)"
  }

  data object NeedsPower : WaitReason {
    override val message = "In attesa: serve la carica"
  }

  data class BatteryLow(val levelPercent: Int, val thresholdPercent: Int) : WaitReason {
    override val message = "Batteria al $levelPercent%: in pausa sotto il $thresholdPercent%"
  }

  data object PowerSaver : WaitReason {
    override val message = "Risparmio energetico attivo: attività in pausa"
  }

  data class Thermal(val status: ThermalStatus) : WaitReason {
    override val message = "Telefono troppo caldo: in pausa finché si raffredda"
  }

  data class NetworkNotAllowed(val current: NetworkClass) : WaitReason {
    override val message =
      when (current) {
        NetworkClass.MOBILE -> "In attesa del Wi-Fi: i dati mobili non sono consentiti per questa attività"
        NetworkClass.EXTERNAL_WIFI -> "In attesa del Wi-Fi di casa"
        NetworkClass.ROAMING -> "Roaming: attività sospese"
        else -> "Questa rete non è consentita per questa attività"
      }
    override val isNetworkRelated = true
  }

  data object HeavyNeedsHomeWifi : WaitReason {
    override val message = "Attività pesante: parte solo sul Wi-Fi di casa"
    override val isNetworkRelated = true
  }

  data class CapReached(val kind: CapKind, val usedBytes: Long, val capBytes: Long) : WaitReason {
    override val message =
      "Tetto dati ${if (kind == CapKind.MOBILE) "mobili" else "Wi-Fi"} raggiunto " +
        "(${ByteFormat.usage(usedBytes, capBytes)})"
    override val isNetworkRelated = true
  }

  /** @property budget la quota esaurita, o null se e' la quota dei job. */
  data class AndroidLimit(val budget: ForegroundBudget?) : WaitReason {
    override val message = "Limite di Android raggiunto: riprende la notte dopo"
  }

  data object NasUnreachable : WaitReason {
    override val message = "NAS spento o non raggiungibile"
    override val isNetworkRelated = true
  }

  /** Un "Fai ora" in corso a cui la rete e' cambiata sotto i piedi (es. Wi-Fi -> dati mobili). */
  data class ConfirmationRequired(val kinds: Set<ConfirmationKind>) : WaitReason {
    override val message =
      when {
        ConfirmationKind.ROAMING in kinds -> "Serve la tua conferma per lavorare in roaming"
        ConfirmationKind.MOBILE_DATA in kinds -> "Serve la tua conferma per usare i dati mobili"
        else -> "Serve la tua conferma per superare il tetto dati"
      }
  }
}

/** Quanto lavoro puo' fare un'attivita' che ha il via libera. */
enum class RunScope {
  /** Tutto, NAS compreso. */
  FULL,

  /** Solo quello che e' gia' sul dispositivo (buffer), poi si ferma (E-A6). */
  LOCAL_BUFFER_ONLY,
}

/**
 * Il ritmo delle attivita' a CPU (`BackgroundActivity.cpuIntensive`). Chi lavora guarda [slow]: i due ritmi lenti si eseguono allo stesso
 * modo (lotti piccoli e pause); sono due valori perche' il motivo e' diverso, e chi lo mostra (Diagnostica) non deve dire "il telefono e'
 * caldo" quando e' solo l'app aperta.
 */
enum class Pace {
  NORMAL,

  /** Telefono caldo (LIGHT/MODERATE, o headroom alto): piano, finche' si raffredda (E-A5). */
  REDUCED,

  /**
   * L'app e' in primo piano (review generale, OB-3): il lavoro pesante di fondo si fa da parte per non rubare CPU all'interfaccia, ma
   * continua ad avanzare. In secondo piano torna [NORMAL]. Se il telefono e' anche caldo vale [REDUCED].
   */
  APP_IN_USE,
  ;

  /** Si va piano: lotti piccoli e pause fra un lotto e l'altro. */
  val slow: Boolean get() = this != NORMAL
}

/** La stima del traffico di un "Fai ora", mostrata nella conferma. */
data class TrafficEstimate(
  val bytes: Long?,
  val monthlyUsedBytes: Long,
  val monthlyCapBytes: Long?,
)

/**
 * Il verdetto del motore.
 *
 *  - [Run]: puo' girare adesso (o continuare).
 *  - [Wait]: non deve partire, e dice perche'.
 *  - [Stop]: stava girando e deve fermarsi (salvare il punto di ripresa e uscire): "fermati".
 *  - [NeedsConfirmation]: solo per "Fai ora" su dati mobili / roaming / oltre il tetto.
 */
sealed interface RuleDecision {

  data class Run(
    val scope: RunScope = RunScope.FULL,
    val pace: Pace = Pace.NORMAL,
    val network: NetworkClass,
    /** Byte che restano nel tetto mensile della rete in uso; null = nessun tetto (o nessun traffico). */
    val remainingBytes: Long? = null,
  ) : RuleDecision

  data class Wait(val reason: WaitReason, val alsoBlockedBy: List<WaitReason> = emptyList()) :
    RuleDecision

  data class Stop(val reason: WaitReason, val alsoBlockedBy: List<WaitReason> = emptyList()) :
    RuleDecision

  data class NeedsConfirmation(val kinds: Set<ConfirmationKind>, val estimate: TrafficEstimate?) :
    RuleDecision {

    /** Il testo della conferma, con la stima. */
    val message: String
      get() {
        val sentences = ArrayList<String>()
        sentences +=
          when {
            ConfirmationKind.ROAMING in kinds -> "Sei in roaming: potrebbero esserci costi aggiuntivi"
            ConfirmationKind.MOBILE_DATA in kinds -> "Stai per usare i dati mobili"
            else -> "Stai per superare il tetto dati mensile"
          }
        estimate?.bytes?.let { sentences += "Servono circa ${ByteFormat.size(it)}" }
        val cap = estimate?.monthlyCapBytes
        if (estimate != null && cap != null && ConfirmationKind.OVER_CAP in kinds) {
          sentences += "Tetto mensile: ${ByteFormat.usage(estimate.monthlyUsedBytes, cap)}"
        }
        sentences += "Vuoi continuare?"
        return sentences.joinToString(". ")
      }
  }

  /** Il motivo se non e' un [Run] (per la UI); null se puo' girare o chiede conferma. */
  val blockingReason: WaitReason?
    get() =
      when (this) {
        is Wait -> reason
        is Stop -> reason
        else -> null
      }

  val isRun: Boolean get() = this is Run
}
