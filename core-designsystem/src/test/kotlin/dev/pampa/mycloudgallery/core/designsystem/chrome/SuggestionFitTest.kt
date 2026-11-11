package dev.pampa.mycloudgallery.core.designsystem.chrome

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * CU-2 / CS-3 (giro 1 della fase H): le voci della barra dei suggerimenti restano distinguibili. Prima si rimpicciolisce il testo, poi le voci corte restano
 * intere, le lunghe si accorciano togliendo la parte che hanno in comune (cio' che l'utente ha gia' scritto) e due voci uguali a vista non compaiono mai.
 * La misura finta: ogni carattere e' largo 10 pixel alla misura piu' grande, 9 alla media, 8 alla piu' piccola.
 */
class SuggestionFitTest {

  private val steps = 3

  private fun width(text: String, step: Int): Float = text.length * (10f - step)

  private fun fit(labels: List<String>, available: Float) = SuggestionFit.layout(labels, available, steps, ::width)

  private fun texts(result: SuggestionFitResult) = result.shown.map { it.text }

  @Test
  fun voci_cheStannoIntere_restanoIntereAParteUguali() {
    val result = fit(listOf("mare", "Roma", "Video"), available = 300f)
    assertThat(result.step).isEqualTo(0)
    assertThat(texts(result)).containsExactly("mare", "Roma", "Video").inOrder()
    assertThat(result.shown.map { it.weight }.toSet()).containsExactly(1f)
  }

  @Test
  fun unaVoceLunga_prendeLoSpazioDelleCorte_inveceDiAccorciarsi() {
    // «mare ad agosto» a parti uguali (100 px) si accorcerebbe; con le altre due corte entra intera.
    val result = fit(listOf("mare ad agosto", "Roma", "Video"), available = 300f)
    assertThat(texts(result)).containsExactly("mare ad agosto", "Roma", "Video").inOrder()
    assertThat(result.shown[0].weight).isGreaterThan(result.shown[1].weight)
  }

  @Test
  fun primaSiRimpicciolisceIlTesto() {
    // 24 caratteri: 240 px alla misura grande, 216 alla media: con 230 px entrano alla media.
    val result = fit(listOf("Castiglione", "della Pescaia"), available = 230f)
    assertThat(result.step).isEqualTo(1)
    assertThat(texts(result)).containsExactly("Castiglione", "della Pescaia").inOrder()
  }

  @Test
  fun iCompletamenti_perdonoLaParteInComune_nonQuellaCheLiDistingue() {
    val labels = listOf("compleanno della nonna a Firenze", "Castiglione della Pescaia", "compleanno di Anna al mare")
    val result = fit(labels, available = 330f)
    val shown = texts(result)
    assertThat(shown).hasSize(3)
    // Le due voci che cominciano con «compleanno» cominciano con i puntini e con la parola che le distingue.
    assertThat(shown[0]).startsWith("${SuggestionFit.Ellipsis}della")
    assertThat(shown[2]).startsWith("${SuggestionFit.Ellipsis}di")
    assertThat(shown.toSet()).hasSize(3)
    result.shown.forEach { assertThat(width(it.text, result.step)).isAtMost(it.weight) }
  }

  @Test
  fun laParteCheLeDistingueResta_ancheInMezzo() {
    // Stesso inizio e stessa fine: restano le cifre che le distinguono.
    val labels = listOf("foto del mare 2023 agosto", "foto del mare 2024 agosto", "Roma")
    val shown = texts(fit(labels, available = 150f))
    assertThat(shown[0]).contains("2023")
    assertThat(shown[1]).contains("2024")
    assertThat(shown.toSet()).hasSize(3)
  }

  @Test
  fun maiDueVociUgualiAVista_seNonCiStaCioCheLeDistingueLaSecondaEsce() {
    // La differenza e' in mezzo a una parola lunga e il posto e' piccolo: in coda, all'inizio e in mezzo verrebbero uguali.
    val labels = listOf("xxxxxxxxAxxxxxxxx", "xxxxxxxxBxxxxxxxx", "Roma")
    val result = fit(labels, available = 128f)
    assertThat(result.shown.map { it.index }).containsExactly(0, 2).inOrder()
    assertThat(texts(result).toSet()).hasSize(2)
  }

  @Test
  fun unaVoceAccorciataSiConfondeConUnAltraSoloSeCiPotrebbeEssere() {
    val e = SuggestionFit.Ellipsis
    assertThat(SuggestionFit.matches("${e}xxxxx", "xxxxxxxxBxxxxxxxx")).isTrue()
    assertThat(SuggestionFit.matches("${e}2023$e", "foto del mare 2024 agosto")).isFalse()
    assertThat(SuggestionFit.matches("Roma$e", "Roma nord")).isTrue()
    // I puntini nascondono almeno una lettera: «Roma…» non e' «Roma».
    assertThat(SuggestionFit.matches("Roma$e", "Roma")).isFalse()
    assertThat(SuggestionFit.matches("Roma", "Roma")).isTrue()
  }

  @Test
  fun dueVociIdentiche_unaSola() {
    val result = fit(listOf("Natale", "Natale", "Roma"), available = 300f)
    assertThat(texts(result)).containsExactly("Natale", "Roma").inOrder()
    assertThat(result.shown.map { it.index }).containsExactly(0, 2).inOrder()
  }

  @Test
  fun leCorteNonSiAccorcianoPerColpaDiUnaLunga() {
    val slots = SuggestionFit.fill(listOf(40f, 400f, 50f), available = 240f)
    assertThat(slots[0]).isEqualTo(40f)
    assertThat(slots[2]).isEqualTo(50f)
    assertThat(slots[1]).isEqualTo(150f)
  }

  @Test
  fun senzaParteInComune_iPuntiniInCoda() {
    val shown = SuggestionFit.shorten("Castiglione della Pescaia", others = listOf("Roma"), slot = 120f) { it.length * 8f }
    assertThat(shown).startsWith("Castiglio")
    assertThat(shown).endsWith(SuggestionFit.Ellipsis)
  }

  @Test
  fun unaEmojiNonSiSpezza() {
    for (slot in 4..12) {
      val shown = SuggestionFit.shorten("mare 🌊 sole e vento", others = emptyList(), slot = slot * 8f) { it.length * 8f }
      // Nessuna meta' di emoji: ogni surrogato alto ha il suo basso subito dopo, e nessun basso e' da solo.
      shown.forEachIndexed { i, c ->
        if (Character.isHighSurrogate(c)) assertThat(shown.getOrNull(i + 1)?.let(Character::isLowSurrogate)).isTrue()
        if (Character.isLowSurrogate(c)) assertThat(shown.getOrNull(i - 1)?.let(Character::isHighSurrogate)).isTrue()
      }
    }
  }
}
