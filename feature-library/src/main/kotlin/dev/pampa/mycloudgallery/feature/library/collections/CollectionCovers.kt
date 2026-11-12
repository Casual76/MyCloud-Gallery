package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconColors
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryIconColors
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
import kotlinx.coroutines.flow.distinctUntilChanged
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

  /**
   * La richiesta della miniatura di [media] in [view] alla taglia [bucket], o null se il media non e' nella Libreria visibile (un nascosto, un
   * eliminato). I1: le schede grandi chiedono la taglia che copre il loro lato ([CollectionCardBucket]), le righe la M di sempre.
   */
  fun request(view: TimelineView, media: MediaId, bucket: ThumbBucket = ThumbBucket.M): ThumbRequest? {
    val viewIndex = view.viewIndexOf(media)
    if (viewIndex < 0) return null
    val ref = library.refOrNull(media) ?: return null
    val index = view.snapshotIndex(viewIndex)
    val version = (view.snapshot as? ColumnarTimelineSnapshot)?.versionKeyAt(index) ?: MediaGridState.DefaultVersionKey
    return ThumbRequest(ThumbKey(media, version, bucket), ref)
  }

  /** I1: la foto di una voce: la sua copertina, o quella del suo luogo; null se non ce n'e' (o non ancora cercata). Legge lo stato dei luoghi. */
  fun mediaOf(entry: CollectionEntry): MediaId? = entry.cover ?: placeCover(entry.coverPlace)

  /**
   * I1: la finestra delle miniature delle schede vicine allo schermo ([visible] sullo schermo, [ahead] subito dopo nei caroselli), alla taglia
   * [bucket], sulla vista [view]: **una** chiamata per tutta la scheda (la finestra annulla cio' che non contiene, quindi non si chiama da ogni
   * carosello). Le voci senza foto o con una foto fuori dalla Libreria visibile non chiedono niente.
   */
  fun window(view: TimelineView, visible: List<MediaId>, ahead: List<MediaId>, bucket: ThumbBucket) {
    val now = visible.distinct().mapNotNull { request(view, it, bucket) }
    val next = (ahead.distinct() - visible.toSet()).mapNotNull { request(view, it, bucket) }
    if (now.isNotEmpty() || next.isNotEmpty()) loader.window(now, next)
  }

  /**
   * Giro 1 (CS-8): le stesse richieste di [window], ma **tenute accanto** alla finestra di chi la possiede ([ThumbnailLoader.keep]): sul tablet, con una
   * pagina aperta nel pannello accanto (una griglia con la sua finestra), le schede delle Raccolte chiedono le loro foto senza annullare le celle della
   * griglia. Solo media della Libreria visibile, come [window]; [release] le toglie.
   */
  fun keep(view: TimelineView, visible: List<MediaId>, ahead: List<MediaId>, bucket: ThumbBucket) {
    loader.keep(KEEP_OWNER, (visible + ahead).distinct().mapNotNull { request(view, it, bucket) })
  }

  /** Toglie le richieste tenute da [keep] (il pannello accanto si e' chiuso, o la scheda non c'e' piu'). */
  fun release() {
    loader.keep(KEEP_OWNER, emptyList())
  }

  /**
   * Prepara le copertine di [entries] per l'indice di adesso e per ogni suo cambiamento: cerca le foto dei luoghi (un passaggio, fuori dal thread
   * principale) e ritaglia i volti a [facePx]. Le miniature NON si chiedono qui (I1): le chiede la finestra delle schede vicine allo schermo
   * ([window], da [CollectionCoverWindow]). Gira finche' la scheda e' composta; una nuova chiamata (elenco cambiato) sostituisce la precedente.
   */
  suspend fun refresh(entries: List<CollectionEntry>, facePx: Int): Unit = coroutineScope {
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
    dose.apply(view).collect { current -> placesPass(current, places) }
  }

  /** Il passaggio dei luoghi per [current]; un indice gia' passato con gli stessi luoghi non si ripassa. */
  private suspend fun placesPass(current: TimelineView, places: Set<Int>) {
    val previous = lastPass
    val found = when {
      places.isEmpty() -> emptyMap()
      previous != null && previous.first === current && previous.second == places -> HashMap(placeCovers)
      else -> withContext(compute) { newestByPlace(current, places) }.also { placePasses++ }
    }
    lastPass = current to places
    placeCovers.keys.retainAll(found.keys)
    placeCovers.putAll(found)
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

  /**
   * I6 (review CG-I1): le revoche ascoltate per tutta la vita delle copie dei volti, non solo mentre la scheda e' composta ([refresh] gira dentro
   * [CollectionCoverWindow], che in modalita' modifica non c'e'). A ogni segno nuovo si buttano le copie permesse con un segno diverso: nessun volto
   * revocato resta in memoria ne' si disegna al ritorno, prima che la scheda rifaccia i suoi. Lo lancia il proprietario ([CollectionsLayoutViewModel]).
   */
  suspend fun dropRevoked() {
    val crops = faceCrops ?: return
    crops.revocations.collect { token ->
      faceImages.filterValues { it.token != token }.keys.forEach { faceImages.remove(it) }
    }
  }

  /** I6: quanti volti tiene adesso (per le prove: dopo una revoca, nessuno). */
  val heldFaces: Int get() = faceImages.size

  private fun faceKey(face: FaceLocator, sizePx: Int): String = face.encode() + "@" + sizePx

  /** Nessun segno delle revoche ancora visto. */
  private object Unseen

  companion object {
    /** Il nome con cui le Raccolte tengono le loro richieste accanto alla finestra della griglia ([keep]). */
    const val KEEP_OWNER: String = "collections"

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
 * I1: il lato dei ritagli dei volti delle schede delle persone: l'altezza della scheda (il volto la riempie), al piu' [FACE_MAX_PX] come gli avatar di
 * feature-people (`PeopleDimens.CropMaxPx`), cosi' sul telefono la scheda e la griglia di "Tutte le persone" chiedono lo stesso ritaglio alla stessa
 * cache e nessuno lo ritaglia due volte.
 */
@Composable
internal fun collectionFacePx(): Int = with(LocalDensity.current) { CollectionsDimens.PersonCardHeight.roundToPx() }.coerceAtMost(FACE_MAX_PX)

/** Il tetto dei ritagli, lo stesso degli avatar di feature-people. */
internal const val FACE_MAX_PX = 384

/**
 * I1: la taglia delle miniature delle schede grandi: la piu' piccola della cache che copre il lato lungo della scheda piu' larga (di fatto la L, la
 * piu' grande che la cache ha: la M delle righe a tutta scheda si sgranerebbe). Le foto appuntate sulla mappa usano la stessa, cosi' sono le stesse
 * miniature delle schede dei luoghi (una decodifica sola).
 */
@Composable
internal fun collectionCardBucket(): ThumbBucket = ThumbBucket.covering(with(LocalDensity.current) { CollectionsDimens.WideCardWidth.roundToPx() })

/**
 * I1: le schede **vicine allo schermo**. Ogni carosello (e la mappa) dice quali sue voci si vedono e quali stanno per entrare; quando un carosello esce
 * dalla composizione (la colonna lo ha lasciato lontano) se ne va anche da qui. [CollectionCoverWindow] ne fa **una** finestra di miniature per tutta la
 * scheda.
 */
@Stable
internal class CoverViewport {
  private class Near(val visible: List<CollectionEntry>, val ahead: List<CollectionEntry>)

  private val near = mutableStateMapOf<String, Near>()

  fun report(key: String, visible: List<CollectionEntry>, ahead: List<CollectionEntry>) {
    near[key] = Near(visible, ahead)
  }

  fun drop(key: String) {
    near.remove(key)
  }

  fun visible(): List<CollectionEntry> = near.values.flatMap { it.visible }

  fun ahead(): List<CollectionEntry> = near.values.flatMap { it.ahead }
}

/** Le schede vicine allo schermo della scheda di adesso; null fuori dalle Raccolte (le righe di altri moduli non ne hanno bisogno). */
internal val LocalCoverViewport = staticCompositionLocalOf<CoverViewport?> { null }

/**
 * Prepara le copertine di [entries] (luoghi e volti) **una volta** per elenco, e di nuovo solo se l'elenco o la Libreria cambiano: non a ogni
 * scorrimento. I1: le miniature si chiedono per le sole schede vicine allo schermo ([viewport]) alla taglia delle schede, rifatte quando cambia cio' che
 * e' vicino o la Libreria (con la stessa dose dei luoghi: al piu' una volta al secondo mentre il catalogo cresce). Sul telefono, e sul tablet con il
 * pannello accanto vuoto, sono **la** finestra delle miniature; giro 1 (CS-8): sul tablet con una pagina aperta accanto ([beside]) quella e' una
 * griglia con la sua finestra, e le schede **tengono** le loro richieste accanto alla sua ([CollectionCovers.keep]) invece di sostituirla.
 */
@Composable
internal fun CollectionCoverWindow(covers: CollectionCovers, entries: List<CollectionEntry>, viewport: CoverViewport, beside: Boolean) {
  val facePx = collectionFacePx()
  LaunchedEffect(covers, entries, facePx) {
    if (entries.isNotEmpty()) covers.refresh(entries, facePx)
  }
  val bucket = collectionCardBucket()
  LaunchedEffect(covers, viewport, beside, bucket) {
    val near = snapshotFlow { viewport.visible().mapNotNull(covers::mediaOf) to viewport.ahead().mapNotNull(covers::mediaOf) }.distinctUntilChanged()
    try {
      combine(PassDose().apply(covers.view), near) { view, wanted -> view to wanted }
        .collectLatest { (view, wanted) ->
          if (beside) covers.keep(view, wanted.first, wanted.second, bucket) else covers.window(view, wanted.first, wanted.second, bucket)
        }
    } finally {
      // Il pannello si chiude o la scheda se ne va: le richieste tenute non restano accanto alla finestra di nessuno.
      if (beside) covers.release()
    }
  }
}

/**
 * La famiglia di una voce: il colore della sua piastrella quando non c'e' una foto. Una famiglia per sezione, cosi' la pagina si legge a colpo d'occhio
 * (le persone, i ricordi, i luoghi, il NAS, le utilita') e i posti protetti hanno un aspetto loro. I1: i colori sono quelli del componente condiviso
 * delle icone ([GalleryIconFamily], I3), la stessa lingua di Impostazioni e Backup.
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

/**
 * I1: la famiglia del componente condiviso per una voce della famiglia [family] con il tono [tone] e l'icona [icon]: il cestino e' sempre "pericolo"
 * (lo stesso segno di Impostazioni), le altre voci prendono il colore della loro sezione.
 */
internal fun iconFamilyOf(family: CollectionArtFamily, tone: FluidTone, icon: ImageVector? = null): GalleryIconFamily = when {
  icon == Icons.Rounded.Delete || tone == FluidTone.Danger -> GalleryIconFamily.Danger
  else -> when (family) {
    CollectionArtFamily.People -> GalleryIconFamily.People
    CollectionArtFamily.Memories, CollectionArtFamily.Trips -> GalleryIconFamily.Memories
    CollectionArtFamily.Places -> GalleryIconFamily.Places
    CollectionArtFamily.Nas -> GalleryIconFamily.Backup
    CollectionArtFamily.Utility -> GalleryIconFamily.System
    CollectionArtFamily.Protected -> GalleryIconFamily.Security
    CollectionArtFamily.Other -> GalleryIconFamily.Photos
  }
}

/** La forma di un volto: un tondo continuo. */
internal fun faceShape(side: Dp) = ContinuousCornerShape(side / 2)

/**
 * La piastrella di una riga delle Raccolte (le righe che restano: le prove e le pagine degli altri moduli): la foto ([cover], o quella del luogo
 * [coverPlace]), il mosaico dei luoghi ([coverPlaces]), i volti ([faces]), o l'icona sul colore della famiglia [family] (il componente condiviso).
 * Senza copertine ([LocalCollectionCovers] null), o finche' non sono pronte, la piastrella della famiglia.
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
  val iconFamily = iconFamilyOf(family, tone, icon)
  // Un volto solo e' un tondo pieno: senza la piastrella sotto, che ne lascerebbe vedere gli angoli.
  if (faceImages.size == 1) {
    Image(bitmap = faceImages[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(side).clip(faceShape(side)))
    return
  }
  if (covers == null || (media == null && mosaic.isEmpty() && faceImages.isEmpty())) {
    GalleryIconTile(icon = icon, family = iconFamily, size = side)
    return
  }
  val colors = rememberGalleryIconColors(iconFamily)
  GalleryIconTile(family = iconFamily, size = side) {
    when {
      media != null -> CoverImage(covers, media, Modifier.matchParentSize())
      mosaic.isNotEmpty() -> MapMosaic(covers, mosaic, icon, colors, Modifier.matchParentSize(), CollectionRowArtSize, CollectionsDimens.MapBadge)
      else -> Faces(faceImages, colors.top)
    }
  }
}

/**
 * CU-15 (giro 2): il mosaico della Mappa. Due per due, come i quadrati di una carta: una foto per luogo (i piu' fotografati), prima in diagonale, i
 * quadrati senza foto nel colore della famiglia; al centro il segno della mappa. Una foto nascosta non si disegna ([CoverImage]). I1: la stessa figura,
 * grande, e' la mappa delle Raccolte quando la carta vera non c'e' (nessuna mappa, nessun luogo con un centro).
 */
@Composable
internal fun MapMosaic(
  covers: CollectionCovers?,
  media: List<MediaId>,
  icon: ImageVector,
  colors: GalleryIconColors,
  modifier: Modifier,
  side: Dp,
  badge: Dp,
  aspect: Float = 1f,
  bucket: ThumbBucket = ThumbBucket.M,
) {
  val cellW = (side - CollectionsDimens.MosaicGap) / 2
  val cellH = (side / aspect - CollectionsDimens.MosaicGap) / 2
  Box(modifier = modifier) {
    if (covers != null) {
      media.take(MOSAIC_CELLS).forEachIndexed { i, id ->
        val (x, y) = MOSAIC_ORDER[i]
        CoverImage(covers, id, Modifier.offset(x = (cellW + CollectionsDimens.MosaicGap) * x, y = (cellH + CollectionsDimens.MosaicGap) * y).size(cellW, cellH), bucket)
      }
    }
    Box(
      modifier = Modifier.align(Alignment.Center).size(badge).clip(faceShape(badge)).background(colors.top),
      contentAlignment = Alignment.Center,
    ) {
      Icon(imageVector = icon, contentDescription = null, tint = colors.glyph, modifier = Modifier.size(badge * MAP_BADGE_GLYPH))
    }
  }
}

/** Il glifo dentro il segno della mappa, in frazione del segno (12 su 18 dp). */
private const val MAP_BADGE_GLYPH = 2f / 3f

/** CU-15: i quadrati del mosaico in ordine di riempimento (colonna, riga): prima la diagonale, poi l'altra. */
private val MOSAIC_ORDER = listOf(0 to 0, 1 to 1, 1 to 0, 0 to 1)

/** CU-15: quattro quadrati, due per due. */
internal const val MOSAIC_CELLS = 4

/**
 * Una copertina: la miniatura di [media] alla taglia [bucket], se il media e' nella Libreria visibile di adesso (un nascosto non si disegna mai). Finche'
 * la taglia chiesta non c'e', una piu' piccola gia' in memoria (la griglia della Libreria la tiene spesso): niente buco, niente salto. Nessuna
 * animazione (il movimento e' dell'engine).
 */
@Composable
internal fun CoverImage(covers: CollectionCovers, media: MediaId, modifier: Modifier, bucket: ThumbBucket = ThumbBucket.M) {
  val view by covers.view.collectAsState()
  val request = remember(view, media, bucket) { covers.request(view, media, bucket) } ?: return
  val image = covers.loader.image(request.key, request.ref).value
    ?: FALLBACK_BUCKETS.firstNotNullOfOrNull { b -> if (b == bucket) null else covers.loader.peek(request.key.copy(bucket = b)) }
    ?: return
  Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

/** Le taglie da guardare in memoria mentre quella chiesta non c'e': dalla piu' grande. */
private val FALLBACK_BUCKETS = ThumbBucket.entries.reversed()

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
