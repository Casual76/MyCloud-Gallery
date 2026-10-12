package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Fixtures.content
import dev.pampa.mycloudgallery.core.model.Fixtures.contentChangedInTheMiddle
import org.junit.Assert.assertThrows
import org.junit.Test

class FingerprintTest {

  // Valori di riferimento calcolati in modo indipendente (Python, hashlib) dalla specifica in
  // QuickHashAlgorithm: se cambiano, e' cambiato il contratto tra dispositivi e serve un "qh2".
  @Test
  fun hashRapidoQh1RispettaIValoriDiRiferimento() {
    assertThat(QuickHashAlgorithm.ofContent(ByteArray(0)).hex)
      .isEqualTo("a01b6e1bea97ca4a6bd40ea182888dd9196c7d13a8708093501035ab52eb2f72")
    assertThat(QuickHashAlgorithm.ofContent("0123456789".toByteArray()).hex)
      .isEqualTo("fdeb5642ec1bdb9961e1c3233beac1da2a8ddd18a323ec50ae0c1dc67416c890")
    val big = ByteArray(200_000) { (it % 251).toByte() }
    assertThat(QuickHashAlgorithm.ofContent(big).hex)
      .isEqualTo("82989ff45af3fe95c576cfc863cf7605e44f7b945097a6610aaba8f4095235ec")
    val edge = ByteArray(2 * QuickHashAlgorithm.CHUNK_BYTES) { (it % 7).toByte() }
    assertThat(QuickHashAlgorithm.ofContent(edge).hex)
      .isEqualTo("bdd2b7915b21e004ae50bc349c884ff46de850449f564719bc382bff0e512fde")
    assertThat(Sha256.of(big).hex).isEqualTo("e24bc62381f1224fbbb74688663f8f9743b9680b193edd666835e97b06e730eb")
  }

  @Test
  fun lHashRapidoNonEUnSha256DelFile() {
    val small = "0123456789".toByteArray()
    assertThat(QuickHashAlgorithm.ofContent(small).hex).isNotEqualTo(Sha256.of(small).hex)
  }

  @Test
  fun leRegioniDaLeggereSonoTuttoIlFileFinoA128KiBPoiTestaECoda() {
    assertThat(QuickHashAlgorithm.regions(0)).isEmpty()
    assertThat(QuickHashAlgorithm.regions(10)).containsExactly(0L until 10L)
    assertThat(QuickHashAlgorithm.regions(131_072)).containsExactly(0L until 131_072L)
    assertThat(QuickHashAlgorithm.regions(131_073))
      .containsExactly(0L until 65_536L, 65_537L until 131_073L).inOrder()
  }

  @Test
  fun calcoloConTestaECodaDellaMisuraSbagliataERifiutato() {
    assertThrows(IllegalArgumentException::class.java) {
      QuickHashAlgorithm.compute(300_000, ByteArray(10), ByteArray(QuickHashAlgorithm.CHUNK_BYTES))
    }
    assertThrows(IllegalArgumentException::class.java) {
      QuickHashAlgorithm.compute(10, ByteArray(10), ByteArray(1))
    }
  }

  /** Il limite dichiarato dell'hash rapido: per questo da solo non unisce mai. */
  @Test
  fun unFileCambiatoSoloNelMezzoHaLoStessoHashRapidoMaNonLoStessoHashCompleto() {
    val a = Fingerprint.ofContent(content(1))
    val b = Fingerprint.ofContent(contentChangedInTheMiddle(1))

    assertThat(a.quickHash).isEqualTo(b.quickHash)
    assertThat(a.copy(fullHash = null).match(b.copy(fullHash = null))).isEqualTo(FingerprintMatch.PROBABLY_SAME)
    assertThat(a.copy(fullHash = null).match(b)).isEqualTo(FingerprintMatch.PROBABLY_SAME)
    assertThat(a.match(b)).isEqualTo(FingerprintMatch.DIFFERENT)
  }

  @Test
  fun confrontoDelleImpronte() {
    val a = Fixtures.full(1)
    assertThat(a.match(Fixtures.full(1))).isEqualTo(FingerprintMatch.SAME)
    assertThat(a.match(Fixtures.full(2))).isEqualTo(FingerprintMatch.DIFFERENT)
    assertThat(a.match(Fixtures.quick(2))).isEqualTo(FingerprintMatch.DIFFERENT) // hash rapido diverso
    assertThat(a.match(a.copy(size = a.size + 1))).isEqualTo(FingerprintMatch.DIFFERENT)
  }

  @Test
  fun leConoscenzeSiUnisconoSoloTraImpronteCompatibili() {
    val withFull = Fixtures.full(1)
    val quickOnly = Fixtures.quick(1)

    assertThat(quickOnly.withKnowledgeOf(withFull).fullHash).isEqualTo(withFull.fullHash)
    assertThat(withFull.withKnowledgeOf(quickOnly)).isEqualTo(withFull)
    assertThrows(IllegalArgumentException::class.java) { quickOnly.withKnowledgeOf(Fixtures.full(2)) }
  }

  @Test
  fun hashMalformatiSonoRifiutatiEMaiuscoleNormalizzate() {
    assertThrows(IllegalArgumentException::class.java) { Sha256("abc") }
    assertThrows(IllegalArgumentException::class.java) { QuickHash("Z".repeat(64)) }
    val upper = Fixtures.full(1).fullHash!!.hex.uppercase()
    assertThat(Sha256.parse(" $upper ")).isEqualTo(Fixtures.full(1).fullHash)
  }
}
