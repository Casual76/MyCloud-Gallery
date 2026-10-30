package dev.pampa.mycloudgallery.feature.memories.sheets

import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.memories.contract.LessSheetState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesMessage
import dev.pampa.mycloudgallery.feature.memories.contract.UndoKind
import dev.pampa.mycloudgallery.feature.memories.contract.UndoOffer
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * I fogli "Mostra meno" e "Non ricordarmi questo periodo" e l'**"Annulla" per 10 secondi** (docs/fase9-progetto.md §1.5 `LessSheetState`, §2.3, D11,
 * E-MEM9, E-MEM11, E-MEM12, E-MEM22), senza Compose: la pagina del ricordo lo usa per le sue azioni. Non sa niente di nomi salvati: il nome della
 * persona arriva gia' letto e vive solo nello stato del foglio.
 *
 *  - **Persona**: due livelli, `LESS` ("Mostra meno": niente ricordi dedicati, mai copertina) e `NEVER` ("Mai nei ricordi"); "Togli la scelta" la cancella.
 *    La scelta di adesso si legge da [Memories.lessChoices] e il foglio la mostra.
 *  - **Periodo**: i giorni di partenza sono quelli del ricordo, al piu' [LessTarget.Days.MAX_DAYS]; i due estremi si spostano di un giorno alla volta,
 *    mai oltre i limiti (almeno un giorno, al piu' 366). Vale solo "Mai" (D11).
 *  - **Effetto immediato**: `Memories.setLess` ripulisce subito lo scaffale; chi mostra la notifica la rivaluta (D19, P13). Poi, per 10 secondi,
 *    [undo] offre "Annulla": la scelta torna come prima. Passati i 10 secondi la scelta resta.
 *
 * Il tempo e' quello del [scope] (`delay`): nei test e' virtuale. Nessun log: niente nomi, ne' id, ne' giorni.
 */
class LessFlow(
  private val memories: Memories,
  private val scope: CoroutineScope,
  private val onFailure: (MemoriesMessage) -> Unit = {},
) {
  private val sheetState = MutableStateFlow<LessSheetState?>(null)

  /** Il foglio aperto ([LessSheetState.Person], [LessSheetState.Days]), l'ultima scelta fatta ([LessSheetState.Done]) o [LessSheetState.Undone]; null = niente. */
  val sheet: StateFlow<LessSheetState?> = sheetState.asStateFlow()

  /** Cosa si puo' annullare adesso (per 10 secondi), o null. Include "Non mostrare questo ricordo" ([offerDismissUndo]). */
  private val undoState = MutableStateFlow<UndoOffer?>(null)
  val undo: StateFlow<UndoOffer?> = undoState.asStateFlow()

  private var pending: (suspend () -> Unit)? = null
  private var timer: Job? = null

  // --- Persona ----------------------------------------------------------------------------------------------------------------------

  /** Apre "Mostra meno <nome>" per [person] ([name] gia' letto, null = "Questa persona"). */
  fun openPerson(person: PersonId, name: String?) {
    sheetState.value = LessSheetState.Person(person, name, levelOf(person))
  }

  /** Sceglie [level] per la persona del foglio aperto; `null` = "Togli la scelta". Chiude il foglio e offre "Annulla" (non per "Togli"). */
  fun choose(level: LessLevel?) {
    val open = sheetState.value as? LessSheetState.Person ?: return
    val before = open.level
    if (level == before) {
      sheetState.value = null
      return
    }
    sheetState.value = null
    scope.launch {
      if (!attempt { memories.setLess(LessTarget.Person(open.person), level) }) return@launch
      if (level == null) return@launch
      sheetState.value = LessSheetState.Done(LessChoice(LessTarget.Person(open.person), level, open.name))
      offer(UndoKind.LESS_PERSON) { memories.setLess(LessTarget.Person(open.person), before) }
    }
  }

  // --- Periodo ----------------------------------------------------------------------------------------------------------------------

  /** Apre "Non ricordarmi questo periodo", con i giorni del ricordo (al piu' 366). */
  fun openDays(range: DayRange) {
    sheetState.value = LessSheetState.Days(clip(range))
  }

  /** Sposta gli estremi del periodo del foglio aperto: [fromDelta] giorni per l'inizio, [toDelta] per la fine. Mai oltre 366 giorni, mai sotto uno. */
  fun adjustDays(fromDelta: Int, toDelta: Int) {
    val open = sheetState.value as? LessSheetState.Days ?: return
    sheetState.value = LessSheetState.Days(adjust(open.range, fromDelta, toDelta))
  }

  /** Il periodo ridotto al solo primo giorno ("Solo un giorno"). */
  fun onlyFirstDay() {
    val open = sheetState.value as? LessSheetState.Days ?: return
    sheetState.value = LessSheetState.Days(DayRange.of(open.range.from))
  }

  /** Il periodo del foglio rimesso com'era all'apertura, ad esempio quello di tutto il ricordo. */
  fun resetDays(range: DayRange) {
    if (sheetState.value is LessSheetState.Days) sheetState.value = LessSheetState.Days(clip(range))
  }

  /** "Non ricordare questo periodo": la scelta vale subito, con "Annulla" per 10 secondi. */
  fun confirmDays() {
    val open = sheetState.value as? LessSheetState.Days ?: return
    val target = LessTarget.Days(open.range)
    sheetState.value = null
    scope.launch {
      if (!attempt { memories.setLess(target, LessLevel.NEVER) }) return@launch
      sheetState.value = LessSheetState.Done(LessChoice(target, LessLevel.NEVER, null))
      offer(UndoKind.NEVER_DAYS) { memories.setLess(target, null) }
    }
  }

  // --- Chiudere, annullare ----------------------------------------------------------------------------------------------------------

  /** Chiude il foglio aperto senza fare niente. */
  fun dismissSheet() {
    if (sheetState.value is LessSheetState.Person || sheetState.value is LessSheetState.Days) sheetState.value = null
  }

  /** "Non mostrare questo ricordo" e' gia' stato fatto da chi chiama: offre "Annulla" per 10 secondi (il ricordo ritorna con lo stesso id). */
  fun offerDismissUndo(id: MemoryId) {
    offer(UndoKind.DISMISS) { memories.dismiss(id, false) }
  }

  /** "Annulla": rimette la scelta com'era. Non fa niente se il tempo e' finito. */
  fun undo() {
    val restore = pending ?: return
    timer?.cancel()
    pending = null
    undoState.value = null
    scope.launch {
      if (attempt { restore() }) sheetState.value = LessSheetState.Undone
    }
  }

  /** Ferma il conto alla rovescia (la schermata si chiude); la scelta resta. */
  fun close() {
    timer?.cancel()
    pending = null
  }

  private fun offer(kind: UndoKind, restore: suspend () -> Unit) {
    timer?.cancel()
    pending = restore
    undoState.value = UndoOffer(kind)
    timer = scope.launch {
      delay(UndoOffer.WINDOW_SECONDS * 1_000L)
      pending = null
      undoState.value = null
      if (sheetState.value is LessSheetState.Done) sheetState.value = null
    }
  }

  private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
    block()
    true
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    onFailure(MemoriesMessage.FAILED)
    false
  }

  private fun levelOf(person: PersonId): LessLevel? =
    memories.lessChoices.value.firstOrNull { (it.target as? LessTarget.Person)?.person == person }?.level

  companion object {
    /** Il periodo ridotto a [LessTarget.Days.MAX_DAYS] giorni dall'inizio. */
    fun clip(range: DayRange): DayRange =
      if (range.days <= LessTarget.Days.MAX_DAYS) range else DayRange(range.from, range.from.plusDays(LessTarget.Days.MAX_DAYS - 1L))

    /** Sposta gli estremi di [range]; il risultato ha almeno un giorno e al piu' 366 (se l'inizio supera la fine, la fine lo segue). */
    fun adjust(range: DayRange, fromDelta: Int, toDelta: Int): DayRange {
      var from = range.from.plusDays(fromDelta.toLong())
      var to = range.to.plusDays(toDelta.toLong())
      if (to.isBefore(from)) {
        if (fromDelta != 0 && toDelta == 0) to = from else from = to
      }
      if (ChronoUnit.DAYS.between(from, to) + 1 > LessTarget.Days.MAX_DAYS) {
        if (toDelta != 0) from = to.minusDays(LessTarget.Days.MAX_DAYS - 1L) else to = from.plusDays(LessTarget.Days.MAX_DAYS - 1L)
      }
      return DayRange(from, to)
    }
  }
}
