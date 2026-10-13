package dev.pampa.mycloudgallery.core.designsystem.grid

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawModifierNode
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryCellDefaults
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

// La cella **leggera** della griglia della Libreria (docs/fase3-progetto.md §1.2, §2.4, §7).
//
// `GalleryMediaCell` (catalogo 1.2) e' il componente di riferimento: bello, ma costruito per una manciata di celle
// (otto `Box`, quattro animazioni per cella, un `fluidPressable` a testa). Una griglia a 120 Hz ne mostra cento e ne
// compone una fila nuova a ogni fotogramma di un lancio: qui la stessa cella e' **un solo nodo** che si disegna da se'
// (`CacheDrawModifierNode`, cioe' `drawWithCache` in forma di nodo), con gli stessi colori, raggi e badge.
//
// Le tre regole che la rendono economica:
//  1. **niente composizione per lo stato**: la miniatura, la pressione e la selezione si leggono dentro il nodo
//     (`observeReads`), e quando cambiano si ridisegna **solo questa cella** (ognuna ha il suo layer): una miniatura che
//     arriva non ricompone niente;
//  2. **niente tocchi per cella**: i gesti li riconosce la griglia, con un solo `pointerInput` e il layout per l'hit
//     test; la cella legge soltanto chi e' premuto ([GalleryGridInteractionState]);
//  3. **niente animazioni fuori dalla selezione e dalla pressione**: la dissolvenza della miniatura esiste solo per una
//     miniatura che arriva mentre la cella e' gia' sullo schermo, e il luccichio del segnaposto non c'e' (il ThumbHash e'
//     gia' un'anteprima).

/** I segni di una cella (D15: in Fase 3 durata, preferito, modificata e non leggibile). Un valore: si confronta. */
@Immutable
data class GalleryGridCellBadges(
  val favorite: Boolean = false,
  val edited: Boolean = false,
  /** La durata gia' formattata ("0:42") per un video, null per il resto. */
  val duration: String? = null,
  /** Il file non si legge (E-M10): niente miniatura, mai; la cella lo dice con un glifo. */
  val unreadable: Boolean = false,
) {
  companion object {
    val None: GalleryGridCellBadges = GalleryGridCellBadges()
  }
}

/**
 * Cio' che la griglia condivide con tutte le sue celle: chi e' premuto, se si sta selezionando e cosa. Le celle lo
 * leggono dentro il proprio nodo; quando cambia, solo le celle il cui stato cambia davvero si ridisegnano.
 *
 * Le chiavi sono opache (la galleria usa il `MediaId`): il design system non sa cosa sia un media.
 */
@Stable
class GalleryGridInteractionState {

  /** La chiave della cella sotto il dito, o null. */
  var pressedKey: Any? by mutableStateOf(null)

  /** Quanto e' premuta la cella [pressedKey] (0..1): la griglia lo anima con le molle di `FluidMotion`. */
  val pressProgress: Animatable<Float, AnimationVector1D> = Animatable(0f)

  /** Vero in modalita' selezione: ogni cella mostra il cerchio. */
  var selectionMode: Boolean by mutableStateOf(false)

  /** L'apparizione dei cerchi (0..1), una per tutta la griglia. */
  val selectionModeProgress: Animatable<Float, AnimationVector1D> = Animatable(0f)

  /** Le chiavi selezionate. */
  var selectedKeys: Set<Any> by mutableStateOf(emptySet())

  /** Solo per i test di budget: chiamata a ogni disegno di una cella, con la sua chiave. Mai in produzione. */
  var drawProbe: ((Any) -> Unit)? = null

  /** Il dito si appoggia su [key]: la cella cede (`FluidMotion.instant`, arriva in fondo prima che il dito si alzi). */
  suspend fun press(key: Any, reducedMotion: Boolean) {
    if (pressedKey != key) {
      pressProgress.snapTo(0f)
      pressedKey = key
    }
    if (reducedMotion) pressProgress.snapTo(1f) else pressProgress.animateTo(1f, FluidMotion.instant())
  }

  /** Il dito si alza: la cella torna (la pressione e' gia' arrivata in fondo, vedi [press]). */
  suspend fun release(reducedMotion: Boolean) {
    val key = pressedKey ?: return
    if (reducedMotion) pressProgress.snapTo(0f) else pressProgress.animateTo(0f, FluidMotion.fadeOut())
    if (pressedKey == key) pressedKey = null
  }

  /** La pressione si annulla subito (il dito ha cominciato a scorrere): nessuna animazione. */
  fun cancelPress() {
    pressedKey = null
  }

  /** Entra o esce dalla modalita' selezione, con la molla della chrome. */
  suspend fun animateSelectionMode(on: Boolean, reducedMotion: Boolean) {
    selectionMode = on
    val target = if (on) 1f else 0f
    if (reducedMotion) selectionModeProgress.snapTo(target) else selectionModeProgress.animateTo(target, FluidMotion.snappy())
  }
}

/**
 * Le azioni e la voce di una cella, per l'accessibilita' (i tocchi veri li riconosce la griglia). Una sola istanza per
 * griglia: nessuna lambda per cella.
 */
@Stable
interface GalleryGridCellActions {

  /** La descrizione per il lettore di schermo, costruita **solo quando serve** ("Foto, 12 marzo 2024, 10:15, Oslo"). */
  fun describe(key: Any): String

  /** Il tocco: apre, o in modalita' selezione seleziona. */
  fun click(key: Any)

  /** La pressione lunga: seleziona. */
  fun longClick(key: Any)

  /** Le etichette delle due azioni ("Apri", "Seleziona"). */
  val clickLabel: String?
  val longClickLabel: String?
}

/**
 * Le misure, i colori, i glifi e il testo delle celle di una griglia, **risolti una volta** dal tema: ogni cella li
 * legge senza toccare una `CompositionLocal`. Lo crea [rememberGalleryGridCellStyle].
 */
@Stable
class GalleryGridCellStyle internal constructor(
  internal val density: Density,
  internal val layoutDirection: LayoutDirection,
  internal val placeholderTint: Color,
  internal val frameColor: Color,
  internal val pressDim: Color,
  internal val badgeScrim: Color,
  internal val badgeContent: Color,
  internal val badgeHairline: Color,
  internal val ringFill: Color,
  internal val selectedFill: Color,
  internal val checkTint: Color,
  internal val unreadableTint: Color,
  internal val play: VectorPainter,
  internal val favorite: VectorPainter,
  internal val edited: VectorPainter,
  internal val check: VectorPainter,
  internal val broken: VectorPainter,
  internal val textMeasurer: TextMeasurer,
  internal val badgeText: TextStyle,
  internal val selectedText: String,
  internal val notSelectedText: String,
  internal val loadingText: String,
) {
  internal val onPhotoFilter: ColorFilter = ColorFilter.tint(badgeContent)
  internal val checkFilter: ColorFilter = ColorFilter.tint(checkTint)
  internal val unreadableFilter: ColorFilter = ColorFilter.tint(unreadableTint)

  private fun Dp.px(): Float = with(density) { toPx() }

  internal val badgeHeight = GalleryDimens.BadgeHeight.px()
  internal val badgeGlyph = GalleryDimens.BadgeGlyph.px()
  internal val badgeInset = GalleryDimens.BadgeInset.px()
  internal val iconPadding = BadgeIconPadding.px()
  internal val labeledPadding = BadgeLabeledPadding.px()
  internal val glyphGap = BadgeGlyphGap.px()
  internal val clusterGap = BadgeClusterGap.px()
  internal val rowGap = BadgeRowGap.px()
  internal val hairline = BadgeHairline.px()
  internal val indicator = GalleryDimens.SelectionIndicator.px()
  internal val ring = SelectionRing.px()
  internal val checkSize = SelectionCheck.px()
  internal val brokenSize = BrokenGlyph.px()
  internal val badgesMinSide = BadgesMinCell.px()
  private val maxCorner = FluidRadius.Small.px()
  private val referenceCell = CornerReferenceCell.px()

  private val outlines = HashMap<Long, Outline>()

  /**
   * La sagoma di una cella grande [size]: un angolo continuo che scala col lato (le celle di "Anni" sono piccole, e
   * dieci dp di raggio su un francobollo lo farebbero sembrare un bottone). Una cache piccola: le celle di una griglia
   * quadrata hanno tutte la stessa misura.
   */
  internal fun outlineFor(size: Size): Outline {
    val packed = (size.width.toBits().toLong() shl 32) or (size.height.toBits().toLong() and 0xFFFFFFFFL)
    outlines[packed]?.let { return it }
    if (outlines.size >= MaxCachedOutlines) outlines.clear()
    val share = (min(size.width, size.height) / referenceCell).coerceIn(MinCornerShare, 1f)
    val radius = with(density) { (maxCorner * share).toDp() }
    return ContinuousCornerShape(radius).createOutline(size, layoutDirection, density).also { outlines[packed] = it }
  }

  internal fun capsule(size: Size): Outline = FluidCapsuleShape.createOutline(size, layoutDirection, density)
}

/** Lo stile delle celle della griglia, dal tema corrente. Uno per griglia. */
@Composable
fun rememberGalleryGridCellStyle(): GalleryGridCellStyle {
  val scheme = MaterialTheme.colorScheme
  val density = LocalDensity.current
  val direction = LocalLayoutDirection.current
  val textMeasurer = rememberTextMeasurer(cacheSize = TextCacheSize)
  val badgeText = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum", color = GalleryOnPhoto.Content)
  val play = rememberVectorPainter(Icons.Rounded.PlayArrow)
  val favorite = rememberVectorPainter(Icons.Rounded.Favorite)
  val edited = rememberVectorPainter(Icons.Rounded.Tune)
  val check = rememberVectorPainter(Icons.Rounded.Check)
  val broken = rememberVectorPainter(Icons.Rounded.BrokenImage)
  val selectedText = stringResource(R.string.cell_selected)
  val notSelectedText = stringResource(R.string.cell_not_selected)
  val loadingText = stringResource(R.string.cell_loading)
  return remember(scheme, density, direction, textMeasurer, badgeText, play, favorite, edited, check, broken, selectedText) {
    GalleryGridCellStyle(
      density = density,
      layoutDirection = direction,
      placeholderTint = lerp(scheme.surfaceContainerHighest, scheme.primary, PlaceholderAccentShare),
      frameColor = scheme.primaryContainer,
      pressDim = scheme.scrim.copy(alpha = PressedDimAlpha),
      badgeScrim = GalleryOnPhoto.scrim(scheme),
      badgeContent = GalleryOnPhoto.Content,
      badgeHairline = GalleryOnPhoto.Content.copy(alpha = HairlineAlpha),
      ringFill = scheme.scrim.copy(alpha = RingFillAlpha),
      selectedFill = scheme.primary,
      checkTint = scheme.onPrimary,
      unreadableTint = scheme.onSurfaceVariant,
      play = play,
      favorite = favorite,
      edited = edited,
      check = check,
      broken = broken,
      textMeasurer = textMeasurer,
      badgeText = badgeText,
      selectedText = selectedText,
      notSelectedText = notSelectedText,
      loadingText = loadingText,
    )
  }
}

/**
 * Una cella della griglia: la miniatura di un media, con il segnaposto, i badge, la pressione e la selezione.
 *
 * Non ha tocchi propri (li gestisce la griglia, [GalleryGridCellActions] serve solo all'accessibilita') ne' misura
 * propria: chi la mette in una fila le da' la larghezza e l'altezza della sua colonna.
 *
 * @param key l'identita' opaca della cella: confrontata con [GalleryGridInteractionState.pressedKey] e `selectedKeys`.
 * @param image la miniatura, come stato osservabile: letta **nel nodo**, mai nella composizione.
 * @param fallback una miniatura di un'altra taglia gia' in memoria, finche' [image] non arriva (un cambio di livello non
 *   fa lampeggiare i segnaposto).
 * @param placeholder il segnaposto sfocato (ThumbHash), o null per la tinta della palette.
 */
@Composable
fun GalleryGridCell(
  key: Any,
  image: State<ImageBitmap?>,
  badges: GalleryGridCellBadges,
  style: GalleryGridCellStyle,
  interaction: GalleryGridInteractionState,
  actions: GalleryGridCellActions,
  modifier: Modifier = Modifier,
  fallback: ImageBitmap? = null,
  placeholder: ImageBitmap? = null,
) {
  Spacer(modifier.then(GalleryGridCellElement(key, image, fallback, placeholder, badges, style, interaction, actions)))
}

private class GalleryGridCellElement(
  val key: Any,
  val image: State<ImageBitmap?>,
  val fallback: ImageBitmap?,
  val placeholder: ImageBitmap?,
  val badges: GalleryGridCellBadges,
  val style: GalleryGridCellStyle,
  val interaction: GalleryGridInteractionState,
  val actions: GalleryGridCellActions,
) : ModifierNodeElement<GalleryGridCellNode>() {

  override fun create(): GalleryGridCellNode =
    GalleryGridCellNode(key, image, fallback, placeholder, badges, style, interaction, actions)

  override fun update(node: GalleryGridCellNode) {
    node.update(key, image, fallback, placeholder, badges, style, interaction, actions)
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "galleryGridCell"
  }

  override fun equals(other: Any?): Boolean =
    other is GalleryGridCellElement && other.key == key && other.image === image && other.fallback === fallback &&
      other.placeholder === placeholder && other.badges == badges && other.style === style &&
      other.interaction === interaction && other.actions === actions

  override fun hashCode(): Int {
    var h = key.hashCode()
    h = 31 * h + System.identityHashCode(image)
    h = 31 * h + System.identityHashCode(fallback)
    h = 31 * h + System.identityHashCode(placeholder)
    h = 31 * h + badges.hashCode()
    h = 31 * h + System.identityHashCode(style)
    h = 31 * h + System.identityHashCode(interaction)
    return 31 * h + System.identityHashCode(actions)
  }
}

private class GalleryGridCellNode(
  var key: Any,
  var image: State<ImageBitmap?>,
  var fallback: ImageBitmap?,
  var placeholder: ImageBitmap?,
  var badges: GalleryGridCellBadges,
  var style: GalleryGridCellStyle,
  var interaction: GalleryGridInteractionState,
  var actions: GalleryGridCellActions,
) : DelegatingNode(), ObserverModifierNode, SemanticsModifierNode, CompositionLocalConsumerModifierNode {

  override val shouldAutoInvalidate: Boolean get() = false

  /** La miniatura che si disegna adesso (quella di [image] quando c'e'). */
  private var bitmap: ImageBitmap? = null
  private var isPressed = false
  private var isSelected = false
  private var inSelectionMode = false

  /** La dissolvenza della miniatura che arriva mentre la cella e' sullo schermo. */
  private val imageAlpha = Animatable(1f)

  /** La cella selezionata: la foto si rimpicciolisce in una cornice dell'accento. */
  private val selectedProgress = Animatable(0f)

  private val cache = delegate(CacheDrawModifierNode { buildDraw() })

  override fun onAttach() {
    observe(initial = true)
  }

  override fun onObservedReadsChanged() {
    observe(initial = false)
  }

  fun update(
    key: Any,
    image: State<ImageBitmap?>,
    fallback: ImageBitmap?,
    placeholder: ImageBitmap?,
    badges: GalleryGridCellBadges,
    style: GalleryGridCellStyle,
    interaction: GalleryGridInteractionState,
    actions: GalleryGridCellActions,
  ) {
    val newItem = key != this.key || image !== this.image
    this.key = key
    this.image = image
    this.fallback = fallback
    this.placeholder = placeholder
    this.badges = badges
    this.style = style
    this.interaction = interaction
    this.actions = actions
    if (newItem) {
      // Un'altra cella (la fila e' stata riusata per un'altra posizione): niente animazioni ereditate.
      bitmap = null
      isPressed = false
      isSelected = false
    }
    if (isAttached) observe(initial = newItem)
    cache.invalidateDrawCache()
    invalidateSemantics()
  }

  private fun observe(initial: Boolean) {
    var newBitmap: ImageBitmap? = null
    var newPressed = false
    var newMode = false
    var newSelected = false
    observeReads {
      newBitmap = image.value
      newPressed = interaction.pressedKey == key
      newMode = interaction.selectionMode
      newSelected = newMode && key in interaction.selectedKeys
    }
    val reduced = currentValueOf(LocalFluidMotionPolicy).reducedMotion

    if (newBitmap !== bitmap) {
      val arriving = bitmap == null && newBitmap != null && !initial && !reduced
      val loadedChanged = (bitmap == null) != (newBitmap == null)
      bitmap = newBitmap
      if (arriving) {
        coroutineScope.launch {
          imageAlpha.snapTo(0f)
          imageAlpha.animateTo(1f, FluidMotion.fadeIn())
        }
      } else {
        coroutineScope.launch { imageAlpha.snapTo(1f) }
      }
      cache.invalidateDrawCache()
      // "In caricamento" vale finche' la miniatura non c'e': si aggiorna solo quando arriva o se ne va.
      if (loadedChanged || initial) invalidateSemantics()
    }
    if (newPressed != isPressed) {
      isPressed = newPressed
      cache.invalidateDraw()
    }
    if (newMode != inSelectionMode) {
      inSelectionMode = newMode
      cache.invalidateDraw()
      invalidateSemantics()
    }
    if (newSelected != isSelected) {
      isSelected = newSelected
      val target = if (newSelected) 1f else 0f
      coroutineScope.launch {
        if (initial || reduced) selectedProgress.snapTo(target) else selectedProgress.animateTo(target, FluidMotion.standard())
      }
      invalidateSemantics()
    } else if (initial) {
      coroutineScope.launch { selectedProgress.snapTo(if (newSelected) 1f else 0f) }
    }
  }

  override fun SemanticsPropertyReceiver.applySemantics() {
    val s = style
    contentDescription = actions.describe(key)
    val chosen = isSelected
    if (inSelectionMode) {
      role = Role.Checkbox
      toggleableState = ToggleableState(chosen)
      selected = chosen
      stateDescription = if (chosen) s.selectedText else s.notSelectedText
    } else {
      role = Role.Button
      if (bitmap == null && fallback == null && !badges.unreadable) stateDescription = s.loadingText
    }
    onClick(label = actions.clickLabel) {
      actions.click(key)
      true
    }
    onLongClick(label = actions.longClickLabel) {
      actions.longClick(key)
      true
    }
  }

  private fun CacheDrawScope.buildDraw(): DrawResult {
    val s = style
    val outline = s.outlineFor(size)
    val photo = bitmap ?: fallback
    val photoBrush = photo?.let { cropBrush(it, size) }
    val placeholderBrush = placeholder?.let { cropBrush(it, size) }
    val badgeLayout = if (min(size.width, size.height) >= s.badgesMinSide) layoutBadges(s, badges, size) else null
    val indicatorCenter = Offset(s.badgeInset + s.indicator / 2f, s.badgeInset + s.indicator / 2f)

    return onDrawBehind {
      interaction.drawProbe?.invoke(key)
      val pressAmount = if (isPressed) interaction.pressProgress.value.coerceIn(0f, 1f) else 0f
      val pressScale = lerpFloat(1f, GalleryCellDefaults.PressedScale, pressAmount)
      val selectedAmount = selectedProgress.value.coerceIn(0f, 1f)
      val modeAmount = if (inSelectionMode || interaction.selectionModeProgress.isRunning) {
        interaction.selectionModeProgress.value.coerceIn(0f, 1f)
      } else {
        0f
      }

      scale(pressScale) {
        // 1. La cornice dell'accento, solo attorno alla foto rimpicciolita di una cella selezionata.
        if (selectedAmount > 0f) drawOutline(outline, s.frameColor, alpha = selectedAmount)

        // 2. La foto: il segnaposto sotto, la miniatura sopra.
        scale(lerpFloat(1f, GalleryCellDefaults.SelectedScale, selectedAmount)) {
          val alpha = if (photoBrush != null) imageAlpha.value.coerceIn(0f, 1f) else 0f
          if (alpha < 1f) {
            if (placeholderBrush != null) drawOutline(outline, placeholderBrush) else drawOutline(outline, s.placeholderTint)
            if (badges.unreadable) drawBroken(s)
          }
          if (photoBrush != null && alpha > 0f) drawOutline(outline, photoBrush, alpha = alpha)
          if (pressAmount > 0f) drawOutline(outline, s.pressDim, alpha = pressAmount)
        }

        // 3. I badge, agli angoli in basso.
        badgeLayout?.let { drawBadges(s, it) }

        // 4. Il cerchio di selezione, in alto a sinistra.
        if (modeAmount > 0f) drawSelectionIndicator(s, indicatorCenter, modeAmount, selectedAmount)
      }
    }
  }

  private fun DrawScope.drawBroken(s: GalleryGridCellStyle) {
    val side = min(s.brokenSize, min(size.width, size.height) / 2f)
    translate((size.width - side) / 2f, (size.height - side) / 2f) {
      with(s.broken) { draw(Size(side, side), colorFilter = s.unreadableFilter) }
    }
  }

  private fun DrawScope.drawSelectionIndicator(s: GalleryGridCellStyle, center: Offset, mode: Float, fill: Float) {
    val radius = s.indicator / 2f
    drawCircle(s.ringFill, radius = radius, center = center, alpha = mode * (1f - fill))
    if (fill > 0f) drawCircle(s.selectedFill, radius = radius, center = center, alpha = mode * fill)
    drawCircle(s.badgeContent, radius = radius - s.ring / 2f, center = center, alpha = mode, style = Stroke(width = s.ring))
    if (fill > 0f) {
      val side = s.checkSize * fill
      translate(center.x - side / 2f, center.y - side / 2f) {
        with(s.check) { draw(Size(side, side), alpha = mode, colorFilter = s.checkFilter) }
      }
    }
  }

  private fun DrawScope.drawBadges(s: GalleryGridCellStyle, layout: BadgeLayout) {
    layout.end?.let { pill ->
      translate(pill.left, pill.top) {
        drawPill(s, pill)
        translate(s.labeledPadding, (pill.size.height - s.badgeGlyph) / 2f) {
          with(s.play) { draw(Size(s.badgeGlyph, s.badgeGlyph), colorFilter = s.onPhotoFilter) }
        }
        pill.text?.let { text ->
          drawText(text, topLeft = Offset(s.labeledPadding + s.badgeGlyph + s.glyphGap, (pill.size.height - text.size.height) / 2f))
        }
      }
    }
    layout.start?.let { pill ->
      translate(pill.left, pill.top) {
        drawPill(s, pill)
        var x = (pill.size.width - pill.glyphsWidth) / 2f
        val y = (pill.size.height - s.badgeGlyph) / 2f
        for (painter in pill.glyphs) {
          translate(x, y) { with(painter) { draw(Size(s.badgeGlyph, s.badgeGlyph), colorFilter = s.onPhotoFilter) } }
          x += s.badgeGlyph + s.clusterGap
        }
      }
    }
  }

  private fun DrawScope.drawPill(s: GalleryGridCellStyle, pill: Pill) {
    drawOutline(pill.outline, s.badgeScrim)
    drawOutline(pill.outline, s.badgeHairline, style = Stroke(width = s.hairline))
  }

  override fun onDetach() {
    bitmap = null
  }
}

/** Una pillola di badge, gia' misurata: dove sta, quanto e' grande, cosa contiene. */
private class Pill(
  val left: Float,
  val top: Float,
  val size: Size,
  val outline: Outline,
  val text: TextLayoutResult? = null,
  val glyphs: List<VectorPainter> = emptyList(),
  val glyphsWidth: Float = 0f,
)

private class BadgeLayout(val start: Pill?, val end: Pill?)

/**
 * La riga dei badge in basso, come in `GalleryMediaCell`: i segni a sinistra, la durata a destra. **La durata ha la
 * precedenza**: i segni compaiono solo se ci stanno per intero, mai tagliati a meta'.
 */
private fun CacheDrawScope.layoutBadges(s: GalleryGridCellStyle, badges: GalleryGridCellBadges, size: Size): BadgeLayout? {
  val glyphs = buildList {
    if (badges.favorite) add(s.favorite)
    if (badges.edited) add(s.edited)
  }
  if (glyphs.isEmpty() && badges.duration == null) return null
  val rowWidth = size.width - 2 * s.badgeInset
  val top = size.height - s.badgeInset - s.badgeHeight
  var end: Pill? = null
  if (badges.duration != null) {
    val text = s.textMeasurer.measure(badges.duration, s.badgeText, maxLines = 1)
    val width = min(rowWidth, s.labeledPadding * 2 + s.badgeGlyph + s.glyphGap + text.size.width)
    val pillSize = Size(max(width, s.badgeHeight), s.badgeHeight)
    end = Pill(size.width - s.badgeInset - pillSize.width, top, pillSize, s.capsule(pillSize), text = text)
  }
  var start: Pill? = null
  if (glyphs.isNotEmpty()) {
    val glyphsWidth = glyphs.size * s.badgeGlyph + (glyphs.size - 1) * s.clusterGap
    val pillSize = Size(max(s.badgeHeight, glyphsWidth + 2 * s.iconPadding), s.badgeHeight)
    val room = rowWidth - (end?.let { it.size.width + s.rowGap } ?: 0f)
    if (pillSize.width <= room) start = Pill(s.badgeInset, top, pillSize, s.capsule(pillSize), glyphs = glyphs, glyphsWidth = glyphsWidth)
  }
  return BadgeLayout(start, end)
}

/** Un pennello che disegna [image] ritagliata al centro dentro [size] (come `ContentScale.Crop`), filtrata. */
private fun cropBrush(image: ImageBitmap, size: Size): ShaderBrush {
  val bitmap = image.asAndroidBitmap()
  val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
  val scale = max(size.width / max(1, bitmap.width), size.height / max(1, bitmap.height))
  val matrix = Matrix()
  matrix.setScale(scale, scale)
  matrix.postTranslate((size.width - bitmap.width * scale) / 2f, (size.height - bitmap.height * scale) / 2f)
  shader.setLocalMatrix(matrix)
  return ShaderBrush(shader)
}

private val BadgeIconPadding = 4.dp
private val BadgeLabeledPadding = 7.dp
private val BadgeGlyphGap = 3.dp
private val BadgeClusterGap = 4.dp
private val BadgeRowGap = 4.dp
private val BadgeHairline = 0.5.dp
private val SelectionRing = 1.75.dp
private val SelectionCheck = 14.dp
private val BrokenGlyph = 24.dp

/** Sotto questo lato i badge non ci stanno con garbo (il livello Anni): la cella resta pulita, come in Foto. */
private val BadgesMinCell = 64.dp

/** Il lato a cui la cella prende tutto il raggio di `FluidRadius.Small`; sotto, il raggio scala col lato. */
private val CornerReferenceCell = 100.dp
private const val MinCornerShare = 0.45f
private const val MaxCachedOutlines = 64
private const val PressedDimAlpha = 0.16f
private const val HairlineAlpha = 0.28f
private const val RingFillAlpha = 0.28f
private const val PlaceholderAccentShare = 0.14f
private const val TextCacheSize = 64
