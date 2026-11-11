package dev.pampa.mycloudgallery.core.designsystem.chrome

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * CU-2 / CS-3 (giri 1 e 2 della fase H): le voci della barra dei suggerimenti si mostrano **intere**, come la barra delle parole della tastiera. Prima si
 * rimpicciolisce il testo; si mostrano le voci che entrano intere, fino a tre (due intere sono meglio di tre mozzate); mai puntini in testa e in coda
 * insieme; se nemmeno una entra intera, una sola con i puntini in coda. La misura finta: ogni carattere e' largo 10 pixel alla misura piu' grande, 9 alla
 * media, 8 alla piu' piccola.
 */
class SuggestionFitTest {

  private val steps = 3
  private val e = SuggestionFit.Ellipsis

  private fun width(text: String, step: Int): Float = text.length * (10f - step)

  private fun fit(labels: List<String>, width: Float, itemPadding: Float = 0f, separator: Float = 0f) =
    SuggestionFit.layout(labels, width, itemPadding, separator, steps, ::width)

  private fun texts(result: SuggestionFitResult) = result.shown.map { it.text }

  @Test
  fun vociCheStannoIntere_restanoIntereAParteUguali() {
    val result = fit(listOf("mare", "Roma", "Video"), width = 300f)
    assertThat(result.step).isEqualTo(0)
    assertThat(texts(result)).containsExactly("mare", "Roma", "Video").inOrder()
    assertThat(result.shown.map { it.weight }.toSet()).containsExactly(1f)
  }

  @Test
  fun unaVoceLunga_prendeLoSpazioDelleCorte() {
    val result = fit(listOf("mare ad agosto", "Roma", "Video"), width = 300f)
    assertThat(texts(result)).containsExactly("mare ad agosto", "Roma", "Video").inOrder()
    assertThat(result.shown[0].weight).isGreaterThan(result.shown[1].weight)
  }

  @Test
  fun primaSiRimpicciolisceIlTesto() {
    // 24 caratteri: 240 px alla misura grande, 216 alla media: con 230 px entrano alla media.
    val result = fit(listOf("Castiglione", "della Pescaia"), width = 230f)
    assertThat(result.step).isEqualTo(1)
    assertThat(texts(result)).containsExactly("Castiglione", "della Pescaia").inOrder()
  }

  @Test
  fun dueVociIntereSonoMeglioDiTreMozzate() {
    val labels = listOf("compleanno della nonna", "Castiglione della Pescaia", "compleanno di Anna al mare")
    val result = fit(labels, width = 400f)
    // Alla misura piu' piccola entrano le prime due intere; la terza resta fuori invece di accorciare tutte.
    assertThat(texts(result)).containsExactly("compleanno della nonna", "Castiglione della Pescaia").inOrder()
    assertThat(result.step).isEqualTo(2)
    assertThat(texts(result).none { it.contains(e) }).isTrue()
  }

  @Test
  fun unaVoceCheNonEntraLasciaIlPostoAlleAltre() {
    val result = fit(listOf("una voce lunghissima che qui non entra mai", "Roma", "Video"), width = 200f)
    assertThat(result.shown.map { it.index }).containsExactly(1, 2).inOrder()
    assertThat(texts(result)).containsExactly("Roma", "Video").inOrder()
  }

  @Test
  fun nemmenoUnaIntera_unaSolaConIPuntiniInCoda() {
    val result = fit(listOf("compleanno della nonna a Firenze", "Castiglione della Pescaia"), width = 100f)
    assertThat(result.shown).hasSize(1)
    val only = result.shown.single()
    assertThat(only.index).isEqualTo(0)
    assertThat(only.text).endsWith(e)
    assertThat(only.text).doesNotContain("$e$e")
    assertThat(only.text.startsWith(e)).isFalse()
    assertThat(width(only.text, result.step)).isAtMost(100f)
  }

  @Test
  fun maiPuntiniInTestaEInCoda_eLeVociSonoInterePerTutteLeLarghezze() {
    val labels = listOf("compleanno della nonna", "Castiglione della Pescaia", "compleanno di Anna al mare")
    for (w in 40..700 step 15) {
      val result = fit(labels, width = w.toFloat(), itemPadding = 20f, separator = 1f)
      assertThat(result.shown).isNotEmpty()
      result.shown.forEach { assertThat(it.text.startsWith(e)).isFalse() }
      // O tutte intere, o una sola accorciata in coda.
      if (result.shown.any { it.text.endsWith(e) }) assertThat(result.shown).hasSize(1) else assertThat(texts(result).all { it in labels }).isTrue()
    }
  }

  @Test
  fun ilMargineDelleVociEISeparatoriContano() {
    // Tre voci da 40 px: con 20 px di margine ciascuna e i separatori non entrano in 150 px; due si'.
    val result = fit(listOf("aaaa", "bbbb", "cccc"), width = 150f, itemPadding = 20f, separator = 1f)
    assertThat(texts(result)).containsExactly("aaaa", "bbbb").inOrder()
    assertThat(result.step).isEqualTo(0)
  }

  @Test
  fun dueVociIdentiche_unaSola() {
    val result = fit(listOf("Natale", "Natale", "Roma"), width = 300f)
    assertThat(texts(result)).containsExactly("Natale", "Roma").inOrder()
    assertThat(result.shown.map { it.index }).containsExactly(0, 2).inOrder()
  }

  @Test
  fun unaEmojiNonSiSpezza() {
    for (slot in 4..12) {
      val shown = SuggestionFit.head("mare 🌊 sole e vento", slot = slot * 8f) { it.length * 8f }
      // Nessuna meta' di emoji: ogni surrogato alto ha il suo basso subito dopo, e nessun basso e' da solo.
      shown.forEachIndexed { i, c ->
        if (Character.isHighSurrogate(c)) assertThat(shown.getOrNull(i + 1)?.let(Character::isLowSurrogate)).isTrue()
        if (Character.isLowSurrogate(c)) assertThat(shown.getOrNull(i - 1)?.let(Character::isHighSurrogate)).isTrue()
      }
    }
  }
}
