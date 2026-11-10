package dev.pampa.mycloudgallery.core.widget.testing

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemories
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlanner
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPrefs
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetThumbs
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Il pianificatore di riferimento (§5.2): sceglie **per forza bruta**, con tutti i controlli a ogni elemento dell'istantanea, senza
 * `WidgetFeed` (gira da se' su tutti i candidati, dal piu' recente, con un cursore condiviso fra i widget: E-W11). P2 confronta con lui il
 * `DefaultWidgetPlanner` su 5.000 stati casuali: stesso modo, stesso ricordo, stesso motivo di un messaggio, e ogni foto del vero dentro
 * [eligiblePhotos] (l'ordine della rotazione e' diverso per costruzione).
 *
 * Regole (D3-D8, Q1 (a), Q3 (c)): esclusi screenshot, documenti, immagini animate; mai un nascosto ne' un media non visibile; solo media con
 * miniatura locale; con il blocco dell'app solo media anche sul telefono, nessun ricordo, modo [WidgetMode.Locked]; il ricordo solo se non e'
 * `PERSON` e ha almeno [WidgetRules.MEMORY_MIN_VISIBLE] elementi visibili, con la copertina come cella 0 (o il primo elemento che si puo'
 * mostrare); un errore o il budget superato = `Message(ERROR)`; istantanea assente = `Message(NOT_READY)`.
 */
class ReferenceWidgetPlanner(
  private val visible: VisibleMedia,
  private val mask: HiddenMask,
  private val memories: WidgetMemories,
  private val thumbs: WidgetThumbs,
  private val lock: WidgetLock,
  private val prefs: WidgetPrefs,
) : WidgetPlanner {

  private val epochs = HashMap<WidgetSlot, Long>()
  private var cursor = 0

  override suspend fun plan(slot: WidgetSlot, size: WidgetSize, budgetMs: Long): WidgetPlan {
    val epoch = (epochs[slot] ?: 0L) + 1
    epochs[slot] = epoch
    val decided = try {
      withTimeoutOrNull(budgetMs) { decide(size) } ?: Decision(WidgetMode.Message(WidgetEmpty.ERROR), emptyList())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Decision(WidgetMode.Message(WidgetEmpty.ERROR), emptyList())
    }
    return WidgetPlan(slot, size, decided.mode, decided.cells, epoch)
  }

  private class Decision(val mode: WidgetMode, val cells: List<WidgetCell>)

  private fun message(reason: WidgetEmpty) = Decision(WidgetMode.Message(reason), emptyList())

  private suspend fun decide(size: WidgetSize): Decision {
    val snapshot = visible.snapshot.value ?: return message(WidgetEmpty.NOT_READY)
    val locked = lock.appLocked.value
    val choice = prefs.choice.value
    if (choice != WidgetChoice.PHOTOS_ONLY && !locked) {
      val memory = memoryDecision(snapshot, size)
      if (memory != null) return memory
      if (choice == WidgetChoice.MEMORIES_ONLY) return message(WidgetEmpty.NO_MEMORY_TODAY)
    }
    if (choice == WidgetChoice.MEMORIES_ONLY && locked) return message(WidgetEmpty.NO_MEMORY_TODAY)
    return photosDecision(snapshot, size, locked)
  }

  /** Tutti i media che il widget potrebbe mostrare come foto adesso (per la parita' con il pianificatore vero). */
  suspend fun eligiblePhotos(): Set<MediaId> {
    val snapshot = visible.snapshot.value ?: return emptySet()
    return eligible(snapshot, lock.appLocked.value).shown
  }

  private class Eligible(val shown: LinkedHashSet<MediaId>, val anyVisible: Boolean, val anyOnPhone: Boolean)

  private suspend fun eligible(snapshot: TimelineSnapshot, locked: Boolean): Eligible {
    val candidates = (0 until snapshot.size).filter { !excluded(snapshot, it) }.map { snapshot.idAt(it) }
    val ok = checkedVisible(candidates)
    val onPhone = if (locked) {
      val phone = lock.onPhone(ok)
      ok.filterTo(LinkedHashSet()) { it in phone }
    } else {
      ok
    }
    val withThumb = thumbs.local(onPhone)
    return Eligible(onPhone.filterTo(LinkedHashSet()) { it in withThumb }, ok.isNotEmpty(), onPhone.isNotEmpty())
  }

  private suspend fun photosDecision(snapshot: TimelineSnapshot, size: WidgetSize, locked: Boolean): Decision {
    val eligible = eligible(snapshot, locked)
    if (!eligible.anyVisible) return message(WidgetEmpty.NO_MEDIA)
    if (locked && !eligible.anyOnPhone) return message(WidgetEmpty.LOCKED_NOTHING_ON_PHONE)
    if (eligible.shown.isEmpty()) return message(WidgetEmpty.NO_LOCAL_THUMBS)
    val list = eligible.shown.toList()
    val take = minOf(size.cells, list.size)
    val picked = List(take) { list[(cursor + it) % list.size] }
    cursor = (cursor + take) % list.size
    val final = finalFilter(picked)
    if (final.isEmpty()) return message(WidgetEmpty.ERROR)
    return Decision(if (locked) WidgetMode.Locked else WidgetMode.Photos, cells(snapshot, final))
  }

  private suspend fun memoryDecision(snapshot: TimelineSnapshot, size: WidgetSize): Decision? {
    val pick = memories.today() ?: return null
    if (pick.kind == MemoryKind.PERSON) return null
    val visibleMembers = checkedVisible(pick.members)
    if (visibleMembers.size < WidgetRules.MEMORY_MIN_VISIBLE) return null
    val ordered = listOf(pick.cover) + pick.members.filter { it != pick.cover }
    val showable = ordered.filter { it in visibleMembers }.filter { id -> snapshot.indexOf(id).let { it >= 0 && !excluded(snapshot, it) } }
    val withThumb = thumbs.local(showable)
    val picked = showable.filter { it in withThumb }.take(size.cells)
    if (picked.isEmpty()) return null
    val final = finalFilter(picked)
    if (final.size != picked.size || final.isEmpty()) return null
    return Decision(WidgetMode.Memory(pick.id, pick.title), cells(snapshot, final))
  }

  /** Visibili adesso e non nascosti, con le letture che lanciano (un errore non passa per una Libreria vuota). */
  private suspend fun checkedVisible(ids: Collection<MediaId>): LinkedHashSet<MediaId> {
    if (ids.isEmpty()) return LinkedHashSet()
    val shown = visible.visibleOrThrow(ids)
    val hidden = mask.hiddenAmongOrThrow(ids)
    return ids.filterTo(LinkedHashSet()) { it in shown && it !in hidden }
  }

  /** Il filtro finale (D5), subito prima di consegnare: ricontrolla visibili e nascosti su ogni cella. */
  private suspend fun finalFilter(ids: List<MediaId>): List<MediaId> {
    val ok = checkedVisible(ids)
    return ids.filter { it in ok }
  }

  private fun excluded(snapshot: TimelineSnapshot, i: Int): Boolean =
    snapshot.kindAt(i) == MediaKind.ANIMATED_IMAGE ||
      snapshot.sourceAt(i) == MediaSource.SCREENSHOT ||
      CellFlags.has(snapshot.flagsAt(i), CellFlags.DOCUMENT)

  private fun cells(snapshot: TimelineSnapshot, ids: List<MediaId>): List<WidgetCell> = ids.mapIndexed { index, id ->
    val i = snapshot.indexOf(id)
    WidgetCell(index, id, snapshot.kindAt(i), snapshot.localDayAt(i))
  }
}
