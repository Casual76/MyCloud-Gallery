package dev.pampa.mycloudgallery.feature.map.map

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.map.R
import dev.pampa.mycloudgallery.feature.map.contract.MapThumbnails
import dev.pampa.mycloudgallery.feature.map.ui.MapDimens
import dev.pampa.mycloudgallery.feature.map.ui.MapText
import dev.pampa.mycloudgallery.feature.map.ui.coverRequest
import dev.pampa.mycloudgallery.feature.map.ui.thumbRequestOf

// LE BOLLE SOPRA LA MAPPA (Fase 9, P4; docs/fase9-progetto.md §2.10 "Bolle", D28, §4.4 "riposizionate senza nuova misura del layout (test di composizione)"; TalkBack:
// "12 foto, Firenze"). Al piu' 60 composable sopra la mappa, ognuno **misurato una volta sola**: la posizione sullo schermo non e' un `offset` ne' un `padding` (che rifarebbero
// la misura a ogni fotogramma) ma la **traslazione del layer grafico**, ricalcolata dentro il layer a ogni fotogramma della mappa (`surface.frame` e `surface.project`, che MapLibre
// risolve con `projection.toScreenLocation`). Un gesto sposta le bolle senza ricomporre e senza toccare il layout. Le miniature si chiedono solo quando cambia l'insieme delle bolle
// (a camera ferma), mai durante il gesto e mai dal NAS (la porta `MapRefs` da' solo sorgenti locali). Solo componenti dell'engine e del design system; superfici piene sotto i
// testi (il conto e il nome non stanno mai direttamente sulla foto o sulla carta).

/** Conta le misure delle bolle (solo per i test di composizione: in produzione non c'e'). */
class LayoutProbe {
  @Volatile
  var measures: Int = 0
}

private val NoImage: State<ImageBitmap?> = mutableStateOf(null)

@Composable
internal fun BubbleLayer(
  bubbles: List<PlacedBubble>,
  surface: MapSurface,
  thumbnails: MapThumbnails,
  snapshot: TimelineSnapshot?,
  onTap: (PlacedBubble) -> Unit,
  modifier: Modifier = Modifier,
  probe: LayoutProbe? = null,
  onZoom: ((PlacedBubble) -> Unit)? = null,
  onZone: ((PlacedBubble) -> Unit)? = null,
) {
  // Le miniature si chiedono quando cambia l'insieme delle bolle (a camera ferma o al cambio di mezzo livello): una finestra sola, senza "in arrivo".
  LaunchedEffect(bubbles, snapshot, thumbnails) {
    val requests = bubbles.mapNotNull { coverRequest(thumbnails, snapshot, it.bubble.cover, ThumbBucket.S) }
    thumbnails.loader.window(requests, emptyList())
  }
  // Fase 10 P8 (D23, E-AC8): le bolle in vista sono anche un **elenco** ("Elenco dei luoghi in vista"), letto in ordine di numero (la piu'
  // grande per prima, a parita' la chiave minore) e non in ordine di posizione sullo schermo; ognuna dice "12 foto, Firenze".
  val order = remember(bubbles) { bubbles.sortedWith(compareByDescending<PlacedBubble> { it.count }.thenBy { it.key.level }.thenBy { it.key.prefix }) }
  val rank = remember(order) { order.withIndex().associate { (i, b) -> b.key to i } }
  val listText = stringResource(R.string.mappa_bubbles_list)
  Box(
    modifier = modifier.fillMaxSize().semantics {
      isTraversalGroup = true
      if (bubbles.isNotEmpty()) {
        contentDescription = listText
        collectionInfo = CollectionInfo(rowCount = bubbles.size, columnCount = 1)
      }
    },
  ) {
    for (placed in bubbles) key(placed.key) { BubbleView(placed, surface, thumbnails, snapshot, onTap, probe, rank[placed.key] ?: 0, onZoom, onZone) }
  }
}

@Composable
private fun BubbleView(
  placed: PlacedBubble,
  surface: MapSurface,
  thumbnails: MapThumbnails,
  snapshot: TimelineSnapshot?,
  onTap: (PlacedBubble) -> Unit,
  probe: LayoutProbe?,
  rank: Int,
  onZoom: ((PlacedBubble) -> Unit)?,
  onZone: ((PlacedBubble) -> Unit)?,
) {
  val resources = LocalContext.current.resources
  val description = MapText.bubble(resources, placed.count, placed.label)
  val zoomText = stringResource(R.string.mappa_bubble_zoom)
  val zoneText = stringResource(R.string.mappa_bubble_zone)
  val bubbleSize = MapDimens.Bubble
  Column(
    modifier = Modifier
      // La posizione: la traslazione del layer, riletta a ogni fotogramma della mappa. Il centro del cerchio sta sul punto; il nome, se c'e', pende sotto.
      .graphicsLayer {
        if (surface.frame < 0L) return@graphicsLayer
        val at = surface.project(placed.at)
        if (at == null) {
          alpha = 0f
        } else {
          alpha = 1f
          translationX = at.x - size.width / 2f
          translationY = at.y - bubbleSize.toPx() / 2f
        }
      }
      .then(if (probe == null) Modifier else Modifier.layout { measurable, constraints -> probe.measures++; val p = measurable.measure(constraints); layout(p.width, p.height) { p.place(0, 0) } })
      .semantics(mergeDescendants = true) {
        contentDescription = description
        role = Role.Button
        // Fase 10 P8: l'ordine di lettura e' quello dell'elenco (per numero), il posto nell'elenco e le due azioni del tocco.
        traversalIndex = rank.toFloat()
        collectionItemInfo = CollectionItemInfo(rowIndex = rank, rowSpan = 1, columnIndex = 0, columnSpan = 1)
        customActions = listOfNotNull(
          onZoom?.let { run -> CustomAccessibilityAction(zoomText) { run(placed); true } },
          onZone?.let { run -> CustomAccessibilityAction(zoneText) { run(placed); true } },
        )
      }
      .fluidPressable(onClick = { onTap(placed) }, role = Role.Button),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    BubbleCircle(placed, thumbnails, snapshot)
    placed.label?.let { label ->
      Text(
        text = label,
        modifier = Modifier
          .padding(top = MapDimens.LabelGap)
          .widthIn(max = MapDimens.LabelMaxWidth)
          .clip(MaterialTheme.shapes.small)
          .background(MaterialTheme.colorScheme.surfaceContainerHigh)
          .padding(horizontal = MapDimens.TightGap, vertical = MapDimens.TightGap / 4),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** La miniatura tonda con l'anello del colore della superficie e, per piu' di una foto, il conto su una capsula piena. */
@Composable
private fun BubbleCircle(placed: PlacedBubble, thumbnails: MapThumbnails, snapshot: TimelineSnapshot?) {
  val scheme = MaterialTheme.colorScheme
  val index = remember(snapshot, placed.bubble.cover) { snapshot?.indexOf(placed.bubble.cover) ?: -1 }
  val request = remember(snapshot, index) { if (snapshot != null && index >= 0) thumbRequestOf(thumbnails, snapshot, index, ThumbBucket.S) else null }
  val image = if (request != null) thumbnails.loader.image(request.key, request.ref) else NoImage
  val placeholder = remember(snapshot, index) { if (snapshot != null && index >= 0) snapshot.thumbHashAt(index)?.let { thumbnails.placeholders.image(it) } else null }
  Box(modifier = Modifier.size(MapDimens.Bubble)) {
    Box(
      modifier = Modifier
        .matchParentSizeCircle()
        .background(scheme.surfaceContainerHigh)
        .border(width = MapDimens.BubbleRing, color = scheme.surface, shape = FluidCapsuleShape),
    ) {
      val shown = image.value ?: placeholder
      if (shown != null) Image(bitmap = shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
    if (placed.count > 1) {
      Text(
        text = GalleryNumbers.format(placed.count),
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .defaultMinSize(minWidth = MapDimens.CountBadgeMin, minHeight = MapDimens.CountBadgeMin)
          .clip(FluidCapsuleShape)
          .background(scheme.primary)
          .padding(horizontal = MapDimens.TightGap / 2),
        style = MaterialTheme.typography.labelMedium,
        color = scheme.onPrimary,
        textAlign = TextAlign.Center,
        maxLines = 1,
      )
    }
  }
}

private fun Modifier.matchParentSizeCircle(): Modifier = this.fillMaxSize().clip(FluidCapsuleShape)
