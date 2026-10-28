package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.GridLayoutEngine
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Tutto cio' da cui dipendono i quattro layout: la vista, lo stile e lo schermo. Quando uno di questi cambia (un
 * filtro, un'istantanea nuova, una rotazione, una finestra ridimensionata) i layout si ricalcolano tutti.
 *
 * @property columns le colonne dei quattro livelli alla larghezza data, da Anni a Tutte (`GalleryGridLevels.forWidth`).
 * @property densityDp la densita' **per le intestazioni**: la densita' dello schermo per la scala del testo (mai sotto
 *   1), cosi' con il testo grande le righe di intestazione crescono e i titoli non si tagliano.
 */
@Immutable
data class GridLayoutInputs(
  val view: TimelineView,
  val style: GridStyle,
  val widthPx: Int,
  val gapPx: Int,
  val densityDp: Float,
  val columns: List<Int>,
) {
  fun spec(level: GridLevel): GridSpec = GridSpec(
    level = level,
    style = style,
    columns = columns[level.ordinal],
    widthPx = widthPx,
    gapPx = gapPx,
    densityDp = densityDp,
  )
}

/**
 * I layout dei quattro livelli per i [GridLayoutInputs] correnti (§2.4: "si precalcolano fuori dal thread principale a
 * ogni nuova istantanea"). Il livello che si sta guardando si calcola per primo; gli altri tre subito dopo, cosi' un
 * pinch trova gia' pronto il layout del livello in cui entra e lo cambia **nello stesso fotogramma**.
 *
 * Una vista piccola (fino a [SyncLimit] elementi: gli screenshot, una Raccolta, i primi secondi della prima lettura) si
 * impagina sul posto, durante la composizione: costa meno di un fotogramma e la griglia non mostra mai un fotogramma
 * vuoto. Le altre si impaginano su [dispatcher].
 */
internal class GridLayoutStore(
  private val engine: GridLayoutEngine,
  private val dispatcher: CoroutineDispatcher,
) {

  private var inputs: GridLayoutInputs? by mutableStateOf(null)
  private val ready = mutableStateMapOf<GridLevel, GridLayout>()

  /** Il layout di [level] per [current], se e' pronto. E' stato osservabile: l'arrivo di un layout ricompone chi lo legge. */
  fun get(current: GridLayoutInputs, level: GridLevel): GridLayout? {
    val layout = ready[level]
    return if (inputs == current) layout else null
  }

  /** Impagina subito, sul posto, se la vista e' piccola; altrimenti null. Non tocca lo stato: lo si consegna con [offer]. */
  fun layoutNowIfSmall(current: GridLayoutInputs, level: GridLevel): GridLayout? =
    if (current.view.size <= SyncLimit) engine.layout(current.view, current.spec(level)) else null

  /** Consegna un layout calcolato altrove (sul posto). */
  fun offer(current: GridLayoutInputs, level: GridLevel, layout: GridLayout) {
    reset(current)
    if (ready[level] == null) ready[level] = layout
  }

  /** Calcola i layout che mancano per [current], [first] per primo. Si interrompe se gli input cambiano nel frattempo. */
  suspend fun compute(current: GridLayoutInputs, first: GridLevel) {
    reset(current)
    val order = listOf(first) + GridLevel.entries.filter { it != first }
    for (level in order) {
      if (inputs != current) return
      if (ready[level] != null) continue
      val layout = if (current.view.size <= SyncLimit) {
        engine.layout(current.view, current.spec(level))
      } else {
        withContext(dispatcher) { AvvioTrace.section("MCG:griglia:layout") { engine.layout(current.view, current.spec(level)) } }
      }
      if (inputs != current) return
      ready[level] = layout
    }
  }

  private fun reset(current: GridLayoutInputs) {
    if (inputs != current) {
      inputs = current
      ready.clear()
    }
  }

  companion object {
    /** Fino a questa dimensione la vista si impagina sul thread principale (pochi millisecondi anche col motore di riferimento). */
    const val SyncLimit: Int = 6_000
  }
}
