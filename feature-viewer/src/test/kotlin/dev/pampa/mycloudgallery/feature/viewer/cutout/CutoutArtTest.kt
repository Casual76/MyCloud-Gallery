package dev.pampa.mycloudgallery.feature.viewer.cutout

import android.app.Application
import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Il disegno del soggetto (Fase 6, P13; D44, §2.10): la maschera a risoluzione di schermo, la distanza esatta dal soggetto, il contorno
 * dell'adesivo (lo stesso raggio del PNG, in proporzione al soggetto) e il bagliore.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class CutoutArtTest {

  private fun mask(w: Int, h: Int, inside: (Int, Int) -> Boolean) =
    ViewerMask(w, h, ByteArray(w * h) { if (inside(it % w, it / w)) 0xff.toByte() else 0 })

  @Test
  fun laMascheraSiRiduceConLaMediaSullAreaEUnBordoSfumatoRestaTale() {
    // 2048x1024: il lato lungo supera 1024, si dimezza.
    val big = mask(2048, 1024) { x, _ -> x < 1001 }
    val display = CutoutArtMath.display(big)
    assertThat(display.width).isEqualTo(1024)
    assertThat(display.height).isEqualTo(512)
    // I due pixel che lo coprono per intero sono 255; il blocco a cavallo del bordo (x = 1000..1001, solo il primo dentro) e' a meta'.
    assertThat(display.alpha[10 * 1024 + 100].toInt() and 0xff).isEqualTo(255)
    assertThat(display.alpha[10 * 1024 + 500].toInt() and 0xff).isEqualTo(127)
    assertThat(display.alpha[10 * 1024 + 700].toInt() and 0xff).isEqualTo(0)
    // Una maschera gia' piccola non si tocca.
    val small = mask(300, 200) { x, y -> x > y }
    assertThat(CutoutArtMath.display(small).alpha).isEqualTo(small.alpha())
  }

  @Test
  fun laDistanzaEsattaSiAccordaConIlCalcoloDiretto() {
    val random = Random(5)
    val w = 23
    val h = 17
    val pixels = BooleanArray(w * h) { random.nextInt(10) == 0 }
    pixels[8 * w + 11] = true
    val display = DisplayMask(w, h, ByteArray(w * h) { if (pixels[it]) 0xff.toByte() else 0 })
    val distance = CutoutArtMath.distances(display)
    for (y in 0 until h) {
      for (x in 0 until w) {
        var best = Float.MAX_VALUE
        for (yy in 0 until h) for (xx in 0 until w) if (pixels[yy * w + xx]) best = minOf(best, hypot((x - xx).toFloat(), (y - yy).toFloat()))
        assertThat(distance[y * w + x]).isWithin(1e-3f).of(best)
      }
    }
  }

  @Test
  fun ilContornoEIlBagliore() {
    val w = 100
    val h = 60
    val display = DisplayMask(w, h, mask(w, h) { x, y -> x in 40..59 && y in 20..39 }.alpha())
    val distance = CutoutArtMath.distances(display)
    val ring = CutoutArtMath.ring(distance, 4f)
    val halo = CutoutArtMath.halo(distance, 10f)
    fun at(bytes: ByteArray, x: Int, y: Int) = bytes[y * w + x].toInt() and 0xff
    // Dentro il soggetto e fino a 4 px il contorno e' pieno; oltre i 5 px e' vuoto.
    assertThat(at(ring, 50, 30)).isEqualTo(255)
    assertThat(at(ring, 62, 30)).isEqualTo(255)
    assertThat(at(ring, 70, 30)).isEqualTo(0)
    // Il bagliore e' pieno sul soggetto, scende con la distanza e a 10 px non c'e'.
    assertThat(at(halo, 50, 30)).isEqualTo(255)
    assertThat(at(halo, 62, 30)).isGreaterThan(at(halo, 66, 30))
    assertThat(at(halo, 70, 30)).isEqualTo(0)
  }

  @Test
  fun ilRaggioDelContornoCrescConIlSoggettoCome_IlPng() {
    val m = mask(2000, 1000) { _, _ -> true }
    val display = CutoutArtMath.display(m)
    // 2000 px sono due pixel per pixel di schermo (la riduzione e' per un fattore intero): la maschera di schermo e' 1000 px.
    assertThat(display.width).isEqualTo(1000)
    // Un soggetto largo la meta' dell'immagine (1000 px): 12 px di immagine, 6 di schermo.
    val radius = CutoutArtMath.outlineRadius(m, ViewerRect(0.25f, 0.2f, 0.75f, 0.6f), display)
    assertThat(radius).isWithin(0.01f).of(6f)
    // Un soggetto minuscolo: almeno 3 px di immagine, e mai meno di un pixel e mezzo di schermo.
    val tiny = CutoutArtMath.outlineRadius(m, ViewerRect(0.5f, 0.5f, 0.51f, 0.51f), display)
    assertThat(tiny).isWithin(0.01f).of(1.5f)
    assertThat(tiny).isAtLeast(1.5f)
    // Una maschera piccola non si riduce: i pixel di immagine sono quelli di schermo.
    val small = mask(500, 250) { _, _ -> true }
    assertThat(CutoutArtMath.outlineRadius(small, ViewerRect(0f, 0f, 1f, 1f), CutoutArtMath.display(small))).isWithin(0.01f).of(6f)
  }

  @Test
  fun leFigureSiCostruisconoEdRilasciano() {
    val subject = CutoutTestImage.subject(320, 180)
    val art = CutoutArt.build(subject)
    assertThat(art.width).isEqualTo(320)
    assertThat(art.height).isEqualTo(180)
    assertThat(art.mask.width).isEqualTo(320)
    assertThat(art.halo.height).isEqualTo(180)
    assertThat(art.outline.width).isEqualTo(320)
    art.release()
  }

  @Test
  fun ilRobotDiProvaHaUnaMascheraCoerenteConIlSuoRettangolo() {
    val subject = CutoutTestImage.subject()
    assertThat(subject.area).isGreaterThan(0.1f)
    assertThat(subject.contains(CutoutTestImage.BODY_X, CutoutTestImage.BODY_Y)).isTrue()
    assertThat(subject.contains(CutoutTestImage.GRASS_X, CutoutTestImage.GRASS_Y)).isFalse()
    // L'immagine e la maschera hanno lo stesso aspetto.
    val bitmap = CutoutTestImage.bitmap(160, 90)
    assertThat(bitmap.width.toFloat() / bitmap.height).isWithin(0.01f).of(subject.mask.width.toFloat() / subject.mask.height)
    assertThat(Bitmap.Config.ALPHA_8).isNotNull()
  }
}
