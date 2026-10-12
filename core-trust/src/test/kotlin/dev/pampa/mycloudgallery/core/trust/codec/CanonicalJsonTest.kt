package dev.pampa.mycloudgallery.core.trust.codec

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalJsonTest {

  private fun reason(text: String): RejectReason = reason(text.toByteArray(Charsets.UTF_8))

  private fun reason(bytes: ByteArray): RejectReason =
    assertThrows(TrustViolation::class.java) { CanonicalJson.parseCanonical(bytes) }.reason

  @Test
  fun ilGiroCanonicoEStabile() {
    val value = jsonObject(
      "zeta" to "testo con \"virgolette\", \\ e a capo\n, emoji 👨‍👩‍👧 e accenti àèì".json(),
      "alfa" to (-42L).json(),
      "lista" to listOf(true.json(), false.json(), Long.MAX_VALUE.json(), Long.MIN_VALUE.json()).json(),
      "oggetto" to jsonObject("b" to 1.json(), "a" to "\u0001".json()),
    )

    val bytes = CanonicalJson.encode(value)

    assertThat(String(bytes, Charsets.UTF_8)).startsWith("""{"alfa":-42,"lista":[true,false,""")
    assertThat(String(bytes, Charsets.UTF_8)).contains("""{"a":"\u0001","b":1}""")
    assertThat(CanonicalJson.parseCanonical(bytes)).isEqualTo(value)
  }

  @Test
  fun formeNonCanonicheRifiutate() {
    assertThat(reason("""{"b":1,"a":2}""")).isEqualTo(RejectReason.NOT_CANONICAL) // chiavi fuori ordine
    assertThat(reason("""{"a":"\/"}""")).isEqualTo(RejectReason.NOT_CANONICAL) // escape superfluo
    assertThat(reason("""{"a":"\u0041"}""")).isEqualTo(RejectReason.NOT_CANONICAL) // escape di una lettera
    assertThat(reason("""{"a":"\u000a"}""")).isEqualTo(RejectReason.NOT_CANONICAL) // \n va scritto corto
  }

  @Test
  fun spaziBomEDatiInCodaRifiutati() {
    assertThat(reason("""{"a": 1}""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(""" {"a":1}""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"a":1}x""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"a":1}{"a":1}""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + """{"a":1}""".toByteArray())).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("")).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun chiaviRipetuteOFuoriAlfabetoRifiutate() {
    assertThat(reason("""{"a":1,"a":2}""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"à":1}""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"1a":1}""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"":1}""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"a_b":1}""")).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun numeriNonInteriONonCanoniciRifiutati() {
    for (bad in listOf("1.5", "1e3", "1E3", "01", "-0", "-", "+1", "9223372036854775808", "-9223372036854775809", "NaN", "Infinity")) {
      assertThat(reason("""{"a":$bad}""")).isEqualTo(RejectReason.MALFORMED)
    }
  }

  @Test
  fun nullELetteraliStortiRifiutati() {
    assertThat(reason("""{"a":null}""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"a":tru}""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"a":True}""")).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun utf8NonValidoESurrogatiSpaiatiRifiutati() {
    val overlong = """{"a":"""".toByteArray() + byteArrayOf(0xC0.toByte(), 0xAF.toByte()) + "\"}".toByteArray()
    val truncated = """{"a":"""".toByteArray() + byteArrayOf(0xE2.toByte(), 0x82.toByte()) + "\"}".toByteArray()
    val encodedSurrogate = """{"a":"""".toByteArray() + byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) + "\"}".toByteArray()

    assertThat(reason(overlong)).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(truncated)).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(encodedSurrogate)).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"a":"\ud800"}""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"a":"\udc00x"}""")).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun controlliNonEscapatiEStringheApertaRifiutati() {
    assertThat(reason("{\"a\":\"riga\nnuova\"}")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"a":"aperta}""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"a":"\x"}""")).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("""{"a":"\u12"}""")).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun annidamentoProfondoRifiutatoSenzaStackOverflow() {
    val deep = "[".repeat(100_000) + "]".repeat(100_000)
    val justOver = "[".repeat(9) + "]".repeat(9)
    val atLimit = "[".repeat(8) + "]".repeat(8)

    assertThat(reason(deep)).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(reason(justOver)).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(CanonicalJson.parseCanonical(atLimit.toByteArray())).isInstanceOf(JsonArray::class.java)
  }

  @Test
  fun stringheEArrayEnormiRifiutati() {
    val longString = """{"a":"${"x".repeat(5000)}"}"""
    val longArray = "[" + (1..9000).joinToString(",") + "]"
    val manyMembers = "{" + (0 until 40).joinToString(",") { "\"k${it.toString().padStart(2, '0')}\":1" } + "}"

    assertThat(reason(longString)).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(reason(longArray)).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(reason(manyMembers)).isEqualTo(RejectReason.TOO_LARGE)
  }

  @Test
  fun leChiaviNonValideNonSiScrivono() {
    assertThrows(IllegalArgumentException::class.java) { CanonicalJson.encode(jsonObject("chiave con spazio" to 1.json())) }
    assertThrows(IllegalArgumentException::class.java) { CanonicalJson.encode(jsonObject("a" to "\ud800".json())) }
  }
}
