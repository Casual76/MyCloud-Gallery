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

  suspend fun enableForAccount(consent: TextReadingConsent)

  suspend fun excludeThisDevice()

  suspend fun includeThisDevice()

  suspend fun disable()

  companion object {
    /** La versione corrente del testo della pagina di attivazione (§2.6). Se cambia, la funzione si richiede. */
    const val CURRENT_TEXT_VERSION: Int = 1
  }
}
