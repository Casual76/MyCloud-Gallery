package dev.pampa.mycloudgallery.core.designsystem.motion

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.util.lerp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.pampa.mycloudgallery.core.designsystem.R
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Le specifiche di movimento della galleria: tutte le curve vengono da [FluidMotion].
 *
 * Qui non c'e' una `tween` scritta a mano per gusto: ogni durata e ogni easing e' una costante
 * dell'engine, e questo oggetto le dispone nei tre gesti che la galleria possiede e l'engine no.
 *
 *  1. **L'espansione dalla miniatura al viewer** ([GalleryExpandSpec], [GalleryViewerTransitionState]):
 *     la miniatura *diventa* il viewer. Una pagina in arrivo non sfuma mai (regola 3): il viewer e'
 *     opaco dal primo fotogramma, e quello che si muove e' la sua sagoma, dal rettangolo della
 *     miniatura a quello dello schermo. La sagoma che cambia raggio a ogni fotogramma e' il caso per
 *     cui esiste Fluid-physics: l'espansione guida un `FluidPhysicsState` con
 *     `driveExternally`, e il contenuto si ritaglia con `fluidPhysicsClip`. Niente raggio animato a
 *     mano in una schermata.
 *  2. **Lo swipe verso il basso per chiudere** ([GalleryDismissSpec]): interattivo. Il dito guida lo
 *     stesso orologio dell'espansione (stesso `progress`), al contrario: la foto si rimpicciolisce e
 *     scende col dito; al rilascio una molla la riporta su o la fa tornare alla miniatura. Chi lascia
 *     andare a meta' non vede mai uno scatto, perche' l'orologio e' uno solo.
 *  3. **Il pinch fra i livelli della griglia** ([GalleryPinchSpec], [GalleryGridPinchState]): continuo
 *     e ancorato al dito. La griglia ha sempre un numero intero di colonne; durante il gesto una scala
 *     continua copre la differenza, e quando si supera la meta' fra due livelli il livello cambia
 *     e la scala si ricalcola per mantenere la stessa dimensione apparente delle celle.
 *
 * Le tre rispettano `FluidMotionPolicy`: con la scala animazioni a zero i passaggi arrivano senza
 * viaggiare (`reducedMotion`).
 */
object GalleryMotion {

  /** Il periodo del luccichio di un segnaposto di caricamento. Decorativo: sparisce con `reducedMotion`. */
  const val ShimmerMillis: Int = 1400

  /** Il luccichio del segnaposto: un andata lenta e ripetuta, con l'easing simmetrico dell'engine. */
  fun shimmerSpec(): InfiniteRepeatableSpec<Float> = infiniteRepeatable(
    animation = tween(durationMillis = ShimmerMillis, easing = FluidMotion.EaseInOut),
  )

  /** Apertura: la miniatura cresce fino al viewer. Durata ed easing sono quelli delle pagine gerarchiche. */
  fun <T> expandOpenSpec(): FiniteAnimationSpec<T> =
    tween(durationMillis = FluidMotion.DurationExpand, easing = FluidMotion.EaseEmphasized)

  /** Chiusura: lo stesso movimento alla rovescia, un po' piu' rapido ("un rilascio"). */
  fun <T> expandCloseSpec(): FiniteAnimationSpec<T> =
    tween(durationMillis = FluidMotion.DurationCollapse, easing = FluidMotion.EaseEmphasized)

  /**
   * Un gesto annullato torna a posto con la molla della casa: le superfici grandi si muovono sulla
   * `smooth` (0,5 s, una posata appena accennata).
   */
  fun <T> restoreSpec(): FiniteAnimationSpec<T> = FluidMotion.smooth()

  /** L'assestamento del pinch dopo il rilascio: la stessa molla delle superfici grandi. */
  fun <T> settleSpec(): FiniteAnimationSpec<T> = FluidMotion.smooth()

  /**
   * L'apparizione di una barra o di un elemento di chrome: sfuma (e' chrome, non una pagina).
   *
   * H1 (D6 della prova dell'11/11, "i tasti del viewer compaiono di scatto"): con la curva d'entrata (`EaseOut`, meta' del percorso nel primo
   * decimo del tempo) il primo fotogramma visibile della barra, che e' anche quello in cui il suo vetro si compone e si disegna per la prima
   * volta, cadeva gia' a meta' opacita': la barra *saltava* dentro. Con la curva simmetrica e la durata dell'apertura del viewer la barra parte
   * piano (quel fotogramma pesante cade quasi trasparente) e arriva insieme all'assestamento della foto.
   */
  fun <T> chromeAppearSpec(): FiniteAnimationSpec<T> = FluidMotion.crossFade(FluidMotion.DurationExpand)
}

// ---------------------------------------------------------------------------------------------
// 1. Espansione dalla miniatura
// ---------------------------------------------------------------------------------------------

/** La geometria dell'espansione, in funzioni pure (si provano senza una composizione). */
object GalleryExpandSpec {

  /** Il rettangolo del viewer al progresso [progress]: 0 = miniatura, 1 = traguardo. */
  fun frame(progress: Float, from: Rect, to: Rect): Rect = Rect(
    left = lerp(from.left, to.left, progress),
    top = lerp(from.top, to.top, progress),
    right = lerp(from.right, to.right, progress),
    bottom = lerp(from.bottom, to.bottom, progress),
  )
}

// ---------------------------------------------------------------------------------------------
// 2. Swipe giu' per chiudere
// ---------------------------------------------------------------------------------------------

/** Le soglie dello swipe verso il basso, in funzioni pure. */
object GalleryDismissSpec {

  /** Quanto (frazione dell'altezza) bisogna trascinare per portare il progresso al minimo. */
  const val TravelFraction: Float = 0.5f

  /** Il progresso del viewer a dito tutto giu': la foto non scende mai sotto il 55% della taglia piena. */
  const val MinViewerProgress: Float = 0.55f

  /** Oltre questa frazione di trascinamento il rilascio chiude. */
  const val DismissDragProgress: Float = 0.30f

  /** Un lancio piu' veloce di questo chiude anche se il dito ha fatto poca strada, in dp/s. */
  const val FlingVelocityDp: Float = 900f

  /** 0..1: quanto il dito ha trascinato verso il basso. Verso l'alto non c'e' progresso. */
  fun dragProgress(dragY: Float, stageHeight: Float): Float {
    if (stageHeight <= 0f) return 0f
    return (dragY.coerceAtLeast(0f) / (stageHeight * TravelFraction)).coerceIn(0f, 1f)
  }

  /** Il progresso del viewer (1 = aperto) per un dato trascinamento: l'orologio dell'espansione, al contrario. */
  fun viewerProgress(dragProgress: Float): Float = lerp(1f, MinViewerProgress, dragProgress.coerceIn(0f, 1f))

  /** Se rilasciare qui chiude: abbastanza strada, oppure un lancio abbastanza veloce. */
  fun shouldDismiss(dragProgress: Float, velocityY: Float, density: Float): Boolean {
    val velocityDp = if (density > 0f) velocityY / density else velocityY
    return dragProgress >= DismissDragProgress || velocityDp >= FlingVelocityDp
  }
}

/**
 * Lo stato dell'espansione dal viewer, e del suo swipe.
 *
 * Un solo orologio: [progress] (0 = miniatura, 1 = aperto). L'apertura e la chiusura lo animano con le
 * specifiche di [GalleryMotion]; il dito lo guida con [dragBy] e al rilascio decide [release].
 * [drag] e' lo spostamento del dito, applicato dal chiamante come traslazione del layer: la sagoma
 * segue il dito senza ri-misurare niente.
 *
 * Non sa nulla di dove stia la miniatura: la sagoma la costruisce chi lo usa (vedi la demo del
 * catalogo), con `FluidPhysicsState.driveExternally(from, to, progress = { progress.value })`.
 */
@Stable
class GalleryViewerTransitionState(initialProgress: Float = 0f) {

  /** 0 = miniatura, 1 = viewer a pieno schermo. */
  val progress: Animatable<Float, *> = Animatable(initialProgress)

  /** Lo spostamento del dito, in pixel. */
  val drag: Animatable<Offset, *> = Animatable(Offset.Zero, Offset.VectorConverter)

  /** Vero finche' un dito sta trascinando. */
  var dragging: Boolean by mutableStateOf(false)
    private set

  /** Se il viewer ha qualcosa da disegnare: aperto, in apertura o in chiusura. */
  val isShown: Boolean get() = progress.value > 0f || progress.isRunning

  /** Apre: dalla miniatura al viewer. */
  suspend fun open(reducedMotion: Boolean = false) {
    drag.snapTo(Offset.Zero)
    if (reducedMotion) progress.snapTo(1f) else progress.animateTo(1f, GalleryMotion.expandOpenSpec())
  }

  /** Chiude: dal viewer (o da dove l'ha lasciato il dito) alla miniatura. */
  suspend fun close(reducedMotion: Boolean = false) {
    dragging = false
    if (reducedMotion) {
      drag.snapTo(Offset.Zero)
      progress.snapTo(0f)
      return
    }
    coroutineScope {
      launch { drag.animateTo(Offset.Zero, GalleryMotion.expandCloseSpec()) }
      progress.animateTo(0f, GalleryMotion.expandCloseSpec())
    }
  }

  /** Il dito si sposta di [delta]: la foto lo segue, e il progresso scende con la distanza. */
  suspend fun dragBy(delta: Offset, stageHeight: Float) {
    dragging = true
    val next = drag.value + delta
    drag.snapTo(next)
    progress.snapTo(GalleryDismissSpec.viewerProgress(GalleryDismissSpec.dragProgress(next.y, stageHeight)))
  }

  /**
   * Il dito si alza. Restituisce `true` se il viewer si chiude, `false` se torna a posto.
   *
   * [velocityY] in pixel al secondo, [density] per leggerlo in dp (la soglia del lancio e' in dp/s,
   * perche' un dito veloce su uno schermo denso e uno lento su uno rado non sono la stessa cosa).
   */
  suspend fun release(velocityY: Float, density: Float, stageHeight: Float, reducedMotion: Boolean = false): Boolean {
    dragging = false
    val dismiss = GalleryDismissSpec.shouldDismiss(
      dragProgress = GalleryDismissSpec.dragProgress(drag.value.y, stageHeight),
      velocityY = velocityY,
      density = density,
    )
    if (dismiss) {
      close(reducedMotion)
    } else if (reducedMotion) {
      drag.snapTo(Offset.Zero)
      progress.snapTo(1f)
    } else {
      coroutineScope {
        launch { drag.animateTo(Offset.Zero, GalleryMotion.restoreSpec()) }
        progress.animateTo(1f, GalleryMotion.restoreSpec())
      }
    }
    return dismiss
  }
}

// ---------------------------------------------------------------------------------------------
// 3. Pinch fra i livelli della griglia
// ---------------------------------------------------------------------------------------------

/** Un livello della griglia: un'etichetta e quante colonne ha. */
@Immutable
data class GalleryGridLevel(@StringRes val label: Int, val columns: Int)

object GalleryGridLevels {

  /**
   * Anni, Mesi, Giorni, Tutte: dal piu' lontano al piu' vicino (piu' colonne = celle piu' piccole).
   * L'elenco e' un parametro di chi lo usa: la Libreria puo' averne altri su un tablet.
   */
  val Default: List<GalleryGridLevel> = listOf(
    GalleryGridLevel(R.string.level_years, columns = 7),
    GalleryGridLevel(R.string.level_months, columns = 5),
    GalleryGridLevel(R.string.level_days, columns = 4),
    GalleryGridLevel(R.string.level_all, columns = 3),
  )
}

/** Il risultato di un passo di pinch: il livello e la scala continua che lo accompagna. */
@Immutable
data class GalleryPinchResolution(val level: Int, val scale: Float)

/** La logica del pinch, pura: dato un livello e una scala grezza, dice dove si e' arrivati. */
object GalleryPinchSpec {

  /**
   * Quanto si puo' andare oltre l'ultimo livello (in rapporto di colonne) prima di fermarsi: un
   * elastico, che al rilascio riporta tutto a posto.
   */
  const val EdgeSlack: Float = 1.18f

  /**
   * L'isteresi del cambio di livello (in logaritmo del rapporto di colonne): evita che la griglia
   * sfarfalli fra due livelli quando il dito indugia proprio a meta'.
   */
  const val Hysteresis: Float = 0.04f

  /**
   * Dato il livello di partenza [level] (indice in [columns]) e la scala grezza [rawScale] (1 = la
   * dimensione delle celle di quel livello, > 1 = piu' grandi), restituisce il livello e la scala
   * da usare: **la dimensione apparente delle celle non cambia** quando il livello cambia.
   */
  fun resolve(columns: List<Int>, level: Int, rawScale: Float): GalleryPinchResolution {
    require(columns.isNotEmpty()) { "Serve almeno un livello." }
    val current = level.coerceIn(0, columns.lastIndex)
    val safeScale = rawScale.coerceAtLeast(MinScale)
    // Le colonne "continue" che la dimensione delle celle di adesso implica.
    val effective = (columns[current] / safeScale)
      .coerceIn(columns.min() / EdgeSlack, columns.max() * EdgeSlack)

    val currentDistance = abs(ln(effective / columns[current]))
    var best = current
    var bestDistance = currentDistance
    columns.forEachIndexed { index, count ->
      val distance = abs(ln(effective / count))
      if (distance < bestDistance) {
        best = index
        bestDistance = distance
      }
    }
    if (best != current && currentDistance - bestDistance < Hysteresis) best = current
    return GalleryPinchResolution(level = best, scale = columns[best] / effective)
  }

  private const val MinScale = 0.05f
}

/**
 * Lo stato del pinch fra livelli.
 *
 * [onPinch] riceve il fattore di zoom di ogni passo del gesto; [onRelease] riporta la scala a 1 con
 * la molla di assestamento, cioe' fa atterrare le celle sulla dimensione esatta del livello scelto.
 * [focal] e' il punto del gesto, in frazione del contenitore: il chiamante lo usa come origine della
 * trasformazione, cosi' la griglia resta ancorata al dito.
 */
@Stable
class GalleryGridPinchState(
  val levels: List<GalleryGridLevel> = GalleryGridLevels.Default,
  initialLevel: Int = 0,
) {
  private val columnCounts = levels.map { it.columns }

  /** L'indice del livello corrente in [levels]. */
  var level: Int by mutableIntStateOf(initialLevel.coerceIn(0, levels.lastIndex))
    private set

  /** La scala continua applicata sopra la griglia a [columns] colonne (1 a riposo). */
  val scale: Animatable<Float, *> = Animatable(1f)

  /** L'origine della trasformazione, in frazione (0..1) del contenitore. */
  var focal: Offset by mutableStateOf(Offset(0.5f, 0.5f))
    private set

  /** Vero finche' le due dita sono giu'. */
  var pinching: Boolean by mutableStateOf(false)
    private set

  /** Le colonne del livello corrente. */
  val columns: Int get() = levels[level].columns

  suspend fun onPinch(zoom: Float, focalFraction: Offset) {
    pinching = true
    focal = focalFraction
    val resolution = GalleryPinchSpec.resolve(columnCounts, level, scale.value * zoom)
    level = resolution.level
    scale.snapTo(resolution.scale)
  }

  suspend fun onRelease(reducedMotion: Boolean = false) {
    pinching = false
    if (reducedMotion) scale.snapTo(1f) else scale.animateTo(1f, GalleryMotion.settleSpec())
  }

  /** Salta a un livello (per un selettore a segmenti), con la stessa molla del rilascio. */
  suspend fun jumpTo(index: Int, reducedMotion: Boolean = false) {
    val target = index.coerceIn(0, levels.lastIndex)
    if (target == level) return
    // Le colonne continue partono dal livello vecchio e arrivano al nuovo: la dimensione apparente
    // delle celle cambia con la molla invece di saltare.
    val ratio = columnCounts[level].toFloat() / columnCounts[target]
    level = target
    scale.snapTo(scale.value / ratio)
    onRelease(reducedMotion)
  }
}
