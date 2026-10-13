package dev.pampa.mycloudgallery.core.imaging.budget

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.Immutable

/**
 * Il budget di memoria e di concorrenza della pipeline delle immagini, derivato da `memoryClass` (D5, rischio
 * "memoria" del §7 di `docs/fase3-progetto.md`). Un solo posto per i numeri: la cache delle miniature, il segnaposto,
 * la bitmap piu' grande che il viewer decodifica e i tetti di concorrenza escono da qui, cosi' il report del pacchetto
 * e i test dicono le stesse cifre.
 *
 * Le formule sono proporzioni di `memoryClass` con un pavimento (il telefono piu' modesto funziona ancora) e un
 * soffitto (un tablet con `largeHeap` non deve far crescere la cache all'infinito: a 120 Hz conta il lavoro, non la
 * quantita' di bitmap tenute). Le bitmap vivono nella memoria nativa (API 26+), ma il limitatore di memoria di
 * Android 17 (T-23) conta tutto il processo: per questo il budget segue `memoryClass` e non la sola heap Java.
 *
 * | memoryClass | cache miniature | bitmap del viewer (tetto) |
 * |---|---|---|
 * | 128 MB | 32 MB | 25 MB |
 * | 192 MB | 48 MB | 38 MB |
 * | 256 MB (S25) | 64 MB | 51 MB |
 * | 512 MB | 96 MB (soffitto) | 64 MB (soffitto) |
 */
@Immutable
data class ImagingBudget(
  /** Il `memoryClass` da cui deriva, in MB. */
  val memoryClassMb: Int,
  /** Quanto possono occupare insieme le miniature in cache (somma delle bitmap). */
  val thumbnailCacheBytes: Long,
  /** Quanti segnaposto ThumbHash 32x32 tenere (circa 4 KB l'uno). */
  val placeholderEntries: Int,
  /** Quante miniature si caricano insieme (il tetto di concorrenza del pianificatore). */
  val thumbnailConcurrency: Int,
  /** La bitmap piu' grande che il viewer decodifica per una pagina, in byte (ARGB_8888). */
  val viewerBitmapBytes: Long,
  /** Quante decodifiche del viewer in volo insieme (E-V5: al massimo tre). */
  val viewerConcurrency: Int,
  /** Il lato lungo massimo di una decodifica campionata di un RAW/DNG (E-M2): 4096. */
  val rawMaxSide: Int,
) {

  init {
    require(thumbnailCacheBytes > 0 && viewerBitmapBytes > 0) { "Budget non valido." }
    require(thumbnailConcurrency >= 1 && viewerConcurrency >= 1 && placeholderEntries >= 1 && rawMaxSide >= 1) {
      "Budget non valido."
    }
  }

  /** I pixel (ARGB_8888) che stanno nel tetto di una bitmap del viewer. */
  val viewerMaxPixels: Long get() = viewerBitmapBytes / BYTES_PER_PIXEL

  companion object {
    const val MB: Long = 1024L * 1024L

    /** Byte per pixel di una bitmap ARGB_8888 (le decodifiche del viewer e le miniature). */
    const val BYTES_PER_PIXEL: Long = 4L

    /** Il tetto di decodifiche del viewer in volo (E-V5). */
    const val VIEWER_CONCURRENCY: Int = 3

    /** Il lato lungo massimo di un RAW decodificato campionato. */
    const val RAW_MAX_SIDE: Int = 4096

    private const val PLACEHOLDER_ENTRIES = 512

    /**
     * Il budget per un dispositivo con [memoryClassMb] e, se [lowRam], con la memoria dimezzata. [cores] e' il
     * numero di processori (il tetto delle miniature in volo sta fra 2 e 4: il collo di bottiglia e' il MediaProvider,
     * non la CPU).
     */
    fun forMemoryClass(memoryClassMb: Int, lowRam: Boolean = false, cores: Int = 4): ImagingBudget {
      val mc = memoryClassMb.coerceIn(MIN_MEMORY_CLASS_MB, MAX_MEMORY_CLASS_MB)
      val cache = (mc * MB / 4).coerceIn(24 * MB, 96 * MB)
      val viewer = (mc * MB / 5).coerceIn(24 * MB, 64 * MB)
      return ImagingBudget(
        memoryClassMb = mc,
        thumbnailCacheBytes = if (lowRam) maxOf(cache / 2, 16 * MB) else cache,
        placeholderEntries = if (lowRam) PLACEHOLDER_ENTRIES / 2 else PLACEHOLDER_ENTRIES,
        thumbnailConcurrency = if (lowRam) 2 else cores.coerceIn(2, 4),
        viewerBitmapBytes = if (lowRam) maxOf(viewer / 2, 16 * MB) else viewer,
        viewerConcurrency = VIEWER_CONCURRENCY,
        rawMaxSide = RAW_MAX_SIDE,
      )
    }

    /** Il budget di questo dispositivo: `ActivityManager.getMemoryClass()` e `isLowRamDevice()`. */
    fun of(context: Context): ImagingBudget {
      val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      return forMemoryClass(
        memoryClassMb = manager.memoryClass,
        lowRam = manager.isLowRamDevice,
        cores = Runtime.getRuntime().availableProcessors(),
      )
    }

    private const val MIN_MEMORY_CLASS_MB = 48
    private const val MAX_MEMORY_CLASS_MB = 1024
  }
}
