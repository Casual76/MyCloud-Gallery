package dev.pampa.mycloudgallery.feature.people.activation

import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesRequirements
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesSwitch
import dev.pampa.mycloudgallery.feature.people.contract.ComputingDevice
import dev.pampa.mycloudgallery.feature.people.contract.EraseSheetState
import dev.pampa.mycloudgallery.feature.people.contract.FacesAnalyzeEstimate
import dev.pampa.mycloudgallery.feature.people.contract.FaceModelRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// LE PORTE DELLA PAGINA DI ATTIVAZIONE, DELL'AVVISO E DI IMPOSTAZIONI -> PERSONE (docs/fase8-progetto.md §1.4, §2.1, riga P7 del §4.4). Le lega
// :app (P13) una volta sola, con un'unica `ActivationPorts` (opzionale per Dagger, `ActivationPortsModule`): finche' non c'e', le schermate vedono i
// null-object di core-faces (Persone spenta e non accendibile) e nascondono cio' che non si puo' fare. Un solo oggetto, e non una porta per ogni
// pezzo, perche' Persone ha altri pacchetti (P8, P9) che dichiarano le loro porte opzionali: due dichiarazioni dello stesso tipo nel grafo
// romperebbero la compilazione di :app.

/**
 * Fare di questo dispositivo un indicizzatore (F6-D15), per chi attiva Persone senza nessun dispositivo che analizzi le foto ("Fallo su questo
 * dispositivo"). `:app` lo lega al ruolo Indicizzatore di Ricerca e modelli (stima compresa: la mostra quella pagina). Vero se il ruolo e' acceso.
 */
fun interface ActivationIndexerRole {
  suspend fun enable(): Boolean
}

/**
 * Il nome del dispositivo **dell'account** che ha acceso Persone, per l'avviso ("Attivato da Tablet di casa"): `:app` lo legge dall'autore della
 * voce `faces` e dal registro. Mai un familiare (Q3): solo i dispositivi dello stesso account. Null = non si sa.
 */
fun interface FacesActivatedBy {
  suspend fun label(): String?
}

/**
 * «Analizza ora» (CA-2, correzioni C; risposta dell'utente del 30/10): la stima di cio' che resta ("Analizzare le tue N foto richiede circa M minuti") e il tocco
 * confermato che chiede il calcolo adesso. **E' l'unico modo di scavalcare le regole di sottofondo** (notte, carica, Wi-Fi): il primo calcolo dopo l'accensione le segue
 * come ogni altro giro. Non scarica originali dal NAS: un media senza anteprima aspetta come sempre. `:app` lo lega a `FacesBacklog` e ai ritmi misurati.
 *
 * Contare cio' che resta rilegge il catalogo (L242): [estimate] gira fuori dal thread principale, mai sul percorso dell'avvio, e la pagina si disegna prima.
 */
interface FacesAnalyzeNow {
  /** La stima di adesso; null = non si sa (Persone non lavora qui, o la lettura non e' riuscita). Nessun errore: un guasto e' null. */
  suspend fun estimate(): FacesAnalyzeEstimate?

  /** Sui dati mobili, in roaming o oltre il tetto: la frase delle regole con la stima dei dati; null su Wi-Fi (le regole non chiedono niente). */
  suspend fun networkNote(estimate: FacesAnalyzeEstimate): String?

  /** Il tocco confermato: il calcolo parte adesso (un lavoro dell'utente, una richiesta alla volta). */
  fun start()
}

/** Cio' che Impostazioni -> Persone mostra oltre allo stato dell'interruttore: chi calcola, l'avanzamento, le persone nascoste, i modelli, la cancellazione. */
data class PeopleSettingsFacts(
  val devices: List<ComputingDevice> = emptyList(),
  val progress: FacesStatus = FacesStatus.Off,
  val hiddenPeople: Int = 0,
  val models: List<FaceModelRow> = emptyList(),
  /** L'avanzamento di "Disattiva e cancella", se c'e' (lo scrive P8). */
  val erase: EraseSheetState.Progress? = null,
  /** DR-1 (correzioni B): questo dispositivo ha il ruolo di Indicizzatore. Di serie si'; senza, la pagina dice che qui le persone non si calcolano. */
  val computesHere: Boolean = true,
) {
  override fun toString(): String = "PeopleSettingsFacts(${devices.size} dispositivi, nascoste=$hiddenPeople)"
}

/** Tutto cio' che le schermate di P7 chiedono a `:app`. */
interface ActivationPorts {
  val switch: FacesSwitch

  /** I requisiti dell'attivazione (D5) e la recinzione (D34). */
  val requirements: FacesRequirements

  /** Null = non si offre "Fallo su questo dispositivo". */
  val indexerRole: ActivationIndexerRole?

  /** Null = l'avviso non dice quale dispositivo ha attivato. */
  val activatedBy: FacesActivatedBy?

  /** Il resto di Impostazioni -> Persone; di serie vuoto. */
  val facts: StateFlow<PeopleSettingsFacts>

  /** CA-2: «Analizza ora» con la stima. Null = non si offre (di serie). */
  val analyze: FacesAnalyzeNow? get() = null
}

/** Persone spenta e non accendibile: i null-object di core-faces. Il default finche' `:app` non lega le porte vere. */
object NoActivationPorts : ActivationPorts {
  override val switch: FacesSwitch = NoFacesSwitch
  override val requirements: FacesRequirements = NoFacesRequirements
  override val indexerRole: ActivationIndexerRole? = null
  override val activatedBy: FacesActivatedBy? = null
  override val facts: StateFlow<PeopleSettingsFacts> = MutableStateFlow(PeopleSettingsFacts())
}
