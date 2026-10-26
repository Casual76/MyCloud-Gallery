package dev.pampa.mycloudgallery.core.ai.contract

import kotlinx.coroutines.flow.StateFlow

// La LETTURA DEL TESTO NELLE FOTO come funzione da attivare (docs/fase6-progetto.md D41, D42, E-A18, E-A19; risposte Q4 e Q13).
// Contratto di P0. Lo stato dell'account e' l'impostazione personale sincronizzata `text_reading` (core-personal); l'esclusione di
// questo dispositivo e' locale. L'implementazione la lega :app (P11); il motore OCR e il suo cancello `MlKitBootstrap` (l'UNICO
// che chiama `MlKit.initialize`, solo con [TextReadingState.OnForAccount] e `thisDevice = true`) sono di P4 in core-ai/.../ocr/.
//
// Spenta (default) = ML Kit non si inizializza e non manda niente: `MlKitInitProvider` e' tolto dal manifest fuso, nessuna classe
// fuori da core-ai/.../ocr/ nomina `com.google.mlkit` (Phase6ArchitectureTest), DataTransport resta inerte.

/** Lo stato della lettura del testo visto da QUESTO dispositivo (§1.5). */
sealed interface TextReadingState {
  /** Spenta per l'account (default). */
  data object Off : TextReadingState

  /** Attiva per l'account; [thisDevice] falso = questo dispositivo si e' escluso ("Non su questo dispositivo", E-A19). */
  data class OnForAccount(val thisDevice: Boolean) : TextReadingState

  /**
   * Attiva per l'account (con la versione [textVersion] del testo), ma **questo dispositivo non ha ancora visto l'avviso** (D41: "ogni altro
   * dispositivo dell'account lo dice una volta e si puo' escludere"; LT-2 e R8): la voce e' arrivata da un altro dispositivo, o il testo e'
   * cambiato. Finche' l'utente non conferma qui (o non si esclude, "Non su questo dispositivo") ML Kit non parte: nessuna lettura, nessuna
   * statistica. Non e' un'esclusione: l'utente non ha deciso niente.
   */
  data class AwaitingNotice(val textVersion: Int) : TextReadingState

  /** Spegnimento in corso: motore rilasciato, lavori di DataTransport annullati; la coda si toglie al riavvio (E-A18). */
  data object Disabling : TextReadingState

  /** Vero solo quando ML Kit puo' girare QUI. */
  val activeHere: Boolean get() = this is OnForAccount && thisDevice
}

/**
 * Il consenso dato sulla pagina di attivazione (§2.6): la versione del testo letto e l'istante (orologio monotono) in cui la pagina
 * era sullo schermo. **La costruisce SOLO la pagina di attivazione di feature-search** (P8, `TextReadingActivation*`):
 * Phase6ArchitectureTest fallisce se un altro sorgente di produzione la costruisce. Senza un consenso la funzione non si accende.
 */
class TextReadingConsent(val textVersion: Int, val shownAtMonotonicNanos: Long) {
  init {
    require(textVersion > 0) { "Versione del testo non valida." }
  }

  override fun toString(): String = "Consenso alla lettura del testo, v$textVersion"
}

/**
 * L'interruttore della lettura del testo (D41). [enableForAccount] la accende per tutti i dispositivi dell'account (gli altri lo
 * dicono una volta e si possono escludere); [excludeThisDevice] e [includeThisDevice] valgono solo qui; [disable] la spegne per
 * l'account: da quel momento ML Kit non parte piu', anche dopo il riavvio (F69).
 */
interface TextReadingSwitch {
  val state: StateFlow<TextReadingState>

  /**
   * Lo stato **adesso**, letto in modo sincrono, senza aspettare che [state] (un flusso che si aggiorna su un altro thread) si sia mosso:
   * e' quello che il cancello di ML Kit guarda a ogni lettura. Lo spegnimento e l'esclusione lo cambiano prima di qualunque altra cosa, cosi'
   * una lettura che parte un istante dopo non trova ancora "attiva" (R7). Di serie [state].
   */
  fun currentState(): TextReadingState = state.value

  /** Questo dispositivo si e' escluso ("Non su questo dispositivo")? Vale anche da spenta: l'esclusione e' locale e si puo' dare prima (R8). */
  val excludedHere: StateFlow<Boolean>

  /**
   * La scelta fatta qui non e' ancora arrivata agli altri dispositivi (il registro dei dati personali non c'e' o non ha l'ora del NAS): vale
   * qui subito, e viaggia alla prima sincronizzazione. La pagina lo dice (R6, I8).
   */
  val syncPending: StateFlow<Boolean>

  suspend fun enableForAccount(consent: TextReadingConsent)

  suspend fun excludeThisDevice()

  suspend fun includeThisDevice()

  suspend fun disable()

  companion object {
    /** La versione corrente del testo della pagina di attivazione (§2.6). Se cambia, la funzione si richiede. */
    const val CURRENT_TEXT_VERSION: Int = 1
  }
}
