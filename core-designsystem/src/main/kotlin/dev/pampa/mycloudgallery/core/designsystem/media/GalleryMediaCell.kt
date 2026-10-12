package dev.pampa.mycloudgallery.core.designsystem.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryMotion
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto

/** I valori di riferimento della cella della griglia. */
object GalleryCellDefaults {

  /**
   * La sagoma della cella: angolo continuo, il piu' piccolo dei raggi dell'engine. In una griglia
   * fitta (livello "Tutte") il chiamante puo' passare un raggio nullo; il ritaglio lo decide lui.
   */
  val shape: Shape = ContinuousCornerShape(FluidRadius.Small)

  /** Quanto si rimpicciolisce la foto quando la cella e' selezionata: una scala leggera, non un salto. */
  const val SelectedScale: Float = 0.86f

  /** Quanto cede la cella sotto il dito. Un filo piu' del 2,6% di default: nella griglia le celle sono piccole. */
  const val PressedScale: Float = 0.965f

  /** Il rapporto minimo e massimo che una cella "in proporzione" accetta (un panorama non la sfonda). */
  const val MinAspectRatio: Float = 0.5f
  const val MaxAspectRatio: Float = 2f
}

/**
 * La cella della griglia: la miniatura di un media, quadrata o in proporzione originale.
 *
 *  - **Quadrata o in proporzione**: [aspectRatio] 1 e' la griglia quadrata; larghezza/altezza della
 *    foto e' l'opzione "proporzioni originali".
 *  - **Angoli continui**: [GalleryCellDefaults.shape], mai circolari.
 *  - **Segnaposto**: finche' [loaded] e' falso la cella e' una tinta con un luccichio lento (spento con
 *    `reducedMotion`); [placeholderColor] puo' essere il colore dominante del media, se l'indice lo ha.
 *    Quando arriva l'immagine entra con una dissolvenza: e' una miniatura, non una pagina.
 *  - **Premuta**: la cella cede (`fluidPressable`), niente onda.
 *  - **Selezione**: con [selectionMode] ogni cella mostra un cerchio; se [selected] la foto si
 *    rimpicciolisce dentro una cornice dell'accento e il cerchio diventa una spunta.
 *  - **Badge**: quattro angoli, quattro slot. In alto a sinistra sta la selezione; [topEnd] e' lo stato
 *    di dove si trova il media (solo NAS, al sicuro, errore...), [bottomStart] i segni (preferito,
 *    condiviso, modificato) e [bottomEnd] la durata di un video. Vedi [GalleryBadgeView].
 *
 * L'immagine e' uno **slot**: la cella non sa caricare niente (niente librerie di immagini qui: Coil
 * arriva con la Libreria, in `:app`).
 *
 * @param contentDescription cosa pronuncia un lettore di schermo ("Foto, 12 marzo 2024, Oslo"). I
 *   badge ci si uniscono da soli: "...Preferito, Solo sul NAS".
 */
@Composable
fun GalleryMediaCell(
  contentDescription: String,
  modifier: Modifier = Modifier,
  aspectRatio: Float = 1f,
  loaded: Boolean = true,
  selectionMode: Boolean = false,
  selected: Boolean = false,
  shape: Shape = GalleryCellDefaults.shape,
  placeholderColor: Color = Color.Unspecified,
  interactionSource: MutableInteractionSource? = null,
  onClick: (() -> Unit)? = null,
  onLongClick: (() -> Unit)? = null,
  topEnd: (@Composable () -> Unit)? = null,
  bottomStart: (@Composable () -> Unit)? = null,
  bottomEnd: (@Composable () -> Unit)? = null,
  image: @Composable BoxScope.() -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val isSelected = selectionMode && selected
  val loadingText = stringResource(R.string.cell_loading)
  val selectedText = stringResource(R.string.cell_selected)
  val notSelectedText = stringResource(R.string.cell_not_selected)

  val source = interactionSource ?: remember { MutableInteractionSource() }
  val pressed by source.collectIsPressedAsState()
  val pressDim by animateFloatAsState(
    targetValue = if (pressed) 1f else 0f,
    animationSpec = if (pressed) FluidMotion.instant() else FluidMotion.fadeOut(),
    label = "cell press dim",
  )
  val photoScale by animateFloatAsState(
    targetValue = if (isSelected) GalleryCellDefaults.SelectedScale else 1f,
    animationSpec = FluidMotion.standard(),
    label = "cell selected scale",
  )
  val frameAlpha by animateFloatAsState(
    targetValue = if (isSelected) 1f else 0f,
    animationSpec = FluidMotion.crossFade(),
    label = "cell selection frame",
  )
  val imageAlpha by animateFloatAsState(
    targetValue = if (loaded) 1f else 0f,
    animationSpec = FluidMotion.fadeIn(),
    label = "cell image fade",
  )

  Box(
    modifier = modifier
      .aspectRatio(aspectRatio.coerceIn(GalleryCellDefaults.MinAspectRatio, GalleryCellDefaults.MaxAspectRatio))
      .semantics(mergeDescendants = true) {
        this.contentDescription = contentDescription
        when {
          selectionMode -> {
            toggleableState = ToggleableState(selected)
            this.selected = selected
            stateDescription = if (selected) selectedText else notSelectedText
          }
          !loaded -> stateDescription = loadingText
        }
      }
      .fluidPressable(
        onClick = onClick,
        onLongClick = onLongClick,
        pressedScale = GalleryCellDefaults.PressedScale,
        role = if (selectionMode) Role.Checkbox else Role.Button,
        interactionSource = source,
        focusShape = shape,
      ),
  ) {
    // La cornice dell'accento: si vede solo attorno alla foto rimpicciolita di una cella selezionata.
    Box(
      modifier = Modifier
        .matchParentSize()
        .graphicsLayer { alpha = frameAlpha }
        .clip(shape)
        .background(scheme.primaryContainer),
    )

    // La foto: il segnaposto sotto, l'immagine sopra che entra a dissolvenza.
    Box(
      modifier = Modifier
        .matchParentSize()
        .graphicsLayer {
          scaleX = photoScale
          scaleY = photoScale
        }
        .clip(shape),
    ) {
      GalleryPlaceholder(modifier = Modifier.matchParentSize(), color = placeholderColor, active = !loaded)
      if (imageAlpha > 0f) {
        Box(
          modifier = Modifier
            .matchParentSize()
            .graphicsLayer { alpha = imageAlpha },
        ) { image() }
      }
      // Premuta la cella cede (scala) e si fa piu' scura: la scala da sola, a 3,5%, non si vede.
      Box(
        modifier = Modifier
          .matchParentSize()
          .graphicsLayer { alpha = pressDim }
          .background(scheme.scrim.copy(alpha = PressedDimAlpha)),
      )
    }

    // I badge, ai quattro angoli.
    Box(
      modifier = Modifier
        .matchParentSize()
        .padding(GalleryDimens.BadgeInset),
    ) {
      if (selectionMode) {
        GallerySelectionIndicator(selected = selected, modifier = Modifier.align(Alignment.TopStart))
      }
      topEnd?.let { Box(Modifier.align(Alignment.TopEnd)) { it() } }
      if (bottomStart != null || bottomEnd != null) {
        BottomBadgeRow(
          start = bottomStart,
          end = bottomEnd,
          modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        )
      }
    }
  }
}

/**
 * La riga dei badge in basso: i segni a sinistra, la durata a destra.
 *
 * La durata ha la precedenza. In una griglia fitta una cella e' larga 70 dp, e un cuore piu' una
 * durata non ci stanno: invece di sovrapporle (il difetto di metterle in due `Box` allineati agli
 * angoli) i segni cedono il posto — compaiono solo se c'e' spazio per intero, mai tagliati a meta'.
 */
@Composable
private fun BottomBadgeRow(
  start: (@Composable () -> Unit)?,
  end: (@Composable () -> Unit)?,
  modifier: Modifier = Modifier,
) {
  Layout(
    content = {
      Box { start?.invoke() }
      Box { end?.invoke() }
    },
    modifier = modifier,
  ) { measurables, constraints ->
    val gap = BadgeRowGap.roundToPx()
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val endPlaceable = measurables[1].measure(loose)
    val room = (constraints.maxWidth - endPlaceable.width - gap).coerceAtLeast(0)
    val startPlaceable = if (measurables[0].maxIntrinsicWidth(constraints.maxHeight) <= room) {
      measurables[0].measure(loose)
    } else {
      null
    }
    val height = max(endPlaceable.height, startPlaceable?.height ?: 0)
    layout(constraints.maxWidth, height) {
      startPlaceable?.place(0, height - startPlaceable.height)
      endPlaceable.place(constraints.maxWidth - endPlaceable.width, height - endPlaceable.height)
    }
  }
}

/**
 * Il cerchio di selezione: un anello bianco su uno scrim leggero quando la cella si puo' scegliere,
 * un cerchio pieno dell'accento con la spunta quando e' scelta. Bianco e scrim sono la coppia "sopra
 * una foto" di [GalleryOnPhoto]; l'accento e la sua spunta sono la coppia `primary`/`onPrimary`.
 */
@Composable
fun GallerySelectionIndicator(
  selected: Boolean,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  val fill by animateFloatAsState(
    targetValue = if (selected) 1f else 0f,
    animationSpec = FluidMotion.fluid(),
    label = "selection fill",
  )
  Box(
    modifier = modifier
      .size(GalleryDimens.SelectionIndicator)
      .clip(FluidCapsuleShape)
      .background(
        if (selected) scheme.primary.copy(alpha = fill.coerceIn(0f, 1f)) else GalleryOnPhoto.scrim().copy(alpha = 0.28f),
      )
      .border(width = SelectionRing, color = GalleryOnPhoto.Content, shape = FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    if (fill > 0f) {
      Icon(
        imageVector = Icons.Rounded.Check,
        contentDescription = null,
        modifier = Modifier
          .size(SelectionCheck)
          .graphicsLayer {
            scaleX = fill
            scaleY = fill
          },
        tint = scheme.onPrimary,
      )
    }
  }
}

/**
 * Il segnaposto di una miniatura che sta caricando: una tinta morbida (un gradiente fra due toni
 * dello stesso colore, che da' l'idea di una foto sfocata) con un luccichio che la attraversa piano.
 *
 * Il colore di default viene dalla palette: una lavata dell'accento sul grigio piu' alto, cosi' un
 * secondo accento (Material You, un preset) cambia anche i segnaposto senza una riga in piu'.
 */
@Composable
fun GalleryPlaceholder(
  modifier: Modifier = Modifier,
  color: Color = Color.Unspecified,
  active: Boolean = true,
) {
  val scheme = MaterialTheme.colorScheme
  val near = if (color.isSpecified) color else lerp(scheme.surfaceContainerHighest, scheme.primary, PlaceholderAccentShare)
  val far = lerp(near, scheme.background, PlaceholderFadeShare)
  val sweep: State<Float>? = if (active && LocalFluidMotionPolicy.current.allowDecorativeMotion) {
    rememberInfiniteTransition(label = "placeholder shimmer").animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = GalleryMotion.shimmerSpec(),
      label = "placeholder shimmer sweep",
    )
  } else {
    null
  }
  val glint = scheme.surface.copy(alpha = ShimmerAlpha)
  Box(
    modifier = modifier
      .background(Brush.linearGradient(listOf(near, far)))
      .drawBehind {
        val t = sweep?.value ?: return@drawBehind
        val band = size.maxDimension * ShimmerBand
        val x = lerpFloat(-band, size.width + band, t)
        drawRect(
          brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, glint, Color.Transparent),
            startX = x - band / 2f,
            endX = x + band / 2f,
          ),
        )
      },
  )
}

private val BadgeRowGap: Dp = 4.dp
private const val PressedDimAlpha = 0.16f
private val SelectionRing = 1.75.dp
private val SelectionCheck = 14.dp
private const val PlaceholderAccentShare = 0.14f
private const val PlaceholderFadeShare = 0.38f
private const val ShimmerAlpha = 0.5f
private const val ShimmerBand = 0.6f
