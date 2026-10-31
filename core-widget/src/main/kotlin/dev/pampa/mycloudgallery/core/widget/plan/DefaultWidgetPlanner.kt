package dev.pampa.mycloudgallery.core.widget.plan

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.contract.WidgetFeed
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetClock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemories
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemoryPick
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
 * Il pianificatore del widget (D3-D8, §2.1; risposte Q1 (a) e Q3 (c)): decide che cosa mostra un widget di una misura, **subito prima** del
 * disegno. Il riferimento di forza bruta e' `ReferenceWidgetPlanner`: `WidgetPlannerContract` e la prova di parita' su 5.000 stati
 * (`DefaultWidgetPlannerParityTest`) li mettono a confronto.
 *
 * **Il giro** (per [plan]):
 * 1. istantanea assente = `Message(NOT_READY)` (E-W4: non si ridisegna, resta il contenuto gia' verificato);
 * 2. con il blocco dell'app acceso nessun ricordo e nessun titolo (Q3 (c)): solo foto che sono **anche sul telefono**, `WidgetMode.Locked`;
 * 3. senza blocco e se la scelta lo permette ([WidgetChoice]): il **ricordo di oggi** ([memoryDecision]), mai `PERSON`, con almeno
 *    [WidgetRules.MEMORY_MIN_VISIBLE] elementi visibili, la copertina come cella 0, solo elementi con la miniatura locale; altrimenti le foto;
 * 3. le **foto a caso** da [WidgetFeed] ([photosDecision]), senza screenshot, documenti e immagini animate, solo con la miniatura locale;
 * 4. **filtro finale** (`visibleOrThrow` e `hiddenAmongOrThrow`) sugli id scelti, come ultimo passo: un errore, o il budget superato, e'
 *    `Message(ERROR)`. Mai una lista non verificata.
 *
 * **Il tetto di [WidgetFeed]** (`MAX_SIZE` = 16, ma il triplo della misura grande, 21, non ci sta): le foto si chiedono **a giri**. Ogni giro
 * chiede `min(mancanti x [WidgetRules.OVERFETCH], 16)` media e ne tiene quelli che passano blocco e miniature; si va avanti finche' le celle
 * sono piene, oppure il feed ricomincia da capo (un id gia' visto: la rotazione ha fatto il giro intero, quindi non c'e' altro da trovare),
 * oppure si sono fatti [MAX_ROUNDS] giri (al piu' circa mille candidati: il costo di un piano non cresce con la Libreria). Il cursore del feed e'
 * condiviso (E-W11): due widget mostrano foto diverse.
 *
 * **L'epoca** di un piano parte dall'epoca registrata nel [ledger] per lo slot piu' uno: il contatore in memoria riparte da zero a ogni
 * processo, il registro su file no, e ignora i piani con un'epoca piu' vecchia.
 */
class DefaultWidgetPlanner(
  private val feed: WidgetFeed,
  private val memories: WidgetMemories,
  private val visible: VisibleMedia,
  private val mask: HiddenMask,
  private val thumbs: WidgetThumbs,
  private val lock: WidgetLock,
  private val prefs: WidgetPrefs,
  private val clock: WidgetClock,
  private val ledger: WidgetLedger,
) : WidgetPlanner {

  private val epochs = HashMap<WidgetSlot, Long>()

  override suspend fun plan(slot: WidgetSlot, size: WidgetSize, budgetMs: Long): WidgetPlan {
    val epoch = nextEpoch(slot)
    val started = clock.monotonicMs()
    val decided = try {
      withTimeoutOrNull(budgetMs.coerceAtLeast(1)) { decideWithLockRecheck(size, started, budgetMs) }
        ?: Decision(WidgetMode.Message(WidgetEmpty.ERROR), emptyList())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Un errore di lettura non e' una Libreria vuota ne' una lista da mostrare: nessuna foto (D5, punto 5). Nel log solo il tipo.
      GalleryLog.w(TAG) { "Piano del widget non riuscito (${e.javaClass.simpleName}): nessuna foto." }
      Decision(WidgetMode.Message(WidgetEmpty.ERROR), emptyList())
    }
    return WidgetPlan(slot, size, decided.mode, decided.cells, epoch)
  }

  private suspend fun nextEpoch(slot: WidgetSlot): Long {
    val registered = try {
      ledger.epochOf(slot)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      0L
    }
    return synchronized(epochs) {
      val next = maxOf(epochs[slot] ?: 0L, registered) + 1
      epochs[slot] = next
      next
    }
  }

  private class Decision(val mode: WidgetMode, val cells: List<WidgetCell>)

  private fun message(reason: WidgetEmpty) = Decision(WidgetMode.Message(reason), emptyList())

  /**
   * Decide e, se nel frattempo il blocco dell'app si e' **acceso**, decide da capo con il blocco: un piano fatto senza blocco (con un titolo e
   * con media solo sul NAS) non deve uscire a blocco acceso (Q3 (c)). Il caso opposto (il blocco si spegne) da' un piano piu' severo del
   * necessario e `lockChanged()` lo rifara'.
   */
  private suspend fun decideWithLockRecheck(size: WidgetSize, started: Long, budgetMs: Long): Decision {
    val lockedAtStart = lock.appLocked.value
    val first = decideFor(size, lockedAtStart, started, budgetMs)
    if (!lockedAtStart && lock.appLocked.value) return decideFor(size, locked = true, started, budgetMs)
    return first
  }

  private suspend fun decideFor(size: WidgetSize, locked: Boolean, started: Long, budgetMs: Long): Decision {
    val snapshot = visible.snapshot.value ?: return message(WidgetEmpty.NOT_READY)
    val choice = prefs.choice.value
    if (choice != WidgetChoice.PHOTOS_ONLY && !locked) {
      val memory = memoryDecision(snapshot, size)
      if (memory != null) return memory
      if (choice == WidgetChoice.MEMORIES_ONLY) return message(WidgetEmpty.NO_MEMORY_TODAY)
    }
    if (choice == WidgetChoice.MEMORIES_ONLY && locked) return message(WidgetEmpty.NO_MEMORY_TODAY)
    checkBudget(started, budgetMs)
    return photosDecision(snapshot, size, locked, started, budgetMs)
  }

  /** L'orologio monotono vale quando una lettura non si lascia interrompere dal timeout (codice che blocca): il budget e' un tetto vero. */
  private fun checkBudget(started: Long, budgetMs: Long) {
    if (clock.monotonicMs() - started > budgetMs) throw BudgetExceeded()
  }

  private class BudgetExceeded : RuntimeException("budget del widget superato")

  // --- foto a caso ---------------------------------------------------------------------------------------------------------

  private suspend fun photosDecision(snapshot: TimelineSnapshot, size: WidgetSize, locked: Boolean, started: Long, budgetMs: Long): Decision {
    val accepted = ArrayList<MediaId>(size.cells)
    val seen = HashSet<MediaId>()
    var anyVisible = false
    var anyOnPhone = false
    var rounds = 0
    while (accepted.size < size.cells && rounds < MAX_ROUNDS) {
      rounds++
      // Il triplo di quanto manca; se un giro non basta si raddoppia (fino al tetto del feed): chi non trova niente non va a 3 per volta.
      val want = (((size.cells - accepted.size) * WidgetRules.OVERFETCH) shl (rounds - 1).coerceAtMost(4)).coerceIn(1, WidgetFeed.MAX_SIZE)
      val batch = feed.next(want)
      if (batch.isEmpty()) break
      val fresh = batch.filter { seen.add(it) }
      val turnDone = fresh.size < batch.size
      val allowed = fresh.filter { id -> snapshot.indexOf(id).let { it >= 0 && !excluded(snapshot, it) } }
      if (allowed.isNotEmpty()) anyVisible = true
      val onPhone = if (locked) lock.onPhone(allowed).let { phone -> allowed.filter { it in phone } } else allowed
      if (onPhone.isNotEmpty()) anyOnPhone = true
      if (onPhone.isNotEmpty()) {
        val withThumb = thumbs.local(onPhone)
        for (id in onPhone) if (accepted.size < size.cells && id in withThumb) accepted += id
      }
      checkBudget(started, budgetMs)
      if (turnDone) break
    }
    if (accepted.isEmpty()) return message(whyNothing(snapshot, locked, anyVisible, anyOnPhone))
    val final = finalFilter(accepted)
    if (final.isEmpty()) return message(WidgetEmpty.ERROR)
    return Decision(if (locked) WidgetMode.Locked else WidgetMode.Photos, cells(snapshot, final))
  }

  /**
   * Perche' non c'e' nessuna foto. Il feed fallisce chiuso e su un errore di lettura risponde "niente": prima di dire "Libreria vuota" si
   * rilegge **un** id con le letture che lanciano, cosi' un guasto del database e' `ERROR` e non `NO_MEDIA`.
   */
  private suspend fun whyNothing(snapshot: TimelineSnapshot, locked: Boolean, anyVisible: Boolean, anyOnPhone: Boolean): WidgetEmpty {
    if (!anyVisible) {
      if (snapshot.size > 0) {
        val probe = listOf(snapshot.idAt(0))
        visible.visibleOrThrow(probe)
        mask.hiddenAmongOrThrow(probe)
      }
      return WidgetEmpty.NO_MEDIA
    }
    if (locked && !anyOnPhone) return WidgetEmpty.LOCKED_NOTHING_ON_PHONE
    return WidgetEmpty.NO_LOCAL_THUMBS
  }

  // --- il ricordo di oggi --------------------------------------------------------------------------------------------------

  /**
   * Il ricordo di oggi, o null (allora si passa alle foto, o a "Solo ricordi" senza ricordo). Mai `PERSON`; almeno
   * [WidgetRules.MEMORY_MIN_VISIBLE] elementi **visibili** fra i membri (E-W19); la copertina per prima, poi gli altri nell'ordine del
   * ricordo; solo elementi nell'istantanea (un membro di un gruppo che non e' la copertina non ha una cella), non esclusi per tipo, con la
   * miniatura locale. I controlli vanno a blocchi: un ricordo di migliaia di elementi non costa migliaia di letture.
   */
  private suspend fun memoryDecision(snapshot: TimelineSnapshot, size: WidgetSize): Decision? {
    val pick: WidgetMemoryPick = memories.today() ?: return null
    if (pick.kind == MemoryKind.PERSON) return null
    val ordered = listOf(pick.cover) + pick.members.filter { it != pick.cover }
    val picked = ArrayList<MediaId>(size.cells)
    var visibleCount = 0
    var offset = 0
    while (offset < ordered.size && (visibleCount < WidgetRules.MEMORY_MIN_VISIBLE || picked.size < size.cells)) {
      val chunk = ordered.subList(offset, minOf(ordered.size, offset + CHUNK))
      offset += chunk.size
      val ok = checkedVisible(chunk)
      visibleCount += ok.size
      if (picked.size < size.cells) {
        val showable = chunk.filter { it in ok }.filter { id -> snapshot.indexOf(id).let { it >= 0 && !excluded(snapshot, it) } }
        val withThumb = if (showable.isEmpty()) emptySet() else thumbs.local(showable)
        for (id in showable) if (picked.size < size.cells && id in withThumb) picked += id
      }
    }
    if (visibleCount < WidgetRules.MEMORY_MIN_VISIBLE || picked.isEmpty()) return null
    val final = finalFilter(picked)
    if (final.size != picked.size) return null
    return Decision(WidgetMode.Memory(pick.id, pick.title), cells(snapshot, final))
  }

  // --- filtro finale e pezzi -----------------------------------------------------------------------------------------------

  /** Visibili adesso e non nascosti, con le letture che **lanciano** (un guasto non passa per una Libreria vuota). */
  private suspend fun checkedVisible(ids: Collection<MediaId>): Set<MediaId> {
    if (ids.isEmpty()) return emptySet()
    val shown = visible.visibleOrThrow(ids)
    val hidden = mask.hiddenAmongOrThrow(ids)
    return ids.filterTo(LinkedHashSet()) { it in shown && it !in hidden }
  }

  /** Il filtro finale (D5), subito prima di consegnare: ricontrolla visibili e nascosti su ogni cella scelta. */
  private suspend fun finalFilter(ids: List<MediaId>): List<MediaId> {
    val ok = checkedVisible(ids)
    return ids.filter { it in ok }
  }

  /** Screenshot, documenti e immagini animate non sono "ricordi" (D4, come F9-D5). */
  private fun excluded(snapshot: TimelineSnapshot, i: Int): Boolean =
    snapshot.kindAt(i) == MediaKind.ANIMATED_IMAGE ||
      snapshot.sourceAt(i) == MediaSource.SCREENSHOT ||
      CellFlags.has(snapshot.flagsAt(i), CellFlags.DOCUMENT)

  private fun cells(snapshot: TimelineSnapshot, ids: List<MediaId>): List<WidgetCell> = ids.mapIndexed { index, id ->
    val i = snapshot.indexOf(id)
    WidgetCell(index, id, snapshot.kindAt(i), snapshot.localDayAt(i))
  }

  companion object {
    private const val TAG = "WidgetPlanner"

    /** Al piu' 64 giri, da 16 candidati a regime (circa mille celle guardate per piano), qualunque sia la Libreria (100 mila o piu'). */
    const val MAX_ROUNDS: Int = 64

    /** Quanti membri di un ricordo si controllano per volta. */
    private const val CHUNK: Int = 32
  }
}
