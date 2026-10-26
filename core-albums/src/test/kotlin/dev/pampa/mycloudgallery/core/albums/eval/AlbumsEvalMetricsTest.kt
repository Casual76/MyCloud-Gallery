package dev.pampa.mycloudgallery.core.albums.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import java.time.LocalDate
import org.junit.Test

/** Le metriche di F88 e il confronto di F89 a mano, con numeri piccoli e verificabili. Dati sintetici. */
class AlbumsEvalMetricsTest {
  private fun m(n: Int): String = FakeAlbumLibrary.id(n).value

  private fun ids(range: IntRange): List<String> = range.map { m(it) }

  private fun day(m: Int, d: Int): LocalDate = LocalDate.of(2026, m, d)

  private fun trip(id: String = "v01", from: LocalDate = day(3, 12), to: LocalDate = day(3, 19)) = EvalTrip(id, from, to, "Titolo $id")

  // --- F88 -----------------------------------------------------------------------------------------------------------------------

  @Test
  fun precisioneECoperturaAMano() {
    // 10 foto del viaggio (1..10). L'album ne inserisce 8 giuste (1..8) e 2 sbagliate (101, 102); ne suggerisce 1 giusta (9) e 3 no (103..105).
    val run = TripRun("v01", ids(1..8) + ids(101..102), listOf(m(9)) + ids(103..105))
    val metrics = AlbumsEvalMetrics.judge(run, ids(1..10).toSet(), (ids(101..102) + ids(103..105)).toSet())
    assertThat(metrics.truth).isEqualTo(10)
    assertThat(metrics.sure).isEqualTo(10)
    assertThat(metrics.sureRight).isEqualTo(8)
    assertThat(metrics.sureWrong).isEqualTo(2)
    assertThat(metrics.sureUnjudged).isEqualTo(0)
    assertThat(metrics.suggested).isEqualTo(4)
    assertThat(metrics.suggestedRight).isEqualTo(1)
    assertThat(metrics.covered).isEqualTo(9)
    assertThat(metrics.missing).isEqualTo(1)
    assertThat(metrics.precision).isWithin(1e-12).of(0.8)
    assertThat(metrics.coverage).isWithin(1e-12).of(0.9)
    assertThat(metrics.sureCoverage).isWithin(1e-12).of(0.8)
    assertThat(metrics.meets(AlbumsEvalTargets())).isFalse()
    assertThat(metrics.meets(AlbumsEvalTargets(precision = 0.8, coverage = 0.9))).isTrue()
  }

  @Test
  fun unSicuroNonGiudicatoContaComeNonGiusto() {
    // 5 sicuri: 3 del viaggio, 1 segnato sbagliato, 1 mai giudicato.
    val run = TripRun("v01", ids(1..5), emptyList())
    val metrics = AlbumsEvalMetrics.judge(run, ids(1..3).toSet(), setOf(m(4)))
    assertThat(metrics.sureRight).isEqualTo(3)
    assertThat(metrics.sureWrong).isEqualTo(1)
    assertThat(metrics.sureUnjudged).isEqualTo(1)
    assertThat(metrics.precision).isWithin(1e-12).of(0.6)
    assertThat(metrics.precisionJudged).isWithin(1e-12).of(0.75)
  }

  @Test
  fun unaFotoSegnataDelViaggioNonPuoEssereSbagliata() {
    // Le etichette sono esclusive per costruzione; qui, se per assurdo fossero in conflitto, il giusto vince (non si conta due volte).
    val run = TripRun("v01", listOf(m(1)), emptyList())
    val metrics = AlbumsEvalMetrics.judge(run, setOf(m(1)), setOf(m(1)))
    assertThat(metrics.sureRight).isEqualTo(1)
    assertThat(metrics.sureWrong).isEqualTo(0)
    assertThat(metrics.sureRight + metrics.sureWrong + metrics.sureUnjudged).isEqualTo(metrics.sure)
  }

  @Test
  fun inUnAlbumVuotoOSenzaVeritaLeProporzioniNonSiInventano() {
    val empty = AlbumsEvalMetrics.judge(TripRun("v01", emptyList(), emptyList()), ids(1..4).toSet(), emptySet())
    assertThat(empty.precision.isNaN()).isTrue()
    assertThat(empty.coverage).isEqualTo(0.0)
    assertThat(empty.meets(AlbumsEvalTargets())).isFalse()
    val noTruth = AlbumsEvalMetrics.judge(TripRun("v01", ids(1..3), emptyList()), emptySet(), emptySet())
    assertThat(noTruth.coverage.isNaN()).isTrue()
    assertThat(noTruth.meets(AlbumsEvalTargets())).isFalse()
    assertThat(AlbumsEvalReport.pct(Double.NaN)).isEqualTo("-")
  }

  @Test
  fun unSuggeritoNonSiContaFraISicuriNeDueVolte() {
    val run = TripRun("v01", ids(1..3), ids(2..6))
    // Il 2 e il 3 sono fra i sicuri: non tornano fra i suggeriti.
    assertThat(run.suggested).containsExactlyElementsIn(ids(4..6)).inOrder()
    assertThat(TripRun("v01", listOf(m(1), m(1), m(2)), emptyList()).sure).containsExactly(m(1), m(2)).inOrder()
  }

  @Test
  fun iTotaliSommanoISingoliViaggi() {
    val a = AlbumsEvalMetrics.judge(TripRun("v01", ids(1..10), emptyList()), ids(1..10).toSet(), emptySet())
    val b = AlbumsEvalMetrics.judge(TripRun("v02", ids(21..30), ids(31..32)), ids(21..40).toSet(), emptySet())
    val total = AlbumsEvalMetrics.total(listOf(a, b))
    assertThat(total.truth).isEqualTo(30)
    assertThat(total.sure).isEqualTo(20)
    assertThat(total.covered).isEqualTo(22)
    assertThat(total.precision).isWithin(1e-12).of(1.0)
    assertThat(total.coverage).isWithin(1e-12).of(22.0 / 30)
    assertThat(AlbumsEvalMetrics.total(emptyList())).isEqualTo(TripMetrics.ZERO)
  }

  @Test
  fun ilResocontoDiF88DiceSeGliObiettiviSonoRaggiuntiESoloNumeri() {
    val good = AlbumsEvalMetrics.Entry(
      trip("v01"), TripRun("v01", ids(1..99) + listOf(m(500)), ids(100..105), series = 2, placeRecognized = true, alternatives = 1),
      AlbumsEvalMetrics.judge(TripRun("v01", ids(1..99) + listOf(m(500)), ids(100..105)), ids(1..105).toSet() + m(110), setOf(m(500))),
    )
    val report = AlbumsEvalMetrics.report(listOf(good))
    val text = report.toNumbersText()
    println(text)
    assertThat(report.total.precision).isWithin(1e-12).of(0.99)
    assertThat(report.meets).isTrue()
    assertThat(report.conclusive).isTrue()
    assertThat(text).contains("Precisione dei sicuri: RAGGIUNTA (99.0%)")
    assertThat(text).contains("Copertura: RAGGIUNTA")
    assertThat(text).contains("Sicuri senza giudizio: 0 (misura completa)")
    assertThat(text).contains("v01 | 8 | 106 | 100 | 99 | 1 | 0 | 6 | 6 | 105 | 1 | 99.0% | 99.1% | 2 | si | 1")
    // Mai titolo, date o id.
    assertThat(text).doesNotContain("Titolo")
    assertThat(text).doesNotContain("2026")
    assertThat(text).doesNotContain(m(1))
    // Con dei sicuri non giudicati la misura non e' conclusiva e il resoconto lo dice.
    val open = AlbumsEvalMetrics.Entry(trip("v02"), TripRun("v02", ids(1..10), emptyList()), AlbumsEvalMetrics.judge(TripRun("v02", ids(1..10), emptyList()), ids(1..5).toSet(), emptySet()))
    val openReport = AlbumsEvalMetrics.report(listOf(open))
    assertThat(openReport.conclusive).isFalse()
    assertThat(openReport.toNumbersText()).contains("Sicuri senza giudizio: 5 (contano come sbagliati")
    assertThat(openReport.toNumbersText()).contains("NON raggiunta")
  }

  // --- F89 -----------------------------------------------------------------------------------------------------------------------

  private val since = day(1, 1)

  private fun proposal(from: LocalDate, to: LocalDate, trip: Boolean = true) = ProposedDays(from, to, trip)

  @Test
  fun unaPropostaCheCopreIlViaggioLoTrova() {
    val listed = listOf(trip("v01", day(3, 12), day(3, 19)))
    // Esatta, piu' lunga di un giorno per parte (la partenza e il ritorno), e di poco piu' corta.
    for (p in listOf(proposal(day(3, 12), day(3, 19)), proposal(day(3, 11), day(3, 20)), proposal(day(3, 13), day(3, 19)))) {
      val found = TripsMatching.compare(listed, listOf(p), since, homeKnown = true)
      assertThat(found.verdicts["v01"]).isEqualTo(TripVerdict.FOUND)
      assertThat(found.meets).isTrue()
      assertThat(found.unlisted).isEqualTo(0)
    }
  }

  @Test
  fun unaPropostaTroppoCortaOTroppoLungaLoTocca() {
    val listed = listOf(trip("v01", day(3, 12), day(3, 19)))
    // 3 giorni su 8 (meno della meta'), e 8 giorni dentro 30 (il viaggio e' piccolo per la proposta).
    for (p in listOf(proposal(day(3, 12), day(3, 14)), proposal(day(3, 1), day(3, 30)), proposal(day(3, 19), day(3, 25)))) {
      val found = TripsMatching.compare(listed, listOf(p), since, homeKnown = true)
      assertThat(found.verdicts["v01"]).isEqualTo(TripVerdict.TOUCHED)
      assertThat(found.meets).isFalse()
      assertThat(found.unlisted).isEqualTo(1)
    }
  }

  @Test
  fun senzaProposteIlViaggioManca() {
    val listed = listOf(trip("v01", day(3, 12), day(3, 19)))
    val found = TripsMatching.compare(listed, listOf(proposal(day(5, 1), day(5, 5))), since, homeKnown = true)
    assertThat(found.verdicts["v01"]).isEqualTo(TripVerdict.MISSING)
    assertThat(found.missing).isEqualTo(1)
    assertThat(found.unlisted).isEqualTo(1)
  }

  @Test
  fun glieventiNonSonoViaggiEUnViaggioVecchioNonConta() {
    val listed = listOf(trip("v01", day(3, 12), day(3, 19)), trip("v02", LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 5)))
    val proposals = listOf(proposal(day(3, 12), day(3, 19), trip = false), proposal(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 5)))
    val found = TripsMatching.compare(listed, proposals, since, homeKnown = true)
    // L'evento negli stessi giorni non trova il viaggio; il viaggio del 2020 e' fuori dagli ultimi 2 anni (e la sua proposta non si conta).
    assertThat(found.verdicts["v01"]).isEqualTo(TripVerdict.MISSING)
    assertThat(found.verdicts["v02"]).isEqualTo(TripVerdict.OUTSIDE)
    assertThat(found.listed).isEqualTo(1)
    assertThat(found.tripProposals).isEqualTo(0)
    assertThat(found.eventProposals).isEqualTo(1)
  }

  @Test
  fun unaPropostaInPiuNonESbagliataMaSiConta() {
    val listed = listOf(trip("v01", day(3, 12), day(3, 19)))
    val found = TripsMatching.compare(listed, listOf(proposal(day(3, 12), day(3, 19)), proposal(day(7, 1), day(7, 4))), since, homeKnown = true)
    assertThat(found.meets).isTrue()
    assertThat(found.tripProposals).isEqualTo(2)
    assertThat(found.unlisted).isEqualTo(1)
  }

  @Test
  fun unElencoVuotoNonRaggiungeF89EIlResocontoHaSoloNumeri() {
    assertThat(TripsMatching.compare(emptyList(), emptyList(), since, homeKnown = false).meets).isFalse()
    val listed = listOf(trip("v01", day(3, 12), day(3, 19)), trip("v02", day(6, 1), day(6, 9)))
    val found = TripsMatching.compare(listed, listOf(proposal(day(3, 12), day(3, 19))), since, homeKnown = true)
    val text = found.toNumbersText()
    println(text)
    assertThat(text).contains("2 viaggi nell'elenco")
    assertThat(text).contains("trovati: 1")
    assertThat(text).contains("non trovati: 1")
    assertThat(text).contains("v01 trovato, v02 non trovato")
    assertThat(text).contains("F89: NON raggiunto")
    assertThat(text).doesNotContain("2026")
    assertThat(text).doesNotContain("Titolo")
  }

  @Test
  fun laSovrapposizioneSiMisuraInGiorni() {
    assertThat(TripsMatching.overlapDays(day(3, 1), day(3, 10), day(3, 10), day(3, 20))).isEqualTo(1)
    assertThat(TripsMatching.overlapDays(day(3, 1), day(3, 10), day(3, 11), day(3, 20))).isEqualTo(0)
    assertThat(TripsMatching.overlapDays(day(3, 1), day(3, 31), day(3, 10), day(3, 12))).isEqualTo(3)
  }
}
