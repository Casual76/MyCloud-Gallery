package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// L'ATTIVAZIONE PER ACCOUNT, L'AVVISO E L'ESCLUSIONE (docs/fase8-progetto.md §1.2 "Stato", "Interruttore", §2.1, D3-D5, D30, D34, E-F7-E-F12).
// Contratti di P0; l'interruttore vero (switch/) e' di P7, la pagina di attivazione e l'avviso (feature-people/.../activation/) pure. Lo
// stesso modello della lettura del testo (F6-D41), ma qui nulla esce dalla casa e i dati sono **solo dell'account**. La scelta vale per
// l'account (voce `faces` dei dati personali, con la generazione e la versione del testo, `FacesSettingCodec` di core-personal); l'esclusione
// di un dispositivo ("Non su questo dispositivo") e' **locale**, mai una voce. Mai la generazione nei log (D37).

/** Cosa fa questo dispositivo quando Persone e' attiva per l'account (D4, D34). */
enum class HereState {
  /** Calcola (se e' un indicizzatore), riceve e mostra; sincronizza con il NAS sulle share di prova. */
  ACTIVE,

  /** "Non su questo dispositivo": niente calcolo, nessuna copia dei dati dei volti, dati locali cancellati (chiave per prima, D14). */
  EXCLUDED,

  /** La recinzione delle share vere (D34, `facesSyncTestSharesOnly`): tutto in locale, niente sul NAS, nomi e correzioni per dispositivo. */
  LOCAL_ONLY,
}

/** Lo stato di Persone visto da QUESTO dispositivo (§1.2, §2.1). */
sealed interface FacesState {
  /** Spenta per l'account (voce assente o `off`): la sezione Persone spiega cosa fa, con "Attiva riconoscimento dei volti". */
  data object Off : FacesState

  /** Attiva per l'account con la generazione [generation]; [here] dice cosa fa questo dispositivo. */
  data class On(val generation: FaceGeneration, val here: HereState) : FacesState {
    override fun toString(): String = "FacesState.On($here)"
  }

  /**
   * Attiva per l'account (generazione [generation], testo versione [textVersion]), ma **questo dispositivo non ha ancora visto l'avviso**
   * (D4, E-F7): la voce e' arrivata da un altro dispositivo, o il testo e' cambiato. Finche' l'utente non sceglie qui, **niente si scarica,
   * si calcola o si mostra**. Non e' un'esclusione: l'utente non ha deciso niente.
   */
  data class AwaitingNotice(val generation: FaceGeneration, val textVersion: Int) : FacesState {
    override fun toString(): String = "FacesState.AwaitingNotice(v$textVersion)"
  }

  /** "Disattiva e cancella" della generazione [generation] in corso (D29, §2.8): calcolo e pubblicazione fermi, si cancella. */
  data class Erasing(val generation: FaceGeneration) : FacesState {
    override fun toString(): String = "FacesState.Erasing"
  }

  /** La voce `faces` ha una forma che questa versione non capisce (E-F12): niente calcolo, niente pubblicazione, nessuna cancellazione. */
  data object NeedsUpdate : FacesState

  /** Vero quando questo dispositivo puo' trattare dati dei volti adesso (calcolare, scaricare, mostrare). */
  val activeHere: Boolean get() = this is On && here != HereState.EXCLUDED

  /** La generazione che questo stato nomina, se ne nomina una. */
  val generationOrNull: FaceGeneration?
    get() = when (this) {
      is On -> generation
      is AwaitingNotice -> generation
      is Erasing -> generation
      Off, NeedsUpdate -> null
    }
}

/**
 * Il consenso dato sulla pagina di attivazione (§2.1, D3): la versione del testo letto e l'istante (orologio monotono) in cui la pagina era
 * sullo schermo. **La costruisce SOLO la pagina di attivazione di feature-people** (`activation/FacesActivation*.kt`, P7):
 * Phase8ArchitectureTest fallisce se un altro sorgente di produzione la costruisce. Senza un consenso Persone non si accende.
 */
class FacesActivationConsent(val textVersion: Int, val shownAtMonotonicNanos: Long) {
  init {
    require(textVersion > 0) { "Versione del testo non valida." }
  }

  override fun toString(): String = "Consenso a Persone, v$textVersion"
}

/** L'esito di [FacesSwitch.activate] (D3, D5, E-F10). */
sealed interface ActivateOutcome {
  /** Attivata con la generazione nuova [generation]: vale **subito** qui, anche senza l'ora del NAS ([FacesSwitch.syncPending]). */
  data class Activated(val generation: FaceGeneration) : ActivateOutcome {
    override fun toString(): String = "ActivateOutcome.Activated"
  }

  /** Nessuna area privata dichiarata, o uguale all'area comune (L23): "Serve un'area privata sul NAS". Nessuna voce scritta. */
  data object NoPrivateArea : ActivateOutcome

  /** Nessun indicizzatore dell'account negli ultimi 7 giorni (F6-D14); [canBecomeIndexer]: "Fallo su questo dispositivo". */
  data class NoIndexer(val canBecomeIndexer: Boolean) : ActivateOutcome

  /** Il consenso e' di un testo diverso da quello corrente ([FacesSwitch.CURRENT_TEXT_VERSION]): la pagina si rilegge. */
  data object StaleConsent : ActivateOutcome

  /** La voce dell'account ha una forma sconosciuta (E-F12): "Aggiorna l'app". */
  data object NeedsUpdate : ActivateOutcome

  /** Persone non e' disponibile su questo dispositivo (nessuna famiglia pronta, l'interruttore non e' legato): niente si accende. */
  data object Unavailable : ActivateOutcome
}

/**
 * L'esito di [FacesSwitch.reconcile] (D30): [Done] = lo stato locale e' allineato alla voce (i dati di una generazione non piu' corrente
 * sono cancellati, chiave per prima); [Failed] = non si e' potuto (il gancio risponde `Hold` e quel giro non pubblica). [code] stabile.
 */
sealed interface ReconcileOutcome {
  data object Done : ReconcileOutcome

  data class Failed(val code: String) : ReconcileOutcome
}

/**
 * L'interruttore di Persone (D3, D4, D30). [activate] la accende per l'account con una **generazione nuova** (gli altri dispositivi lo
 * dicono una volta e si possono escludere); [acceptNotice] chiude l'avviso su questo dispositivo; [excludeThisDevice] e
 * [includeThisDevice] valgono solo qui ("Attiva qui" scarica dal NAS senza generazione nuova; sotto la recinzione ricalcola).
 * "Disattiva e cancella" non sta qui: e' `FacesEraser` (P8), con la sua conferma.
 *
 * [reconcile] applica la voce `faces` letta dai dati personali (null = assente = spenta): se la voce nomina una generazione diversa da quella
 * locale (o `off`), cancella **prima** i dati locali di quella generazione (D30). E' il gancio `PrePublishHook` di `NasPersonalSync`
 * (legato da :app: `Failed` = `Hold`) e gira anche all'avvio. Una voce [FacesSetting.Unknown] non cancella niente.
 *
 * [fresh] (D30): vero solo dopo un giro **completo** dei dati personali in questo processo da meno di [FRESH_MILLIS] (orologio monotono);
 * senza, nessun dato dei volti si pubblica. [syncPending]: la scelta fatta qui non e' ancora arrivata agli altri dispositivi.
 */
interface FacesSwitch {
  val state: StateFlow<FacesState>

  /** Lo stato **adesso**, letto in modo sincrono: i lavori dei volti lo guardano prima di ogni passo (come la lettura del testo, R7). */
  fun currentState(): FacesState = state.value

  val fresh: StateFlow<Boolean>

  val syncPending: StateFlow<Boolean>

  /**
   * CE-3 (correzioni A): Persone e' spenta **qui** perche' un altro dispositivo dell'account ha fatto "Disattiva e cancella" e questo dispositivo ha tolto i
   * suoi dati. La sezione lo dice sopra "Attiva riconoscimento dei volti"; si spegne con una nuova attivazione. Di serie (fake, null-object) mai.
   */
  val offElsewhere: StateFlow<Boolean> get() = MutableStateFlow(false)

  /**
   * L350 (correzioni C): lo stato di sopra e' quello **vero**? Falso finche' le persone non sono montate in questo processo (la famiglia non e' ancora pronta:
   * pochi secondi dopo un avvio a freddo): allora [state] risponde `Off` come un null-object, e una schermata che lo mostrasse direbbe «Persone e' spenta» a chi
   * l'ha accesa (e «Attiva» risponderebbe «non disponibile»). Le pagine di Persone mostrano uno scheletro finche' e' falso (il principio del primo disegno
   * istantaneo: mai uno stato sbagliato). Vero anche quando il montaggio e' fallito o non puo' esserci (nessuna famiglia): li' `Off` e «non disponibile» sono la
   * verita'. Di serie (fake, null-object) sempre vero.
   */
  val known: StateFlow<Boolean> get() = MutableStateFlow(true)

  suspend fun activate(consent: FacesActivationConsent): ActivateOutcome

  suspend fun acceptNotice(textVersion: Int)

  suspend fun excludeThisDevice()

  suspend fun includeThisDevice()

  suspend fun reconcile(setting: FacesSetting?): ReconcileOutcome

  companion object {
    /** La versione corrente del testo della pagina di attivazione (§2.1). Se cambia, tutti i dispositivi tornano all'avviso. */
    const val CURRENT_TEXT_VERSION: Int = 1

    /** D30: quanto vale un giro completo dei dati personali per pubblicare dati dei volti (orologio monotono). */
    const val FRESH_MILLIS: Long = 15L * 60 * 1000
  }
}
