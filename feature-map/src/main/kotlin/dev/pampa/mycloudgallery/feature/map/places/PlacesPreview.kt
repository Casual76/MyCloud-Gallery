package dev.pampa.mycloudgallery.feature.map.places

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoClusterIndex
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.places.contract.PlacesAttribution
import dev.pampa.mycloudgallery.feature.map.map.MapStyles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

// L'ANTEPRIMA DELLA MAPPA DEI LUOGHI (fase I, I1, 11/11). L'utente ha scelto le Raccolte "come Samsung": in cima ai Luoghi una **mappa vera** a tutta
// larghezza, con le foto appuntate dei luoghi piu' fotografati. Qui si decide **che pezzo di mondo** mostrare e **dove cadono i luoghi** sulla carta; la
// carta la disegna [PlacesPreviewRenderer] (MapLibre con lo stile **solo offline**: la carta di base negli asset, nessuna sorgente in rete, mai una
// richiesta, come la mini-mappa del viewer, D29, E-MAP16). Le foto sopra la carta NON stanno qui: le disegna la scheda delle Raccolte con la sua strada
// delle miniature e i suoi cancelli (la Libreria visibile: mai un nascosto). Qui escono solo un'immagine della carta e delle frazioni (0..1) per luogo.
//
// **Il centro di un luogo** e' la media (pesata sul numero di foto) dei centri delle celle piu' fini dell'indice con quel luogo: l'indice contiene solo
// celle visibili, quindi un luogo con soli nascosti non c'e'. Le coordinate restano qui dentro: fuori escono l'id del luogo e una posizione sulla carta.
//
// **Lo zoom** e' quello che fa stare tutti i luoghi nel riquadro con un margine (il mondo di MapLibre e' largo 512 dp a zoom 0, come `GeoGrid.TILE_PX`),
// fra [PlacesPreviewFrames.MIN_ZOOM] e lo zoom massimo della carta di base ([MapStyles.OFFLINE_MAX_ZOOM]) meno un passo, cosi' un luogo solo non si
// apre su una carta vuota.

/** Un luogo sulla carta dell'anteprima: [x] e [y] in frazioni della larghezza e dell'altezza (0..1, dall'angolo in alto a sinistra). */
data class PreviewPin(val placeId: Int, val x: Float, val y: Float) {
  override fun toString(): String = "PreviewPin"
}

/** Il centro e lo zoom della carta (zoom di MapLibre). */
data class PreviewCamera(val center: GeoE6, val zoom: Double) {
  override fun toString(): String = "PreviewCamera(${"%.1f".format(zoom)})"
}

/** Che cosa mostrare: la camera e i luoghi che cadono nel riquadro, nell'ordine dato (i piu' fotografati prima). */
data class PreviewFrame(val camera: PreviewCamera, val pins: List<PreviewPin>)

/** L'anteprima pronta: la carta (solo la carta di base), i luoghi sopra e il credito della carta. */
class PlacesPreview(val bitmap: Bitmap, val pins: List<PreviewPin>, val attribution: String) {
  override fun toString(): String = "PlacesPreview(${pins.size})"
}

/** Chi disegna la carta: [widthDp] x [heightDp] dp alla densita' [density], con la carta di base scura o chiara. Null se non puo' (MapLibre non c'e'). */
fun interface PlacesPreviewRenderer {
  suspend fun render(camera: PreviewCamera, widthDp: Int, heightDp: Int, density: Float, dark: Boolean): Bitmap?
}

/** Nessuna carta: le Raccolte tengono il mosaico delle foto dei luoghi. */
object NoPlacesPreviewRenderer : PlacesPreviewRenderer {
  override suspend fun render(camera: PreviewCamera, widthDp: Int, heightDp: Int, density: Float, dark: Boolean): Bitmap? = null
}

/** Le funzioni pure dell'anteprima: provate su JVM (`PlacesPreviewFramesTest`). */
object PlacesPreviewFrames {

  /** Mai piu' lontano di cosi': mezzo mondo (un viaggio in un altro continente resta dentro la carta). */
  const val MIN_ZOOM: Double = 1.0

  /** Mai piu' vicino della carta di base meno un passo: un luogo solo resta dentro una regione che si riconosce. */
  val MAX_ZOOM: Double = MapStyles.OFFLINE_MAX_ZOOM - 1.0

  /** Il margine attorno ai luoghi, in frazione del lato piu' corto: le foto appuntate non escono dal bordo. */
  const val INSET: Float = 0.18f

  private val WORLD = GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6)

  /** Uno zoom oltre cui ogni cella e' all'ultimo livello (come `PlacesStates`). */
  private const val FINEST_ZOOM = 23.0

  /**
   * I centri dei luoghi [places]: per ognuno la media delle celle piu' fini dell'indice con quel luogo, pesata sul numero di foto. Un solo passaggio;
   * un luogo senza celle (solo nascosti, o uscito dall'indice) non c'e'.
   */
  fun centers(index: GeoClusterIndex, places: Set<Int>): Map<Int, GeoE6> {
    if (places.isEmpty()) return emptyMap()
    val sums = HashMap<Int, DoubleArray>()
    for (cluster in index.clusters(WORLD, FINEST_ZOOM, 1)) {
      val place = cluster.placeId ?: continue
      if (place !in places) continue
      val acc = sums.getOrPut(place) { DoubleArray(3) }
      acc[0] += GeoGrid.x(cluster.center.lonE6).toDouble() * cluster.count
      acc[1] += GeoGrid.y(cluster.center.latE6).toDouble() * cluster.count
      acc[2] += cluster.count.toDouble()
    }
    return sums.mapValues { (_, acc) ->
      val x = acc[0] / acc[2]
      val y = acc[1] / acc[2]
      GeoE6(GeoGrid.latE6(y.toLong()), GeoGrid.lonE6(x.toLong()))
    }
  }

  /**
   * La camera che fa stare i luoghi [order] (con un centro in [centers]) in [widthDp] x [heightDp] con il margine [INSET], e dove cade ciascuno.
   * Null se nessun luogo ha un centro. Il mondo di MapLibre a zoom z e' largo 512 x 2^z dp.
   */
  fun frame(centers: Map<Int, GeoE6>, order: List<Int>, widthDp: Float, heightDp: Float): PreviewFrame? {
    val placed = order.filter { it in centers }.distinct()
    if (placed.isEmpty() || widthDp <= 0f || heightDp <= 0f) return null
    // Le coordinate di Mercatore in frazioni del mondo (0..1).
    val xs = placed.associateWith { GeoGrid.x(centers.getValue(it).lonE6).toDouble() / GeoGrid.SIDE }
    val ys = placed.associateWith { GeoGrid.y(centers.getValue(it).latE6).toDouble() / GeoGrid.SIDE }
    val west = xs.values.min()
    val east = xs.values.max()
    val north = ys.values.min()
    val south = ys.values.max()
    val inset = min(widthDp, heightDp) * INSET
    val usableW = (widthDp - 2 * inset).coerceAtLeast(1f)
    val usableH = (heightDp - 2 * inset).coerceAtLeast(1f)
    val spanX = east - west
    val spanY = south - north
    val zoomX = if (spanX > 0) log2(usableW / (spanX * GeoGrid.TILE_PX)) else Double.POSITIVE_INFINITY
    val zoomY = if (spanY > 0) log2(usableH / (spanY * GeoGrid.TILE_PX)) else Double.POSITIVE_INFINITY
    val zoom = min(zoomX, zoomY).coerceIn(MIN_ZOOM, MAX_ZOOM)
    val cx = (west + east) / 2
    val cy = (north + south) / 2
    val world = GeoGrid.TILE_PX * 2.0.pow(zoom)
    val pins = placed.mapNotNull { id ->
      val px = widthDp / 2 + (xs.getValue(id) - cx) * world
      val py = heightDp / 2 + (ys.getValue(id) - cy) * world
      val fx = (px / widthDp).toFloat()
      val fy = (py / heightDp).toFloat()
      if (fx in 0f..1f && fy in 0f..1f) PreviewPin(id, fx, fy) else null
    }
    val center = GeoE6(GeoGrid.latE6((cy * GeoGrid.SIDE).toLong()), GeoGrid.lonE6((cx * GeoGrid.SIDE).toLong()))
    return PreviewFrame(PreviewCamera(center, zoom), pins)
  }

  private fun log2(v: Double): Double = ln(v) / ln(2.0)
}

/**
 * L'anteprima dei Luoghi per le Raccolte: segue l'indice dei punti (che la sezione Luoghi ha gia' fatto partire al primo sguardo, mai all'avvio) e, per i
 * luoghi chiesti, prepara la carta e le posizioni. **Non avvia niente da sola**: finche' l'indice si costruisce non emette; con un indice vuoto o guasto
 * emette null (le Raccolte tengono il mosaico). Le carte si ricordano **solo in memoria** (le ultime [CACHE_SIZE], mai su disco) e si buttano con [clear]
 * (blocco dell'app, poca memoria, un "Nascondi").
 */
class PlacesPreviewService(
  private val points: MapPoints,
  private val renderer: PlacesPreviewRenderer,
  private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
  private val cache = object : LinkedHashMap<String, Bitmap>(CACHE_SIZE, LOAD, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > CACHE_SIZE
  }

  /**
   * L'anteprima di [places] (i piu' fotografati prima) a [widthDp] x [heightDp] dp. Un valore per ogni indice nuovo che cambia l'inquadratura; null =
   * nessuna carta (indice vuoto o guasto, MapLibre assente, nessun luogo con un centro).
   */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  fun preview(places: List<Int>, widthDp: Int, heightDp: Int, density: Float, dark: Boolean): Flow<PlacesPreview?> {
    require(widthDp > 0 && heightDp > 0) { "Misura non valida." }
    val wanted = places.filter { it > 0 }.distinct()
    return points.index
      .map { state ->
        when (state) {
          GeoIndexState.Building -> Pending
          is GeoIndexState.Ready -> withContext(compute) {
            PlacesPreviewFrames.frame(PlacesPreviewFrames.centers(state.index, wanted.toSet()), wanted, widthDp.toFloat(), heightDp.toFloat())
          } ?: NoFrame
          else -> NoFrame
        }
      }
      .distinctUntilChanged()
      .transformLatest { frame ->
        when (frame) {
          Pending -> Unit
          NoFrame -> emit(null)
          is PreviewFrame -> emit(draw(frame, widthDp, heightDp, density, dark))
        }
      }
  }

  private suspend fun draw(frame: PreviewFrame, widthDp: Int, heightDp: Int, density: Float, dark: Boolean): PlacesPreview? {
    val key = "${frame.camera.center.latE6}/${frame.camera.center.lonE6}/${"%.3f".format(frame.camera.zoom)}/$widthDp/$heightDp/$density/$dark"
    val cached = synchronized(cache) { cache[key] }
    val bitmap = cached ?: renderer.render(frame.camera, widthDp, heightDp, density, dark)?.also { synchronized(cache) { cache[key] = it } } ?: return null
    return PlacesPreview(bitmap, frame.pins, PlacesAttribution.MAP_BASE_SHORT)
  }

  /** Dimentica le carte (blocco dell'app, poca memoria, un nascosto: la carta si rifa' con l'indice di adesso). */
  fun clear() = synchronized(cache) { cache.clear() }

  /** Due segni dell'indice che non sono un'inquadratura. */
  private object Pending

  private object NoFrame

  private companion object {
    const val CACHE_SIZE = 4
    const val LOAD = 0.75f
  }
}
