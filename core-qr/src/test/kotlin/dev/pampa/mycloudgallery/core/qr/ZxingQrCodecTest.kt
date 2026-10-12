package dev.pampa.mycloudgallery.core.qr

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.qr.QrRaster.blurred
import dev.pampa.mycloudgallery.core.qr.QrRaster.noisy
import dev.pampa.mycloudgallery.core.qr.QrRaster.padded
import dev.pampa.mycloudgallery.core.qr.QrRaster.rotate180
import dev.pampa.mycloudgallery.core.qr.QrRaster.rotate270
import dev.pampa.mycloudgallery.core.qr.QrRaster.rotate90
import dev.pampa.mycloudgallery.core.qr.QrRaster.rotateBy
import dev.pampa.mycloudgallery.core.qr.QrRaster.scaled
import dev.pampa.mycloudgallery.core.qr.QrRaster.unevenLight
import dev.pampa.mycloudgallery.core.qr.codec.QrRead
import dev.pampa.mycloudgallery.core.qr.codec.ZxingQrCodec
import java.util.Base64
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La prova "andata e ritorno" su JVM, senza fotocamera: testo → matrice → immagine → (rotazione, scala,
 * rumore, sfocatura, luce storta) → testo. Cosi' lo scanner si prova prima di tenere in mano un telefono.
 */
class ZxingQrCodecTest {

  private val codec = ZxingQrCodec

  // --- testi di prova ------------------------------------------------------------------------------------

  /** Un invito verosimile: prefisso e base64url. */
  private val invite: String = "MCG1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(Random(1).nextBytes(180))

  /** Il caso limite: 600 caratteri di base64url (il tetto). */
  private val max600: String = Base64.getUrlEncoder().withoutPadding().encodeToString(Random(2).nextBytes(450)).take(600)

  /** Il peggiore per densita': 600 caratteri ASCII stampabili a caso, tutti in modalita' byte. */
  private val dense600: String = Random(3).let { random -> String(CharArray(600) { (33 + random.nextInt(94)).toChar() }) }

  private val accented = "Perché è già così: àèìòù € ñ ü ß 日本語"

  private fun roundTrip(text: String, image: Luma): String? = codec.decode(image.data, image.width, image.height)

  private fun assertReads(text: String, image: Luma, what: String) {
    assertWithMessage("$what (${text.length} caratteri, ${image.width}x${image.height})")
      .that(roundTrip(text, image))
      .isEqualTo(text)
  }

  // --- la matrice ---------------------------------------------------------------------------------------

  @Test
  fun laMatriceHaUnaVersioneValida_eIRiquadriDAngolo() {
    for (text in listOf("A", invite, max600, accented)) {
      val bits = codec.encode(text)
      assertThat(bits.size).isAtLeast(21)
      assertThat(bits.size).isAtMost(177)
      assertThat((bits.size - 17) % 4).isEqualTo(0) // 21 + 4 * (versione - 1)
      // il riquadro di posizione: 7x7 con anello scuro e centro 3x3 scuro, a tre angoli
      for ((ox, oy) in listOf(0 to 0, bits.size - 7 to 0, 0 to bits.size - 7)) {
        for (i in 0 until 7) {
          assertThat(bits[ox + i, oy]).isTrue()
          assertThat(bits[ox + i, oy + 6]).isTrue()
          assertThat(bits[ox, oy + i]).isTrue()
          assertThat(bits[ox + 6, oy + i]).isTrue()
        }
        assertThat(bits[ox + 3, oy + 3]).isTrue()
        assertThat(bits[ox + 1, oy + 1]).isFalse()
      }
    }
  }

  @Test
  fun lAscii_vaSenzaIntestazioneDiCodifica_eLAccentataInUtf8() {
    // lo stesso testo, due volte, da' la stessa matrice (deterministica)
    assertThat(codec.encode(invite)).isEqualTo(codec.encode(invite))
    // un testo con accenti si legge uguale: la codifica viaggia con il QR
    val image = QrRaster.renderWithModulePx(codec.encode(accented), 6)
    assertThat(codec.decode(image.data, image.width, image.height)).isEqualTo(accented)
  }

  // --- tetti --------------------------------------------------------------------------------------------

  @Test
  fun seicentoCaratteri_siCodificanoESiLeggono() {
    assertThat(max600).hasLength(600)
    assertThat(dense600).hasLength(600)
    for (text in listOf(max600, dense600)) {
      assertReads(text, QrRaster.renderWithModulePx(codec.encode(text), 4), "600 caratteri, 4 px per modulo")
      assertReads(text, QrRaster.renderWithModulePx(codec.encode(text), 8), "600 caratteri, 8 px per modulo")
    }
  }

  @Test
  fun seicentoCaratteriAccentati_stannoNelTetto() {
    val text = "è".repeat(QrLimits.MAX_CHARS)
    assertReads(text, QrRaster.renderWithModulePx(codec.encode(text), 5), "600 caratteri a due byte")
  }

  @Test
  fun oltreIlTetto_siRifiuta_enonSiTronca() {
    assertThrows(IllegalArgumentException::class.java) { codec.encode("x".repeat(QrLimits.MAX_CHARS + 1)) }
    assertThrows(IllegalArgumentException::class.java) { codec.encode("") }
  }

  @Test
  fun unQrOltreIlTetto_siLeggeMaNonSiConsegna() {
    // fabbricato con ZXing direttamente: il nostro codec non lo produrrebbe
    val longText = "y".repeat(QrLimits.MAX_CHARS + 25)
    val matrix = com.google.zxing.qrcode.encoder.Encoder.encode(
      longText,
      com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
      null,
    ).matrix
    val size = matrix.width
    val bits = dev.pampa.mycloudgallery.core.qr.contract.QrBits(
      size,
      BooleanArray(size * size) { matrix.get(it % size, it / size).toInt() == 1 },
    )
    val image = QrRaster.renderWithModulePx(bits, 5)
    assertThat(codec.decode(image.data, image.width, image.height)).isNull()
    assertThat(ZxingQrCodec.read(image.data, image.width, image.height)).isEqualTo(QrRead.TooLong)
  }

  // --- andata e ritorno: rotazione ---------------------------------------------------------------------

  @Test
  fun rotazioniDiNovantaGradi() {
    for (text in listOf("A", invite, max600, accented)) {
      val image = QrRaster.renderWithModulePx(codec.encode(text), 5)
      assertReads(text, image, "0 gradi")
      assertReads(text, image.rotate90(), "90 gradi")
      assertReads(text, image.rotate180(), "180 gradi")
      assertReads(text, image.rotate270(), "270 gradi")
    }
  }

  @Test
  fun rotazioniQualunque_comeUnTelefonoInclinato() {
    val image = QrRaster.renderWithModulePx(codec.encode(invite), 6)
    for (degrees in listOf(8.0, 15.0, 30.0, 45.0, 60.0, 100.0, 200.0, 315.0)) {
      assertReads(invite, image.rotateBy(degrees), "$degrees gradi")
    }
  }

  // --- andata e ritorno: scala -------------------------------------------------------------------------

  @Test
  fun moduliDiMisureDiverse() {
    for (modulePx in listOf(2, 3, 4, 6, 10, 16)) {
      assertReads("MCG1:ABCDEFGHIJ", QrRaster.renderWithModulePx(codec.encode("MCG1:ABCDEFGHIJ"), modulePx), "$modulePx px/modulo")
    }
    for (modulePx in listOf(3, 4, 6, 10)) {
      assertReads(max600, QrRaster.renderWithModulePx(codec.encode(max600), modulePx), "600 caratteri, $modulePx px/modulo")
    }
  }

  @Test
  fun scaleNonIntere_ingrandimentoERiduzione() {
    val image = QrRaster.renderWithModulePx(codec.encode(invite), 8)
    for (factor in listOf(0.45, 0.6, 0.77, 1.0, 1.3, 1.9, 2.4)) {
      assertReads(invite, image.scaled(factor), "scala $factor")
    }
    val dense = QrRaster.renderWithModulePx(codec.encode(max600), 8)
    for (factor in listOf(0.55, 0.8, 1.35)) {
      assertReads(max600, dense.scaled(factor), "600 caratteri, scala $factor")
    }
  }

  @Test
  fun ilQrNonRiempieIlFotogramma() {
    val image = QrRaster.renderWithModulePx(codec.encode(invite), 5).padded(border = 120)
    assertReads(invite, image, "QR piccolo in un fotogramma grande")
    assertReads(invite, image.rotate90(), "idem, ruotato")
  }

  // --- andata e ritorno: rumore e difetti del sensore -------------------------------------------------

  /**
   * Lo scanner guarda molti fotogrammi al secondo e basta che ne legga uno: con il rumore si misura quanti
   * fotogrammi su [frames] (stesso QR, rumore diverso, semi fissi) si leggono, non se si legge il primo.
   */
  private fun readRate(text: String, image: Luma, frames: Int = 10, noise: (Luma, Int) -> Luma): Int =
    (1..frames).count { seed ->
      val frame = noise(image, seed)
      codec.decode(frame.data, frame.width, frame.height) == text
    }

  @Test
  fun rumoreGaussiano_sensoreScadente() {
    for ((text, modulePx) in listOf(invite to 6, max600 to 6, max600 to 4)) {
      val image = QrRaster.renderWithModulePx(codec.encode(text), modulePx)
      for (sigma in listOf(10.0, 20.0)) {
        val rate = readRate(text, image) { img, seed -> img.noisy(sigma, seed = seed) }
        assertWithMessage("${text.length} caratteri, $modulePx px/modulo, rumore sigma $sigma: $rate/10")
          .that(rate).isAtLeast(9)
      }
      val heavy = readRate(text, image) { img, seed -> img.noisy(sigma = 25.0, seed = seed) }
      assertWithMessage("${text.length} caratteri, $modulePx px/modulo, rumore sigma 25: $heavy/10")
        .that(heavy).isAtLeast(7)
    }
  }

  @Test
  fun pixelGuasti_unFotogrammoSuDueSiLegge() {
    // Pixel bianchi o neri a caso (0,1% e 0,3%): ZXing cerca i riquadri d'angolo su righe di pixel e un
    // pixel guasto nel punto sbagliato lo fa fallire, quindi non tutti i fotogrammi si leggono. Ma ne
    // bastano pochi dei dieci che arrivano in un secondo.
    for (text in listOf(invite, max600)) {
      val image = QrRaster.renderWithModulePx(codec.encode(text), 6)
      for (density in listOf(0.001, 0.003)) {
        val rate = readRate(text, image) { img, seed -> img.noisy(sigma = 8.0, saltPepper = density, seed = seed) }
        assertWithMessage("${text.length} caratteri, pixel guasti $density: $rate/10").that(rate).isAtLeast(5)
      }
    }
  }

  @Test
  fun seicentoCaratteriConRumoreERotazione() {
    val image = QrRaster.renderWithModulePx(codec.encode(max600), 6)
    assertReads(max600, image.noisy(sigma = 20.0, seed = 5), "600 caratteri con rumore")
    assertReads(max600, image.rotate90().noisy(sigma = 20.0, seed = 6), "600 caratteri ruotato di 90 con rumore")
    assertReads(max600, image.rotateBy(20.0).noisy(sigma = 15.0, seed = 7), "600 caratteri a 20 gradi con rumore")
  }

  @Test
  fun fuoriFuoco() {
    val image = QrRaster.renderWithModulePx(codec.encode(invite), 8)
    assertReads(invite, image.blurred(radius = 2), "sfocatura 2 px")
    assertReads(invite, image.scaled(0.6).blurred(radius = 1).noisy(sigma = 12.0), "ridotto, sfocato e rumoroso")
  }

  @Test
  fun luceStorta_eContrastoBasso() {
    val image = QrRaster.renderWithModulePx(codec.encode(invite), 6)
    assertReads(invite, image.unevenLight(darkest = 60, lightest = 200, gradient = 0), "contrasto basso")
    assertReads(invite, image.unevenLight(darkest = 20, lightest = 255, gradient = 90), "gradiente di luce")
    assertReads(invite, image.unevenLight(darkest = 50, lightest = 210, gradient = 60).noisy(sigma = 12.0), "gradiente e rumore")
  }

  @Test
  fun unQrChiaroSuScuro_nonSiLegge_perScelta() {
    // Le nostre schermate disegnano sempre scuro su chiaro; uno scanner che leggesse anche l'inverso
    // raddoppierebbe il lavoro a ogni fotogramma per un caso che non produciamo.
    val image = QrRaster.renderWithModulePx(codec.encode(invite), 6, dark = 255, light = 0)
    assertThat(codec.decode(image.data, image.width, image.height)).isNull()
  }

  // --- ingressi che non sono un QR ----------------------------------------------------------------------

  @Test
  fun inputCheNonESonoUnQr_nonFannoCrashEDannoNull() {
    val random = Random(99)
    val noise = ByteArray(300 * 300).also { random.nextBytes(it) }
    val stripes = ByteArray(200 * 100) { i -> if ((i % 200) / 4 % 2 == 0) 0 else -1 } // un codice a barre finto
    val cases: List<Triple<ByteArray, Int, Int>> = listOf(
      Triple(ByteArray(0), 0, 0),
      Triple(ByteArray(0), 10, 10),
      Triple(ByteArray(100), 0, 100),
      Triple(ByteArray(100), 100, 0),
      Triple(ByteArray(100), -5, 20),
      Triple(ByteArray(100), 20, -5),
      Triple(ByteArray(50), 10, 10), // array piu' corto di larghezza x altezza
      Triple(ByteArray(1), 1, 1),
      Triple(ByteArray(4), 2, 2),
      Triple(ByteArray(300 * 300), 300, 300), // tutto nero
      Triple(ByteArray(300 * 300) { -1 }, 300, 300), // tutto bianco
      Triple(ByteArray(300 * 300) { if (it % 2 == 0) 0 else -1 }, 300, 300), // scacchiera fine
      Triple(noise, 300, 300),
      Triple(stripes, 200, 100),
      Triple(ByteArray(40 * 40) { -1 }, 40, 40),
      Triple(ByteArray(10 * 4000) { (it % 13).toByte() }, 10, 4000), // striscia sottile
      Triple(ByteArray(10), Int.MAX_VALUE, Int.MAX_VALUE), // dimensioni che farebbero traboccare il prodotto
    )
    for ((data, w, h) in cases) {
      assertThat(codec.decode(data, w, h)).isNull()
      assertThat(ZxingQrCodec.read(data, w, h)).isEqualTo(QrRead.None)
    }
  }

  @Test
  fun unQrRovinato_oltreLaCorrezioneDErrore_danNull() {
    val image = QrRaster.renderWithModulePx(codec.encode(invite), 6)
    // si cancella un terzo del QR: oltre il 15% che M puo' recuperare
    val broken = image.data.copyOf()
    for (y in image.height / 3 until image.height * 2 / 3) {
      for (x in 0 until image.width) broken[y * image.width + x] = if ((x / 6 + y / 6) % 2 == 0) 0 else -1
    }
    assertThat(codec.decode(broken, image.width, image.height)).isNull()
  }

  @Test
  fun lImmagineDiUnaDimensioneIncoerente_conUnArrayPiuLungo_siLeggeLaPrimaParte() {
    // un buffer piu' grande del necessario (CameraX riusa i buffer): conta solo larghezza x altezza
    val image = QrRaster.renderWithModulePx(codec.encode(invite), 5)
    val bigger = image.data.copyOf(image.data.size + 1000)
    assertThat(codec.decode(bigger, image.width, image.height)).isEqualTo(invite)
  }
}
