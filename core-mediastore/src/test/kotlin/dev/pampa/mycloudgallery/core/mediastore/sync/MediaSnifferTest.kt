package dev.pampa.mycloudgallery.core.mediastore.sync

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaContent
import org.junit.Test

/** E-M12: le firme dei media che il telefono contiene passano; file azzerati, testo e archivi no. */
class MediaSnifferTest {

  private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

  private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

  @Test
  fun leFirmeDiImmaginiEVideoPassano() {
    val media = listOf(
      FakeMediaContent.jpeg(64, seed = 1),
      FakeMediaContent.mp4(64, seed = 1),
      bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0),
      ascii("GIF89a"),
      ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 "),
      bytes(0x49, 0x49, 0x2A, 0x00, 8, 0, 0, 0), // TIFF / DNG
      ascii("\u0000\u0000\u0000\u0018ftypheic"),
      ascii("\u0000\u0000\u0000\u0018ftypavif"),
      ascii("\u0000\u0000\u0000\u0008wide\u0000\u0000\u0000\u0000mdat"),
      bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01), // WebM / MKV
      ascii("FUJIFILMCCD-RAW 0201"),
    )
    media.forEach { assertThat(MediaSniffer.looksLikeMedia(it, null)).isTrue() }
  }

  @Test
  fun fileAzzeratiTestoEArchiviNo() {
    assertThat(MediaSniffer.looksLikeMedia(ByteArray(0), "image/jpeg")).isFalse()
    assertThat(MediaSniffer.looksLikeMedia(ByteArray(4_096), "image/jpeg")).isFalse()
    assertThat(MediaSniffer.looksLikeMedia(ByteArray(512) { 0x7F.toByte() }, null)).isFalse()
    assertThat(MediaSniffer.looksLikeMedia(ascii("<!DOCTYPE html><html>"), "image/jpeg")).isFalse()
    assertThat(MediaSniffer.looksLikeMedia(ascii("{\"errore\": 404}"), null)).isFalse()
    assertThat(MediaSniffer.looksLikeMedia(ascii("<?xml version=\"1.0\"?><svg/>"), "image/svg+xml")).isFalse()
    assertThat(MediaSniffer.looksLikeMedia(ascii("%PDF-1.7"), "image/jpeg")).isFalse()
    assertThat(MediaSniffer.looksLikeMedia(bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00), null)).isFalse()
  }

  @Test
  fun unMimeConFirmaPrecisaEByteSbagliatiNo() {
    val binary = ByteArray(256) { (it * 37 + 11).toByte() }.also { it[0] = 0x13 }
    assertThat(MediaSniffer.looksLikeMedia(binary, "image/jpeg")).isFalse()
    assertThat(MediaSniffer.looksLikeMedia(binary, "video/mp4")).isFalse()
    // Formato sconosciuto senza firma precisa: decide META.
    assertThat(MediaSniffer.looksLikeMedia(binary, "image/x-sconosciuto")).isTrue()
    assertThat(MediaSniffer.looksLikeMedia(binary, null)).isTrue()
  }
}
