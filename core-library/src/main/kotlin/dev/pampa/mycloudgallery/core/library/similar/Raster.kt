package dev.pampa.mycloudgallery.core.library.similar

// Fase 5A, P15 (docs/fase5-progetto.md §2.12): l'immagine come la vede la firma visiva. Puro Kotlin: la logica "pixel -> firma" e il
// confronto si provano sulla JVM, senza Android; la decodifica (ImageDecoder, miniature dell'indice, fotogrammi dei video) sta in
// `BitmapRasters.kt` e nelle porte di `SignatureSources.kt`.

/**
 * Un'immagine **dritta** (l'orientamento e' gia' applicato) a 8 bit per canale: un intero `0xRRGGBB` per pixel, righe dall'alto.
 * L'alfa non conta (una foto non ne ha). Non porta mai nome, percorso o id: nei log compaiono solo le misure.
 */
class Raster(val width: Int, val height: Int, private val rgb: IntArray) {

  init {
    require(width > 0 && height > 0) { "Immagine senza pixel." }
    require(width.toLong() * height <= MAX_PIXELS) { "Immagine oltre il tetto di pixel." }
    require(rgb.size == width * height) { "I pixel non corrispondono alle misure." }
  }

  /** Il pixel `0xRRGGBB` alla colonna [x], riga [y]. */
  fun at(x: Int, y: Int): Int = rgb[y * width + x]

  /** Il pixel in posizione lineare [index] (riga per riga). */
  internal fun pixel(index: Int): Int = rgb[index]

  override fun toString(): String = "Raster(${width}x$height)"

  companion object {
    /**
     * Il tetto dei pixel che la firma accetta (32 MP): le sorgenti vere sono gia' ridotte (originali del telefono decodificati a
     * circa 256 px, miniature dell'indice a 200 o 400 px); il tetto difende dalla sorgente che sbaglia la taglia.
     */
    const val MAX_PIXELS: Long = 32L * 1024 * 1024

    /** Un'immagine dai suoi pixel `0xAARRGGBB` o `0xRRGGBB` (l'alfa si ignora). */
    fun of(width: Int, height: Int, argb: IntArray): Raster = Raster(width, height, IntArray(argb.size) { argb[it] and 0xFFFFFF })
  }
}
