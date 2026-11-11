package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fase H (D10, richiesta dell'utente del 31/10): **tirare oltre il fondo** della griglia aggiorna la Libreria (rilettura del telefono, del NAS e un
 * giro di backup con le regole di sempre: lo decide chi ospita la griglia). La Libreria ha i piu' recenti in fondo, quindi il gesto e' continuare a
 * tirare verso l'alto quando si e' gia' sul fondo.
 *
 * Il bordo e' quello del bordo elastico dell'engine (`FluidEdgeOverscrollState`, che pero' aggiorna solo dal bordo alto): **uno spostamento solo**,
 * la gomma di UIKit `f(x) = x·d·c / (d + c·x)` con `c = 0,55` e l'asintoto al 24% dell'altezza della vista, la soglia a [GridPullDefaults.Trigger]
 * (76 dp, `FluidScreenDefaults.RefreshTrigger`) di spostamento visivo e, oltre la soglia, [GridPullDefaults.Hold] (56 dp) aperti finche' dura il
 * lavoro. L'indicatore vive nello spazio che lo spostamento apre ([GridPullIndicator]).
 *
 *  - Segue il dito **senza molla nel mezzo** (lezione di P2): lo spostamento del fotogramma e' quello del dito; la molla parte al rilascio, con i
 *    token di `FluidMotion` (`DampingChrome`, `ResponseStandard`), in uno scope suo: un tocco che arriva non la lascia a meta'.
 *  - Solo il dito (`NestedScrollSource.UserInput`); i resti di un lancio arrivano al bordo come prima (lo stiramento della piattaforma).
 *  - Prima di scorrere la lista, un dito che torna indietro richiude lo spostamento.
 *  - Superare la soglia da' un colpo d'aptica (una volta; si riarma se si torna sotto).
 *  - Mentre un aggiornamento e' in corso lo spazio resta aperto e il gesto non ne fa partire un altro.
 *  - Non costa niente quando non si tira: lo stato si legge nel blocco del layer della lista, e l'indicatore si compone solo mentre c'e' qualcosa
 *    da mostrare.
 */
@Stable
internal class GridPullState(private val scope: CoroutineScope, private val reducedMotion: Boolean) : NestedScrollConnection {

  /** Lo spostamento visivo della lista verso l'alto, in pixel (>= 0). Si legge nel blocco del layer, mai in composizione. */
  var offsetPx by mutableFloatStateOf(0f)
    private set

  /** Vero mentre l'aggiornamento chiesto dal gesto lavora (lo spazio resta aperto e l'indicatore gira). */
  var refreshing by mutableStateOf(false)
    private set

  internal var triggerPx: Float = 0f
  internal var holdPx: Float = 0f
  private var dimensionPx: Float = 0f
  private var rawPx: Float = 0f
  private var armed = false
  private var settle: Job? = null

  /** Il gesto vale adesso (non in selezione, non durante un pinch). */
  internal var enabled: () -> Boolean = { true }

  /** Il lavoro dell'aggiornamento: lo spazio resta aperto finche' finisce (al piu' [GridPullDefaults.MaxHoldMillis]). */
  internal var onRefresh: (suspend () -> Unit)? = null

  /** La soglia e' stata superata (l'aptica). */
  internal var onArmed: () -> Unit = {}

  /** Quanto manca alla soglia, da 0 a 1: guida l'indicatore. */
  val progress: Float get() = if (triggerPx <= 0f) 0f else (offsetPx / triggerPx).coerceIn(0f, 1f)

  fun updateViewport(heightPx: Int) {
    dimensionPx = (heightPx * MaximumBounceFraction).coerceAtLeast(0f)
  }

  override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
    if (source != NestedScrollSource.UserInput || refreshing) return Offset.Zero
    val offset = offsetPx
    // Il dito torna verso i piu' vecchi (in giu'): prima si richiude lo spostamento, poi scorre la lista.
    if (offset <= 0f || available.y <= 0f) return Offset.Zero
    settle?.cancel()
    val consumed = min(available.y, offset)
    applyOffset(offset - consumed)
    return Offset(0f, consumed)
  }

  override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
    if (source != NestedScrollSource.UserInput || refreshing || dimensionPx <= 0f || onRefresh == null) return Offset.Zero
    // Il resto verso l'alto quando la lista e' gia' in fondo (il dito sale, la lista non ha piu' strada): e' il tiro.
    val leftover = available.y
    if (leftover >= 0f || !enabled()) return Offset.Zero
    settle?.cancel()
    rawPx += -leftover
    offsetPx = rubberBand(rawPx, dimensionPx)
    noteThreshold()
    return Offset(0f, leftover)
  }

  override suspend fun onPreFling(available: Velocity): Velocity {
    if (offsetPx <= 0f || refreshing) return Velocity.Zero
    release()
    // Il rilascio di un tiro non lancia la lista: la velocita' e' del bordo.
    return available
  }

  /** Il dito si alza (anche fermo: il lancio parte comunque, a velocita' zero). Oltre la soglia l'aggiornamento parte. */
  internal fun release() {
    val work = onRefresh
    if (armed && offsetPx >= triggerPx && work != null) {
      armed = false
      refreshing = true
      settle?.cancel()
      settle = scope.launch {
        val start = System.nanoTime()
        settleTo(holdPx)
        withTimeoutOrNull(GridPullDefaults.MaxHoldMillis) { runCatching { work() } }
        // Lo spazio resta aperto almeno un attimo: un aggiornamento istantaneo non sembra un gesto andato a vuoto.
        val elapsed = (System.nanoTime() - start) / NanosPerMilli
        if (elapsed < GridPullDefaults.MinHoldMillis) delay(GridPullDefaults.MinHoldMillis - elapsed)
        refreshing = false
        settleTo(0f)
      }
    } else {
      armed = false
      settle?.cancel()
      settle = scope.launch { settleTo(0f) }
    }
  }

  /** Solo per i test e gli screenshot: lo spostamento come se il dito avesse tirato fin li'. */
  internal fun pullTo(visualPx: Float) {
    applyOffset(visualPx)
    noteThreshold()
  }

  private fun noteThreshold() {
    if (triggerPx <= 0f) return
    if (!armed && offsetPx >= triggerPx) {
      armed = true
      onArmed()
    } else if (armed && offsetPx < triggerPx) {
      armed = false
    }
  }

  private fun applyOffset(value: Float) {
    val clamped = value.coerceIn(0f, max(0f, dimensionPx))
    offsetPx = clamped
    rawPx = inverseRubberBand(clamped, dimensionPx)
  }

  private suspend fun settleTo(target: Float) {
    if (offsetPx == target) return
    if (reducedMotion || dimensionPx <= 0f) {
      applyOffset(target)
      return
    }
    animate(
      initialValue = offsetPx,
      targetValue = target,
      animationSpec = FluidMotion.pixels(FluidMotion.DampingChrome, FluidMotion.ResponseStandard),
    ) { value, _ -> offsetPx = value.coerceIn(0f, dimensionPx) }
    applyOffset(target)
  }

  internal companion object {
    /** La resistenza iniziale della gomma (quella dell'engine, `OverscrollResistance`). */
    const val Resistance = 0.55f

    /** L'asintoto della gomma: il 24% dell'altezza della vista (`MaximumBounceFraction` dell'engine). */
    const val MaximumBounceFraction = 0.24f

    private const val NanosPerMilli = 1_000_000L

    /** La gomma di UIKit: [raw] pixel di dito danno questo spostamento, mai oltre [dimension]. */
    fun rubberBand(raw: Float, dimension: Float): Float {
      if (dimension <= 0f || raw <= 0f) return 0f
      return raw * dimension * Resistance / (dimension + Resistance * raw)
    }

    /** L'inversa: quanti pixel di dito servono per lo spostamento [visual]. */
    fun inverseRubberBand(visual: Float, dimension: Float): Float {
      if (dimension <= 0f || visual <= 0f) return 0f
      val v = visual.coerceAtMost(dimension * 0.999f)
      return v * dimension / (Resistance * (dimension - v))
    }
  }
}

/** Le misure del tiro oltre il fondo: quelle del tira-per-aggiornare dell'engine (`FluidScreenDefaults`). */
internal object GridPullDefaults {
  /** La soglia: spostamento visivo oltre il quale il rilascio aggiorna (`FluidScreenDefaults.RefreshTrigger`). */
  val Trigger: Dp = 76.dp

  /** Lo spazio che resta aperto mentre l'aggiornamento lavora (`FluidScreenDefaults.RefreshHold`). */
  val Hold: Dp = 56.dp

  /** Il diametro dell'indicatore (`RefreshIndicatorSize` di `FluidScreen`). */
  val IndicatorSize: Dp = 24.dp

  /** Lo spazio resta aperto almeno tanto, e non piu' di tanto: il resto lo dice la nuvola in alto. */
  const val MinHoldMillis: Long = 600L
  const val MaxHoldMillis: Long = 2_500L
}

/**
 * L'indicatore del tiro: lo spinner dell'engine (`FluidSpinner`, i raggi che si rivelano col dito, come nel tira-per-aggiornare di `FluidScreen`)
 * a meta' dello spazio aperto sopra [bottomPadding] (il posto della pillola). Si compone solo mentre il bordo e' spostato o l'aggiornamento lavora;
 * lo spostamento si legge nel blocco del layer.
 */
@Composable
internal fun BoxScope.GridPullIndicator(state: GridPullState, bottomPadding: Dp) {
  val shown by remember(state) { derivedStateOf { state.offsetPx > 0f || state.refreshing } }
  if (!shown) return
  val refreshing = state.refreshing
  Box(
    modifier = Modifier
      .align(Alignment.BottomCenter)
      .padding(bottom = bottomPadding)
      .graphicsLayer {
        val opened = state.offsetPx
        alpha = if (state.refreshing) 1f else smoothStep((state.progress / RevealFraction).coerceIn(0f, 1f))
        translationY = -opened / 2f + GridPullDefaults.IndicatorSize.toPx() / 2f
      }
      .testTag(GridPullIndicatorTag),
  ) {
    FluidSpinner(size = GridPullDefaults.IndicatorSize, progress = if (refreshing) null else { { state.progress } })
  }
}

internal const val GridPullIndicatorTag = "griglia-aggiorna"

/** Quanto della soglia serve perche' l'indicatore sia del tutto visibile (come in `FluidScreen`). */
private const val RevealFraction = 0.55f

private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)
