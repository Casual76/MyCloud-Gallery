package dev.pampa.mycloudgallery.feature.library.collections

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryIconColors
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

// LA MAPPA IN ANTEPRIMA DEI LUOGHI (fase I, I1, 11/11). "Come Samsung": in cima ai Luoghi una mappa vera a tutta larghezza, con le foto dei luoghi piu'
// fotografati appuntate sopra; un tocco apre la Mappa. La carta arriva da feature-map attraverso la porta [CollectionsMapPreview] (la lega `:app`, come la
// mini-mappa del viewer): MapLibre resta dentro `feature/map/`, lo stile e' quello **solo offline** (nessuna richiesta di rete, mai), e la carta e'
// un'**immagine ferma**: mentre la pagina scorre costa quanto una foto. Si chiede solo quando la scheda e' sullo schermo e la pagina e' ferma, e si
// ricorda in memoria. Le foto sopra la carta passano dalla stessa strada delle schede (la Libreria visibile: mai un nascosto). Senza la porta, senza
// carta o senza luoghi con un centro resta il **mosaico** delle foto dei luoghi (lo stesso disegno della piastrella di prima, grande).

/** Un luogo sulla carta: l'id GeoNames e dove cade, in frazioni della larghezza e dell'altezza (0..1). Mai una coordinata. */
@Immutable
data class CollectionMapPin(val placeId: Int, val x: Float, val y: Float) {
  override fun toString(): String = "CollectionMapPin"
}

/** La carta pronta: l'immagine (solo la carta di base), i luoghi sopra e il credito della carta. */
class CollectionMapImage(val bitmap: Bitmap, val pins: List<CollectionMapPin>, val attribution: String) {
  override fun toString(): String = "CollectionMapImage(${pins.size})"
}

/**
 * La porta della mappa in anteprima (I1): `:app` la lega sopra feature-map (`PlacesPreviewService`). [preview] emette la carta per i luoghi [places] (i piu'
 * fotografati prima) a [widthDp] x [heightDp] dp, e di nuovo quando l'indice dei punti cambia l'inquadratura; null = nessuna carta (resta il mosaico).
 * Non va mai in rete.
 */
fun interface CollectionsMapPreview {
  fun preview(places: List<Int>, widthDp: Int, heightDp: Int, density: Float, dark: Boolean): Flow<CollectionMapImage?>
}

/** La mappa in anteprima della scheda di adesso; null = il mosaico (le prove, e `:app` senza la mappa). */
val LocalCollectionsMapPreview = staticCompositionLocalOf<CollectionsMapPreview?> { null }

/** Se la colonna delle Raccolte sta scorrendo: la carta si chiede solo a pagina ferma (la prima volta MapLibre si prepara sul thread principale). */
internal val LocalCollectionsScrolling = staticCompositionLocalOf<() -> Boolean> { { false } }

/** Quante foto si appuntano al piu' sulla carta: le altre ci sono gia' nel carosello sotto. */
private const val MAX_PINS = 5

/** La forma della mappa (quella delle schede). */
private val MapShape = ContinuousCornerShape(FluidRadius.Card)

/** Sotto questa luminanza il fondo della pagina e' quello del tema scuro. */
private const val DARK_SURFACE = 0.5f

/** La carta e' larga il doppio di quanto e' alta. */
private const val MAP_ASPECT = 2f

/** L'opacita' del fondo della capsula "Mappa" e del credito sopra la carta: si leggono senza coprirla. */
private const val CHIP_ALPHA = 0.88f

/**
 * La mappa in cima ai Luoghi: la carta con le foto dei luoghi [places] appuntate, o il mosaico delle loro foto; in basso a sinistra la capsula con il segno
 * e il nome della voce ("Mappa"). Un tocco la apre ([onSelect] con [entry]).
 */
@Composable
internal fun CollectionMapCard(entry: CollectionEntry, places: List<CollectionEntry>, family: CollectionArtFamily, selected: Boolean, onSelect: (CollectionEntry) -> Unit) {
  val covers = LocalCollectionCovers.current
  val port = LocalCollectionsMapPreview.current
  val scrolling = LocalCollectionsScrolling.current
  val viewport = LocalCoverViewport.current
  val scheme = MaterialTheme.colorScheme
  val dark = scheme.surface.luminance() < DARK_SURFACE
  val density = LocalDensity.current
  val colors = rememberGalleryIconColors(if (family == CollectionArtFamily.Places) GalleryIconFamily.Places else iconFamilyOf(family, entry.tone, entry.icon))
  val bucket = collectionCardBucket()
  // I luoghi della carta, i piu' fotografati prima: le schede sotto (che hanno il luogo) e il mosaico della voce.
  val placeEntries = remember(places) { places.filter { it.coverPlace > 0 } }
  val placeIds = remember(placeEntries, entry) { (placeEntries.map { it.coverPlace } + entry.coverPlaces).filter { it > 0 }.distinct() }
  var size by remember { mutableStateOf(IntSize.Zero) }
  var image by remember { mutableStateOf<MapImageUi?>(null) }
  // Giro 1 (CU-24): le due capsule in basso (il nome della voce e il credito della carta), misurate: le foto appuntate non ci vanno sotto.
  var chip by remember { mutableStateOf(IntSize.Zero) }
  var credit by remember { mutableStateOf(IntSize.Zero) }
  val widthDp = with(density) { size.width.toDp().value.roundToInt() }
  val heightDp = with(density) { size.height.toDp().value.roundToInt() }
  if (port != null && widthDp > 0 && heightDp > 0) {
    LaunchedEffect(port, placeIds, widthDp, heightDp, dark) {
      // Mai mentre la pagina scorre: la prima carta prepara MapLibre sul thread principale.
      snapshotFlow { scrolling() }.first { !it }
      port.preview(placeIds, widthDp, heightDp, density.density, dark).collect { ready -> image = ready?.let(::MapImageUi) }
    }
  }
  if (viewport != null) {
    val key = "map:" + entry.code
    LaunchedEffect(viewport, placeEntries) { viewport.report(key, placeEntries.take(MAX_PINS.coerceAtLeast(MOSAIC_CELLS)), emptyList()) }
    DisposableEffect(viewport, key) { onDispose { viewport.drop(key) } }
  }
  val spoken = entry.spoken()
  Box(
    modifier = Modifier
      .mapCardSize()
      // Solo una scheda piazzata davvero sullo schermo (non quella che la colonna prepara in anticipo) chiede la carta.
      .onGloballyPositioned { if (it.size != size) size = it.size }
      .fluidPressable(onClick = { onSelect(entry) }, role = Role.Button)
      .semantics(mergeDescendants = true) {
        contentDescription = spoken
        if (selected) this.selected = true
      }
      .then(if (selected) Modifier.background(scheme.primary, MapShape).padding(CollectionsDimens.SelectedRing) else Modifier)
      .clip(MapShape)
      .background(Brush.linearGradient(listOf(colors.top, colors.bottom))),
  ) {
    val ready = image
    if (ready != null && covers != null) {
      Image(bitmap = ready.bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
      val pad = with(density) { CollectionsDimens.MapChipPadding.roundToPx() }
      val keepOut = listOf(
        IntRect(pad, size.height - pad - chip.height, pad + chip.width, size.height - pad),
        IntRect(size.width - pad - credit.width, size.height - pad - credit.height, size.width - pad, size.height - pad),
      )
      MapPins(ready.pins, placeEntries, covers, size, bucket, keepOut)
      Text(
        text = ready.attribution,
        style = MaterialTheme.typography.labelSmall,
        color = scheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(CollectionsDimens.MapChipPadding)
          .onSizeChanged { credit = it }
          .clip(FluidCapsuleShape)
          .background(scheme.surface.copy(alpha = CHIP_ALPHA))
          .padding(horizontal = CollectionsDimens.TileBadgeHorizontal, vertical = CollectionsDimens.TileBadgeVertical),
      )
    } else {
      val media = if (covers == null) emptyList() else placeIds.mapNotNull { covers.placeCover(it) }
      val width = with(density) { size.width.toDp() }
      if (size.width > 0) {
        MapMosaic(covers, media, Icons.Rounded.Map, colors, Modifier.matchParentSize(), width, CollectionsDimens.MapBadgeLarge, MAP_ASPECT, bucket)
      }
    }
    MapChip(entry.title, colors.top, Modifier.align(Alignment.BottomStart).padding(CollectionsDimens.MapChipPadding).onSizeChanged { chip = it })
  }
}

/** Larga quanto la colonna, alta la meta' (al piu' [CollectionsDimens.MapCardMaxHeight]: sul tablet largo la carta non riempie lo schermo). */
private fun Modifier.mapCardSize(): Modifier = layout { measurable, constraints ->
  val width = if (constraints.hasBoundedWidth) constraints.maxWidth else CollectionsDimens.MapCardMaxHeight.roundToPx() * MAP_ASPECT.toInt()
  val height = minOf((width / MAP_ASPECT).roundToInt(), CollectionsDimens.MapCardMaxHeight.roundToPx())
  val placeable = measurable.measure(Constraints.fixed(width, height))
  layout(width, height) { placeable.place(0, 0) }
}

/** La carta come la disegna Compose: l'immagine convertita una volta sola. */
private class MapImageUi(source: CollectionMapImage) {
  val bitmap: ImageBitmap = source.bitmap.asImageBitmap()
  val pins: List<CollectionMapPin> = source.pins
  val attribution: String = source.attribution
}

/**
 * Le foto appuntate: per ogni luogo (i piu' fotografati prima) la sua foto in un tondo con il filo chiaro, centrata sul punto; un luogo troppo vicino a uno
 * gia' appuntato si salta (le foto non si coprono), e cosi' uno che cadrebbe sotto una delle capsule [keepOut] (giro 1, CU-24: resta nel carosello sotto).
 * Solo i luoghi con una foto nella Libreria visibile di adesso: un luogo diventato tutto nascosto non appunta niente, anche prima che la carta si rifaccia.
 */
@Composable
private fun MapPins(
  pins: List<CollectionMapPin>,
  places: List<CollectionEntry>,
  covers: CollectionCovers,
  size: IntSize,
  bucket: dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket,
  keepOut: List<IntRect>,
) {
  val density = LocalDensity.current
  val pinPx = with(density) { CollectionsDimens.MapPin.roundToPx() }
  val minGap = with(density) { CollectionsDimens.MapPinMinGap.toPx() }
  val order = places.map { it.coverPlace }
  val chosen = ArrayList<Triple<MediaId, Int, Int>>()
  for (id in order) {
    if (chosen.size == MAX_PINS) break
    val pin = pins.firstOrNull { it.placeId == id } ?: continue
    val media = covers.placeCover(id) ?: continue
    val x = (pin.x * size.width).roundToInt()
    val y = (pin.y * size.height).roundToInt()
    if (chosen.any { (_, cx, cy) -> hypot((cx - x).toFloat(), (cy - y).toFloat()) < minGap }) continue
    if (keepOut.any { pinTouches(it, x, y, pinPx / 2) }) continue
    chosen += Triple(media, x, y)
  }
  val surface = MaterialTheme.colorScheme.surface
  // Il piu' fotografato per ultimo: sta sopra gli altri.
  chosen.asReversed().forEach { (media, x, y) ->
    Box(
      modifier = Modifier
        .offset { IntOffset(x - pinPx / 2, y - pinPx / 2) }
        .size(CollectionsDimens.MapPin)
        .clip(FluidCapsuleShape)
        .background(surface)
        .padding(CollectionsDimens.MapPinRing)
        .clip(FluidCapsuleShape),
    ) {
      CoverImage(covers, media, Modifier.matchParentSize(), bucket)
    }
  }
}

/** Se un tondo di raggio [radius] centrato in ([x], [y]) tocca il riquadro [box] (vuoto = nessun riquadro). */
internal fun pinTouches(box: IntRect, x: Int, y: Int, radius: Int): Boolean {
  if (box.width <= 0 || box.height <= 0) return false
  val nearestX = x.coerceIn(box.left, box.right)
  val nearestY = y.coerceIn(box.top, box.bottom)
  return hypot((x - nearestX).toFloat(), (y - nearestY).toFloat()) < radius
}

/** La capsula in basso a sinistra: il segno della mappa nel colore dei Luoghi e il nome della voce. */
@Composable
private fun MapChip(title: String, tint: androidx.compose.ui.graphics.Color, modifier: Modifier) {
  val scheme = MaterialTheme.colorScheme
  Row(
    modifier = modifier
      .heightIn(min = CollectionsDimens.MapChipHeight)
      .clip(FluidCapsuleShape)
      .background(scheme.surface.copy(alpha = CHIP_ALPHA))
      .padding(horizontal = CollectionsDimens.MapChipPadding),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(CollectionsDimens.SectionTitleGap * 2),
  ) {
    Icon(imageVector = Icons.Rounded.Map, contentDescription = null, tint = tint, modifier = Modifier.size(CollectionsDimens.MapChipGlyph))
    Text(text = title, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}
