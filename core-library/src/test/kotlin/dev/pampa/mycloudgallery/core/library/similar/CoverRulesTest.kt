package dev.pampa.mycloudgallery.core.library.similar

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import org.junit.Test

/** La copertina del §2.12 (Fase 5A, P15), regola per regola, nell'ordine: pixel, compressione, originale, peso, identita'. */
class CoverRulesTest {

  private fun id(n: Int) = MediaId("00000000-0000-4000-8000-%012d".format(n))

  private fun photo(
    n: Int,
    width: Int = 4000,
    height: Int = 3000,
    bytes: Long = 3_000_000,
    mime: String = "image/jpeg",
    original: Boolean = false,
  ) = CoverFacts(id(n), width.toLong() * height, bytes, CoverRules.formatOf(mime), bitrate = null, cameraOriginal = original, isVideo = false)

  private fun video(n: Int, width: Int = 1920, height: Int = 1080, bytes: Long = 50_000_000, bitrate: Long? = 8_000_000, original: Boolean = false) =
    CoverFacts(id(n), width.toLong() * height, bytes, "video/mp4", bitrate, original, isVideo = true)

  @Test
  fun laRegola1PiuPixelVinceSuTutto() {
    // Il cloud ha ridotto la foto ma ha un file piu' grande e il formato dell'originale: i pixel decidono.
    val phone = photo(1, 4000, 3000, bytes = 2_000_000, original = true)
    val cloud = photo(2, 2000, 1500, bytes = 9_000_000)
    assertThat(CoverRules.choose(listOf(cloud, phone))).isEqualTo(id(1))
    assertThat(CoverRules.choose(listOf(phone, cloud))).isEqualTo(id(1))
    // Il cloud ha ingrandito (stessi pixel dell'originale piu' uno): i pixel vincono anche contro l'originale della fotocamera.
    val upscaled = photo(3, 4001, 3000, bytes = 1_000_000)
    assertThat(CoverRules.choose(listOf(phone, upscaled))).isEqualTo(id(3))
  }

  @Test
  fun laRegola2StessoFormatoMenoCompressioneCioePiuByteSuPixel() {
    val q50 = photo(1, bytes = 1_500_000)
    val q95 = photo(2, bytes = 4_800_000)
    assertThat(CoverRules.choose(listOf(q50, q95))).isEqualTo(id(2))
    assertThat(CoverRules.choose(listOf(q95, q50))).isEqualTo(id(2))
  }

  @Test
  fun laRegola2PerIVideoERisoluzioneEPoiBitrate() {
    val low = video(1, bitrate = 4_000_000, bytes = 25_000_000)
    val high = video(2, bitrate = 12_000_000, bytes = 75_000_000)
    val bigger = video(3, 3840, 2160, bitrate = 2_000_000, bytes = 10_000_000)
    assertThat(CoverRules.choose(listOf(low, high))).isEqualTo(id(2))
    assertThat(CoverRules.choose(listOf(low, high, bigger))).isEqualTo(id(3))
  }

  @Test
  fun laRegola3FormatiDiversiVinceLOriginaleDellaFotocamera() {
    // Stessi pixel: l'HEIC originale del telefono e il JPEG convertito dal cloud (piu' pesante: una conversione non ha piu' dettaglio).
    val heic = photo(1, bytes = 1_800_000, mime = "image/heic", original = true)
    val jpeg = photo(2, bytes = 4_000_000, mime = "image/jpeg")
    assertThat(CoverRules.choose(listOf(jpeg, heic))).isEqualTo(id(1))
    // L'originale e' il JPEG (telefono) e la copia e' un HEIC: vince il JPEG.
    val heicCopy = photo(3, bytes = 9_000_000, mime = "image/heic")
    val jpegPhone = photo(4, bytes = 3_000_000, mime = "image/jpeg", original = true)
    assertThat(CoverRules.choose(listOf(heicCopy, jpegPhone))).isEqualTo(id(4))
  }

  @Test
  fun dentroIlFormatoVincenteContaLaCompressioneAnchePerLeCopieNonOriginali() {
    // Originale JPEG a q80, una copia JPEG a q95 (stesso formato: meno compressione) e un HEIC: vince il JPEG meno compresso.
    val phone = photo(1, bytes = 3_000_000, original = true)
    val jpegHigh = photo(2, bytes = 5_000_000)
    val heic = photo(3, bytes = 6_000_000, mime = "image/heic")
    assertThat(CoverRules.choose(listOf(phone, jpegHigh, heic))).isEqualTo(id(2))
  }

  @Test
  fun laRegola4SenzaOriginaleVinceIlFilePiuGrandePoiLIdentitaMinore() {
    val heic = photo(2, bytes = 2_000_000, mime = "image/heic")
    val jpeg = photo(3, bytes = 2_500_000, mime = "image/jpeg")
    assertThat(CoverRules.choose(listOf(heic, jpeg))).isEqualTo(id(3))
    // Parita' piena: l'identita' minore, qualunque l'ordine.
    val x = photo(7)
    val y = photo(5)
    assertThat(CoverRules.choose(listOf(x, y))).isEqualTo(id(5))
    assertThat(CoverRules.choose(listOf(y, x))).isEqualTo(id(5))
  }

  @Test
  fun piuOriginaliDiFormatiDiversiVinceQuelloPiuGrande() {
    // Il telefono salva HEIC e JPEG dello stesso scatto: entrambi originali, vince il file piu' grande.
    val heic = photo(1, bytes = 2_000_000, mime = "image/heic", original = true)
    val jpeg = photo(2, bytes = 3_500_000, mime = "image/jpeg", original = true)
    assertThat(CoverRules.choose(listOf(heic, jpeg))).isEqualTo(id(2))
  }

  @Test
  fun laSceltaNonDipendeDallOrdineDiPartenzaNemmenoNeiCicli() {
    // Il ciclo che un confronto a coppie non risolve: A batte B per compressione, B batte C per originale, C batte A per peso.
    val a = photo(1, bytes = 4_000_000, mime = "image/jpeg")
    val b = photo(2, bytes = 3_000_000, mime = "image/jpeg", original = true)
    val c = photo(3, bytes = 5_000_000, mime = "image/heic", original = true)
    val all = listOf(a, b, c)
    val results = listOf(all, all.reversed(), listOf(b, c, a), listOf(c, a, b)).map { CoverRules.choose(it) }.toSet()
    assertThat(results).hasSize(1)
  }

  @Test
  fun unaCopiaSolaEUnaCopiaSenzaMisureNonRompeNiente() {
    assertThat(CoverRules.choose(listOf(photo(1)))).isEqualTo(id(1))
    val unknown = CoverFacts(id(2), 0, 9_000_000, CoverRules.UNKNOWN_FORMAT, null, false, false)
    assertThat(CoverRules.choose(listOf(unknown, photo(3)))).isEqualTo(id(3))
  }

  @Test
  fun iFormatiSiRiconoscono() {
    assertThat(CoverRules.formatOf("image/JPG")).isEqualTo("image/jpeg")
    assertThat(CoverRules.formatOf("image/jpeg; charset=x")).isEqualTo("image/jpeg")
    assertThat(CoverRules.formatOf("image/heif")).isEqualTo("image/heic")
    assertThat(CoverRules.formatOf(null)).isEqualTo(CoverRules.UNKNOWN_FORMAT)
    assertThat(CoverRules.formatOf("application/octet-stream")).isEqualTo(CoverRules.UNKNOWN_FORMAT)
    assertThat(CoverRules.formatOf("video/quicktime")).isEqualTo("video/mp4")
  }
}
