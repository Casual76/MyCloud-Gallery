package dev.pampa.mycloudgallery.feature.memories.page

import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryOrigin
import dev.pampa.mycloudgallery.core.memories.contract.MemoryView
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesMessage
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryAction
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryHeader
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageUi
import dev.pampa.mycloudgallery.feature.memories.sheets.LessFlow
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * LA PAGINA DEL RICORDO (Fase 9, P9; docs/fase9-progetto.md §1.5 `MemoryPageState`, §2.4, D9, D10, D13, D19, D31, E-MEM5, E-MEM13, E-H8). Il titolo
 * grande, il sottotitolo, "Riproduci", la griglia degli elementi e il menu. Non sa niente di Compose.
 *
 *  - **Istantanea**: il primo fotogramma ha gia' il titolo, il conto e la copertina (la voce dello scaffale, [MemoryPageUi.preview]); la griglia
 *    arriva appena `Memories.memory` risponde. Mai un'attesa sul NAS, mai la Libreria da rileggere.
 *  - **La vista e' viva**: `Memories.memory` si rifa' a ogni cambio dell'istantanea (un nascosto esce subito, E-MEM5); in piu' il **filtro finale**
 *    dei Nascosti ([FinalFilter]) toglie chi e' stato nascosto un attimo fa (D31). Sotto [Memory.MIN_MEMBERS] elementi: [MemoryPageState.Gone]
 *    ("Questo ricordo non c'e' piu'") e ritorno alla pagina Ricordi (D19).
 *  - **"Visto" si segna qui**, una volta sola, la prima volta che la pagina e' pronta (D10): mai dalla sola sezione.
 *  - **Le azioni** ([MemoryAction]) sono quelle del menu: "Mostra meno <nome>" solo per un ricordo `PERSON` con il nome (cioe' con Persone attiva
 *    qui), "Non ricordarmi questo periodo", "Non mostrare questo ricordo" (con "Annulla" per 10 secondi) e "Apri l'album" per un viaggio diventato
 *    album (E-MEM13). "Riproduci" apre la presentazione: lo fa la rotta.
 */
class MemoryPageController(
  private val id: MemoryId,
  private val memories: Memories,
  private val names: MemoryNames,
  private val filter: FinalFilter,
  private val scope: CoroutineScope,
) {
  private val message = MutableStateFlow<MemoriesMessage?>(null)
  private val less = LessFlow(memories, scope, onFailure = { message.value = it })

  @Volatile private var everReady = false
  @Volatile private var markedSeen = false

  /** La memoria di adesso, per le azioni (il periodo e la persona). */
  @Volatile private var current: Current? = null

  private class Current(val memory: Memory, val personName: String?)

  private val preview: MemoryCard? = memories.shelf.value.items.firstOrNull { it.id == id }

  private val page: StateFlow<MemoryPageState> = memories.memory(id).transformLatest { view -> emit(stateOf(view)) }
    .stateIn(scope, SharingStarted.Eagerly, MemoryPageState.Loading)

  val ui: StateFlow<MemoryPageUi> = combine(page, less.sheet, less.undo, message) { state, sheet, undo, msg ->
    MemoryPageUi(page = state, preview = if (state is MemoryPageState.Ready) null else preview, less = sheet, undo = undo, message = msg)
  }.stateIn(scope, SharingStarted.Eagerly, MemoryPageUi(MemoryPageState.Loading, preview))

  private suspend fun stateOf(view: MemoryView?): MemoryPageState {
    if (view == null) {
      current = null
      // Mai pronta e non sullo scaffale: non c'e' mai stato (o l'id e' vecchio); altrimenti e' uscito (sotto 3 elementi, scartato, D19).
      return if (everReady || preview != null || memories.shelf.value.items.any { it.id == id }) MemoryPageState.Gone else MemoryPageState.NotFound
    }
    val shown = filter.shown(view.view)
    if (shown.size < Memory.MIN_MEMBERS) {
      current = null
      return MemoryPageState.Gone
    }
    val snapshot = view.view.snapshot
    val live = if (shown.size == view.view.size) view.view else IndexOrderedTimelineView.of(snapshot, shown)
    val cover = if (view.cover in shown) view.cover else shown.first()
    val memory = view.memory
    val personName = names.person(memory.title.person) ?: view.personName
    current = Current(memory, personName)
    everReady = true
    markSeenOnce()
    val header = MemoryHeader(id, memory.kind, memory.title, personName, names.places(memory.title.placeIds))
    return MemoryPageState.Ready(header, live, cover, actionsOf(memory, personName), memory.origin)
  }

  private fun actionsOf(memory: Memory, personName: String?): Set<MemoryAction> = buildSet {
    add(MemoryAction.PLAY)
    // "Mostra meno <nome>": solo un ricordo di una persona, e solo se il nome si legge (Persone attiva qui, D12).
    if (memory.kind == MemoryKind.PERSON && memory.title.person != null && personName != null) add(MemoryAction.LESS_PERSON)
    add(MemoryAction.NEVER_DAYS)
    add(MemoryAction.DISMISS)
    if (memory.origin is MemoryOrigin.TripAlbum) add(MemoryAction.OPEN_ALBUM)
  }

  /** "Visto": la prima volta che la pagina e' pronta, mai dalla sola sezione (D10). Viaggia con il giorno locale. */
  private fun markSeenOnce() {
    if (markedSeen) return
    markedSeen = true
    scope.launch { guarded { memories.markSeen(id) } }
  }

  // --- Le azioni del menu ------------------------------------------------------------------------------------------------------------

  /** "Mostra meno <nome>": apre il foglio. */
  fun lessPerson() {
    val c = current ?: return
    val person = c.memory.title.person ?: return
    less.openPerson(person, c.personName)
  }

  /** "Non ricordarmi questo periodo": apre il foglio con i giorni del ricordo. */
  fun neverDays() {
    val c = current ?: return
    less.openDays(c.memory.title.days)
  }

  /** I giorni con cui il foglio si e' aperto (il periodo del ricordo, ridotto a 366): "Tutto il periodo del ricordo" li rimette. */
  fun wholePeriod(): DayRange? = current?.memory?.title?.days?.let(LessFlow::clip)

  fun resetDays() {
    wholePeriod()?.let { less.resetDays(it) }
  }

  fun adjustDays(fromDelta: Int, toDelta: Int) = less.adjustDays(fromDelta, toDelta)

  fun onlyFirstDay() = less.onlyFirstDay()

  fun confirmDays() = less.confirmDays()

  fun chooseLess(level: dev.pampa.mycloudgallery.core.memories.contract.LessLevel?) = less.choose(level)

  fun dismissSheet() = less.dismissSheet()

  /** "Non mostrare questo ricordo": vale subito e su tutti i dispositivi (E-MEM9); "Annulla" lo riporta entro 10 secondi. */
  fun dismissMemory() {
    scope.launch {
      if (guarded { memories.dismiss(id) }) less.offerDismissUndo(id)
    }
  }

  /** "Annulla" (entro 10 secondi). */
  fun undo() = less.undo()

  /** L'album del viaggio, se c'e' ("Apri l'album", E-MEM13). */
  fun album(): AlbumId? = (current?.memory?.origin as? MemoryOrigin.TripAlbum)?.album

  fun dismissMessage() {
    message.value = null
  }

  /** La schermata si chiude: il conto alla rovescia di "Annulla" si ferma, la scelta resta. */
  fun close() = less.close()

  private suspend fun guarded(block: suspend () -> Unit): Boolean = try {
    block()
    true
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    message.value = MemoriesMessage.FAILED
    false
  }
}
