package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Fase 6 (P0): i codici dei dati AI sono contratti del formato `ai` (byte nei corpi) e del database (codici). */
class Phase6TypesTest {

  /** I byte del formato (§2.3 di docs/fase6-progetto.md): fissati, mai cambiati. */
  @Test
  fun iCodiciDelFormatoAiSonoQuelliDelProgetto() {
    assertThat(SpaceCode.entries.associate { it.wire to it.code }).containsExactly(1, "embeddinggemma2_float_v1")
    assertThat(VecSource.entries.associate { it.wire to it.code }).containsExactly(1, "analysis", 2, "frames", 3, "phone_original")
    assertThat(OcrSource.entries.associate { it.wire to it.code }).containsExactly(1, "analysis", 2, "hq", 3, "frames", 4, "phone_original")
    assertThat(OcrEngineCode.entries.associate { it.wire to it.code }).containsExactly(1, "mlkit_v2_latin")
  }

  /** D4: lo spazio corrente e' EmbeddingGemma 2 float, 768 dimensioni, salvato in float16 (1536 byte a vettore). */
  @Test
  fun loSpazioCorrenteEQuelloDecisoInFase0() {
    val space = SpaceCode.CURRENT.space
    assertThat(space.id).isEqualTo(EmbeddingSpace.EMBEDDING_GEMMA2_FLOAT_V1)
    assertThat(space.dimension).isEqualTo(768)
    assertThat(space.precision).isEqualTo(VectorPrecision.FLOAT16)
    assertThat(space.vectorBytes).isEqualTo(1536)
    assertThat(SpaceCode.of(space)).isEqualTo(SpaceCode.CURRENT)
    // E-I4: stesso modello in un'altra precisione o dimensione = un altro spazio, sconosciuto al formato.
    assertThat(SpaceCode.of(space.copy(precision = VectorPrecision.FLOAT32))).isNull()
    assertThat(SpaceCode.of(space.copy(dimension = 256))).isNull()
  }

  @Test
  fun unCodiceSconosciutoNonEUnaSorgente() {
    assertThat(SpaceCode.fromWire(2)).isNull()
    assertThat(VecSource.fromWire(0)).isNull()
    assertThat(OcrSource.fromWire(5)).isNull()
    assertThat(OcrEngineCode.fromWire(2)).isNull()
    assertThat(VecSource.ANALYSIS.singleVector).isTrue()
    assertThat(VecSource.FRAMES.singleVector).isFalse()
  }

  /** D34: il bit dei documenti e' il 16, il primo libero dopo la Fase 5. */
  @Test
  fun ilBitDeiDocumentiEIl16() {
    assertThat(CellFlags.DOCUMENT).isEqualTo(1 shl 16)
    assertThat(CellFlags.ALL.keys.last()).isEqualTo("DOCUMENT")
    assertThat(PresenceBadge.of(CellFlags.DOCUMENT)).isNull()
  }
}
