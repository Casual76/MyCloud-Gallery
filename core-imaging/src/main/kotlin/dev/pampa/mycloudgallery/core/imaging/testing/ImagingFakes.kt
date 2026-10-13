package dev.pampa.mycloudgallery.core.imaging.testing

import android.graphics.Bitmap
import android.os.CancellationSignal
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedSpec
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbStats
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.contract.ZoomSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

// I fake di core-imaging (D5, D23): in src/main, cosi' i moduli a valle (core-library, feature-*) li riusano nei
// loro test e negli screenshot senza cicli. Mai nella release: tools/check.ps1 fallisce se una classe di un package
// `testing` compare nel dex dell'APK di release.

/**
 * I pixel della Libreria sintetica: gradienti deterministici dal seme, cosi' due esecuzioni (e due screenshot)
 * sono identiche e ogni cella si distingue dalle vicine. Niente rete, niente file: la matematica sta qui sotto e
 * [argb] gira anche su JVM puro; solo [bitmap] ha bisogno di Android.
 */
object SyntheticPixels {

  /** Il lato lungo massimo delle bitmap sintetiche: la griglia le scala, non serve di piu'. */
  const val MAX_SIDE: Int = 96

  /** I pixel ARGB di un gradiente diagonale [width] x [height] con una luce morbida, dal [seed]. */
  fun argb(seed: Long, width: Int, height: Int): IntArray {
    require(width > 0 && height > 0) { "Dimensioni non valide." }
    val hash = mix(seed)
    val hue1 = ((hash and 0x3FFL) % 360L).toFloat()
    val hue2 = (hue1 + 40f + (((hash shr 10) and 0xFFL) % 90L).toFloat()) % 360f
    val c1 = hsv(hue1, 0.55f, 0.92f)
    val c2 = hsv(hue2, 0.65f, 0.55f)
    val lightX = 0.2f + (((hash shr 20) and 0xFFL).toFloat() / 255f) * 0.6f
    val lightY = 0.15f + (((hash shr 28) and 0xFFL).toFloat() / 255f) * 0.4f
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val fx = x / max(1f, width - 1f)
        val fy = y / max(1f, height - 1f)
        val t = (fx * 0.6f + fy * 0.4f).coerceIn(0f, 1f)
        val dx = fx - lightX
        val dy = fy - lightY
        val glow = (1f - (dx * dx + dy * dy) * 5f).coerceIn(0f, 1f) * 0.25f
        pixels[y * width + x] = lerp(c1, c2, t, glow)
      }
    }
    return pixels
  }

  /** La bitmap del gradiente (richiede Android: gira in Robolectric e sul telefono). */
  fun bitmap(seed: Long, width: Int, height: Int): Bitmap {
    val bitmap = createBitmap(width, height)
    bitmap.setPixels(argb(seed, width, height), 0, width, 0, 0, width, height)
    return bitmap
  }

  /** Larghezza e altezza di una bitmap sintetica con la proporzione di [width] x [height] e il lato lungo <= [px]. */
  fun sizeFor(width: Int, height: Int, px: Int): Pair<Int, Int> {
    val longSide = min(max(px, 1), MAX_SIDE)
    val scale = longSide.toFloat() / max(width, height)
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
  }

  private fun mix(seed: Long): Long {
    var z = seed + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return (z xor (z ushr 31)) and Long.MAX_VALUE
  }

  private fun hsv(h: Float, s: Float, v: Float): Int {
    val c = v * s
    val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
    val m = v - c
    val (r, g, b) = when {
      h < 60 -> Triple(c, x, 0f)
      h < 120 -> Triple(x, c, 0f)
      h < 180 -> Triple(0f, c, x)
      h < 240 -> Triple(0f, x, c)
      h < 300 -> Triple(x, 0f, c)
      else -> Triple(c, 0f, x)
    }
    return (0xFF shl 24) or ((r + m) * 255).roundToInt().coerceIn(0, 255).shl(16) or
      ((g + m) * 255).roundToInt().coerceIn(0, 255).shl(8) or ((b + m) * 255).roundToInt().coerceIn(0, 255)
  }

  private fun lerp(a: Int, b: Int, t: Float, light: Float): Int {
    fun channel(shift: Int): Int {
      val ca = (a shr shift) and 0xFF
      val cb = (b shr shift) and 0xFF
      val value = ca + (cb - ca) * t
      return (value + (255 - value) * light).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
  }
}

/** La sorgente della Libreria sintetica: gradienti per [MediaRef.Synthetic], niente per tutto il resto. */
class SyntheticThumbnailSource : ThumbnailSource {

  override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? {
    // Come ogni sorgente: se la richiesta e' gia' stata annullata non si spende nemmeno il gradiente.
    cancel.throwIfCanceled()
    return loadNow(ref, px)
  }

  /** Lo stesso senza sospendere: il gradiente costa microsecondi, e i fake sincroni ne hanno bisogno. */
  fun loadNow(ref: MediaRef, px: Int): Bitmap? {
    val synthetic = ref as? MediaRef.Synthetic ?: return null
    val (w, h) = SyntheticPixels.sizeFor(synthetic.width, synthetic.height, px)
    return SyntheticPixels.bitmap(synthetic.seed, w, h)
  }
}

/**
 * Un caricatore **sincrono** sopra una [ThumbnailSource] (di default la sintetica): la prima volta che una cella
 * legge [image] la bitmap viene prodotta e messa in cache. E' una semplificazione dei fake (il contratto dice che
 * `image` non avvia niente): serve a far disegnare la griglia nei test e negli screenshot senza tempo virtuale.
 * Registra le finestre ricevute per i test della griglia.
 */
class FakeThumbnailLoader(
  private val render: (MediaRef, Int) -> Bitmap? = SyntheticThumbnailSource()::loadNow,
) : ThumbnailLoader {

  private val cache = HashMap<ThumbKey, MutableState<ImageBitmap?>>()
  private val statsFlow = MutableStateFlow(ThumbStats())

  /** Le finestre ricevute (visibili, in arrivo), nell'ordine. */
  val windows: MutableList<Pair<List<ThumbRequest>, List<ThumbRequest>>> = mutableListOf()

  /** Quante bitmap sono state prodotte. */
  var produced: Int = 0
    private set

  override fun image(key: ThumbKey, ref: MediaRef): State<ImageBitmap?> = cache.getOrPut(key) {
    val bitmap = render(ref, key.bucket.px)
    if (bitmap != null) produced++
    mutableStateOf(bitmap?.asImageBitmap())
  }

  override fun peek(key: ThumbKey): ImageBitmap? = cache[key]?.value

  override fun window(visible: List<ThumbRequest>, ahead: List<ThumbRequest>) {
    windows += visible to ahead
  }

  override fun trim(level: Int) {
    cache.clear()
  }

  override val stats: StateFlow<ThumbStats> = statsFlow
}

/** Segnaposto finti: un gradiente 32x32 dal contenuto del [ThumbHash] (stesso hash, stessa immagine). */
class FakePlaceholderCache : PlaceholderCache {
  private val cache = HashMap<ThumbHash, ImageBitmap>()

  override fun image(h: ThumbHash): ImageBitmap =
    cache.getOrPut(h) { SyntheticPixels.bitmap(h.base64.hashCode().toLong(), SIDE, SIDE).asImageBitmap() }

  private companion object {
    const val SIDE = 32
  }
}

/**
 * Un'immagine del viewer finta, per [MediaRef.Synthetic]: l'anteprima, poi (dopo [latencyMs]) la versione nitida.
 * Sorgente lenta (E-V1), irraggiungibile o in errore a scelta del test; conta le decodifiche in volo (E-V5).
 */
class FakeViewerImageSource(
  private val thumbs: ThumbnailSource = SyntheticThumbnailSource(),
  /** Quanto ci mette la versione nitida; 0 = subito. */
  var latencyMs: Long = 0,
  /** Se non e' null, ogni flusso emette solo questo stato (sorgente irraggiungibile, E-V2, o decodifica fallita). */
  var forced: ViewerImageState? = null,
) : ViewerImageSource {

  /** Flussi attivi adesso. */
  var inFlight: Int = 0
    private set

  /** Il massimo di flussi attivi insieme (E-V5: "al massimo 3"). */
  var maxInFlight: Int = 0
    private set

  /** Quanti flussi sono stati annullati prima di finire. */
  var cancelled: Int = 0
    private set

  override fun state(ref: MediaRef, screen: IntSize): Flow<ViewerImageState> = flow {
    inFlight++
    maxInFlight = max(maxInFlight, inFlight)
    var finished = false
    try {
      forced?.let {
        emit(it)
        finished = true
        return@flow
      }
      val synthetic = ref as? MediaRef.Synthetic
      if (synthetic == null) {
        emit(ViewerImageState.Failed(ImageFailure.NOT_FOUND))
        finished = true
        return@flow
      }
      val preview = thumbs.load(ref, SyntheticPixels.MAX_SIDE / 2, CancellationSignal())
      if (preview != null) emit(ViewerImageState.Preview(preview.asImageBitmap()))
      emit(ViewerImageState.Loading(progress = null))
      if (latencyMs > 0) delay(latencyMs)
      val (w, h) = SyntheticPixels.sizeFor(synthetic.width, synthetic.height, SyntheticPixels.MAX_SIDE)
      emit(ViewerImageState.Sharp(SyntheticPixels.bitmap(synthetic.seed, w, h).asImageBitmap(), hdr = false, colorSpace = null))
      finished = true
    } finally {
      inFlight--
      if (!finished) cancelled++
    }
  }

  override fun zoomSpec(ref: MediaRef): ZoomSpec? = (ref as? MediaRef.Synthetic)?.let { ZoomSpec(it.width, it.height, tiled = true) }

  override fun animated(ref: MediaRef): AnimatedSpec? = null

  /** Comodo per i test: lo stato "irraggiungibile" del NAS con la dimensione da mostrare (E-V1, E-V2). */
  fun unreachable(reason: UnavailableReason = UnavailableReason.NAS_UNREACHABLE, sizeBytes: Long? = null) {
    forced = ViewerImageState.Unavailable(reason, sizeBytes)
  }
}
