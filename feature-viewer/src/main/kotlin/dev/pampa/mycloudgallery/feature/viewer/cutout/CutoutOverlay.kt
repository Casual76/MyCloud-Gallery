package dev.pampa.mycloudgallery.feature.viewer.cutout

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryMotion
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.livetext.ContentMapping
import dev.pampa.mycloudgallery.feature.viewer.page.ZoomableMediaState
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Il ritaglio del soggetto disegnato sopra la foto della pagina corrente (D44, §2.10): il **velo che pulsa** mentre MagicTouch lavora, poi
 * il **soggetto che si stacca** (il resto si attenua, il soggetto si solleva di poco con un contorno luminoso), la **barra** "Copia ·
 * Condividi · Salva come nuova foto" e l'interruttore "Contorno" (l'adesivo con il bordo bianco). Sta **fuori** dalla trasformazione dello
 * zoom e ne rifa i conti a ogni fotogramma ([ZoomableMediaState.contentMapping]): il soggetto segue la foto a ogni livello di zoom.
 *
 * Con "Rimuovi animazioni" di sistema niente movimento: il velo e' fermo, il soggetto compare gia' staccato. Non fa niente se lo stato non
 * e' di questa pagina: il ritaglio di un media non compare mai su un altro (E-H1).
 *
 * @param bitmap la foto che si vede in questa pagina: e' la stessa dei pixel su cui si e' ritagliato (stessa proporzione, stesso raddrizzamento).
 */
@Composable
internal fun CutoutOverlay(
  controller: CutoutController,
  id: MediaId,
  zoom: ZoomableMediaState,
  aspect: Float,
  bitmap: ImageBitmap?,
  modifier: Modifier = Modifier,
) {
  val view = LocalView.current
  SideEffect { controller.view = view }
  DisposableEffect(controller) { onDispose { controller.view = null } }
  if (controller.owner != id) return
  val state = controller.state
  val lifted = state is CutoutState.Lifted || state is CutoutState.Saving || state is CutoutState.Saved
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val announce = stringResource(R.string.cutout_lifted_announce)
  LaunchedEffect(controller.art) { if (controller.art != null) view.announceForAccessibility(announce) }

  BoxWithConstraints(modifier.fillMaxSize()) {
    val page = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
    val mapping = zoom.contentMapping(page, aspect)
    if (state is CutoutState.Lifting) {
      LiftingVeil(controller.origin, mapping, reduced)
    }
    val art = controller.art
    if (lifted && art != null) {
      LiftedSubject(controller, art, bitmap, mapping, reduced)
      CutoutBar(controller)
    }
    // I6: dai Nascosti "Salva come nuova foto" chiede prima conferma.
    CutoutHiddenSaveSheet(
      visible = controller.confirmingHiddenSave,
      onConfirm = controller::confirmHiddenSave,
      onCancel = controller::cancelHiddenSave,
    )
  }
}

/** Il velo che pulsa mentre il modello lavora (D44: 150-400 ms): una velatura sulla foto e un anello dove e' caduto il dito. */
@Composable
private fun LiftingVeil(origin: Pair<Float, Float>?, mapping: ContentMapping, reduced: Boolean) {
  // La pulsazione: il respiro lento del design system (`GalleryMotion.shimmerSpec`, nessun movimento scritto a mano) andata e ritorno; con
  // "Rimuovi animazioni" resta ferma a meta'.
  val transition = rememberInfiniteTransition(label = "velo del ritaglio")
  val ramp by transition.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = GalleryMotion.shimmerSpec(), label = "pulsazione")
  val phase = if (reduced) 0.5f else 1f - kotlin.math.abs(2f * ramp - 1f)
  val veilColor = GalleryOnPhoto.Content
  val progressLabel = stringResource(R.string.cutout_lifting)
  val density = LocalDensity.current.density
  Canvas(Modifier.fillMaxSize().semantics { contentDescription = progressLabel }) {
    val tl = mapping.imageToScreen(Offset(0f, 0f))
    val br = mapping.imageToScreen(Offset(1f, 1f))
    drawRect(veilColor.copy(alpha = VEIL_MIN + (VEIL_MAX - VEIL_MIN) * phase), topLeft = tl, size = Size(br.x - tl.x, br.y - tl.y))
    if (origin != null) {
      val center = mapping.imageToScreen(Offset(origin.first, origin.second))
      val radius = (RING_MIN_DP + (RING_MAX_DP - RING_MIN_DP) * phase) * density
      drawCircle(veilColor.copy(alpha = RING_ALPHA * (1.2f - phase)), radius = radius, center = center, style = Stroke(width = 3f * density))
    }
  }
}

/** Il soggetto staccato: il resto si attenua, il soggetto si solleva con il bagliore; con "Contorno" il bordo bianco dell'adesivo. */
@Composable
private fun LiftedSubject(controller: CutoutController, art: CutoutArt, bitmap: ImageBitmap?, mapping: ContentMapping, reduced: Boolean) {
  // Parte da 0 e arriva a 1 appena compare (senza animazioni, subito a 1).
  var shown by remember { mutableStateOf(reduced) }
  LaunchedEffect(Unit) { shown = true }
  val progress by animateFloatAsState(
    targetValue = if (shown) 1f else 0f,
    animationSpec = if (reduced) snap() else FluidMotion.smooth(),
    label = "il soggetto si stacca",
  )
  val outline = controller.outline
  val dim = MaterialTheme.colorScheme.scrim
  val light = GalleryOnPhoto.Content
  val description = stringResource(R.string.cutout_subject_desc)
  Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
    val tl = mapping.imageToScreen(Offset(0f, 0f))
    val br = mapping.imageToScreen(Offset(1f, 1f))
    val dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt())
    val dstSize = IntSize(max(1, (br.x - tl.x).roundToInt()), max(1, (br.y - tl.y).roundToInt()))
    // Il resto della foto si attenua.
    drawRect(dim.copy(alpha = SCRIM_ALPHA * progress))
    if (bitmap == null) return@Canvas
    val pivot = Offset((tl.x + br.x) / 2f, (tl.y + br.y) / 2f)
    val lift = 1f + LIFT_SCALE * progress
    withTransform({ scale(lift, lift, pivot) }) {
      // Il bagliore (e', con l'interruttore acceso, sostituito dal bordo bianco dell'adesivo, che e' lo stesso del PNG).
      if (outline) {
        drawImage(art.outline, dstOffset = dstOffset, dstSize = dstSize, colorFilter = ColorFilter.tint(light), filterQuality = FilterQuality.Medium)
      } else {
        drawImage(art.halo, dstOffset = dstOffset, dstSize = dstSize, alpha = HALO_ALPHA * progress, colorFilter = ColorFilter.tint(light), filterQuality = FilterQuality.Medium)
      }
      drawSubject(bitmap, art, dstOffset, dstSize)
    }
  }
}

/** La foto ritagliata dalla maschera: si disegna in uno strato e la maschera lo taglia (`DstIn`). */
private fun DrawScope.drawSubject(bitmap: ImageBitmap, art: CutoutArt, dstOffset: IntOffset, dstSize: IntSize) {
  drawIntoCanvas { canvas ->
    val bounds = Rect(dstOffset.x.toFloat(), dstOffset.y.toFloat(), (dstOffset.x + dstSize.width).toFloat(), (dstOffset.y + dstSize.height).toFloat())
    canvas.saveLayer(bounds, Paint())
    drawImage(bitmap, dstOffset = dstOffset, dstSize = dstSize, filterQuality = FilterQuality.High)
    drawImage(art.mask, dstOffset = dstOffset, dstSize = dstSize, blendMode = BlendMode.DstIn, filterQuality = FilterQuality.Medium)
    canvas.restore()
  }
}

// --- La barra ----------------------------------------------------------------------------------------------------------------------

/**
 * La barra del soggetto staccato: l'interruttore "Contorno" e le tre azioni, in basso e al centro (il chrome del viewer si toglie mentre il
 * soggetto e' staccato: niente controlli uno sopra l'altro). Bersagli da 48 dp; ogni azione ha la sua etichetta per TalkBack. Senza vetro:
 * sta dentro la pagina, che e' la sorgente dello sfondo (come la barra del testo dal vivo): una tinta piena dello schema.
 */
@Composable
private fun CutoutBar(controller: CutoutController) {
  val state = controller.state
  val saving = state is CutoutState.Saving
  val saved = state is CutoutState.Saved
  val busy = controller.working || saving || saved
  val copy = stringResource(R.string.cutout_bar_copy)
  val share = stringResource(R.string.cutout_bar_share)
  val save = stringResource(R.string.cutout_bar_save)
  Box(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 20.dp), contentAlignment = Alignment.BottomCenter) {
    // Un tocco negli spazi fra i tasti e' della barra: non arriva alla pagina (che chiuderebbe il ritaglio).
    Column(
      modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlineSwitch(on = controller.outline, enabled = !busy, onToggle = controller::toggleOutline)
      val scheme = MaterialTheme.colorScheme
      Row(
        modifier = Modifier
          .clip(FluidCapsuleShape)
          .background(scheme.surfaceContainerHigh)
          .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        BarAction(Icons.Rounded.ContentCopy, copy, enabled = !busy, onClick = controller::copy)
        BarAction(Icons.Rounded.IosShare, share, enabled = !busy, onClick = controller::share)
        when {
          saving -> BarProgress(stringResource(R.string.cutout_saving))
          saved -> BarAction(Icons.Rounded.CheckCircle, stringResource(R.string.cutout_saved), enabled = false, onClick = {})
          else -> BarAction(Icons.Rounded.SaveAlt, save, enabled = !busy, wide = true, onClick = controller::save)
        }
      }
    }
  }
}

/** L'interruttore "Contorno": una capsula da 48 dp con la spunta quando e' acceso. */
@Composable
private fun OutlineSwitch(on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
  val label = stringResource(R.string.cutout_outline)
  val state = stringResource(if (on) R.string.cutout_outline_on else R.string.cutout_outline_off)
  val scheme = MaterialTheme.colorScheme
  val container = if (on) scheme.primaryContainer else scheme.surfaceContainerHigh
  val content = if (on) scheme.onPrimaryContainer else scheme.onSurface
  Row(
    modifier = Modifier
      .defaultMinSize(minHeight = TARGET)
      .clip(FluidCapsuleShape)
      .background(container)
      .alpha(if (enabled) 1f else DISABLED_ALPHA)
      .semantics(mergeDescendants = true) {
        contentDescription = label
        stateDescription = state
      }
      .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
      .padding(horizontal = 18.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (on) Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = content)
    Text(label, color = content, style = MaterialTheme.typography.labelLarge)
  }
}

/** Un'azione della barra: l'icona sopra, l'etichetta sotto; almeno 48 dp in ogni direzione. */
@Composable
private fun BarAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, wide: Boolean = false) {
  val scheme = MaterialTheme.colorScheme
  Column(
    modifier = Modifier
      .defaultMinSize(minWidth = if (wide) WIDE_ACTION else ACTION_WIDTH, minHeight = ACTION_HEIGHT)
      .alpha(if (enabled) 1f else DISABLED_ALPHA)
      .semantics(mergeDescendants = true) { contentDescription = label }
      .fluidPressable(onClick = { if (enabled) onClick() }, role = Role.Button, focusShape = FluidCapsuleShape)
      .padding(horizontal = 10.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = scheme.onSurface)
    Spacer(Modifier.height(2.dp))
    Text(label, color = scheme.onSurface, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
  }
}

/** "Salvo la foto nuova…" al posto di "Salva come nuova foto" mentre il PNG si scrive. */
@Composable
private fun BarProgress(label: String) {
  val scheme = MaterialTheme.colorScheme
  Column(
    modifier = Modifier
      .defaultMinSize(minWidth = WIDE_ACTION, minHeight = ACTION_HEIGHT)
      .semantics(mergeDescendants = true) { contentDescription = label }
      .padding(horizontal = 10.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = scheme.primary)
    Spacer(Modifier.height(2.dp))
    Text(label, color = scheme.onSurface, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
  }
}

private val TARGET: Dp = 48.dp
private val ACTION_WIDTH: Dp = 72.dp
private val WIDE_ACTION: Dp = 112.dp
private val ACTION_HEIGHT: Dp = 56.dp
private const val DISABLED_ALPHA = 0.45f
private const val SCRIM_ALPHA = 0.55f
private const val HALO_ALPHA = 0.85f
private const val LIFT_SCALE = 0.025f
private const val VEIL_MIN = 0.04f
private const val VEIL_MAX = 0.20f
private const val RING_MIN_DP = 14f
private const val RING_MAX_DP = 34f
private const val RING_ALPHA = 0.8f

