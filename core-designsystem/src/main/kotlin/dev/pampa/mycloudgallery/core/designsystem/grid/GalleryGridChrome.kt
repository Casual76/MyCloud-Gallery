package dev.pampa.mycloudgallery.core.designsystem.grid

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.a11y.galleryGlass
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

// La chrome della griglia: i pezzi di vetro che galleggiano sopra le foto della Libreria. Pochi e piccoli, perche' ogni
// lastra di vetro sopra un contenuto che scorre si ricattura a ogni fotogramma (regola del vetro: sul contenitore, mai
// sulla riga).

/**
 * I **segmenti dei livelli** (§2.4): Anni · Mesi · Giorni · Tutte, in una capsula di vetro che galleggia sopra la
 * griglia. E' l'alternativa al pinch per chi non puo' pizzicare (accessibilita') e la via piu' rapida per saltare
 * piu' livelli; dentro c'e' il controllo a segmenti dell'engine (la lente che scivola sul segmento scelto).
 */
@Composable
fun GalleryGridLevelBar(
  labels: List<String>,
  selected: Int,
  onSelect: (Int) -> Unit,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .widthIn(max = LevelBarMaxWidth)
      .fillMaxWidth()
      .galleryGlass(state = backdrop, tint = GlassDefaults.floatingTint(), shape = FluidCapsuleShape, role = GlassRole.Floating)
      .padding(LevelBarPadding),
  ) {
    FluidSegmentedControl(
      options = labels.indices.toList(),
      selected = selected.coerceIn(0, labels.lastIndex),
      onSelect = onSelect,
      label = { labels[it] },
    )
  }
}

/**
 * Un **avviso** sopra la griglia (accesso parziale E-P1, foto in attesa E-P21): una lastra di vetro compatta con l'icona
 * in una piastrella del tono, il titolo, una frase breve e, sulla stessa riga, l'azione che risolve (senza frase, [message] vuoto: il
 * segno, il titolo e il tasto in una striscia sola). Mai un muro: la
 * griglia resta sotto e si scorre; due avvisi insieme occupano meno di un terzo dello schermo.
 */
@Composable
fun GalleryGridNotice(
  icon: ImageVector,
  title: String,
  message: String,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  family: GalleryIconFamily? = null,
) {
  val scheme = MaterialTheme.colorScheme
  val shape = ContinuousCornerShape(FluidRadius.Card)
  Row(
    modifier = modifier
      .widthIn(max = NoticeMaxWidth)
      .fillMaxWidth()
      .galleryGlass(state = backdrop, tint = GlassDefaults.floatingTint(), shape = shape, role = GlassRole.Floating)
      .padding(NoticePadding),
    horizontalArrangement = Arrangement.spacedBy(NoticeGap),
    verticalAlignment = Alignment.Top,
  ) {
    if (family != null) {
      // CU-8 (fase I): la piastrella della famiglia, come in tutta l'app (niente piastrelle pallide accanto alle colorate).
      GalleryIconTile(icon = icon, family = family, size = NoticeTile)
    } else {
      Box(
        modifier = Modifier
          .size(NoticeTile)
          .background(scheme.primaryContainer, ContinuousCornerShape(FluidRadius.Small)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(NoticeGlyph), tint = scheme.onPrimaryContainer)
      }
    }
    // I4 (fase I, «l'app parla troppo»): senza frase l'avviso e' una striscia, il segno, due parole e il tasto sulla stessa riga, anche a testo
    // grande (CS-13: impilate, due strisce coprivano quasi meta' dello schermo): il titolo va al piu' su due righe accanto al tasto.
    if (message.isBlank()) {
      StripBody(title = title, actionLabel = actionLabel, onAction = onAction, modifier = Modifier.weight(1f))
      return@Row
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NoticeTextGap)) {
      Text(text = title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
      if (message.isNotBlank()) Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
        maxLines = NoticeMessageLines,
        overflow = TextOverflow.Ellipsis,
      )
      if (actionLabel != null && onAction != null) {
        // L'azione sotto la frase, allineata al testo: la riga resta tutta per le parole.
        Box(modifier = Modifier.padding(top = NoticeActionGap).defaultMinSize(minHeight = GalleryDimens.MinTouchTarget), contentAlignment = Alignment.CenterStart) {
          FluidButton(text = actionLabel, onClick = onAction, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Medium)
        }
      }
    }
  }
}

/**
 * Il corpo di una striscia (CU-26, CS-16): il titolo accanto al tasto quando ci sta con almeno [StripTitleMin] (al piu' due righe); altrimenti il
 * titolo intero sopra e il tasto sotto. Il titolo non si taglia mai a «12 …»: dice quante foto e che cosa aspettano.
 */
@Composable
private fun StripBody(title: String, actionLabel: String?, onAction: (() -> Unit)?, modifier: Modifier) {
  val scheme = MaterialTheme.colorScheme
  val style = MaterialTheme.typography.titleSmall
  val measurer = rememberTextMeasurer()
  // CS-27 (fase I): a testo normale il tasto sta accanto al titolo quando il titolo ci sta intero in due righe (nessuna parola spezzata); a testo
  // grande va sempre sotto.
  val bigText = LocalDensity.current.fontScale > StripBigTextScale
  SubcomposeLayout(modifier = modifier) { constraints ->
    val width = constraints.maxWidth
    val button = if (actionLabel != null && onAction != null) {
      subcompose("tasto") {
        Box(modifier = Modifier.defaultMinSize(minHeight = GalleryDimens.MinTouchTarget), contentAlignment = Alignment.Center) {
          FluidButton(text = actionLabel, onClick = onAction, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Medium)
        }
      }.first().measure(Constraints(maxWidth = width))
    } else {
      null
    }
    val gap = NoticeGap.roundToPx()
    val room = if (button == null) width else width - button.width - gap
    val fitsBeside = button == null || (!bigText && room >= StripTitleMin.roundToPx() && fitsWhole(measurer, title, style, room))
    if (fitsBeside) {
      val text = subcompose("titolo") {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = StripTitleLines, overflow = TextOverflow.Ellipsis)
      }.first().measure(Constraints(maxWidth = room.coerceAtLeast(0)))
      val height = maxOf(text.height, button?.height ?: 0)
      layout(width, height) {
        text.place(0, (height - text.height) / 2)
        button?.place(width - button.width, (height - button.height) / 2)
      }
    } else {
      val text = subcompose("titolo-intero") {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
      }.first().measure(Constraints(maxWidth = width))
      val below = text.height + NoticeActionGap.roundToPx()
      layout(width, below + button.height) {
        text.place(0, 0)
        button.place(0, below)
      }
    }
  }
}

/** Il titolo sta intero in [room] px: al piu' due righe, senza tagli, e nessuna parola piu' larga della riga (niente parole spezzate). */
private fun fitsWhole(measurer: TextMeasurer, title: String, style: TextStyle, room: Int): Boolean {
  if (room <= 0) return false
  val whole = measurer.measure(title, style, constraints = Constraints(maxWidth = room), maxLines = StripTitleLines)
  if (whole.hasVisualOverflow) return false
  return title.split(' ').filter { it.isNotEmpty() }.all { word -> measurer.measure(word, style, maxLines = 1, softWrap = false).size.width <= room }
}

/** Sotto questa larghezza il titolo di una striscia non sta accanto al tasto: il tasto va sotto. */
private val StripTitleMin: Dp = 56.dp

/** Oltre questa scala del testo il tasto della striscia va sempre sotto. */
private const val StripBigTextScale = 1.15f

/**
 * Il tasto **"Oggi"** (§2.4, "torna a oggi"): una capsula di vetro che compare quando si e' lontani dai piu' recenti e
 * riporta in fondo. Bersaglio di 48 dp, cresce sotto il dito come ogni tasto (`fluidPressable` prima del vetro).
 */
@Composable
fun GalleryGridJumpButton(
  label: String,
  icon: ImageVector,
  onClick: () -> Unit,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  contentDescription: String = label,
) {
  Box(
    modifier = modifier
      .defaultMinSize(minHeight = GalleryDimens.MinTouchTarget)
      .semantics { this.contentDescription = contentDescription }
      .fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier
        .height(JumpHeight)
        .galleryGlass(state = backdrop, tint = GlassDefaults.floatingTint(), shape = FluidCapsuleShape, role = GlassRole.Floating)
        .padding(horizontal = JumpPadding),
      horizontalArrangement = Arrangement.spacedBy(JumpGap),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(JumpGlyph), tint = MaterialTheme.colorScheme.primary)
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** La larghezza massima della capsula dei livelli: su un tablet non attraversa lo schermo. */
val LevelBarMaxWidth: Dp = 360.dp

private val LevelBarPadding = 6.dp
private val NoticeMaxWidth = 560.dp
private val NoticePadding = 12.dp
private val NoticeGap = 12.dp
private val NoticeTextGap = 2.dp
private val NoticeActionGap = 4.dp
private val NoticeTile = 36.dp
private val NoticeGlyph = 20.dp
private const val NoticeMessageLines = 3
private const val StripTitleLines = 2
private val JumpHeight = 40.dp
private val JumpPadding = 14.dp
private val JumpGap = 6.dp
private val JumpGlyph = 18.dp
