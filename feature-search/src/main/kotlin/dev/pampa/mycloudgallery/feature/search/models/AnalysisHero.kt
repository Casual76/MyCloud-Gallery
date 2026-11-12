package dev.pampa.mycloudgallery.feature.search.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryStatusMark
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryStatusColors
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRing
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.search.R
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens

// LA TESTATA DELL'ANALISI (grafica XU, 12/11/2026). Sorella di quella del Backup (`BackupHero`), che all'utente piace: la stessa grammatica, nello
// stesso ordine e con le stesse misure — l'anello grande della galleria con il percento grande e «pronte / totale» piccolo dentro, il segno di stato
// appoggiato sull'arco in basso a destra, un'etichetta di due parole, **una** riga, un tasto quando serve. Cambia solo la tinta dell'arco: quella della
// ricerca (indaco) al posto di quella del backup (blu); a lavoro finito il verde del «fatto», come la copertura di Ricerca e modelli.
//
// Non e' estratta da `BackupHero`: quel file e' di feature-backup (ci lavora un altro agente) e i suoi modelli sono del contratto del backup. Qui la
// testata e' ricomposta con gli **stessi** componenti comuni (`GalleryProgressRing`, `GalleryStatusMark`, `FluidButton`) e gli stessi numeri
// (`SearchDimens.AnalysisHero*` = `BackupDimens.Hero*`); se un giorno si vuole un componente solo, e' questo file che si sposta in core-designsystem.
//
// Il costo: l'anello avanza con la sua molla letta nel disegno (nessuna ricomposizione a ogni passo) e scatta con «Rimuovi animazioni»; senza un totale
// l'arco corto gira, ma in un livello suo (`graphicsLayer`): ridisegna l'anello, non la pagina.

/** Che cosa c'e' dentro l'anello: quanto e' pieno, se gira senza misura, i numeri o il glifo, e la frase per TalkBack. */
@Immutable
internal class AnalysisRing(
  val progress: Float?,
  val busy: Boolean,
  val percent: Int?,
  val ready: String?,
  val total: String?,
  val glyph: ImageVector,
  val done: Boolean,
  val description: String?,
)

/** L'anello per lo stato dell'analisi: le foto pronte sul totale (i numeri veri), o il glifo dello stato quando un totale non c'e'. */
@Composable
internal fun analysisRing(status: LibraryAnalysisStatus): AnalysisRing {
  val sign = analysisSign(status)
  val counts = readyOf(status)
  return when {
    counts != null -> {
      val (ready, total) = counts
      val percent = analysisPercent(ready, total)
      AnalysisRing(
        progress = ready.toFloat() / total,
        busy = false,
        percent = percent,
        ready = GalleryNumbers.format(ready),
        total = stringResource(R.string.models_count_of_total, GalleryNumbers.format(total)),
        glyph = sign.glyph,
        done = ready >= total,
        description = stringResource(R.string.analysis_ring_description, percent, GalleryNumbers.format(ready), GalleryNumbers.format(total)),
      )
    }
    status == LibraryAnalysisStatus.AllReady -> AnalysisRing(1f, busy = false, percent = FullPercent, ready = null, total = null, glyph = sign.glyph, done = true, description = null)
    // In corso senza un totale: l'arco corto gira (in un livello suo). Gli altri stati senza numeri: l'anello vuoto e il glifo dello stato.
    status is LibraryAnalysisStatus.Running -> AnalysisRing(null, busy = true, percent = null, ready = null, total = null, glyph = sign.glyph, done = false, description = null)
    // Il glifo al centro e' l'analisi stessa: il perche' (la freccia, il triangolo...) lo dice gia' il mirino della pagina, una volta.
    else -> AnalysisRing(0f, busy = false, percent = null, ready = null, total = null, glyph = AnalysisGlyph, done = false, description = null)
  }
}

/** Un tasto della testata: il primo pieno se e' la cosa da fare ([filled]), tonale se e' solo una possibilita'. */
@Immutable
internal class AnalysisHeroButton(val label: String, val icon: ImageVector, val filled: Boolean, val onClick: () -> Unit)

/**
 * La testata: anello, etichetta, riga, tasti. Sta dentro un contenitore pieno di chi la usa (un gruppo di righe), mai sul vetro.
 *
 * @param onOpen se c'e', etichetta e riga sono un tasto con la freccia (Ricerca e modelli: apre la pagina "Analisi delle foto").
 * @param corner un tasto nell'angolo in alto a destra (la «i» della copertura).
 * @param below cio' che si apre sotto (il perche' dietro la «i»).
 */
@Composable
internal fun AnalysisHero(
  status: LibraryAnalysisStatus,
  modifier: Modifier = Modifier,
  ring: AnalysisRing = analysisRing(status),
  onOpen: (() -> Unit)? = null,
  buttons: List<AnalysisHeroButton> = emptyList(),
  background: Color = MaterialTheme.colorScheme.surfaceContainerLow,
  corner: (@Composable BoxScope.() -> Unit)? = null,
  below: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val sign = analysisSign(status)
  val label = analysisLabel(status) ?: return
  val line = analysisLine(status)
  val sentence = analysisSentence(LocalResources.current, status)
  Box(modifier = modifier.fillMaxWidth().testTag(AnalysisStatusTag)) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(SearchDimens.AnalysisHeroPadding),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(SearchDimens.AnalysisHeroGap),
    ) {
      HeroRing(ring = ring, mark = sign.mark, background = background)
      HeroTexts(label = label, line = line, sentence = sentence, onOpen = onOpen)
      if (buttons.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SearchDimens.AnalysisLineGap)) {
          buttons.forEach { button ->
            FluidButton(
              text = button.label,
              onClick = button.onClick,
              style = if (button.filled) FluidButtonStyle.Filled else FluidButtonStyle.Tinted,
              size = FluidButtonSize.Large,
              fillWidth = true,
              leading = { Icon(imageVector = button.icon, contentDescription = null) },
            )
          }
        }
      }
      below?.invoke(this)
    }
    corner?.invoke(this)
  }
}

/** L'anello con il centro e il segno di stato appoggiato sull'arco (come quello del Backup). */
@Composable
private fun HeroRing(ring: AnalysisRing, mark: GalleryIconStatus?, background: Color) {
  val size = GalleryProgressRingDefaults.Hero
  val hue = if (ring.done) rememberGalleryStatusColors(GalleryIconStatus.Done).top else galleryRingColor(GalleryIconFamily.Search)
  Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
    GalleryProgressRing(
      progress = if (ring.busy) null else ring.progress,
      modifier = Modifier
        .matchParentSize()
        // L'arco che gira senza misura ridisegna solo questo livello.
        .graphicsLayer()
        .then(if (ring.description != null) Modifier.semantics { contentDescription = ring.description } else Modifier),
      color = hue,
      strokeWidth = GalleryProgressRingDefaults.stroke(size),
      animate = true,
    ) {
      // I numeri si vedono ma TalkBack non li rilegge: la frase dell'anello (o della testata) li dice gia'.
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = SearchDimens.AnalysisRingInset).semantics { hideFromAccessibility() },
      ) {
        val percent = ring.percent
        if (percent != null) {
          Text(
            text = stringResource(R.string.analysis_percent, percent),
            style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
          )
          if (ring.ready != null && ring.total != null) {
            // Su una riga se ci sta; con il testo grande «/ 15.000» va a capo dentro l'anello, mai sopra l'arco.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(SearchDimens.AnalysisLineGap, Alignment.CenterHorizontally)) {
              Text(text = ring.ready, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
              Text(text = ring.total, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
          }
        } else {
          Icon(imageVector = ring.glyph, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(SearchDimens.AnalysisHeroGlyph))
        }
      }
    }
    if (mark != null) {
      GalleryStatusMark(
        status = mark,
        size = SearchDimens.AnalysisHeroMark,
        ring = background,
        // L'etichetta sotto l'anello dice gia' lo stato a voce: il segno non lo ripete.
        contentDescription = null,
        modifier = Modifier.align(Alignment.BottomEnd).offset(x = -SearchDimens.AnalysisHeroMarkInset, y = -SearchDimens.AnalysisHeroMarkInset),
      )
    }
  }
}

/** L'etichetta (grande) e la riga (con l'icona del perche' davanti); con [onOpen] sono un tasto con la freccia. TalkBack sente la frase intera. */
@Composable
private fun HeroTexts(label: String, line: AnalysisLine?, sentence: String, onOpen: (() -> Unit)?) {
  val openLabel = stringResource(R.string.analysis_open)
  val ink = MaterialTheme.colorScheme.onSurfaceVariant
  val touch = if (onOpen != null) {
    Modifier.fluidPressable(onClick = onOpen, role = Role.Button, focusShape = ContinuousCornerShape(FluidRadius.Control))
  } else {
    Modifier
  }
  Column(
    modifier = Modifier
      .defaultMinSize(minWidth = GalleryDimens.MinTouchTarget, minHeight = GalleryDimens.MinTouchTarget)
      .then(touch)
      .semantics(mergeDescendants = true) {
        if (onOpen != null) role = Role.Button
        contentDescription = listOf(sentence, if (onOpen != null) openLabel else "").filter { it.isNotEmpty() }.joinToString(". ")
      },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(SearchDimens.AnalysisLineGap),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.semantics { heading() },
    )
    if (line != null || onOpen != null) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SearchDimens.AnalysisLineGap)) {
        if (line?.glyph != null) Icon(imageVector = line.glyph, contentDescription = null, tint = ink, modifier = Modifier.size(SearchDimens.InlineGlyph))
        if (line != null) {
          Text(text = line.text, style = MaterialTheme.typography.bodyMedium, color = ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
        }
        if (onOpen != null) Icon(imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = ink, modifier = Modifier.size(SearchDimens.InlineGlyph))
      }
    }
  }
}

private const val FullPercent = 100

internal const val AnalysisStatusTag: String = "analisi-stato"
