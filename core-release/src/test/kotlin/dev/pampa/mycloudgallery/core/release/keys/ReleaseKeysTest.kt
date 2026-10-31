package dev.pampa.mycloudgallery.core.release.keys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.release.codec.EcdsaP256
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestKeys
import dev.pampa.mycloudgallery.core.release.testkeys.ReleaseTestPrivateKey
import java.io.File
import java.math.BigInteger
import java.security.Signature
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 10 (P1, D31; C440, C442): le chiavi fissate. Le impronte del codice sono quelle di `docs/sicurezza.md` §5.6; i segnaposto K1 e K2 sono
 * punti della curva derivati da una frase (nessuno ne conosce la privata); KT e' una coppia vera di prova, la cui pubblica il passo 7i di
 * `tools/check.ps1` sa trovare; KT vale solo nella build debug.
 */
class ReleaseKeysTest {

  private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .first { File(it, "settings.gradle").isFile && File(it, "core-release").isDirectory }

  private val doc: List<String> = File(root, "docs/sicurezza.md").readLines()

  private fun docLine(name: String): String = doc.singleOrNull { it.startsWith("| **$name** |") } ?: error("riga di $name non trovata in §5.6")

  private fun docFingerprint(name: String): String? = Regex("`([0-9a-f]{64})`").find(docLine(name))?.groupValues?.get(1)

  @Test
  fun leImprontesDelCodiceSonoQuelleDelDocumento() {
    assertThat(docFingerprint("K1")).isEqualTo(ReleaseKeys.K1.fingerprint)
    assertThat(docFingerprint("K2")).isEqualTo(ReleaseKeys.K2.fingerprint)
    assertThat(docFingerprint("KT")).isEqualTo(ReleaseTestKeys.KT.fingerprint)
    for (key in ReleaseKeys.ALL + ReleaseTestKeys.KT) assertThat(EcdsaP256.sha256Hex(key.spki)).isEqualTo(key.fingerprint)
    assertThat(ReleaseKeys.ALL.map { it.id }).containsExactly(ReleaseKeyIds.K1, ReleaseKeyIds.K2).inOrder()
    for (name in listOf("K1", "K2")) {
      assertWithMessage("§5.6: la riga di $name dice se e' un segnaposto").that(docLine(name).contains("segnaposto")).isEqualTo(ReleaseKeys.PROVISIONAL)
    }
  }

  /** La derivazione dei segnaposto: x = SHA-256(frase#contatore) mod p, il primo con un y; y pari. */
  private fun placeholder(label: String): ByteArray {
    var counter = 0
    while (true) {
      val x = BigInteger(1, EcdsaP256.sha256("$label#$counter".toByteArray(Charsets.UTF_8))).mod(EcdsaP256.P)
      val rhs = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(EcdsaP256.B).mod(EcdsaP256.P)
      var y = rhs.modPow(EcdsaP256.P.add(BigInteger.ONE).shiftRight(2), EcdsaP256.P)
      if (y.multiply(y).mod(EcdsaP256.P) == rhs) {
        if (y.testBit(0)) y = EcdsaP256.P.subtract(y)
        return EcdsaP256.SPKI_PREFIX + fixed(x) + fixed(y)
      }
      counter++
    }
  }

  private fun fixed(v: BigInteger): ByteArray {
    val bytes = v.toByteArray()
    val out = ByteArray(32)
    val n = minOf(32, bytes.size)
    System.arraycopy(bytes, bytes.size - n, out, 32 - n, n)
    return out
  }

  @Test
  fun iSegnapostoNonHannoUnaChiavePrivata() {
    if (!ReleaseKeys.PROVISIONAL) return
    for ((name, key) in listOf("K1" to ReleaseKeys.K1, "K2" to ReleaseKeys.K2)) {
      val expected = placeholder("MyCloud Gallery, chiave di pubblicazione del manifest $name, segnaposto senza chiave privata")
      assertWithMessage("$name e' il punto derivato dalla frase").that(key.spki).isEqualTo(expected)
    }
  }

  @Test
  fun ktPubblicaEPrivataSonoUnaCoppia() {
    val message = "MCGR1 prova".toByteArray()
    val signature = Signature.getInstance(EcdsaP256.SIGNATURE).run {
      initSign(ReleaseTestPrivateKey.kt())
      update(message)
      sign()
    }
    assertThat(EcdsaP256.verify(EcdsaP256.publicKey(ReleaseTestKeys.KT.spki), message, EcdsaP256.normalize(signature))).isTrue()
    assertThat(EcdsaP256.verify(EcdsaP256.publicKey(ReleaseKeys.K1.spki), message, EcdsaP256.normalize(signature))).isFalse()
  }

  @Test
  fun ilPasso7iTrovaKtNelSuoFileENonAltrove() {
    val source = File(root, "core-release/src/main/kotlin/dev/pampa/mycloudgallery/core/release/testing/ReleaseTestKeys.kt").readText()
    // La stessa espressione di tools/check.ps1 (passo 7i).
    val literals = Regex("\"([A-Za-z0-9+/=]{64,})\"").findAll(source).map { it.groupValues[1] }.toList()
    assertThat(literals).contains(ReleaseTestKeys.KT_SPKI)
    val keys = File(root, "core-release/src/main/kotlin/dev/pampa/mycloudgallery/core/release/keys/ReleaseKeys.kt").readText()
    for (literal in literals) assertWithMessage("KT nelle chiavi di release").that(keys).doesNotContain(literal)
    val check = File(root, "tools/check.ps1").readText()
    assertThat(check).contains("core-release\\src\\main\\kotlin\\dev\\pampa\\mycloudgallery\\core\\release\\testing\\ReleaseTestKeys.kt")
  }

  @Test
  fun soloLaBuildDebugSiFidaDiKt() {
    val debug = ReleaseTestKeys.DEBUG_TRUST
    assertThat(debug.forChannel(ReleaseChannel.DEBUG).keyIds).containsExactly(ReleaseKeyIds.K1, ReleaseKeyIds.K2, ReleaseKeyIds.KT)
    assertThat(debug.forChannel(ReleaseChannel.BETA).keyIds).containsExactly(ReleaseKeyIds.K1, ReleaseKeyIds.K2)
    assertThat(debug.forChannel(ReleaseChannel.STABLE).keyIds).containsExactly(ReleaseKeyIds.K1, ReleaseKeyIds.K2)
    assertThat(ReleaseTrust.RELEASE.keyIds).containsExactly(ReleaseKeyIds.K1, ReleaseKeyIds.K2)
    assertThat(ReleaseTrust.RELEASE.toString()).isEqualTo("ReleaseTrust[1, 2]")
  }

  @Test
  fun unaChiaveFissataSbagliataNonSiCostruisce() {
    assertThrows(IllegalArgumentException::class.java) { ReleaseKeys.pinned(1, ReleaseTestKeys.KT_SPKI, "0".repeat(64)) }
    val offCurve = ReleaseTestKeys.KT.spki.also { it[90] = (it[90].toInt() xor 1).toByte() }
    val text = java.util.Base64.getEncoder().encodeToString(offCurve)
    assertThrows(IllegalArgumentException::class.java) { ReleaseKeys.pinned(1, text, EcdsaP256.sha256Hex(offCurve)) }
    assertThrows(IllegalArgumentException::class.java) { ReleaseTestKeys.trustOf(listOf(ReleaseTestKeys.KT, ReleaseTestKeys.KT)) }
  }
}
