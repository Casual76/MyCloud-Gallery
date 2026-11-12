package dev.pampa.mycloudgallery.feature.nas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.theme.FluidGroupOpenEdge
import dev.antigravity.fluidengine.ui.theme.FluidGroupSegment
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidLoading
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoDetail
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.nas.NasDimens
import dev.pampa.mycloudgallery.feature.nas.R

// I pezzi delle pagine della 4B che non sono chrome: gli avvisi, gli stati vuoti, il cerchio di selezione. Tutti dai ruoli dello
// schema (nessun colore a mano) e dai toni dell'indicatore di stato globale, le cui coppie `GalleryContrastTest` verifica su ogni
// accento; `NasContrastTest` verifica quelle di questo file con le stesse soglie.

/** Quanto un avviso chiede attenzione: sono i toni del piede della Libreria, nello stesso ordine. */
internal enum class NasTone {
  /** Tutto a posto. */
  Calm,

  /** Un lavoro in corso. */
  Working,

  /** Fermo per una ragione normale (sola lettura, a riposo). */
  Waiting,

  /** Da guardare, senza colpa (il NAS non risponde). */
  Attention,

  /** Serve l'utente, o qualcosa si e' fermato. */
  Alarm,
}

/** I colori di un tono: il riempimento, il testo e il glifo. Le stesse coppie dell'indicatore di stato globale. */
@Immutable
internal data class NasToneStyle(val container: Color, val text: Color, val glyph: Color)

/** Il tono risolto da uno schema (pura: il contrasto di ogni coppia si verifica senza comporre). */
internal fun nasToneStyle(tone: NasTone, s: ColorScheme): NasToneStyle = when (tone) {
  NasTone.Calm -> NasToneStyle(s.primaryContainer, s.onPrimaryContainer, s.onPrimaryContainer)
  NasTone.Working -> NasToneStyle(s.secondaryContainer, s.onSecondaryContainer, s.onSecondaryContainer)
  NasTone.Waiting -> NasToneStyle(s.surfaceContainerHighest, s.onSurface, s.onSurfaceVariant)
  NasTone.Attention -> NasToneStyle(s.tertiaryContainer, s.onTertiaryContainer, s.onTertiaryContainer)
  NasTone.Alarm -> NasToneStyle(s.errorContainer, s.onErrorContainer, s.error)
}

/**
 * Un avviso in pagina: una scheda del tono con il glifo, il titolo, la frase e, se serve, un'azione a parole ("Riprova"). Sta
 * nel contenuto (non e' vetro: non galleggia). Per TalkBack e' una cosa sola, titolo e frase insieme.
 */
@Composable
internal fun NasNotice(
  tone: NasTone,
  glyph: ImageVector,
  title: String,
  detail: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  val style = nasToneStyle(tone, MaterialTheme.colorScheme)
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(ContinuousCornerShape(FluidRadius.Card))
      .background(style.container)
      .padding(NasDimens.BlockGap),
    horizontalArrangement = Arrangement.spacedBy(NasDimens.BlockGap),
  ) {
    Icon(imageVector = glyph, contentDescription = null, tint = style.glyph, modifier = Modifier.padding(top = NasDimens.LineGap))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NasDimens.LineGap)) {
      Column(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(NasDimens.LineGap),
      ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = style.text)
        Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = style.text)
      }
      if (actionLabel != null && onAction != null) {
        Text(
          text = actionLabel,
          modifier = Modifier
            .defaultMinSize(minHeight = GalleryDimens.MinTouchTarget)
            .fluidPressable(onClick = onAction, role = Role.Button, focusShape = FluidCapsuleShape)
            .padding(top = NasDimens.LineGap),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          textDecoration = TextDecoration.Underline,
          color = style.text,
        )
      }
    }
  }
}

/**
 * Uno stato vuoto o d'attesa nella forma comune (fase I, CU-23: [GalleryFigureState]): la figura, il titolo, il perche' dietro la «i» (TalkBack lo
 * legge dal tasto, senza tocco) e, se serve, l'azione. Niente paragrafi sotto il titolo.
 */
@Composable
internal fun NasEmptyState(
  icon: ImageVector,
  title: String,
  detail: String,
  modifier: Modifier = Modifier,
  tone: GalleryIllustrationTone = GalleryIllustrationTone.Brand,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  var open by rememberSaveable(title) { mutableStateOf(false) }
  GalleryFigureState(icon = icon, title = title, modifier = modifier.fillMaxWidth().padding(NasDimens.StatePadding), tone = tone, fill = false) {
    GalleryInfoButton(label = detail, open = open, onToggle = { open = !open })
    GalleryInfoDetail(
      text = detail,
      visible = open,
      modifier = Modifier.widthIn(max = NasDimens.StateTextMaxWidth),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (actionLabel != null && onAction != null) {
      FluidButton(text = actionLabel, onClick = onAction, size = FluidButtonSize.Large, style = FluidButtonStyle.Filled)
    }
  }
}

/** L'attesa di una lettura dal NAS: il cerchio che gira e la frase. */
@Composable
internal fun NasLoadingBlock(text: String, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(NasDimens.StatePadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(NasDimens.StateGap),
  ) {
    FluidLoading()
    Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/**
 * Il cerchio di selezione di una riga: un anello vuoto, o il cerchio dell'accento con la spunta. Decorativo: lo stato lo dice la
 * riga a TalkBack ("selezionato"), non il disegno.
 */
@Composable
internal fun NasSelectDot(selected: Boolean, modifier: Modifier = Modifier) {
  val s = MaterialTheme.colorScheme
  Box(
    modifier = modifier
      .size(NasDimens.SelectDot)
      .clip(FluidCapsuleShape)
      .then(
        if (selected) {
          Modifier.background(s.primary)
        } else {
          Modifier.border(width = NasDimens.SelectRing, color = s.onSurfaceVariant, shape = FluidCapsuleShape)
        },
      )
      .clearAndSetSemantics { },
    contentAlignment = Alignment.Center,
  ) {
    if (selected) {
      Icon(imageVector = Icons.Rounded.Check, contentDescription = null, tint = s.onPrimary, modifier = Modifier.size(NasDimens.SelectGlyph))
    }
  }
}

/** Un'intestazione di gruppo quieta, nella lingua delle altre pagine (la didascalia sopra un gruppo). */
@Composable
internal fun NasSectionTitle(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = NasDimens.BlockGap)
      .semantics { heading() },
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * Una lista lunga come una pila di gruppi: ogni gruppo e' un pannello con al piu' [NasDimens.RowsPerGroup] righe (un gruppo e' un
 * nodo, e una cartella puo' avere migliaia di voci), tagliati con i segmenti di Fluid e sovrapposti di [FluidGroupOpenEdge] perche'
 * la giuntura non si veda. [row] disegna una riga; i separatori li mette questa funzione.
 */
internal fun <T> LazyListScope.groupedRows(items: List<T>, keyPrefix: String, row: @Composable (T) -> Unit) {
  val chunks = items.chunked(NasDimens.RowsPerGroup)
  chunks.forEachIndexed { index, chunk ->
    item(key = "$keyPrefix-$index") {
      FluidListGroup(
        segment = when {
          chunks.size == 1 -> FluidGroupSegment.Whole
          index == 0 -> FluidGroupSegment.First
          index == chunks.lastIndex -> FluidGroupSegment.Last
          else -> FluidGroupSegment.Middle
        },
        modifier = if (index > 0) Modifier.pullUp(FluidGroupOpenEdge) else Modifier,
      ) {
        chunk.forEachIndexed { at, entry ->
          if (at > 0 || index > 0) FluidListDivider()
          row(entry)
        }
      }
    }
  }
}

/** Fa rientrare l'elemento sopra il precedente di [by]: i gruppi tagliati di una lista lunga si toccano senza giuntura. */
private fun Modifier.pullUp(by: Dp): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints)
  val shift = by.roundToPx().coerceAtMost(placeable.height)
  layout(placeable.width, placeable.height - shift) { placeable.place(0, -shift) }
}

/**
 * Una frase in cima alla pagina dopo un gesto ("Operazione avviata", il motivo per cui un piano non parte): una riga del tono con
 * il glifo, la frase, il "Vedi" se porta alle operazioni e la "x" per chiuderla. Tutto in un bersaglio di almeno 48 dp.
 */
@Composable
internal fun NasNoticeLine(
  notice: UiNotice,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenOperations: (() -> Unit)? = null,
) {
  val style = nasToneStyle(notice.tone, MaterialTheme.colorScheme)
  val dismissText = stringResource(R.string.nas_notice_dismiss)
  val glyph = if (notice.tone == NasTone.Alarm || notice.tone == NasTone.Attention) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(ContinuousCornerShape(FluidRadius.Card))
      .background(style.container)
      .padding(start = NasDimens.BlockGap),
    horizontalArrangement = Arrangement.spacedBy(NasDimens.BlockGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(imageVector = glyph, contentDescription = null, tint = style.glyph)
    Text(
      text = notice.counted?.let { pluralStringResource(it.plural, it.count, GalleryNumbers.format(it.count)) } ?: stringResource(notice.text),
      modifier = Modifier.weight(1f).padding(vertical = NasDimens.BlockGap),
      style = MaterialTheme.typography.bodyMedium,
      color = style.text,
    )
    if (notice.opensOperations && onOpenOperations != null) {
      Text(
        text = stringResource(R.string.nas_open_operations),
        modifier = Modifier
          .defaultMinSize(minWidth = GalleryDimens.MinTouchTarget, minHeight = GalleryDimens.MinTouchTarget)
          .fluidPressable(onClick = onOpenOperations, role = Role.Button, focusShape = FluidCapsuleShape)
          .padding(horizontal = NasDimens.BlockGap)
          .wrapContentHeight(Alignment.CenterVertically),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        textDecoration = TextDecoration.Underline,
        color = style.text,
      )
    }
    Box(
      modifier = Modifier
        .size(GalleryDimens.MinTouchTarget)
        .semantics { contentDescription = dismissText }
        .fluidPressable(onClick = onDismiss, role = Role.Button, focusShape = FluidCapsuleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(imageVector = Icons.Rounded.Close, contentDescription = null, tint = style.glyph)
    }
  }
}

/**
 * Lo spazio a destra di una riga, **sempre lo stesso** dentro e fuori dalla selezione: il cerchio di selezione quando si sceglie,
 * la freccia "si apre" quando si sfoglia (se la riga e' una cartella), niente altrimenti. Se la larghezza cambiasse, il nome di una
 * voce potrebbe andare a capo entrando in selezione e la lista si sposterebbe (P6 di `docs/grafica-progetto.md`).
 */
@Composable
internal fun NasTrailingSlot(selecting: Boolean, selected: Boolean, opens: Boolean, modifier: Modifier = Modifier) {
  Box(modifier = modifier.size(NasDimens.SelectDot), contentAlignment = Alignment.Center) {
    when {
      selecting -> NasSelectDot(selected = selected)
      opens -> Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(NasDimens.ChevronGlyph),
      )
    }
  }
}

/**
 * La barra di avanzamento di una scheda: quella di Fluid, ma di un colore che si distingue dalla sua pista (3:1). Il riempimento di
 * `FluidProgressBar` e' `primary` sopra una pista `onSurface` al 10%: con alcuni accenti chiari (misurato con "ember" in chiaro: 1,7:1)
 * il riempimento quasi non si vede. Qui si porta `primary` verso `onSurface` del minimo che serve; i numeri accanto dicono comunque
 * tutto (la barra non e' mai l'unico segnale).
 */
@Composable
internal fun NasProgressBar(progress: Float, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  val color = remember(scheme) { nasBarColor(scheme) }
  FluidProgressBar(progress = { progress }, modifier = modifier, color = color, height = NasDimens.BarHeight)
}

/** La pista di `FluidProgressBar` sopra una scheda (`FluidListGroup`): `onSurface` al 10% (la costante e' dell'engine). */
internal fun nasBarTrack(scheme: ColorScheme): Color = scheme.onSurface.copy(alpha = BAR_TRACK_ALPHA).compositeOver(scheme.surfaceContainerLow)

/** Il colore del riempimento: `primary` se gia' si distingue dalla pista, altrimenti il piu' vicino a `primary` che ci riesce. */
internal fun nasBarColor(scheme: ColorScheme): Color {
  val track = nasBarTrack(scheme)
  if (GalleryLegibility.contrast(scheme.primary, track) >= BAR_CONTRAST) return scheme.primary
  var low = 0f
  var high = 1f
  repeat(BAR_SEARCH_STEPS) {
    val mid = (low + high) / 2f
    if (GalleryLegibility.contrast(lerp(scheme.primary, scheme.onSurface, mid), track) >= BAR_CONTRAST) high = mid else low = mid
  }
  return lerp(scheme.primary, scheme.onSurface, high)
}

private const val BAR_TRACK_ALPHA = 0.10f
private const val BAR_CONTRAST = 3f
private const val BAR_SEARCH_STEPS = 14
