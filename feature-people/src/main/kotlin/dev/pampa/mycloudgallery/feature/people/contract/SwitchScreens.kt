package dev.pampa.mycloudgallery.feature.people.contract

import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.DeviceAck
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.model.StableCode

// Gli STATI dell'attivazione, dell'avviso, di Impostazioni -> Persone e di "Disattiva e cancella" (docs/fase8-progetto.md §1.5, §2.1,
// §2.8, D3-D5, D29-D31, Q3 del 27/10). Contratto di P0; le schermate sono di P7 (activation/) e P8 (erase/). Le due conferme
// (`FacesActivationConsent`, `FacesEraseConfirmation`) le costruiscono SOLO la pagina di attivazione e il foglio, dopo un tocco
// (Phase8ArchitectureTest): questi stati non le contengono. Q3 (a): nessuna traccia dell'attivazione nella pagina Famiglia.

/** I requisiti mostrati dalla pagina di attivazione (§2.1): area privata dichiarata, chi calcola, dove restano i dati. */
data class ActivationRequirements(
  val privateArea: Boolean,
  val indexers: Int,
  val thisDeviceIndexer: Boolean,
  val canBecomeIndexer: Boolean,
  /** La recinzione delle cartelle vere (D34, Q5): i dati dei volti restano su questo dispositivo finche' l'utente non dice di si'. */
  val localOnly: Boolean,
) {
  val met: Boolean get() = privateArea && (indexers > 0 || thisDeviceIndexer)
}

/** Perche' l'attivazione non e' andata (gli esiti di `ActivateOutcome` che la pagina mostra). */
enum class ActivationProblem(override val code: String) : StableCode {
  /** La conferma e' scaduta o il testo e' cambiato: si rilegge la pagina. */
  STALE_CONSENT("stale_consent"),

  NEEDS_UPDATE("needs_update"),

  UNAVAILABLE("unavailable"),
}

/** La pagina di attivazione (§1.5 `FacesActivationState`, §2.1). */
sealed interface FacesActivationState {
  /** Il testo della pagina, versione [textVersion] (il consenso porta questa versione), e i requisiti. */
  data class Explain(val textVersion: Int, val requirements: ActivationRequirements) : FacesActivationState {
    init {
      require(textVersion > 0) { "Versione del testo non valida." }
    }
  }

  /** Nessun dispositivo che calcola (D7 della Fase 6): [canBecomeIndexer] = "Fai calcolare questo dispositivo". */
  data class NeedsIndexer(val canBecomeIndexer: Boolean) : FacesActivationState

  /** Nessuna area privata dichiarata per l'account: i dati dei volti non hanno dove stare (D11). */
  data object NoPrivateArea : FacesActivationState

  data object Activating : FacesActivationState

  /** Attivata: [syncPending] = la voce aspetta la prossima sincronizzazione dei dati personali per arrivare agli altri dispositivi. */
  data class Activated(val syncPending: Boolean) : FacesActivationState

  data class Failed(val problem: ActivationProblem) : FacesActivationState
}

/** L'avviso una tantum sugli altri dispositivi dell'account (§1.5 `FacesNoticeState`, D4). */
sealed interface FacesNoticeState {
  /** [by]: il nome del dispositivo che ha attivato, se si sa ("Attivato da Tablet di casa"). */
  data class Show(val textVersion: Int, val by: String?) : FacesNoticeState {
    override fun toString(): String = "FacesNoticeState.Show(v$textVersion)"
  }

  data object Hidden : FacesNoticeState
}

/** Lo stato mostrato in Impostazioni -> Persone (una riga). */
enum class PeopleSwitchShown(override val code: String) : StableCode {
  OFF("off"),
  ON("on"),
  AWAITING_NOTICE("awaiting_notice"),
  EXCLUDED_HERE("excluded_here"),
  LOCAL_ONLY("local_only"),
  ERASING("erasing"),
  NEEDS_UPDATE("needs_update"),
}

/** Un dispositivo dell'account che calcola i volti (nome mostrato, questo dispositivo, escluso). */
data class ComputingDevice(val label: String, val thisDevice: Boolean, val excluded: Boolean) {
  override fun toString(): String = "ComputingDevice(questo=$thisDevice, escluso=$excluded)"
}

/** Un modello dei volti con la sua licenza (Q1: dentro l'APK), per la riga "Modelli e licenze". */
data class FaceModelRow(val name: String, val version: String, val license: String)

/** Impostazioni -> Persone (§1.5 `PeopleSettingsState`). */
data class PeopleSettingsState(
  val shown: PeopleSwitchShown,
  val devices: List<ComputingDevice>,
  val progress: FacesStatus,
  val excludedHere: Boolean,
  val hiddenPeople: Int,
  val models: List<FaceModelRow>,
  /** "Disattiva e cancella" si offre solo con Persone accesa o in attesa dell'avviso, e mai due volte. */
  val canErase: Boolean,
  val erase: EraseSheetState.Progress?,
) {
  override fun toString(): String = "PeopleSettingsState(${shown.code}, ${devices.size} dispositivi, nascoste=$hiddenPeople)"
}

/** Il foglio e la pagina "Disattiva e cancella" (§1.5 `EraseSheetState`, §2.8). */
sealed interface EraseSheetState {
  /**
   * Cosa si cancella: [namedPeople] persone con un nome, [albumsWithPeople] album smart con la regola "Persone" ([choice] null finche'
   * non si sceglie se tenere le foto o togliere la regola: con album la conferma resta spenta), la cronologia con nomi, i dispositivi che
   * devono confermare. [localOnly]: i dati non sono mai arrivati al NAS (recinzione).
   */
  data class Preview(
    val namedPeople: Int,
    val albumsWithPeople: Int,
    val historyWithNames: Boolean,
    val devices: List<DeviceAck>,
    val choice: PeopleAlbumsChoice?,
    val localOnly: Boolean,
  ) : EraseSheetState {
    val canConfirm: Boolean get() = albumsWithPeople == 0 || choice != null

    override fun toString(): String = "EraseSheetState.Preview($namedPeople, $albumsWithPeople, scelta=${choice?.code})"
  }

  data object Confirming : EraseSheetState

  data object Started : EraseSheetState

  /** La conferma e' scaduta o l'anteprima e' cambiata: si rimostra l'anteprima (niente e' stato toccato). */
  data object Expired : EraseSheetState

  /** L'avanzamento: in locale, sul NAS, le conferme dei dispositivi e, se aspetta, perche'. */
  data class Progress(
    val local: StepState,
    val nas: StepState,
    val devices: List<DeviceAck>,
    val waiting: EraseWait?,
  ) : EraseSheetState {
    val confirmed: Int get() = devices.count { it.state == AckState.CONFIRMED }

    override fun toString(): String = "EraseSheetState.Progress(${local.code}, ${nas.code}, $confirmed/${devices.size})"
  }

  /** "Fatto ovunque" (R12): cancellato dal NAS e **tutti** i dispositivi hanno confermato. Mai per la cancellazione solo locale e mai per "niente da cancellare". */
  data object Done : EraseSheetState

  /** R12: la cancellazione era solo locale (D34, niente sul NAS): "Cancellati da questo dispositivo". Non dice niente del NAS ne' degli altri dispositivi. */
  data object DoneHere : EraseSheetState

  /** R12: non c'era niente da cancellare qui (Persone non attiva su questo dispositivo, o voce che non si capisce): non e' stato toccato niente. */
  data object NothingToErase : EraseSheetState

  /** Tutto fatto tranne le conferme di [devices] (spenti o lontani): finiranno da soli quando tornano. */
  data class WaitingDevices(val devices: List<DeviceAck>) : EraseSheetState {
    override fun toString(): String = "EraseSheetState.WaitingDevices(${devices.size})"
  }
}
