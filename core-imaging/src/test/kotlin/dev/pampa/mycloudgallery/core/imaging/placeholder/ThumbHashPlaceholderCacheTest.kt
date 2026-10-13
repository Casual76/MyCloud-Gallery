package dev.pampa.mycloudgallery.core.imaging.placeholder

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.ThumbHash
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** I segnaposto sfocati (D5): proporzione dal ThumbHash, cache a voci limitate, mai un'eccezione verso la cella. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbHashPlaceholderCacheTest {

  /**
   * Un ThumbHash "fatto a mano" con la proporzione voluta: i primi 5 byte dell'algoritmo pubblico portano il numero
   * di componenti per lato (landscape: lx = 7, ly = [short]; verticale: lx = [short], ly = 7).
   */
  private fun hash(landscape: Boolean, short: Int, filler: Int = 0): ThumbHash {
    val header16 = (if (landscape) 1 shl 15 else 0) or short
    val bytes = byteArrayOf(0x20, 0x20, 0x20, (header16 and 0xFF).toByte(), (header16 shr 8).toByte(), filler.toByte(), 1, 2, 3, 4)
    return ThumbHash.of(bytes)
  }

  private val gray = ThumbHashDecoder { _, side -> IntArray(side * side) { 0xFF808080.toInt() } }

  @Test
  fun laProporzioneDelThumbHashDiventaLaGeometriaDellaBitmap() {
    // Landscape 7:4 -> 32 x 18.
    assertThat(ThumbHashGeometry.sizeOf(hash(landscape = true, short = 4), 32)).isEqualTo(32 to 18)
    // Verticale 3:7 -> 14 x 32.
    assertThat(ThumbHashGeometry.sizeOf(hash(landscape = false, short = 3), 32)).isEqualTo(14 to 32)
    // Un hash molto corto o neutro: quadrato.
    assertThat(ThumbHashGeometry.sizeOf(ThumbHash.of(byteArrayOf(1, 2, 3, 4)), 32)).isEqualTo(32 to 32)
    // Quadrato di fatto: 7:7.
    assertThat(ThumbHashGeometry.sizeOf(hash(landscape = true, short = 7), 32)).isEqualTo(32 to 32)
  }

  @Test
  fun lAltezzaEstremaNonDaMaiZero() {
    val panorama = hash(landscape = true, short = 0) // ly = max(3, 0) = 3 -> 7:3
    val (w, h) = ThumbHashGeometry.sizeOf(panorama, 32)
    assertThat(w).isEqualTo(32)
    assertThat(h).isAtLeast(1)
  }

  @Test
  fun lAnteprimaHaLaProporzioneEIPixelDelDecodificatore() {
    val calls = AtomicInteger()
    val cache = ThumbHashPlaceholderCache({ h, side ->
      calls.incrementAndGet()
      val (w, hgt) = ThumbHashGeometry.sizeOf(h, side)
      IntArray(w * hgt) { 0xFF336699.toInt() }
    })
    val image = cache.image(hash(landscape = true, short = 4))
    assertThat(image.width).isEqualTo(32)
    assertThat(image.height).isEqualTo(18)
    assertThat(image.asAndroidBitmap().getPixel(3, 3)).isEqualTo(0xFF336699.toInt())
    assertThat(calls.get()).isEqualTo(1)
  }

  @Test
  fun lostessoThumbHashDaLaStessaImmagineSenzaRifareLaDecodifica() {
    val calls = AtomicInteger()
    val cache = ThumbHashPlaceholderCache({ _, side -> calls.incrementAndGet(); IntArray(side * side) })
    val h = hash(landscape = true, short = 7)
    assertThat(cache.image(h)).isSameInstanceAs(cache.image(h))
    assertThat(calls.get()).isEqualTo(1)
  }

  @Test
  fun leVociSonoLimitateConPoliticaLru() {
    val calls = AtomicInteger()
    val cache = ThumbHashPlaceholderCache({ _, side -> calls.incrementAndGet(); IntArray(side * side) }, maxEntries = 3)
    val hashes = (0 until 5).map { hash(landscape = true, short = 7, filler = it) }
    val first = cache.image(hashes[0])
    cache.image(hashes[1])
    cache.image(hashes[2])
    // Si usa la 0: la meno usata diventa la 1.
    cache.image(hashes[0])
    cache.image(hashes[3])
    assertThat(cache.size).isEqualTo(3)
    assertThat(cache.image(hashes[0])).isSameInstanceAs(first)
    val before = calls.get()
    cache.image(hashes[1]) // era uscita: si ridecodifica
    assertThat(calls.get()).isEqualTo(before + 1)
  }

  @Test
  fun unDecodificatoreCheSbagliaDaUnGrigioENonUnaEccezione() {
    val throwing = ThumbHashPlaceholderCache({ _, _ -> error("hash illeggibile") })
    val neutral: ImageBitmap = throwing.image(hash(landscape = false, short = 3))
    assertThat(neutral.width).isEqualTo(14)
    assertThat(neutral.asAndroidBitmap().getPixel(0, 0)).isEqualTo(0xFF808080.toInt())

    // Un numero di pixel che non torna: grigio con la geometria dell'hash.
    val wrongSize = ThumbHashPlaceholderCache({ _, _ -> IntArray(7) })
    assertThat(wrongSize.image(hash(landscape = true, short = 4)).asAndroidBitmap().getPixel(1, 1)).isEqualTo(0xFF808080.toInt())
  }

  @Test
  fun unDecodificatoreCheRestituisceSempreUnQuadratoSiUsaCosiCom_e() {
    val cache = ThumbHashPlaceholderCache(gray)
    val image = cache.image(hash(landscape = true, short = 4))
    assertThat(image.width).isEqualTo(32)
    assertThat(image.height).isEqualTo(32)
  }
}
