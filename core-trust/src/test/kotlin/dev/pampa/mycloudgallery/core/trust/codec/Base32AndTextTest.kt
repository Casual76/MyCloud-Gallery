package dev.pampa.mycloudgallery.core.trust.codec

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class Base32AndTextTest {

  @Test
  fun base32FaIlGiroPerOgniLunghezza() {
    val random = Random(7)
    for (size in 0..40) {
      val bytes = random.nextBytes(size)
      val text = Base32.encode(bytes)

      assertThat(text.length).isEqualTo(Base32.encodedLength(size))
      assertThat(text.all { it in "0123456789abcdefghjkmnpqrstvwxyz" }).isTrue()
      assertThat(Base32.decode(text)).isEqualTo(bytes)
    }
  }

  @Test
  fun base32RifiutaLeFormeNonCanoniche() {
    val canonical = Base32.encode(byteArrayOf(0x55))

    assertThrows(TrustViolation::class.java) { Base32.decode(canonical.uppercase()) }
    assertThrows(TrustViolation::class.java) { Base32.decode(canonical + "0") } // lunghezza impossibile
    assertThrows(TrustViolation::class.java) { Base32.decode("ai") } // 'i' fuori alfabeto
    assertThrows(TrustViolation::class.java) { Base32.decode("01") } // bit di coda non nulli
    assertThrows(TrustViolation::class.java) { Base32.decodeExact(canonical, 2) }
    assertThat(Base32.isCanonical(canonical, 1)).isTrue()
  }

  @Test
  fun testoDaMostrare_ammetteEmojiEAccenti_rifiutaControlliEBidi() {
    assertThat(TextRules.displayText("Tablet — profilo Mamma 👨‍👩‍👧", 64)).isNotEmpty()
    assertThat(TextRules.displayText("Città di Mario", 64)).isNotEmpty()

    for (bad in listOf("", " spazio", "a\u0000b", "a\nb", "a\u0085b", "fattura\u202Efdp.exe", "a\u200Bb", "\uFEFFa", "a\u2028b", "x".repeat(65), "a\uD800")) {
      assertThrows(TrustViolation::class.java) { TextRules.displayText(bad, 64) }
    }
  }

  @Test
  fun accountDelNasSoloAsciiStretto() {
    assertThat(TextRules.nasAccount("mamma.rossi-2")).isEqualTo("mamma.rossi-2")
    assertThat(TextRules.sameAccount("Mamma", "mamma")).isTrue()
    for (bad in listOf("", ".nascosto", "con spazio", "mammà", "a/b", "a\\b", "x".repeat(65))) {
      assertThrows(TrustViolation::class.java) { TextRules.nasAccount(bad) }
    }
  }
}
