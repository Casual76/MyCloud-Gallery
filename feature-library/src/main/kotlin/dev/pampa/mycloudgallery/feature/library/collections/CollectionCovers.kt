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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
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
// Giro 2 (rischio di CG e CS, R3): mentre il catalogo si legge la prima volta la vista cambia di continuo. Il passaggio dei luoghi e le richieste
// delle miniature hanno una **dose** ([PassDose]): il primo indice subito, poi al piu' uno al secondo, e sempre l'ultimo; un indice gia' passato con
// gli stessi luoghi (un elenco dei fornitori che cambia solo nei conti) non si ripassa.
//
// **Mai un nascosto.** I riferimenti e i luoghi si cercano nella Libreria visibile ([CollectionItemsSource] con `LibraryFilter.ALL`), che non
// contiene mai i nascosti (E-H1); i volti passano dal cancello di feature-people (`FaceCropGate`). Quando l'indice cambia (una foto nascosta) si
// rifanno: una copertina diventata nascosta sparisce e resta il disegno della famiglia. La foto di una copertina si disegna solo se e' nella vista
// di adesso ([request]), anche fra un passaggio e l'altro.
// **I volti** (CG-5 al giro 2, CU-19 al giro 3): ogni ritaglio tenuto qui porta le sue **foto d'origine** (le celle visibili che contengono il volto,
// come le ha viste il cancello). Si disegna solo se almeno una e' nella vista di adesso: una foto nascosta, eliminata o uscita dal filtro toglie il suo
// volto **subito**, gia' al primo disegno con la vista nuova, senza aspettare niente. Gli altri restano disegnati mentre il cancello li riconferma
// (niente lampo a ogni lotto del backup, che cambia i flag delle celle e quindi la vista) e se ne vanno se il cancello li rifiuta. I segnali che non
// passano dalla vista (cancellazione, Persone spenta o rimontata, cache dei ritagli svuotata) arrivano da [CollectionFaceCrops.revocations] e tolgono
// tutto subito; una persona nascosta o unita cambia invece l'elenco delle voci, e la riga non chiede piu' quel volto.

/**
 * I ritagli dei volti, dalla porta di feature-people (`PeopleCrops`, con il cancello dei media mostrabili e la cache in memoria). **Opzionale**: senza,
 * le righe delle persone hanno la piastrella della famiglia. `:app` la lega in `FacesModule`.
 */
fun interface CollectionFaceCrops {
  /** Il ritaglio di [face] con le sue foto d'origine, o null se il cancello rifiuta (nessuna foto d'origine mostrabile adesso). */
  suspend fun crop(face: FaceLocator, sizePx: Int): CollectionFaceCrop?

  /**
   * CU-19: un segno che cambia quando i ritagli gia' dati non valgono piu' per ragioni che la vista del catalogo non dice (cancellazione, Persone spenta
   * o rimontata, cache svuotata). A ogni valore nuovo le Raccolte buttano tutti i volti che tengono e li richiedono. Di serie non cambia mai.
   */
  val revocations: Flow<Any?> get() = NeverRevoked
}

/**
 * CU-19 (fase H, giro 3): un ritaglio e le sue **foto d'origine**, le celle della Libreria visibile che contengono il volto e che il cancello ha
 * permesso. Senza foto d'origine il cancello rifiuta e il ritaglio non si tiene.
 */
class CollectionFaceCrop(val bitmap: android.graphics.Bitmap, val origins: Set<MediaId>)

/** Le revoche di serie: nessuna. */
private val NeverRevoked: Flow<Any?> = flowOf(Unit)

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
  private val dose: PassDose = PassDose(),
) {

  /** La Libreria visibile: cambia quando cambia l'indice (una foto nascosta, una nuova). */
  val view get() = library.view

  /** Le foto dei luoghi (id GeoNames -> la piu' recente scattata li'), dall'ultimo passaggio. */
  private val placeCovers = mutableStateMapOf<Int, MediaId>()

  /** L'ultimo passaggio dei luoghi: la vista e i luoghi cercati. Lo stesso indice con gli stessi luoghi non si ripassa. */
  private var lastPass: Pair<TimelineView, Set<Int>>? = null

  /** Un ritaglio, le sue foto d'origine e il segno delle revoche con cui il cancello l'ha permesso (CG-5, CU-19). */
  private class FaceImage(val image: ImageBitmap, val origins: Set<MediaId>, val token: Any?)

  /** I volti ritagliati: le righe li leggono e basta. */
  private val faceImages = mutableStateMapOf<String, FaceImage>()

  /** L'ultimo segno delle revoche ([CollectionFaceCrops.revocations]): un volto permesso con un segno diverso non si disegna. */
  private var facesToken by mutableStateOf<Any?>(Unseen)

  /** Quante passate dei luoghi sono state fatte (per le prove della dose: mai una per ogni vista del catalogo che cresce). */
  @Volatile
  var placePasses: Int = 0
    private set

  /** La foto di un luogo, o null (nessuna foto visibile con quel luogo, o non ancora cercata). */
  fun placeCover(placeId: Int): MediaId? = if (placeId > 0) placeCovers[placeId] else null

  /**
   * Il ritaglio di un volto a [sizePx], o null: non ancora pronto, rifiutato dal cancello, revocato, o con **nessuna foto d'origine nella vista di
   * adesso** (CG-5: una foto nascosta un attimo fa non lascia il suo volto sullo schermo, nemmeno prima che la raccolta delle viste se ne accorga). La
   * domanda costa una ricerca per id per foto d'origine (di solito una), sulla vista gia' pronta: niente di proporzionale al catalogo.
   */
  fun faceImage(face: FaceLocator, sizePx: Int): ImageBitmap? {
    val held = faceImages[faceKey(face, sizePx)] ?: return null
    if (held.token != facesToken) return null
    return if (inView(view.value, held.origins)) held.image else null
  }

  private fun inView(view: TimelineView, origins: Set<MediaId>): Boolean = origins.any { view.viewIndexOf(it) >= 0 }

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
  suspend fun refresh(entries: List<CollectionEntry>, requestThumbs: Boolean, facePx: Int): Unit = coroutineScope {
    val places = entries.flatMapTo(HashSet()) { e -> (e.coverPlaces + e.coverPlace).filter { it > 0 } }
    val faces = entries.flatMap { it.faces }.distinct()
    val wanted = faces.mapTo(HashSet()) { faceKey(it, facePx) }
    faceImages.keys.retainAll(wanted)
    val crops = faceCrops
    if (crops != null && faces.isNotEmpty()) {
      launch {
        combine(view, crops.revocations) { current, token -> current to token }
          .collectLatest { (current, token) -> authorizeFaces(current, token, faces, facePx, crops) }
      }
    }
    // La dose (giro 2): il passaggio dei luoghi e le richieste delle miniature al piu' una volta al secondo mentre il catalogo cresce, e sempre
    // sull'ultimo indice. In fila, senza interrompere un passaggio a meta' (un passaggio interrotto e' lavoro buttato).
    dose.apply(view).collect { current -> placesPass(current, entries, places, requestThumbs) }
  }

  /** Il passaggio dei luoghi e le richieste delle miniature per [current]; un indice gia' passato con gli stessi luoghi non si ripassa. */
  private suspend fun placesPass(current: TimelineView, entries: List<CollectionEntry>, places: Set<Int>, requestThumbs: Boolean) {
    val previous = lastPass
    val found = when {
      places.isEmpty() -> emptyMap()
      previous != null && previous.first === current && previous.second == places -> HashMap(placeCovers)
      else -> withContext(compute) { newestByPlace(current, places) }.also { placePasses++ }
    }
    lastPass = current to places
    placeCovers.keys.retainAll(found.keys)
    placeCovers.putAll(found)
    if (requestThumbs) {
      val media = (entries.mapNotNull { it.cover } + found.values).distinct()
      val requests = media.mapNotNull { request(current, it) }
      if (requests.isNotEmpty()) loader.window(requests, emptyList())
    }
  }

  /**
   * CG-5 e CU-19: i volti per la vista [current] e il segno delle revoche [token]. **Prima di ogni attesa**: con un segno nuovo (cancellazione, Persone
   * spenta) si butta tutto; con una vista nuova si tolgono i soli volti la cui foto d'origine non e' piu' nella vista (una ricerca per id ciascuno). Gli
   * altri restano disegnati: niente lampo. Poi il cancello li riconferma tutti insieme, fuori dal thread principale; i permessi restano (con le foto
   * d'origine di adesso), i rifiutati se ne vanno.
   */
  private suspend fun authorizeFaces(current: TimelineView, token: Any?, faces: List<FaceLocator>, facePx: Int, crops: CollectionFaceCrops) {
    if (token != facesToken) {
      facesToken = token
      faceImages.clear()
    } else {
      faceImages.filterValues { !inView(current, it.origins) }.keys.forEach { faceImages.remove(it) }
    }
    val answers = withContext(compute) {
      coroutineScope { faces.map { face -> async { face to crops.crop(face, facePx) } }.awaitAll() }
    }
    for ((face, crop) in answers) {
      val key = faceKey(face, facePx)
      if (crop == null || crop.origins.isEmpty()) {
        faceImages.remove(key)
      } else {
        faceImages[key] = FaceImage(crop.bitmap.asImageBitmap(), crop.origins, token)
      }
    }
  }

  private fun faceKey(face: FaceLocator, sizePx: Int): String = face.encode() + "@" + sizePx

  /** Nessun segno delle revoche ancora visto. */
  private object Unseen

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

/**
 * La dose dei passaggi dei luoghi (giro 2, rischio di CG e CS su R3): il primo indice passa **subito**; dopo, al piu' uno ogni [periodMs], e sempre
 * **l'ultimo** (quelli in mezzo si saltano). Una per [CollectionCovers]: conta anche fra un elenco dei fornitori e il successivo. [now] in
 * millisecondi monotoni (nelle prove, il tempo virtuale della prova).
 */
class PassDose(private val periodMs: Long = PERIOD_MS, private val now: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }) {

  /** Quando e' passato l'ultimo indice; null = mai. Si legge e si scrive solo da chi raccoglie (il thread principale della scheda). */
  private var last: Long? = null

  /** [flow] con la dose: un valore subito, poi al piu' uno ogni [periodMs], sempre l'ultimo; chi raccoglie e' lento non accumula valori vecchi. */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun <T> apply(flow: Flow<T>): Flow<T> = flow.transformLatest { value ->
    last?.let { at ->
      val wait = at + periodMs - now()
      if (wait > 0) delay(wait)
    }
    last = now()
    emit(value)
  }.conflate()

  companion object {
    /** Al piu' un passaggio al secondo mentre il catalogo cresce. */
    const val PERIOD_MS: Long = 1_000L
    private const val NANOS_PER_MILLI = 1_000_000L
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
    // I posti protetti sono scuri su chiaro, e in tema scuro **grafite** con il segno chiaro (CU-18, giro 2): l'inverso del tema scuro e' bianco, la
    // cosa piu' luminosa della pagina sulla voce piu' riservata.
    CollectionArtFamily.Protected -> if (scheme.surface.luminance() < DARK_SURFACE) {
      ArtColors(scheme.surfaceContainerHighest, lerp(scheme.surfaceContainerHighest, scheme.primaryContainer, ART_DEPTH), scheme.onSurface)
    } else {
      ArtColors(scheme.inverseSurface, lerp(scheme.inverseSurface, scheme.primary, ART_DEPTH), scheme.inverseOnSurface)
    }
    CollectionArtFamily.Other -> when (tone) {
      FluidTone.Danger -> pair(scheme.errorContainer, scheme.error, scheme.onErrorContainer)
      FluidTone.Neutral -> pair(scheme.surfaceContainerHighest, scheme.secondary, scheme.onSurface)
      else -> pair(scheme.primaryContainer, scheme.primary, scheme.onPrimaryContainer)
    }
  }
}

/** Sotto questa luminanza il fondo della pagina e' quello del tema scuro. */
private const val DARK_SURFACE = 0.5f

/** Quanto del colore pieno entra nell'angolo in basso della sfumatura: abbastanza per dare profondita', poco per non far gridare la piastrella. */
private const val ART_DEPTH = 0.42f

/** La forma della piastrella (il raggio segue il lato, come in `FluidToneTile`). */
private fun artShape(side: Dp) = ContinuousCornerShape(FluidRadius.Small * (side / FluidToneTileSize))

/** La forma di un volto: un tondo continuo. */
private fun faceShape(side: Dp) = ContinuousCornerShape(side / 2)

/**
 * La piastrella di una riga delle Raccolte: la foto ([cover], o quella del luogo [coverPlace]), il mosaico dei luoghi ([coverPlaces]: la Mappa), i
 * volti ([faces]: uno pieno, due o tre raccolti), o l'icona sul colore della famiglia [family]. Senza copertine ([LocalCollectionCovers] null), o
 * finche' non sono pronte, la piastrella della famiglia.
 */
@Composable
internal fun CollectionRowArt(
  icon: ImageVector,
  tone: FluidTone,
  family: CollectionArtFamily,
  cover: MediaId? = null,
  faces: List<FaceLocator> = emptyList(),
  coverPlace: Int = 0,
  coverPlaces: List<Int> = emptyList(),
) {
  val covers = LocalCollectionCovers.current
  val side = CollectionRowArtSize
  val px = with(LocalDensity.current) { side.roundToPx() }
  val media = if (covers == null) null else cover ?: covers.placeCover(coverPlace)
  val mosaic = if (covers == null || media != null) emptyList() else coverPlaces.take(MOSAIC_CELLS).mapNotNull { covers.placeCover(it) }
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
      // Sotto il mosaico l'icona non si disegna: il segno della mappa e' gia' al centro.
      if (mosaic.isEmpty()) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = colors.ink,
          modifier = Modifier.align(androidx.compose.ui.Alignment.Center).size(CollectionsDimens.RowArtGlyph),
        )
      }
    }
    if (covers == null) return@Box
    if (media != null) {
      CoverImage(covers, media, Modifier.matchParentSize().clip(shape))
    } else if (mosaic.isNotEmpty()) {
      Mosaic(covers, mosaic, icon, colors, Modifier.matchParentSize().clip(shape))
    } else if (faceImages.size > 1) {
      Faces(faceImages, colors.from)
    }
  }
}

/**
 * CU-15 (giro 2): la piastrella della Mappa. Due per due, come i quadrati di una carta: una foto per luogo (i piu' fotografati), prima in diagonale
 * (con due luoghi si legge a scacchiera), i quadrati senza foto nel colore della famiglia; al centro, dove i quadrati si toccano, il segno della
 * mappa. Cosi' la Mappa non ripete la piastrella del primo luogo che le sta sotto. Una foto nascosta non si disegna ([CoverImage]).
 */
@Composable
private fun Mosaic(covers: CollectionCovers, media: List<MediaId>, icon: ImageVector, colors: ArtColors, modifier: Modifier) {
  val cell = (CollectionRowArtSize - CollectionsDimens.MosaicGap) / 2
  val far = cell + CollectionsDimens.MosaicGap
  Box(modifier = modifier) {
    media.forEachIndexed { i, id ->
      val (x, y) = MOSAIC_ORDER[i]
      CoverImage(covers, id, Modifier.offset(x = far * x, y = far * y).size(cell))
    }
    Box(
      modifier = Modifier.align(androidx.compose.ui.Alignment.Center).size(CollectionsDimens.MapBadge).clip(faceShape(CollectionsDimens.MapBadge))
        .background(colors.from),
      contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
      Icon(imageVector = icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(CollectionsDimens.MapBadgeGlyph))
    }
  }
}

/** CU-15: i quadrati del mosaico in ordine di riempimento (colonna, riga): prima la diagonale, poi l'altra. */
private val MOSAIC_ORDER = listOf(0 to 0, 1 to 1, 1 to 0, 0 to 1)

/** CU-15: quattro quadrati, due per due. */
private const val MOSAIC_CELLS = 4

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
