package dev.pampa.mycloudgallery.core.personal.contract

import kotlinx.coroutines.flow.StateFlow

// Fase 6 (docs/fase6-progetto.md §1.3 "core-personal", D41, D48, D50): le impostazioni personali nuove, sincronizzate fra i
// dispositivi dello stesso account come le altre (4.7, l'ultima modifica vince con l'ora del NAS). Firme di P0:
//  - `SearchPersonalSettings` (lettura del testo, ordine dei risultati): l'implementazione in core-personal/.../settings/ e' di P8;
//  - `MotionPersonalSettings` (animazione delle foto in movimento, risposta Q16 (b)): l'implementazione
//    core-personal/.../settings/SyncedMotionSettings.kt e l'interruttore in Impostazioni della Libreria sono di P14.
// I campi delle voci sono in `PersonalFields` (SETTING_TEXT_READING, SETTING_SEARCH_ORDER, SETTING_MOTION_ANIMATION).

/**
 * La scelta dell'account sulla lettura del testo nelle foto (D41). [acceptedTextVersion] e' la versione del testo della pagina
 * di attivazione che l'utente ha letto: se l'app ne mostra una piu' nuova (per esempio dopo una cattura che trova altro), la
 * funzione si richiede.
 */
sealed interface TextReadingChoice {
  /** Spenta (default): ML Kit non si inizializza su nessun dispositivo dell'account. */
  data object Off : TextReadingChoice

  data class OnForAccount(val acceptedTextVersion: Int) : TextReadingChoice {
    init {
      require(acceptedTextVersion > 0) { "Versione del testo non valida." }
    }
  }

  companion object {
    /** Dal valore della voce ([PersonalValue.Number]); un valore che non e' un numero positivo = spenta. */
    fun fromValue(value: PersonalValue?): TextReadingChoice {
      val n = (value as? PersonalValue.Number)?.value ?: return Off
      return if (n in 1..Int.MAX_VALUE.toLong()) OnForAccount(n.toInt()) else Off
    }

    fun toValue(choice: TextReadingChoice): PersonalValue = when (choice) {
      Off -> PersonalValue.Number(0)
      is OnForAccount -> PersonalValue.Number(choice.acceptedTextVersion.toLong())
    }
  }
}

/** L'ordine dei risultati della ricerca come impostazione (D48). core-search ha il suo `ResultOrder`: :app li lega per codice. */
enum class SearchOrderSetting(val code: String) {
  /** "Recenti", il default. */
  RECENT("recent"),

  /** "Pertinenti". */
  RELEVANT("relevant"),
  ;

  companion object {
    /** Un codice sconosciuto (una versione piu' nuova dell'app) = il default. */
    fun fromCode(code: String?): SearchOrderSetting = entries.firstOrNull { it.code == code } ?: RECENT
  }
}

/** Le impostazioni personali della ricerca (D41, D48). Implementazione di P8. */
interface SearchPersonalSettings {
  val textReading: StateFlow<TextReadingChoice>

  suspend fun setTextReading(choice: TextReadingChoice)

  val searchOrder: StateFlow<SearchOrderSetting>

  suspend fun setSearchOrder(order: SearchOrderSetting)
}

/** L'impostazione personale delle foto in movimento (D50, Q16 (b)). Implementazione di P14. */
interface MotionPersonalSettings {
  /** "Animazione delle foto in movimento": accesa di default. Spenta = mai l'animazione d'apertura; il badge funziona sempre. */
  val motionAnimation: StateFlow<Boolean>

  suspend fun setMotionAnimation(on: Boolean)
}
