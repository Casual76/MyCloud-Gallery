package dev.pampa.mycloudgallery.core.nasmedia

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasmedia.detect.ExifThumbnail
import org.junit.Test

/** D17: la miniatura EXIF letta al rilevamento. Lettura difensiva: un dato incoerente vale "nessuna miniatura". */
class ExifThumbnailTest {

  private val thumb = Jpegs.thumbnail(160, 120, padding = 4_000)

  @Test
  fun trovaLaMiniaturaInBigEndianELittleEndian() {
    for (little in listOf(false, true)) {
      val jpeg = Jpegs.withExif(4000, 3000, thumb, little = little)
      val found = ExifThumbnail.find(jpeg.copyOf(65_536))
      assertThat(found).isEqualTo(thumb)
      assertThat(ExifThumbnail.jpegSize(found!!)).isEqualTo(160 to 120)
    }
  }

  @Test
  fun senzaIfd1ONonJpegNonCeMiniatura() {
    assertThat(ExifThumbnail.find(Jpegs.withExif(4000, 3000, thumbnail = null))).isNull()
    assertThat(ExifThumbnail.find(ByteArray(1000) { 7 })).isNull()
    assertThat(ExifThumbnail.find(ByteArray(0))).isNull()
    // Compressione non JPEG (1 = non compressa).
    assertThat(ExifThumbnail.find(Jpegs.withExif(4000, 3000, thumb, compression = 1))).isNull()
  }

  @Test
  fun unaMiniaturaTroppoGrandeNonEUnaMiniatura() {
    val big = Jpegs.thumbnail(160, 120, padding = ExifThumbnail.MAX_BYTES + 10)
    assertThat(ExifThumbnail.find(Jpegs.withExif(4000, 3000, big))).isNull()
  }

  @Test
  fun unaMiniaturaOltreLaFineDeiByteLettiNonSiInventa() {
    // La testa e' tagliata nel mezzo della miniatura: il file dichiara piu' byte di quelli che abbiamo.
    val jpeg = Jpegs.withExif(4000, 3000, thumb)
    val cut = jpeg.copyOf(150)
    assertThat(ExifThumbnail.find(cut)).isNull()
  }

  @Test
  fun offsetELunghezzaOstiliNonFannoCrashare() {
    val jpeg = Jpegs.withExif(4000, 3000, thumb)
    // Si sporcano uno per volta tutti i byte dell'APP1: nessuna eccezione, mai una miniatura fuori dai limiti.
    val app1End = 4 + 2 + jpeg.u16(4)
    for (i in 4 until minOf(app1End + 10, jpeg.size)) {
      for (value in listOf(0x00, 0xFF, 0x7F, 0x80)) {
        val copy = jpeg.copyOf()
        copy[i] = value.toByte()
        val found = ExifThumbnail.find(copy)
        if (found != null) {
          assertThat(found.size).isAtMost(ExifThumbnail.MAX_BYTES)
          assertThat(found[0]).isEqualTo(0xFF.toByte())
          assertThat(found[1]).isEqualTo(0xD8.toByte())
        }
      }
    }
  }

  @Test
  fun unNumeroEnormeDiVociNonAlloca() {
    val jpeg = Jpegs.withExif(4000, 3000, thumb)
    // Il conteggio delle voci dell'IFD0 (a 2 byte dall'inizio del TIFF) portato al massimo.
    val tiff = 2 + 2 + 2 + 6
    val copy = jpeg.copyOf()
    copy[tiff + 8] = 0xFF.toByte()
    copy[tiff + 9] = 0xFF.toByte()
    assertThat(ExifThumbnail.find(copy)).isNull()
  }

  @Test
  fun leDimensioniEnormiNonSonoUnaMiniatura() {
    assertThat(ExifThumbnail.jpegSize(Jpegs.thumbnail(8_000, 6_000))).isNull()
    assertThat(ExifThumbnail.jpegSize(Jpegs.thumbnail(160, 120))).isEqualTo(160 to 120)
    assertThat(ExifThumbnail.jpegSize(byteArrayOf(1, 2, 3))).isNull()
  }

  @Test
  fun conUnOrientamentoDiversoDaUnoLaMiniaturaPortaIlSuoEXIF() {
    val rotated = ExifThumbnail.withOrientation(thumb, 6)
    assertThat(rotated.size).isEqualTo(thumb.size + 36)
    assertThat(rotated.copyOfRange(0, 2)).isEqualTo(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    // L'APP1 sta subito dopo l'SOI e dice 6.
    assertThat(rotated[2]).isEqualTo(0xFF.toByte())
    assertThat(rotated[3]).isEqualTo(0xE1.toByte())
    assertThat(rotated.copyOfRange(6, 12)).isEqualTo("Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1))
    assertThat(rotated[2 + 4 + 6 + 8 + 2 + 8 + 1]).isEqualTo(6.toByte())
    // Il resto e' la miniatura com'era.
    assertThat(rotated.copyOfRange(2 + 36, rotated.size)).isEqualTo(thumb.copyOfRange(2, thumb.size))
    // Le dimensioni si leggono ancora.
    assertThat(ExifThumbnail.jpegSize(rotated)).isEqualTo(160 to 120)
    // Idempotente: una miniatura che ha gia' un EXIF non se ne vede aggiungere un altro.
    assertThat(ExifThumbnail.withOrientation(rotated, 3)).isEqualTo(rotated)
  }

  @Test
  fun conOrientamentoNormaleOFuoriNormaLaMiniaturaNonCambia() {
    assertThat(ExifThumbnail.withOrientation(thumb, 1)).isEqualTo(thumb)
    assertThat(ExifThumbnail.withOrientation(thumb, 0)).isEqualTo(thumb)
    assertThat(ExifThumbnail.withOrientation(thumb, 9)).isEqualTo(thumb)
  }

  private fun ByteArray.u16(i: Int): Int = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)
}
