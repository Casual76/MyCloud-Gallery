package dev.pampa.mycloudgallery.feature.widget.frame

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface
import dev.pampa.mycloudgallery.core.describe.contract.MediaDescriber
import dev.pampa.mycloudgallery.core.describe.contract.MediaFacts
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.plan.WidgetBudget
import dev.pampa.mycloudgallery.feature.widget.contract.WidgetUiState
import dev.pampa.mycloudgallery.feature.widget.host.WidgetServices
import java.time.DateTimeException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Il piano di un widget con le sue bitmap, pronto per essere **registrato e disegnato** (§2.1, passi 2-5): [plan] e' gia' ridotto a quello che sta nei tetti di D7
 * (`WidgetBudget.trimmed`), [bitmaps] stanno una per cella del piano. Un piano senza foto ha [bitmaps] vuote. Non e' ancora nel registro: lo mette
 * `GlanceWidgetRefresher` **subito prima** del disegno, dentro il suo cancello (D5: un "Nascondi" che arriva nel frattempo lo scarta).
 */
internal class Prepared(val plan: WidgetPlan, val bitmaps: List<Bitmap>) {
  init {
    require(bitmaps.size == plan.cells.size) { "Una bitmap per cella." }
  }

  override fun toString(): String = "Prepared($plan)"
}

/**
 * Un giro del widget fino alla decodifica (§2.1): il piano di [WidgetServices.planner] (che fa gia' il filtro finale), le miniature **locali** decodificate al lato
 * lungo di `WidgetBudget.longSidePx`, il piano **ridotto** a cio' che sta in 7 immagini e 2 MiB. Niente rete, niente NAS, niente originali: le miniature vengono da
 * [WidgetServices.thumbnails]. Un piano `NOT_READY` (E-W4: la Libreria non e' ancora pronta) non si ridisegna: [prepare] risponde null.
 *
 * Il tempo: il piano ha il suo budget (2 s, o 1,5 s a freddo); la decodifica si ferma quando il budget complessivo e' finito e si disegna **quello che c'e'** (un prefisso
 * di celle: la copertina e' l'ultima a cadere); nessuna cella decodificata = "Apri MyCloud Gallery" (`NO_LOCAL_THUMBS`), mai un segnaposto fatto di dati.
 */
internal class WidgetPreparer(private val services: WidgetServices) {

  suspend fun prepare(slot: WidgetSlot, size: WidgetSize, budgetMs: Long): Prepared? {
    val started = services.clock.monotonicMs()
    val plan = services.planner.plan(slot, size, budgetMs)
    // E-W4: un processo freddo senza istantanea. Non e' un errore e non si disegna: resta il contenuto di prima (verificato), si riprova al giro dopo.
    if (WidgetUiState.of(plan) == null) return null
    if (plan.mode is WidgetMode.Message || plan.cells.isEmpty()) return Prepared(plan, emptyList())

    val px = WidgetBudget.longSidePx(size, plan.cells.size)
    val decoded = ArrayList<Bitmap>(plan.cells.size)
    val remaining = (budgetMs - (services.clock.monotonicMs() - started)).coerceAtLeast(MIN_DECODE_MS)
    withTimeoutOrNull(remaining) {
      for (cell in plan.cells) {
        val bitmap = try {
          services.thumbnails.load(cell.media, px)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Mai l'id o il percorso nel log: solo il tipo. Una miniatura che non si decodifica ferma il prefisso (D7: niente segnaposto).
          GalleryLog.w(TAG) { "Miniatura del widget non decodificata (${e.javaClass.simpleName})." }
          null
        } ?: break
        decoded += bitmap
      }
    }
    // Il tetto in byte e in numero (D7): un prefisso. La bitmap che sfora da sola da' zero celle (NO_LOCAL_THUMBS).
    val fitting = WidgetBudget.fitting(decoded.map { it.width to it.height })
    val trimmed = WidgetBudget.trimmed(plan, fitting)
    return Prepared(trimmed, decoded.take(trimmed.cells.size))
  }

  companion object {
    private const val TAG = "WidgetPreparer"

    /** Anche con il budget finito si lascia il tempo di una miniatura: meglio una foto in meno che un widget vuoto per un soffio. */
    private const val MIN_DECODE_MS = 250L

    /**
     * Il fotogramma di [prepared]: lo stato dal piano ridotto, le descrizioni della superficie `WIDGET` (solo tipo e data, `core-describe`), il titolo del ricordo gia'
     * composto (solo `Memory`; mai con il blocco dell'app) e il testo di un messaggio.
     */
    fun frame(prepared: Prepared, describer: MediaDescriber, texts: WidgetTexts): WidgetFrame {
      val plan = prepared.plan
      val state = WidgetUiState.of(plan) ?: error("Un piano senza stato non si disegna.")
      val cells = plan.cells.mapIndexed { index, cell ->
        val description = try {
          describer.describe(cell.media, MediaFacts(cell.kind, cell.localDay, null, null, null, 0, 1, false, null), DescribeSurface.WIDGET)
        } catch (_: IllegalArgumentException) {
          // Un giorno che non e' una data vera: la descrizione dice solo il tipo.
          texts.cellFallback(cell.kind)
        } catch (_: DateTimeException) {
          texts.cellFallback(cell.kind)
        }
        WidgetFrameCell(prepared.bitmaps.getOrNull(index), description.ifBlank { texts.cellFallback(cell.kind) })
      }
      val title = (plan.mode as? WidgetMode.Memory)?.title?.let(texts::compose)
      val message = (state as? WidgetUiState.Message)?.let { texts.message(it.reason) }
      return WidgetFrame(plan.slot, state, cells, title, message)
    }

    /** Il fotogramma **senza immagini** dello svuotamento (D5, D42): nessuna decodifica, nessuna cella. */
    fun blankFrame(slot: WidgetSlot, size: WidgetSize, reason: WidgetEmpty, texts: WidgetTexts): WidgetFrame {
      val state = WidgetUiState.blank(size, reason)
      return WidgetFrame(slot, state, emptyList(), null, texts.message(reason))
    }
  }
}
