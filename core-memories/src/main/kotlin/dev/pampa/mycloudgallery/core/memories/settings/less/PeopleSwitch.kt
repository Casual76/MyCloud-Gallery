package dev.pampa.mycloudgallery.core.memories.settings.less

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Che cosa fa Persone nell'**account** (docs/fase9-progetto.md D11, D37): e' il `FacesState` della Fase 8 visto da core-memories, che non vede
 * core-faces. [OFF] e [ERASING] valgono per tutti i dispositivi (la voce `faces` dei dati personali e' una sola): nessuna persona esiste piu', e
 * le scelte "Mostra meno" fatte su di loro non servono a niente (la lapide le toglie anche agli altri dispositivi).
 *
 * [UNKNOWN] e' "non lo so ancora" (all'avvio, prima del primo giro dei dati personali, o senza una porta legata): **non fa mai niente**.
 * La differenza con `MemoryPeople.available` e' voluta: quella e' falsa anche solo perche' *questo* dispositivo non ha ancora i volti pronti, e li'
 * cancellare le scelte fatte da un altro dispositivo sarebbe un danno per tutto l'account.
 */
enum class PeoplePhase {
  UNKNOWN,
  ON,
  OFF,
  ERASING,
  ;

  /** Persone e' spenta o si sta cancellando: le scelte sulle persone non hanno piu' senso. */
  val gone: Boolean get() = this == OFF || this == ERASING
}

/**
 * La porta dello stato di Persone per `PeopleLessJanitor` (D11): legata da :app (P13) a `FacesSwitch` (`Off`, `Erasing` e il resto). Solo uno
 * stato, nessun nome, nessun id.
 */
interface PeopleSwitch {
  val phase: StateFlow<PeoplePhase>
}

/** Nessuna porta legata: lo stato e' sempre [PeoplePhase.UNKNOWN] e il janitor non cancella niente. */
object NoPeopleSwitch : PeopleSwitch {
  override val phase: StateFlow<PeoplePhase> = MutableStateFlow(PeoplePhase.UNKNOWN).asStateFlow()
}
