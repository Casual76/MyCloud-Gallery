package dev.pampa.mycloudgallery.feature.viewer.gesture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.util.lerp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryDismissSpec
import dev.pampa.mycloudgallery.feature.viewer.page.ContentLayer
import kotlin.math.max

/**
 * Le soglie e la geometria della **chiusura del viewer** (H1, D5 e D7 della prova dell'11/11), in funzioni pure: si provano senza una
 * composizione.
 *
 * Una sola macchina per tre gesti: il trascinamento verso il basso, il pizzico in chiusura a zoom minimo e l'Indietro predittivo. Tutti e
 * tre muovono **solo il contenuto** (la foto, il fotogramma del video), lo sfondo sfuma in proporzione e sotto si vede la pagina da cui si
 * e' partiti; al rilascio oltre la soglia il contenuto vola alla sua cella, altrimenti torna con la molla.
 */
internal object ViewerDismissSpec {

  /** La scala del contenuto con il dito a fine corsa (meta' dell'altezza): come Foto, la foto si rimpicciolisce mentre scende. */
  const val MinDragScale: Float = 0.6f

  /** Il pizzico piu' stretto che il contenuto segue: oltre, non si rimpicciolisce piu'. */
  const val MinPinch: Float = 0.25f

  /** Allargando le dita durante una chiusura il contenuto cresce poco e con resistenza, fino a qui. */
  const val MaxPinch: Float = 1.15f

  /** Quanto si sente l'allargamento oltre la misura piena (la resistenza dell'elastico). */
  const val PinchOverResistance: Float = 0.35f

  /** A questa scala del pizzico lo sfondo e' sparito del tutto. */
  const val PinchFullFade: Float = 0.5f

  /** Sotto questa scala il rilascio di un pizzico chiude. */
  const val PinchDismissScale: Float = 0.85f

  /** Un pizzico che stringe piu' in fretta di cosi' (frazione della misura al secondo) chiude anche se e' corto; che allarga, torna. */
  const val PinchFlingRate: Float = 1.6f

  /** Indietro predittivo: la scala a gesto completo, lo spostamento verso il lato del gesto e verso il basso, quanto sfuma lo sfondo. */
  const val BackMinScale: Float = 0.86f
  const val BackShiftFraction: Float = 0.04f
  const val BackLiftFraction: Float = 0.02f
  const val BackFade: Float = 0.5f

  /** La chiusura senza una cella (album, ricerca, mappa, ricordi): la scala d'arrivo e quanto scende, in frazione dell'altezza. */
  const val AwayScale: Float = 0.72f
  const val AwayDropFraction: Float = 0.12f

  /** La velocita' iniziale del volo (frazioni del viaggio al secondo) non supera questa: un lancio forte non fa rimbalzare sulla cella. */
  const val MaxFlightVelocity: Float = 6f

  /** 0..1: quanto il dito e' sceso (la stessa corsa della chiusura di sempre: meta' dell'altezza). Verso l'alto non c'e' progresso. */
  fun dragProgress(travel: Float, stageHeight: Float): Float = GalleryDismissSpec.dragProgress(travel, stageHeight)

  /** La scala che il trascinamento da' al contenuto: 1 fermo, [MinDragScale] a fine corsa. */
  fun dragShrink(travel: Float, stageHeight: Float): Float = lerp(1f, MinDragScale, dragProgress(travel, stageHeight))

  /** 0..1: quanto il pizzico ha stretto (1 = sfondo sparito). Allargare non da' progresso. */
  fun pinchProgress(pinch: Float): Float = ((1f - pinch) / (1f - PinchFullFade)).coerceIn(0f, 1f)

  /** L'avanzamento della chiusura (lo sfondo e' `1 - progress`): il piu' avanti fra il trascinamento e il pizzico. */
  fun progress(travel: Float, pinch: Float, stageHeight: Float): Float = max(dragProgress(travel, stageHeight), pinchProgress(pinch))

  /** Il pizzico che il contenuto segue da [raw] (il prodotto dei fattori delle dita): sotto la misura piena 1:1, sopra con resistenza. */
  fun resistPinch(raw: Float): Float = when {
    raw <= 1f -> raw.coerceAtLeast(MinPinch)
    else -> (1f + (raw - 1f) * PinchOverResistance).coerceAtMost(MaxPinch)
  }

  /**
   * Se il rilascio chiude. Un lancio verso l'alto o un pizzico che si riapre in fretta tornano sempre (l'utente ha cambiato idea); poi chiude
   * la strada (30% della corsa, come prima), un lancio verso il basso da 900 dp/s, un pizzico sotto l'85% o uno che stringe in fretta.
   *
   * @param velocityYDp la velocita' verticale del contenuto in dp/s (positiva = verso il basso).
   * @param pinchRate la velocita' del pizzico in frazioni della misura al secondo (negativa = stringe).
   */
  fun shouldDismiss(dragProgress: Float, pinch: Float, velocityYDp: Float, pinchRate: Float): Boolean {
    if (velocityYDp <= -GalleryDismissSpec.FlingVelocityDp || pinchRate >= PinchFlingRate) return false
    return dragProgress >= GalleryDismissSpec.DismissDragProgress ||
      velocityYDp >= GalleryDismissSpec.FlingVelocityDp ||
      pinch <= PinchDismissScale ||
      pinchRate <= -PinchFlingRate
  }

  /**
   * La velocita' iniziale del volo in frazioni del viaggio al secondo: la parte della velocita' del dito ([velocity], px/s) che va verso la
   * cella. Mai negativa (un dito che va altrove non spinge indietro) e mai oltre [MaxFlightVelocity].
   */
  fun flightVelocity(velocity: Offset, from: Rect, to: Rect): Float {
    val d = to.center - from.center
    val length2 = d.x * d.x + d.y * d.y
    if (length2 < 1f) return 0f
    return ((velocity.x * d.x + velocity.y * d.y) / length2).coerceIn(0f, MaxFlightVelocity)
  }

  /** Il piu' grande rettangolo di proporzione [aspect] dentro [content], al centro: cio' che la miniatura (`Crop`) mostra della foto. */
  fun centerCrop(content: Rect, aspect: Float): Rect {
    if (content.width <= 0f || content.height <= 0f || aspect <= 0f) return content
    val contentAspect = content.width / content.height
    val width: Float
    val height: Float
    if (aspect >= contentAspect) {
      width = content.width
      height = content.width / aspect
    } else {
      height = content.height
      width = content.height * aspect
    }
    val center = content.center
    return Rect(center.x - width / 2f, center.y - height / 2f, center.x + width / 2f, center.y + height / 2f)
  }
}

/**
 * Dove va il contenuto quando la chiusura e' decisa: [ToCell] alla cella da cui si e' aperto (o a quella di cio' che si sta guardando, se
 * si e' scorso), [Away] quando la cella non si conosce o non e' in vista. [at] e' il contenuto al punto `t` del volo (0 = dove l'ha lasciato
 * il dito, 1 = arrivato).
 */
@Immutable
internal sealed interface FlightPlan {

  fun at(t: Float): ContentLayer

  /**
   * Il volo alla cella. [from] e' il contenuto sullo schermo al rilascio, [to] la cella (coordinate del viewer), [content] il contenuto a
   * riposo nelle coordinate locali (la foto intera nella scena). Il rettangolo sullo schermo va da [from] a [to]; il ritaglio passa dalla
   * foto intera al ritaglio al centro con la proporzione della cella (quello della miniatura): all'arrivo il contenuto *e'* la miniatura.
   */
  class ToCell(val from: Rect, val to: Rect, val content: Rect) : FlightPlan {
    override fun at(t: Float): ContentLayer {
      val f = t.coerceIn(0f, 1f)
      val screen = Rect(lerp(from.left, to.left, f), lerp(from.top, to.top, f), lerp(from.right, to.right, f), lerp(from.bottom, to.bottom, f))
      if (screen.width <= 0f || screen.height <= 0f || content.width <= 0f) return ContentLayer(scale = 0f)
      val window = ViewerDismissSpec.centerCrop(content, screen.width / screen.height)
      val scale = screen.width / window.width
      return ContentLayer(
        scale = scale,
        translation = Offset(screen.left - scale * window.left, screen.top - scale * window.top),
        clip = window,
      )
    }
  }

  /** La chiusura senza cella: dal rilascio il contenuto si rimpicciolisce, scende di [drop] pixel e sfuma. */
  class Away(val from: Rect, val content: Rect, val drop: Float) : FlightPlan {
    override fun at(t: Float): ContentLayer {
      val f = t.coerceIn(0f, 1f)
      if (content.width <= 0f) return ContentLayer(alpha = 1f - f)
      val scale = from.width / content.width * lerp(1f, ViewerDismissSpec.AwayScale, f)
      val center = Offset(from.center.x, from.center.y + drop * f)
      return ContentLayer(
        scale = scale,
        translation = Offset(center.x - scale * content.center.x, center.y - scale * content.center.y),
        alpha = 1f - f,
      )
    }
  }
}

/**
 * Lo stato della chiusura del viewer: **un solo stato** per il trascinamento, il pizzico, l'Indietro predittivo e il volo (H1).
 *
 * Il contenuto sullo schermo e' `scale * locale + translation` ([layer]); lo sfondo e' `1 - [progress]`. Il dito guida il contenuto
 * **senza molla nel mezzo** ([move]: lo spostamento e' quello del dito, il punto sotto il dito resta sotto il dito anche mentre il
 * contenuto si rimpicciolisce); le molle partono al rilascio, con i token di `FluidMotion`: [settleBack] (torna) o [fly] (vola).
 *
 * Si legge solo nel disegno (`graphicsLayer`, `drawBehind`): un fotogramma di gesto non ricompone niente. [phase] cambia solo all'inizio e
 * alla fine di un gesto, e lo leggono le barre e il lettore (si nascondono, si mette in pausa).
 */
@Stable
internal class ViewerDismissState {

  enum class Phase {
    /** Fermo: il contenuto e' al suo posto. */
    Idle,

    /** Un dito (o due, o l'Indietro di sistema) guida il contenuto. */
    Gesture,

    /** Rilasciato sotto la soglia: torna al suo posto con la molla (un dito puo' riprenderlo). */
    Returning,

    /** Chiusura decisa: il contenuto vola alla cella (o si allontana sfumando). */
    Flying,
  }

  var phase: Phase by mutableStateOf(Phase.Idle)
    private set

  /** Vero dal primo movimento del gesto all'ultimo fotogramma del ritorno o del volo. */
  val active: Boolean get() = phase != Phase.Idle

  var scale: Float by mutableFloatStateOf(1f)
    private set

  var translation: Offset by mutableStateOf(Offset.Zero)
    private set

  /** 0 = aperto, 1 = sfondo sparito. */
  var progress: Float by mutableFloatStateOf(0f)
    private set

  /** Quanto e' sceso il punto guidato (il dito, o il punto medio delle dita), in pixel: da qui il rimpicciolirsi e lo sfondo. */
  var travel: Float = 0f
    private set

  /** Il prodotto dei fattori del pizzico (1 = nessun pizzico), gia' con la resistenza. */
  var pinch: Float = 1f
    private set

  private var rawPinch: Float = 1f

  var plan: FlightPlan? by mutableStateOf(null)
    private set

  /** L'orologio del volo: 0 = rilascio, 1 = arrivato. */
  val flight: Animatable<Float, *> = Animatable(0f)

  /** Lo sfondo del viewer: 1 = pieno, 0 = si vede la pagina sotto. */
  val backdropAlpha: Float get() = 1f - progress

  /** Il contenuto in questo fotogramma. */
  fun layer(): ContentLayer {
    val current = plan
    if (current != null) return current.at(flight.value)
    if (phase == Phase.Idle) return ContentLayer.Identity
    return ContentLayer(scale = scale, translation = translation)
  }

  /** Un gesto prende il contenuto (anche mentre tornava: riparte da dove e' adesso). */
  fun begin() {
    if (phase == Phase.Flying) return
    phase = Phase.Gesture
  }

  /**
   * Un passo del gesto: il punto medio delle dita si e' spostato di [pan] (pixel) e le dita si sono strette o allargate di [zoom] (1 con un
   * dito solo) intorno a [centroid] (il punto medio adesso, coordinate della scena). Il contenuto segue il dito 1:1 e si rimpicciolisce
   * intorno a lui: con il trascinamento in proporzione alla discesa, con il pizzico come le dita.
   */
  fun move(pan: Offset, zoom: Float, centroid: Offset, stageHeight: Float) {
    if (phase != Phase.Gesture) return
    val oldShrink = ViewerDismissSpec.dragShrink(travel, stageHeight)
    val nextTravel = travel + pan.y
    val newShrink = ViewerDismissSpec.dragShrink(nextTravel, stageHeight)
    val nextRaw = (rawPinch * zoom).coerceIn(ViewerDismissSpec.MinPinch / 4f, ViewerDismissSpec.MaxPinch * 4f)
    val nextPinch = ViewerDismissSpec.resistPinch(nextRaw)
    val factor = (nextPinch / pinch) * (newShrink / oldShrink)
    // Il punto che stava sotto il punto medio di prima va sotto quello di adesso, e la scala cambia intorno a lui.
    val previous = centroid - pan
    scale *= factor
    translation = Offset(
      factor * (translation.x - previous.x) + centroid.x,
      factor * (translation.y - previous.y) + centroid.y,
    )
    travel = nextTravel
    rawPinch = nextRaw
    pinch = nextPinch
    progress = ViewerDismissSpec.progress(travel, pinch, stageHeight)
  }

  /**
   * L'Indietro predittivo al punto [backProgress] (0..1): il contenuto si rimpicciolisce intorno al centro della scena, va un poco verso il
   * lato da cui parte il gesto ([fromLeft]) e in basso; lo sfondo sfuma fino a meta'. Non e' il `progress` di sistema passato per una curva:
   * e' lo stesso stato del dito, e al rilascio prende la stessa uscita (il volo alla cella).
   */
  fun back(backProgress: Float, fromLeft: Boolean, stage: Size) {
    if (phase == Phase.Flying) return
    phase = Phase.Gesture
    val p = backProgress.coerceIn(0f, 1f)
    val edge = if (fromLeft) 1f else -1f
    val s = lerp(1f, ViewerDismissSpec.BackMinScale, p)
    val center = Offset(stage.width / 2f, stage.height / 2f)
    scale = s
    translation = Offset(
      center.x * (1f - s) + edge * p * stage.width * ViewerDismissSpec.BackShiftFraction,
      center.y * (1f - s) + p * stage.height * ViewerDismissSpec.BackLiftFraction,
    )
    travel = 0f
    rawPinch = 1f
    pinch = 1f
    progress = p * ViewerDismissSpec.BackFade
  }

  /** Se il rilascio con queste velocita' chiude (vedi [ViewerDismissSpec.shouldDismiss]). */
  fun releaseDismisses(velocityYDp: Float, pinchRate: Float, stageHeight: Float): Boolean =
    ViewerDismissSpec.shouldDismiss(ViewerDismissSpec.dragProgress(travel, stageHeight), pinch, velocityYDp, pinchRate)

  /**
   * Torna al suo posto con la molla delle superfici grandi (`FluidMotion.smooth`). Un dito che riprende il contenuto a meta' ([begin])
   * ferma il ritorno dove si trova: la coroutine si annulla e lo stato resta quello di quel fotogramma.
   */
  suspend fun settleBack(reducedMotion: Boolean, spec: AnimationSpec<Float> = FluidMotion.smooth()) {
    if (phase == Phase.Flying || phase == Phase.Idle) return
    phase = Phase.Returning
    val fromScale = scale
    val fromTranslation = translation
    val fromProgress = progress
    val fromTravel = travel
    val fromRaw = rawPinch
    if (!reducedMotion) {
      Animatable(1f).animateTo(0f, spec) {
        if (phase != Phase.Returning) return@animateTo
        scale = lerp(1f, fromScale, value)
        translation = fromTranslation * value
        progress = fromProgress * value
        travel = fromTravel * value
        rawPinch = lerp(1f, fromRaw, value)
        pinch = ViewerDismissSpec.resistPinch(rawPinch)
      }
    }
    if (phase == Phase.Returning) reset()
  }

  /**
   * Il volo: dal contenuto di adesso al [target] dato da [planFor]. La molla e' quella senza rimbalzo (`FluidMotion.snappy`), con la
   * velocita' del dito che va verso la cella ([velocityToward]); lo sfondo finisce di sfumare insieme. Con il movimento ridotto arriva
   * subito.
   */
  suspend fun fly(plan: FlightPlan, initialVelocity: Float, reducedMotion: Boolean, spec: AnimationSpec<Float> = FluidMotion.snappy()) {
    phase = Phase.Flying
    val fromProgress = progress
    flight.snapTo(0f)
    this.plan = plan
    if (reducedMotion) {
      flight.snapTo(1f)
      progress = 1f
      return
    }
    flight.animateTo(1f, spec, initialVelocity) { progress = lerp(fromProgress, 1f, value.coerceIn(0f, 1f)) }
    progress = 1f
  }

  /** Il contenuto sullo schermo adesso, per il rettangolo [content] (coordinate locali): da qui parte il volo. */
  fun screenRect(content: Rect): Rect = ContentLayer(scale = scale, translation = translation).map(content)

  /** Tutto al suo posto, senza animazione (fine del ritorno, o un viewer che si riapre). */
  fun reset() {
    scale = 1f
    translation = Offset.Zero
    progress = 0f
    travel = 0f
    rawPinch = 1f
    pinch = 1f
    plan = null
    phase = Phase.Idle
  }

  companion object {
    /** Il piano del volo: alla cella se si conosce ed e' un rettangolo vero, altrimenti via, sfumando verso il basso. */
    fun planFor(from: Rect, content: Rect, cell: Rect?, stage: Size): FlightPlan = when {
      cell != null && cell.width > 0f && cell.height > 0f -> FlightPlan.ToCell(from = from, to = cell, content = content)
      else -> FlightPlan.Away(from = from, content = content, drop = stage.height * ViewerDismissSpec.AwayDropFraction)
    }

    /** La velocita' del volo verso [plan] per un rilascio a [velocity] px/s (vedi [ViewerDismissSpec.flightVelocity]). */
    fun velocityToward(plan: FlightPlan, velocity: Offset): Float = when (plan) {
      is FlightPlan.ToCell -> ViewerDismissSpec.flightVelocity(velocity, plan.from, plan.to)
      is FlightPlan.Away -> ViewerDismissSpec.flightVelocity(velocity, plan.from, plan.from.translate(0f, plan.drop))
    }
  }
}
