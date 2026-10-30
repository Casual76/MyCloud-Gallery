package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `VisibleText` (Fase 8, correzioni D, R9): un testo "si vede" se ha almeno un carattere che non e' spazio, controllo, formato (ZWJ), segno combinante o non assegnato. */
class VisibleTextTest {

  @Test
  fun zwjESelettoriDiVarianteDaSoliNonSiVedono() {
    assertThat(VisibleText.hasVisible("")).isFalse()
    assertThat(VisibleText.hasVisible("   \t\n")).isFalse()
    assertThat(VisibleText.hasVisible("\u200D")).isFalse()
    assertThat(VisibleText.hasVisible("\uFE0F\uFE0E")).isFalse()
    assertThat(VisibleText.hasVisible("\u200D\uFE0F\u200D")).isFalse()
    assertThat(VisibleText.hasVisible("\u0301")).isFalse()
    assertThat(VisibleText.hasVisible("\u0000\u0007")).isFalse()
    assertThat(VisibleText.hasVisible("\u202E")).isFalse()
  }

  @Test
  fun unaLetteraUnEmojiOUnSegnoSiVedonoInQualunqueAlfabeto() {
    for (text in listOf("a", "Marco", "Анна", "李明", "❤", "❤\uFE0F", "?", "7", "😀", "  x  ", "\u200Da\u200D")) {
      assertThat(VisibleText.hasVisible(text)).isTrue()
    }
  }
}
