package dev.pampa.mycloudgallery.core.faces.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.eval.FacesEvalSampling.Candidate
import dev.pampa.mycloudgallery.core.faces.eval.FacesEvalSampling.FaceCandidate
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import org.junit.Test

/** Cosa si mostra all'utente (§5.3): 40 gruppi pesati per dimensione, tutti o i nominati piu' un campione fino a 80, 60 volti piu' i 10 meno simili. */
class FacesEvalSamplingTest {

  private fun person(n: Int): String = "%08x-0000-4000-8000-%012x".format(n, n.toLong())

  private fun groups(count: Int, size: (Int) -> Int = { 10 }, named: (Int) -> Boolean = { false }): List<Candidate> =
    (1..count).map { Candidate(person(it), size(it), named(it)) }

  private fun face(n: Int, score: Float): FaceCandidate =
    FaceCandidate(FaceLocator(ContentKey("%064x".format(n.toLong() + 1)), 0, FaceBox(100, 100, 5_000, 6_000)), score)

  @Test
  fun laCalibrazioneScegliQuarantaGruppiSenzaRipetizioni() {
    val picked = FacesEvalSampling.calibration(groups(300), seed = 5L)
    assertThat(picked).hasSize(40)
    assertThat(picked.map { it.person }.toSet()).hasSize(40)
  }

  @Test
  fun conMenoDiQuarantaGruppiSiMostranoTutti() {
    assertThat(FacesEvalSampling.calibration(groups(25), seed = 1L)).hasSize(25)
    assertThat(FacesEvalSampling.calibration(emptyList(), seed = 1L)).isEmpty()
    assertThat(FacesEvalSampling.calibration(groups(40), seed = 1L)).hasSize(40)
  }

  @Test
  fun laScelteEDeterministicaDatoIlSeme() {
    val all = groups(300, size = { 3 + it % 50 })
    val a = FacesEvalSampling.calibration(all, seed = 99L).map { it.person }
    assertThat(FacesEvalSampling.calibration(all.shuffled(kotlin.random.Random(3)), seed = 99L).map { it.person }).containsExactlyElementsIn(a)
    assertThat(FacesEvalSampling.calibration(all, seed = 100L).map { it.person }).isNotEqualTo(a)
  }

  @Test
  fun ilPesoEQuelloDellaDimensione() {
    // 20 gruppi grandi (200 volti) e 280 piccoli (3 volti): con peso alla dimensione i grandi entrano quasi sempre.
    val all = groups(300, size = { if (it <= 20) 200 else 3 })
    var big = 0
    var rounds = 0
    for (seed in 1L..200L) {
      big += FacesEvalSampling.calibration(all, seed).count { it.size == 200 }
      rounds++
    }
    val averageBig = big.toDouble() / rounds
    // Senza peso ne entrerebbero 40 * 20/300 = 2,7 in media; con il peso quasi tutti i 20.
    assertThat(averageBig).isGreaterThan(14.0)
  }

  @Test
  fun lAltroEstremoUnGruppoPiccoloPuoEntrareMaDiRado() {
    val all = groups(100, size = { if (it <= 60) 100 else 1 })
    var smallPicked = 0
    for (seed in 1L..100L) smallPicked += FacesEvalSampling.calibration(all, seed).count { it.size == 1 }
    assertThat(smallPicked.toDouble() / 100).isLessThan(1.0)
  }

  @Test
  fun laMisuraMostraTuttiSeSonoAlPiuOttanta() {
    assertThat(FacesEvalSampling.measure(groups(80), seed = 1L)).hasSize(80)
    assertThat(FacesEvalSampling.measure(groups(12), seed = 1L)).hasSize(12)
  }

  @Test
  fun laMisuraConPiuDiOttantaPrendeITuttiINominatiEUnCampioneDegliAltri() {
    val all = groups(400, named = { it % 10 == 0 })
    val picked = FacesEvalSampling.measure(all, seed = 7L)
    assertThat(picked).hasSize(80)
    val named = all.filter { it.named }
    assertThat(named).hasSize(40)
    assertThat(picked.map { it.person }).containsAtLeastElementsIn(named.map { it.person })
    assertThat(picked.count { !it.named }).isEqualTo(40)
    assertThat(picked.map { it.person }.toSet()).hasSize(80)
    assertThat(FacesEvalSampling.measure(all, seed = 7L).map { it.person }).containsExactlyElementsIn(picked.map { it.person })
    assertThat(FacesEvalSampling.measure(all, seed = 8L).filter { !it.named }.map { it.person }).isNotEqualTo(picked.filter { !it.named }.map { it.person })
  }

  @Test
  fun conPiuDiOttantaNomiSiPescaFraINominatiAncheSenzaCampione() {
    val all = groups(300, named = { it <= 120 })
    val picked = FacesEvalSampling.measure(all, seed = 3L)
    assertThat(picked).hasSize(80)
    assertThat(picked.all { it.named }).isTrue()
  }

  @Test
  fun ISoliGruppiConNomeSottoOttantaPiuIlCampioneArrivanoAOttanta() {
    val all = groups(150, named = { it <= 5 })
    val picked = FacesEvalSampling.measure(all, seed = 2L)
    assertThat(picked).hasSize(80)
    assertThat(picked.count { it.named }).isEqualTo(5)
  }

  @Test
  fun ISceltiSonoDalPiuGrandeAlPiuPiccolo() {
    val picked = FacesEvalSampling.calibration(groups(200, size = { 1 + it }), seed = 4L)
    assertThat(picked.map { it.size }).isInOrder(compareByDescending<Int> { it })
  }

  @Test
  fun finoASessantaVoltiSiMostranoTuttiDalPiuAlMenoSimile() {
    val faces = (1..45).map { face(it, it / 100f) }
    val choice = FacesEvalSampling.faces(faces, person(1), seed = 1L)
    assertThat(choice.main).hasSize(45)
    assertThat(choice.leastSimilar).isEmpty()
    assertThat(choice.main.map { it.score }).isInOrder(compareByDescending<Float> { it })
  }

  @Test
  fun OltreSessantaSiMostranoSessantaPiuIDieciMenoSimili() {
    val faces = (1..400).map { face(it, it / 1000f) }
    val choice = FacesEvalSampling.faces(faces, person(2), seed = 9L)
    assertThat(choice.main).hasSize(60)
    assertThat(choice.leastSimilar).hasSize(10)
    // I dieci meno simili sono davvero i dieci con il punteggio piu' basso, dal piu' basso.
    assertThat(choice.leastSimilar.map { it.score }).containsExactlyElementsIn((1..10).map { it / 1000f }).inOrder()
    // Gli altri sessanta non li contengono e non hanno doppioni.
    assertThat(choice.main.map { it.locator }.intersect(choice.leastSimilar.map { it.locator }.toSet())).isEmpty()
    assertThat(choice.all.map { it.locator }.toSet()).hasSize(70)
    assertThat(choice.main.map { it.score }).isInOrder(compareByDescending<Float> { it })
    // Stabile: stesso seme e stesso gruppo, stessi volti.
    assertThat(FacesEvalSampling.faces(faces.shuffled(kotlin.random.Random(1)), person(2), seed = 9L).all.map { it.locator }).containsExactlyElementsIn(choice.all.map { it.locator })
    // Un altro seme sceglie un altro campione, ma i dieci meno simili restano quelli.
    val other = FacesEvalSampling.faces(faces, person(2), seed = 10L)
    assertThat(other.leastSimilar.map { it.locator }).isEqualTo(choice.leastSimilar.map { it.locator })
    assertThat(other.main.map { it.locator }).isNotEqualTo(choice.main.map { it.locator })
  }

  @Test
  fun conSessantunoVoltiNeEntranoComunqueSettanta() {
    val faces = (1..70).map { face(it, it / 100f) }
    val choice = FacesEvalSampling.faces(faces, person(3), seed = 1L)
    assertThat(choice.all).hasSize(70)
    assertThat(choice.leastSimilar).hasSize(10)
  }

  @Test
  fun nessunToStringMostraIdOPosti() {
    val c = Candidate(person(1), 10, true)
    assertThat(c.toString()).doesNotContain(person(1))
    val f = face(1, 0.5f)
    assertThat(f.toString()).doesNotContain("0000")
    assertThat(FacesEvalSampling.faces(listOf(f), person(1), 1L).toString()).doesNotContain(person(1))
  }
}
