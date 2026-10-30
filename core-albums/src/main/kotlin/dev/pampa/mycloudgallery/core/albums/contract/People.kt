package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.flow.StateFlow

// LE PERSONE NEGLI ALBUM SMART (docs/fase8-progetto.md §1.2 "core-albums", §2.7 "Album smart", D27, D28, E-F37). Firme di P0; la regola
// nel motore smart, il campo `smart:people` (`any:<id>,<id>` o `all:…`) e l'editor sono di P10. **core-albums non vede core-faces**: le
// classi arrivano dalla porta [PeopleMembership], che :app lega sopra `PeopleIndex` di core-faces e le chiavi di contenuto dei media.
// Nessun dato dei volti nell'area comune: di un album smart condiviso si condividono solo le foto, con la conferma (F7-D17).

/**
 * La regola "con queste persone" (D27): una o piu' persone, "una qualunque" o "tutte". Al piu' [MAX_PEOPLE] persone, senza ripetizioni.
 * Gli id seguono gli alias (una persona unita in un'altra resta valida: `PeopleIndex.resolve`); una persona dimenticata da' l'avviso
 * "Una persona della regola non e' piu' riconosciuta".
 */
data class PeopleRule(val ids: List<PersonId>, val mode: PeopleMode) {
  init {
    require(ids.isNotEmpty() && ids.size <= MAX_PEOPLE) { "Da 1 a $MAX_PEOPLE persone." }
    require(ids.toSet().size == ids.size) { "Persone ripetute." }
  }

  /** Mai gli id delle persone (D37). */
  override fun toString(): String = "PeopleRule(${ids.size}, ${mode.code})"

  companion object {
    /** §2.7: al piu' 32 persone (sotto i 1.024 caratteri del campo personale). */
    const val MAX_PEOPLE: Int = 32
  }
}

/**
 * Le classi della regola delle persone (D27, come D14 della Fase 7): **sicuro** = un volto assegnato (automatico sopra la soglia, o
 * confermato) a ciascuna persona richiesta (con "tutte") o a una (con "una qualunque"); **suggerito** = almeno un volto nella fascia
 * "forse". Un media senza nessuna delle due non compare nel risultato.
 */
enum class PeopleClass { SURE, LIKELY }

/**
 * Chi sa quali media contengono le persone (D27), legato da :app (P10, P13). [available] e' falso dove i volti non ci sono (Persone
 * spenta, in attesa dell'avviso, dispositivo escluso): allora la regola **non inserisce niente** e l'album lo dice ("Le persone non sono
 * attive su questo dispositivo", E-F37). [classify] guarda solo [candidates] (che il motore prende da `VisibleMedia`: mai un nascosto,
 * D28) e restituisce solo quelli che stanno in una classe. **Lancia** se adesso non sa (i volti non si leggono, le persone non sono montate): "non so" non vale "nessuno" e il
 * motore non scrive niente (R10, correzioni D).
 */
interface PeopleMembership {
  val available: StateFlow<Boolean>

  suspend fun classify(rule: PeopleRule, candidates: Collection<MediaId>): Map<MediaId, PeopleClass>

  /**
   * Quante persone della [rule] non sono piu' riconosciute (dimenticate o sconosciute: `PeopleIndex.resolve` null, E-F27): "Una persona della regola non e'
   * piu' riconosciuta". Una persona unita si segue (alias) e una nascosta vale ancora (E-F28): non contano. Di serie nessuna.
   */
  suspend fun unrecognized(rule: PeopleRule): Int = 0
}
