package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 8 (P0): i tipi dei volti sono contratti del formato `faces/` (P3), del database dei volti (P6) e delle schermate. I byte e i codici
 * sono quelli del §2.4 di `docs/fase8-progetto.md`; nessun valore esce in `toString()` (D37).
 */
class Phase8TypesTest {

  private val key = ContentKey("0f".repeat(32))

  @Test
  fun iCodiciDelFormatoDeiVoltiSonoQuelliDelProgetto() {
    assertThat(FaceSpaceCode.entries.associate { it.wire to it.code }).containsExactly(1, "sface_2021dec_v1")
    assertThat(FaceTier.entries.associate { it.wire to it.code }).containsExactly(0, "none", 1, "weak", 2, "core")
    assertThat(FaceSource.entries.associate { it.wire to it.code }).containsExactly(1, "analysis", 2, "hq_tiles", 3, "frames", 4, "phone_original")
    assertThat(FaceDetectorCode.entries.associate { it.wire to it.code }).containsExactly(1, "yunet_2023mar", 2, "yunet_2026may")
    assertThat(PeopleMode.entries.map { it.code }).containsExactly("any", "all").inOrder()
    assertThat(FaceReasons.ALL).isEqualTo(15)
    assertThat(FaceReasons.isValid(FaceReasons.SMALL or FaceReasons.FRAME)).isTrue()
    assertThat(FaceReasons.isValid(16)).isFalse()
    assertThat(FaceTier.fromWire(3)).isNull()
    assertThat(FaceSpaceCode.fromWire(2)).isNull()
  }

  /** D7: 128 dimensioni, salvate in float16 (256 byte a volto, §2.4); uno spazio diverso da quello dei vettori della ricerca. */
  @Test
  fun loSpazioDeiVoltiESFaceA128Dimensioni() {
    val space = FaceSpaceCode.CURRENT.space
    assertThat(space.id).isEqualTo("sface-2021dec-v1")
    assertThat(space.dimension).isEqualTo(128)
    assertThat(space.precision).isEqualTo(VectorPrecision.FLOAT16)
    assertThat(space.vectorBytes).isEqualTo(256)
    assertThat(space).isNotEqualTo(SpaceCode.CURRENT.space)
    assertThat(FaceTier.CORE.hasVector && FaceTier.WEAK.hasVector && !FaceTier.NONE.hasVector).isTrue()
  }

  /** D22: id delle persone a 128 bit, UUID nell'app e base32 nei file, ordine dei byte; mai nei log. */
  @Test
  fun lIdDiUnaPersonaHaDueFormeEUnOrdine() {
    val id = PersonId.random()
    assertThat(id.bytes()).hasLength(16)
    assertThat(id.base32()).hasLength(26)
    assertThat(PersonId.fromBase32(id.base32())).isEqualTo(id)
    assertThat(PersonId.fromBytes(id.bytes())).isEqualTo(id)
    assertThat(PersonId.parse(" ${id.value.uppercase()} ")).isEqualTo(id)
    assertThat(PersonId.parse("non-un-id")).isNull()
    assertThat(PersonId.fromBase32("0".repeat(25))).isNull()
    val low = PersonId.fromBytes(ByteArray(16))
    val high = PersonId.fromBytes(ByteArray(16) { 0xff.toByte() })
    assertThat(low).isLessThan(high)
    assertThat(id.toString()).doesNotContain(id.value)
    assertThrows(IllegalArgumentException::class.java) { PersonId("ABCDEF00-0000-0000-0000-000000000000") }
  }

  /** D3: la generazione e' casuale, canonica in base32 (26 caratteri), mai nei log. */
  @Test
  fun laGenerazioneECanonicaEMaiNeiLog() {
    val g = FaceGeneration.random()
    assertThat(g.base32).hasLength(FaceGeneration.CHARS)
    assertThat(FaceGeneration.parse(g.base32)).isEqualTo(g)
    assertThat(FaceGeneration.fromBytes(g.bytes())).isEqualTo(g)
    assertThat(FaceGeneration.random()).isNotEqualTo(g)
    assertThat(g.toString()).doesNotContain(g.base32)
    // Non canonica: lunghezza, alfabeto (`i l o u` esclusi), maiuscole, bit di coda.
    assertThat(FaceGeneration.parse(g.base32.dropLast(1))).isNull()
    assertThat(FaceGeneration.parse("u".repeat(26))).isNull()
    assertThat(FaceGeneration.parse(g.base32.uppercase().takeIf { it != g.base32 } ?: "A".repeat(26))).isNull()
    assertThat(FaceGeneration.parse("0".repeat(25) + "1")).isNull()
    assertThrows(IllegalArgumentException::class.java) { FaceGeneration("zzz") }
  }

  /** D23: il localizzatore ha una sola forma scritta e corrisponde a un volto ricalcolato con IoU >= 0,5. */
  @Test
  fun ilLocalizzatoreHaUnaFormaSolaEUnAbbinamentoPerIoU() {
    val loc = FaceLocator(key, 0, FaceBox(1000, 2000, 11000, 14000))
    val text = loc.encode()
    assertThat(text.length).isAtMost(FaceLocator.MAX_CHARS)
    assertThat(FaceLocator.parse(text)).isEqualTo(loc)
    assertThat(FaceLocator.parse(text.replace(":0:", ":00:"))).isNull()
    assertThat(FaceLocator.parse(text.replace(":0:", ":9:"))).isNull()
    assertThat(FaceLocator.parse("$text:")).isNull()
    assertThat(FaceLocator.parse(text.substringBeforeLast(':') + ":65536.2000.70000.14000")).isNull()
    assertThat(FaceLocator.parse(text.replace("11000", "500"))).isNull()
    // Spostato di poco: stesso volto; spostato di molto, altro fotogramma o altro contenuto: no.
    assertThat(loc.matches(key, 0, FaceBox(1500, 2500, 11500, 14500))).isTrue()
    assertThat(loc.matches(key, 0, FaceBox(9000, 2000, 19000, 14000))).isFalse()
    assertThat(loc.matches(key, 1, loc.box)).isFalse()
    assertThat(loc.matches(ContentKey("ab".repeat(32)), 0, loc.box)).isFalse()
    assertThat(loc.toString()).doesNotContain("1000")
    assertThat(FaceBox(0, 0, 10, 10).iou(FaceBox(20, 20, 30, 30))).isEqualTo(0.0)
    assertThrows(IllegalArgumentException::class.java) { FaceBox(10, 0, 10, 5) }
    assertThrows(IllegalArgumentException::class.java) { FaceLocator(key, 9, loc.box) }
  }

  /** §2.5: i riferimenti si ordinano allo stesso modo su ogni dispositivo. */
  @Test
  fun iRiferimentiAiVoltiHannoUnOrdineTotale() {
    val a = FaceRef(key, 0, 0, 5)
    val b = FaceRef(key, 0, 1, 1)
    val c = FaceRef(ContentKey("10".repeat(32)), 0, 0, 0)
    assertThat(listOf(c, b, a).sorted()).containsExactly(a, b, c).inOrder()
    assertThat(a.toString()).isEqualTo("FaceRef")
    assertThrows(IllegalArgumentException::class.java) { FaceRef(key, 0, 64, 0) }
  }

  @Test
  fun laSceltaDellAccountNonMostraLaGenerazione() {
    val g = FaceGeneration.random()
    val on = FacesSetting.On(g, 1)
    assertThat(on.generationOrNull).isEqualTo(g)
    assertThat(FacesSetting.Off(g).generationOrNull).isEqualTo(g)
    assertThat(FacesSetting.Unknown.generationOrNull).isNull()
    assertThat(on.toString()).doesNotContain(g.base32)
    assertThrows(IllegalArgumentException::class.java) { FacesSetting.On(g, 0) }
  }
}
