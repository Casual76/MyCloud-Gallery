package dev.pampa.mycloudgallery.feature.integrations.receive

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fase 5B, P11 (D24, C18, E-X2): da dove viene un flusso (guardando il solo indirizzo) e di che tipo e' (guardando i soli byte). Gli indirizzi sono
 * quelli che un'app ostile puo' costruire: `file://`, il nostro provider con l'utente davanti o le maiuscole, un nome che gli somiglia soltanto.
 */
@RunWith(RobolectricTestRunner::class)
class IncomingOriginsAndMimeTest {

  private val own = "dev.pampa.mycloudgallery"

  private fun origin(uri: String) = IncomingOrigins.classify(Uri.parse(uri), own)

  @Test
  fun soloUnContentDiUnAltraAppEAmmesso() {
    assertThat(origin("content://com.android.providers.media.documents/document/image%3A12")).isEqualTo(IncomingOrigin.CONTENT_OF_ANOTHER_APP)
    assertThat(origin("content://media/external/images/media/12")).isEqualTo(IncomingOrigin.CONTENT_OF_ANOTHER_APP)
    assertThat(origin("content://com.whatsapp.provider.media/item/1")).isEqualTo(IncomingOrigin.CONTENT_OF_ANOTHER_APP)
  }

  @Test
  fun fileEOgniAltroIndirizzoNonEUnFlusso() {
    for (uri in listOf(
      "file:///data/data/dev.pampa.mycloudgallery/databases/gallery.db",
      "file:///storage/emulated/0/DCIM/foto.jpg",
      "FILE:///etc/hosts",
      "http://example.com/foto.jpg",
      "https://example.com/foto.jpg",
      "android.resource://dev.pampa.mycloudgallery/raw/x",
      "intent:#Intent;end",
      "data:image/png;base64,AAAA",
      "/data/data/dev.pampa.mycloudgallery/files/x", // senza schema
      "content:///solo/un/percorso", // senza autorita'
    )) {
      assertWithMessage(uri).that(origin(uri)).isEqualTo(IncomingOrigin.FILE_SCHEME)
    }
  }

  @Test
  fun unProviderNostroSiRiconoscePerLHostAnchePiuMascherato() {
    for (uri in listOf(
      "content://dev.pampa.mycloudgallery.integrations.files/share/a/1/x.jpg",
      "content://dev.pampa.mycloudgallery.integrations.documents/document/abcd1234",
      "content://dev.pampa.mycloudgallery/qualcosa",
      "content://DEV.PAMPA.MyCloudGallery.Integrations.Files/share/x.jpg", // maiuscole
      "content://0@dev.pampa.mycloudgallery.integrations.files/share/x.jpg", // con l'utente davanti: lo stesso provider
      "content://10@dev.pampa.mycloudgallery.androidx-startup/x",
    )) {
      assertWithMessage(uri).that(origin(uri)).isEqualTo(IncomingOrigin.OWN_PROVIDER)
    }
  }

  @Test
  fun unNomeCheSoloSomigliaAlNostroNonELNostro() {
    for (uri in listOf(
      "content://dev.pampa.mycloudgalleryevil/x",
      "content://evil.dev.pampa.mycloudgallery/x",
      "content://dev.pampa.mycloudgallery-evil.provider/x",
      "content://dev.pampa/x",
    )) {
      assertWithMessage(uri).that(origin(uri)).isEqualTo(IncomingOrigin.CONTENT_OF_ANOTHER_APP)
    }
  }

  // --- Il tipo dai byte ------------------------------------------------------------------------------------------------------

  private fun typeOf(head: ByteArray, declared: String? = null): String? = IncomingMime.of(head, declared)

  @Test
  fun iFormatiDeiMediaSiRiconoscono() {
    assertThat(typeOf(ReceiveSamples.jpeg())).isEqualTo("image/jpeg")
    assertThat(typeOf(ReceiveSamples.png())).isEqualTo("image/png")
    assertThat(typeOf(ReceiveSamples.mp4())).isEqualTo("video/mp4")
    assertThat(typeOf(ReceiveSamples.mp4(brand = "qt  "))).isEqualTo("video/quicktime")
    assertThat(typeOf(ReceiveSamples.mp4(brand = "3gp4"))).isEqualTo("video/3gpp")
    assertThat(typeOf(ReceiveSamples.heic())).isEqualTo("image/heic")
    assertThat(typeOf(ReceiveSamples.mp4(brand = "mif1"))).isEqualTo("image/heif")
    assertThat(typeOf(ReceiveSamples.mp4(brand = "avif"))).isEqualTo("image/avif")
    assertThat(typeOf("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(40) { 0x90.toByte() })).isEqualTo("image/webp")
    assertThat(typeOf("GIF89a".toByteArray() + ByteArray(40) { 0x90.toByte() })).isEqualTo("image/gif")
    assertThat(typeOf(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(40) { 0x90.toByte() })).isEqualTo("video/x-matroska")
  }

  @Test
  fun ilTipoDichiaratoNonSiCredeMaServePerIRaw() {
    // JPEG dichiarato come video: vale il byte.
    assertThat(typeOf(ReceiveSamples.jpeg(), "video/mp4")).isEqualTo("image/jpeg")
    // Un RAW (intestazione TIFF) prende il suo tipo dichiarato, ma solo se e' un tipo RAW; un TIFF che dichiara "image/jpeg" resta TIFF.
    val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0x00) + ByteArray(60) { 0x90.toByte() }
    assertThat(typeOf(tiff, "image/x-adobe-dng")).isEqualTo("image/x-adobe-dng")
    assertThat(typeOf(tiff, "image/dng")).isEqualTo("image/dng")
    assertThat(typeOf(tiff, "image/jpeg")).isEqualTo("image/tiff")
    assertThat(typeOf(tiff, null)).isEqualTo("image/tiff")
  }

  @Test
  fun cioCheNonEUnImmagineOUnVideoNonEUnMedia() {
    assertThat(typeOf(ByteArray(0))).isNull()
    assertThat(typeOf(ByteArray(2_000))).isNull() // azzerato
    assertThat(typeOf("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".toByteArray(), "image/svg+xml")).isNull()
    assertThat(typeOf(ReceiveSamples.fakeJpegThatIsHtml(), "image/jpeg")).isNull()
    assertThat(typeOf("%PDF-1.7\n".toByteArray() + ByteArray(100) { 0xFF.toByte() }, "image/jpeg")).isNull()
    assertThat(typeOf(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(100) { 0x90.toByte() }, "video/mp4")).isNull()
    // Audio dentro un contenitore MP4: non e' un video.
    assertThat(typeOf(ReceiveSamples.mp4(brand = "M4A "))).isNull()
    // Un tipo dichiarato senza firma precisa, su byte sconosciuti, non basta se non e' immagine o video.
    assertThat(typeOf(ByteArray(100) { (it * 7 + 0x90).toByte() }, "application/octet-stream")).isNull()
  }
}
