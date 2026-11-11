package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.antigravity.fluidengine.ui.theme.FluidToneTileSize
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

// LE COPERTINE DELLE RACCOLTE (H5, fase H, 11/11; giro di correzione 1, CS-1). L'utente voleva voci "piu' rappresentative e meno standard":
//
//  - una voce con una foto che la rappresenta mostra **quella foto** ([CollectionEntry.cover]: l'ultimo ricordo, un viaggio);
//  - una voce di persone mostra **i volti** ([CollectionEntry.faces]: la persona, o fino a tre persone per "Tutte le persone"), ritagliati da
//    feature-people con la sua porta ([CollectionFaceCrops]), che non ritaglia mai da un media nascosto;
//  - un luogo (e la Mappa) mostra **la foto piu' recente scattata li'** ([CollectionEntry.coverPlace]), cercata nella Libreria visibile;
//  - le voci senza una foto possibile (Nascosti, Eliminati di recente, le utilita', i comandi come "Attiva riconoscimento dei volti") hanno una
//    piastrella **con il colore della sua famiglia** ([CollectionArtFamily]) e un'icona scelta: mai il glifo grigio su grigio.
//
// Tutto ha la stessa misura ([CollectionsDimens.RowArt]), cosi' i testi di un gruppo partono dallo stesso punto.
//
// **Costo.** Le miniature vengono dalla **stessa cache** della griglia, con la stessa chiave (versione e taglia M). I ritagli dei volti dalla cache in
// memoria di feature-people. Le foto dei luoghi con **un solo passaggio** sull'indice visibile, fuori dal thread principale. Tutto questo si fa **una
// volta** per elenco e per indice ([CollectionCovers.refresh]), mai durante lo scorrimento: le righe leggono soltanto.
//
// **Mai un nascosto.** I riferimenti e i luoghi si cercano nella Libreria visibile ([CollectionItemsSource] con `LibraryFilter.ALL`), che non
// contiene mai i nascosti (E-H1); i volti passano dal cancello di feature-people (`FaceCropGate`). Quando l'indice cambia (una foto nascosta) si
// rifanno: una copertina diventata nascosta sparisce e resta il disegno della famiglia.

/**
 * I ritagli dei volti, dalla porta di feature-people (`PeopleCrops`, con il cancello dei media mostrabili e la cache in memoria). **Opzionale**: senza,
 * le righe delle persone hanno la piastrella della famiglia. `:app` la lega in `FacesModule`.
 */
fun interface CollectionFaceCrops {
  suspend fun crop(face: FaceLocator, sizePx: Int): android.graphics.Bitmap?
}

/**
 * Da dove arrivano le copertine: il caricatore delle miniature, la Libreria visibile e i ritagli dei volti. `null` (il default della composizione) =
 * niente foto, solo le piastrelle delle famiglie: e' cosi' nei test e nelle anteprime che non le passano.
 */
@Stable
class CollectionCovers(
  val loader: ThumbnailLoader,
  private val library: ViewerItems,
  private val faceCrops: CollectionFaceCrops? = null,
  private val compute: CoroutineDispatcher = Dispatchers.Default,
) {

  /** La Libreria visibile: cambia quando cambia l'indice (una foto nascosta, una nuova). */
  val view get() = library.view

  /** Le foto dei luoghi (id GeoNames -> la piu' recente scattata li'), dall'ultimo [refresh]. */
  private val placeCovers = mutableStateMapOf<Int, MediaId>()

  /** I volti ritagliati, dall'ultimo [refresh]: le righe li leggono e basta. */
  private val faceImages = mutableStateMapOf<String, ImageBitmap>()

  /** La foto di un luogo, o null (nessuna foto visibile con quel luogo, o non ancora cercata). */
  fun placeCover(placeId: Int): MediaId? = if (placeId > 0) placeCovers[placeId] else null

  /** Il ritaglio di un volto a [sizePx], o null (non ancora pronto, o rifiutato dal cancello). */
  fun faceImage(face: FaceLocator, sizePx: Int): ImageBitmap? = faceImages[faceKey(face, sizePx)]

  /** La richiesta della miniatura di [media] in [view], o null se il media non e' nella Libreria visibile (un nascosto, un eliminato). */
  fun request(view: TimelineView, media: MediaId): ThumbRequest? {
    val viewIndex = view.viewIndexOf(media)
    if (viewIndex < 0) return null
    val ref = library.refOrNull(media) ?: return null
    val index = view.snapshotIndex(viewIndex)
    val version = (view.snapshot as? ColumnarTimelineSnapshot)?.versionKeyAt(index) ?: MediaGridState.DefaultVersionKey
    return ThumbRequest(ThumbKey(media, version, ThumbBucket.M), ref)
  }

  /**
   * Prepara le copertine di [entries] per l'indice di adesso e per ogni suo cambiamento: cerca le foto dei luoghi (un passaggio, fuori dal thread
   * principale), chiede le miniature una volta sola ([requestThumbs]: falso sul tablet, dove la finestra e' della griglia accanto) e ritaglia i
   * volti a [facePx]. Gira finche' la scheda e' composta; una nuova chiamata (elenco cambiato) sostituisce la precedente.
   */
  suspend fun refresh(entries: List<CollectionEntry>, requestThumbs: Boolean, facePx: Int) {
    val places = entries.mapNotNullTo(HashSet()) { it.coverPlace.takeIf { id -> id > 0 } }
    val faces = entries.flatMap { it.faces }.distinct()
    view.collectLatest { current ->
      val found = if (places.isEmpty()) emptyMap() else withContext(compute) { newestByPlace(current, places) }
      placeCovers.keys.retainAll(found.keys)
      placeCovers.putAll(found)
      if (requestThumbs) {
        val media = (entries.mapNotNull { it.cover } + found.values).distinct()
        val requests = media.mapNotNull { request(current, it) }
        if (requests.isNotEmpty()) loader.window(requests, emptyList())
      }
      val crops = faceCrops ?: return@collectLatest
      // Si ritagliano di nuovo a ogni indice nuovo: il cancello di feature-people rifiuta un volto di un media nascosto un attimo fa. Chi c'era resta
      // finche' il nuovo ritaglio non risponde, cosi' la riga non lampeggia.
      val wanted = faces.mapTo(HashSet()) { faceKey(it, facePx) }
      faceImages.keys.retainAll(wanted)
      for (face in faces) {
        val key = faceKey(face, facePx)
        val bitmap = withContext(compute) { crops.crop(face, facePx) }
        if (bitmap == null) faceImages.remove(key) else faceImages[key] = bitmap.asImageBitmap()
      }
    }
  }

  private fun faceKey(face: FaceLocator, sizePx: Int): String = face.encode() + "@" + sizePx

  companion object {
    /**
     * Per ogni luogo di [places], la foto **piu' recente** della vista (la vista va dalla piu' recente): un solo passaggio, che si ferma quando li ha
     * trovati tutti. Senza nascosti, perche' la vista non li contiene.
     */
    fun newestByPlace(view: TimelineView, places: Set<Int>): Map<Int, MediaId> {
      val found = HashMap<Int, MediaId>()
      val snapshot = view.snapshot
      for (v in 0 until view.size) {
        val index = view.snapshotIndex(v)
        val place = snapshot.placeAt(index)
        if (place != 0 && place in places && place !in found) {
          found[place] = snapshot.idAt(index)
          if (found.size == places.size) break
        }
      }
      return found
    }
  }
}

/** Le copertine della scheda Raccolte: le mette `CollectionsTab`; null = solo le piastrelle delle famiglie. */
val LocalCollectionCovers = staticCompositionLocalOf<CollectionCovers?> { null }

/** Il lato della piastrella delle righe delle Raccolte ([CollectionsDimens.RowArt]). */
internal val CollectionRowArtSize: Dp get() = CollectionsDimens.RowArt

/**
 * Prepara le copertine di [entries] (foto, luoghi, volti) **una volta** per elenco, e di nuovo solo se l'elenco o la Libreria cambiano: non a ogni
 * scorrimento. [requestThumbs] falso sul tablet: il pannello accanto puo' essere una griglia, che ha la sua finestra di richieste e non va
 * disturbata; li' la foto c'e' se e' gia' in cache (i volti e i luoghi invece si preparano lo stesso: non toccano la finestra).
 */
@Composable
internal fun CollectionCoverWindow(covers: CollectionCovers, entries: List<CollectionEntry>, requestThumbs: Boolean) {
  val facePx = with(LocalDensity.current) { CollectionRowArtSize.roundToPx() }
  LaunchedEffect(covers, entries, requestThumbs, facePx) {
    if (entries.isNotEmpty()) covers.refresh(entries, requestThumbs, facePx)
  }
}

/**
 * La famiglia di una voce: il colore della sua piastrella quando non c'e' una foto. Una famiglia per sezione, dai colori del tema, cosi' la pagina
 * si legge a colpo d'occhio (le persone, i ricordi, i luoghi, il NAS, le utilita') e i posti protetti hanno un aspetto loro, scuro su chiaro.
 */
enum class CollectionArtFamily {
  People, Memories, Places, Trips, Nas, Utility, Protected, Other;

  companion object {
    /** La famiglia della sezione con il codice [code] ([CollectionSections]). */
    fun of(code: String?): CollectionArtFamily = when (code) {
      CollectionSections.PEOPLE -> People
      CollectionSections.MEMORIES -> Memories
      CollectionSections.PLACES -> Places
      CollectionSections.TRIPS -> Trips
      CollectionSections.NAS -> Nas
      CollectionSections.UTILITY -> Utility
      CollectionSections.HIDDEN, CollectionSections.SAFE -> Protected
      else -> Other
    }
  }
}

/** I colori di una famiglia: il fondo (in sfumatura) e l'icona. */
private class ArtColors(val from: Color, val to: Color, val ink: Color)

@Composable
private fun artColors(family: CollectionArtFamily, tone: FluidTone): ArtColors {
  val scheme = MaterialTheme.colorScheme
  fun pair(container: Color, strong: Color, ink: Color) = ArtColors(container, lerp(container, strong, ART_DEPTH), ink)
  return when (family) {
    CollectionArtFamily.People, CollectionArtFamily.Trips -> pair(scheme.primaryContainer, scheme.primary, scheme.onPrimaryContainer)
    CollectionArtFamily.Memories, CollectionArtFamily.Utility -> pair(scheme.tertiaryContainer, scheme.tertiary, scheme.onTertiaryContainer)
    CollectionArtFamily.Places, CollectionArtFamily.Nas -> pair(scheme.secondaryContainer, scheme.secondary, scheme.onSecondaryContainer)
    CollectionArtFamily.Protected -> ArtColors(scheme.inverseSurface, lerp(scheme.inverseSurface, scheme.primary, ART_DEPTH), scheme.inverseOnSurface)
    CollectionArtFamily.Other -> when (tone) {
      FluidTone.Danger -> pair(scheme.errorContainer, scheme.error, scheme.onErrorContainer)
      FluidTone.Neutral -> pair(scheme.surfaceContainerHighest, scheme.secondary, scheme.onSurface)
      else -> pair(scheme.primaryContainer, scheme.primary, scheme.onPrimaryContainer)
    }
  }
}

/** Quanto del colore pieno entra nell'angolo in basso della sfumatura: abbastanza per dare profondita', poco per non far gridare la piastrella. */
private const val ART_DEPTH = 0.42f

/** La forma della piastrella (il raggio segue il lato, come in `FluidToneTile`). */
private fun artShape(side: Dp) = ContinuousCornerShape(FluidRadius.Small * (side / FluidToneTileSize))

/** La forma di un volto: un tondo continuo. */
private fun faceShape(side: Dp) = ContinuousCornerShape(side / 2)

/**
 * La piastrella di una riga delle Raccolte: la foto ([cover], o quella del luogo [coverPlace]), i volti ([faces]: uno pieno, due o tre raccolti), o
 * l'icona sul colore della famiglia [family]. Senza copertine ([LocalCollectionCovers] null), o finche' non sono pronte, la piastrella della famiglia.
 */
@Composable
internal fun CollectionRowArt(
  icon: ImageVector,
  tone: FluidTone,
  family: CollectionArtFamily,
  cover: MediaId? = null,
  faces: List<FaceLocator> = emptyList(),
  coverPlace: Int = 0,
) {
  val covers = LocalCollectionCovers.current
  val side = CollectionRowArtSize
  val px = with(LocalDensity.current) { side.roundToPx() }
  val media = if (covers == null) null else cover ?: covers.placeCover(coverPlace)
  val faceImages = if (covers == null || media != null) emptyList() else faces.take(MAX_FACES).mapNotNull { covers.faceImage(it, px) }
  Box(modifier = Modifier.size(side)) {
    val colors = artColors(family, tone)
    val shape = artShape(side)
    // Un volto solo e' un tondo pieno: senza la piastrella sotto, che ne lascerebbe vedere gli angoli.
    if (faceImages.size == 1) {
      Image(bitmap = faceImages[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().clip(faceShape(side)))
      return@Box
    }
    Box(
      modifier = Modifier.matchParentSize().clip(shape).background(Brush.linearGradient(listOf(colors.from, colors.to))),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = colors.ink,
        modifier = Modifier.align(androidx.compose.ui.Alignment.Center).size(CollectionsDimens.RowArtGlyph),
      )
    }
    if (covers == null) return@Box
    if (media != null) {
      CoverImage(covers, media, Modifier.matchParentSize().clip(shape))
    } else if (faceImages.size > 1) {
      Faces(faceImages, colors.from)
    }
  }
}

@Composable
private fun CoverImage(covers: CollectionCovers, media: MediaId, modifier: Modifier) {
  val view by covers.view.collectAsState()
  val request = remember(view, media) { covers.request(view, media) } ?: return
  // La foto compare sopra la piastrella quando la miniatura c'e': nessuna animazione (la regola del modulo: il movimento e' dell'engine).
  val image = covers.loader.image(request.key, request.ref).value ?: return
  Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

/**
 * I volti: uno solo riempie il tondo; due affiancati; tre a triangolo (due sopra, uno sotto in mezzo). Ognuno ha un filo del colore della piastrella
 * che lo stacca dal vicino. I volti non pronti non si disegnano: resta la piastrella.
 */
@Composable
private fun Faces(images: List<ImageBitmap>, ring: Color) {
  val side = CollectionRowArtSize
  val disc = CollectionsDimens.FaceDisc
  val right = side - disc
  if (images.size == 2) {
    val middle = (side - disc) / 2
    FaceDisc(images[0], disc, ring, Modifier.offset(y = middle))
    FaceDisc(images[1], disc, ring, Modifier.offset(x = right, y = middle))
    return
  }
  FaceDisc(images[0], disc, ring, Modifier)
  FaceDisc(images[1], disc, ring, Modifier.offset(x = right))
  FaceDisc(images[2], disc, ring, Modifier.offset(x = right / 2, y = right))
}

@Composable
private fun FaceDisc(image: ImageBitmap, side: Dp, ring: Color, modifier: Modifier) {
  Image(
    bitmap = image,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = modifier.size(side).clip(faceShape(side)).border(CollectionsDimens.FaceRing, ring, faceShape(side)),
  )
}

/** Al piu' tre volti in una piastrella: oltre non si riconosce piu' nessuno a 44 dp. */
private const val MAX_FACES = 3
