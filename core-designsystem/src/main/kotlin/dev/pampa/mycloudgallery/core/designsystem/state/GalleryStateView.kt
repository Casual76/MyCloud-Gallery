package dev.pampa.mycloudgallery.core.designsystem.state

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.pampa.mycloudgallery.core.designsystem.R

/** I quattro stati vuoti o d'errore della galleria. */
enum class GalleryStateKind(
  @StringRes val title: Int,
  @StringRes val detail: Int,
  @StringRes val defaultAction: Int,
  val icon: ImageVector,
  val tone: GalleryIllustrationTone,
) {
  /** Nessun media da mostrare: il telefono e' vuoto, o il filtro non lascia niente. */
  LibraryEmpty(
    title = R.string.state_library_empty_title,
    detail = R.string.state_library_empty_detail,
    defaultAction = R.string.state_library_empty_action,
    icon = Icons.Rounded.PhotoLibrary,
    tone = GalleryIllustrationTone.Brand,
  ),

  /** La ricerca non ha trovato niente (con la query nel titolo, se c'e'). */
  NoSearchResults(
    title = R.string.state_no_results_title,
    detail = R.string.state_no_results_detail,
    defaultAction = R.string.state_no_results_action,
    icon = Icons.Rounded.SearchOff,
    tone = GalleryIllustrationTone.Brand,
  ),

  /** Il NAS non risponde. Non e' un fallimento della galleria: si lavora sul locale. */
  NasUnreachable(
    title = R.string.state_nas_title,
    detail = R.string.state_nas_detail,
    defaultAction = R.string.state_nas_action,
    icon = Icons.Rounded.CloudOff,
    tone = GalleryIllustrationTone.Caution,
  ),

  /** Manca il permesso di leggere le foto. */
  PermissionDenied(
    title = R.string.state_permission_title,
    detail = R.string.state_permission_detail,
    defaultAction = R.string.state_permission_action,
    icon = Icons.Rounded.Lock,
    tone = GalleryIllustrationTone.Danger,
  ),
}

/**
 * Uno stato vuoto o d'errore: un'illustrazione, un titolo, una frase e (se serve) un'azione.
 *
 * Nessun posto muto: una schermata che non ha niente da mostrare dice **perche'** e cosa si puo'
 * fare (piano, 2.1: "nessuna schermata resta bloccata o vuota senza spiegazione"). Le illustrazioni
 * sono vettoriali e disegnate dalla palette ([GalleryIllustration]): nessuna immagine esterna, e
 * cambiano con l'accento.
 *
 * L'azione compare solo se [onAction] c'e'. [query] e' il testo cercato, per `NoSearchResults`: finisce
 * nel titolo. [actionLabel] sostituisce la frase di default dell'azione.
 */
@Composable
fun GalleryStateView(
  kind: GalleryStateKind,
  modifier: Modifier = Modifier,
  query: String? = null,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  val title = if (kind == GalleryStateKind.NoSearchResults && !query.isNullOrBlank()) {
    stringResource(R.string.state_no_results_title_query, query)
  } else {
    stringResource(kind.title)
  }
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = StatePadding, vertical = StatePadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(StateGap),
  ) {
    GalleryIllustration(icon = kind.icon, tone = kind.tone)
    Text(
      text = title,
      modifier = Modifier.widthIn(max = StateTextMaxWidth),
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Text(
      text = stringResource(kind.detail),
      modifier = Modifier.widthIn(max = StateTextMaxWidth),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (onAction != null) {
      FluidButton(
        text = actionLabel ?: stringResource(kind.defaultAction),
        onClick = onAction,
        size = FluidButtonSize.Large,
        style = if (kind == GalleryStateKind.NoSearchResults) FluidButtonStyle.Tinted else FluidButtonStyle.Filled,
      )
    }
  }
}

/** Il tono di un'illustrazione: da quale famiglia di ruoli prende i colori. */
enum class GalleryIllustrationTone {
  /** La famiglia del marchio: primario, secondario, terziario. */
  Brand,

  /** Attenzione, non allarme: la famiglia del terziario. */
  Caution,

  /** Un problema che richiede un gesto dell'utente: la famiglia dell'errore. */
  Danger,
}

/**
 * L'illustrazione di uno stato: tre cornici di foto inclinate l'una sull'altra, la prima con il
 * glifo grande, su un alone morbido.
 *
 * E' disegnata solo con la palette: le cornici sono i contenitori dello schema (primario, secondario,
 * terziario) con i raggi dell'engine, l'alone un gradiente radiale dello stesso colore, il glifo un
 * `Icon` vettoriale. Con Material You o un altro preset prende quei colori, senza un file in piu'.
 * E' decorativa: la frase sotto dice tutto, quindi per un lettore di schermo non esiste.
 */
@Composable
fun GalleryIllustration(
  icon: ImageVector,
  modifier: Modifier = Modifier,
  tone: GalleryIllustrationTone = GalleryIllustrationTone.Brand,
  size: Dp = IllustrationSize,
) {
  val s = MaterialTheme.colorScheme
  // Il colore di ognuna delle tre cornici e il colore del glifo, per tono.
  val (front, onFront, back, behind) = when (tone) {
    GalleryIllustrationTone.Brand ->
      Palette(s.primaryContainer, s.onPrimaryContainer, s.secondaryContainer, s.tertiaryContainer)
    GalleryIllustrationTone.Caution ->
      Palette(s.tertiaryContainer, s.onTertiaryContainer, s.secondaryContainer, s.primaryContainer)
    GalleryIllustrationTone.Danger ->
      Palette(s.errorContainer, s.error, s.tertiaryContainer, s.secondaryContainer)
  }
  val frame = ContinuousCornerShape(FluidRadius.Card)
  val frameWidth = size * FrameWidthFraction
  val frameHeight = size * FrameHeightFraction

  Box(
    modifier = modifier
      .size(size)
      .clearAndSetSemantics { },
    contentAlignment = Alignment.Center,
  ) {
    // L'alone: un cerchio che sfuma nel fondo della pagina.
    Box(
      modifier = Modifier
        .size(size)
        .background(
          Brush.radialGradient(listOf(front.copy(alpha = HaloAlpha), Color.Transparent)),
          shape = FluidCapsuleShape,
        ),
    )
    // La cornice piu' lontana, inclinata a destra.
    Box(
      modifier = Modifier
        .offset(x = size * BackOffsetX, y = size * BackOffsetY)
        .size(frameWidth, frameHeight)
        .graphicsLayer { rotationZ = BackTilt }
        .clip(frame)
        .background(behind),
    )
    // La cornice di mezzo, inclinata a sinistra.
    Box(
      modifier = Modifier
        .offset(x = -size * MidOffsetX, y = size * MidOffsetY)
        .size(frameWidth, frameHeight)
        .graphicsLayer { rotationZ = MidTilt }
        .clip(frame)
        .background(back),
    )
    // La cornice davanti, dritta, con il glifo.
    Box(
      modifier = Modifier
        .size(frameWidth, frameHeight)
        .clip(frame)
        .background(front)
        .border(width = FrameRing, color = onFront.copy(alpha = RingAlpha), shape = frame),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(size * GlyphFraction),
        tint = onFront,
      )
    }
    // Due scintille, per non lasciare l'alone vuoto.
    Box(
      modifier = Modifier
        .align(Alignment.TopEnd)
        .offset(x = -size * SparkOffset, y = size * SparkOffset)
        .size(size * SparkLarge)
        .background(onFront.copy(alpha = SparkAlpha), FluidCapsuleShape),
    )
    Box(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .offset(x = size * SparkOffset, y = -size * SparkOffset)
        .size(size * SparkSmall)
        .background(onFront.copy(alpha = SparkAlpha / 2f), FluidCapsuleShape),
    )
  }
}

private data class Palette(val front: Color, val onFront: Color, val back: Color, val behind: Color)

private val IllustrationSize = 168.dp
private val StatePadding = 32.dp
private val StateGap = 12.dp
private val StateTextMaxWidth = 340.dp
private val FrameRing = 1.dp
private const val FrameWidthFraction = 0.5f
private const val FrameHeightFraction = 0.62f
private const val GlyphFraction = 0.26f
private const val HaloAlpha = 0.55f
private const val RingAlpha = 0.22f
private const val SparkAlpha = 0.55f
private const val SparkLarge = 0.07f
private const val SparkSmall = 0.045f
private const val SparkOffset = 0.10f
private const val BackOffsetX = 0.16f
private const val BackOffsetY = -0.02f
private const val MidOffsetX = 0.14f
private const val MidOffsetY = 0.02f
private const val BackTilt = 9f
private const val MidTilt = -8f
