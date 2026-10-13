package dev.pampa.mycloudgallery.core.library.text

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * I conteggi della galleria hanno un solo formato, quello italiano ("92.019 foto"), **anche con il telefono in inglese**
 * (la lingua di default della JVM qui), in tedesco o in francese: [GalleryNumbers] non guarda `Locale.getDefault()`.
 */
class GalleryNumbersTest {

  private lateinit var previous: Locale

  @Before
  fun englishDevice() {
    previous = Locale.getDefault()
    Locale.setDefault(Locale.US)
  }

  @After
  fun restore() {
    Locale.setDefault(previous)
  }

  @Test
  fun sottoMilleNonCeSeparatore() {
    assertThat(GalleryNumbers.format(0)).isEqualTo("0")
    assertThat(GalleryNumbers.format(1)).isEqualTo("1")
    assertThat(GalleryNumbers.format(999)).isEqualTo("999")
  }

  @Test
  fun daMilleInSuIlSeparatoreEIlPunto() {
    assertThat(GalleryNumbers.format(1_000)).isEqualTo("1.000")
    assertThat(GalleryNumbers.format(7_981)).isEqualTo("7.981")
    assertThat(GalleryNumbers.format(92_019)).isEqualTo("92.019")
    assertThat(GalleryNumbers.format(100_000)).isEqualTo("100.000")
    assertThat(GalleryNumbers.format(1_234_567)).isEqualTo("1.234.567")
    assertThat(GalleryNumbers.format(Int.MAX_VALUE)).isEqualTo("2.147.483.647")
    assertThat(GalleryNumbers.format(Long.MAX_VALUE)).isEqualTo("9.223.372.036.854.775.807")
  }

  @Test
  fun iNegativiNonPerdonoIlSegnoNeLaPrimaCifra() {
    assertThat(GalleryNumbers.format(-5)).isEqualTo("-5")
    assertThat(GalleryNumbers.format(-999)).isEqualTo("-999")
    assertThat(GalleryNumbers.format(-1_000)).isEqualTo("-1.000")
    assertThat(GalleryNumbers.format(-92_019)).isEqualTo("-92.019")
    assertThat(GalleryNumbers.format(Long.MIN_VALUE)).isEqualTo("-9.223.372.036.854.775.808")
  }

  @Test
  fun ilRisultatoNonDipendeDallaLinguaDelTelefono() {
    val expected = "92.019"
    for (locale in listOf(Locale.US, Locale.ITALY, Locale.GERMANY, Locale.FRANCE, Locale.ROOT, Locale.forLanguageTag("ar-EG"))) {
      Locale.setDefault(locale)
      assertThat(GalleryNumbers.format(92_019)).isEqualTo(expected)
    }
  }
}
