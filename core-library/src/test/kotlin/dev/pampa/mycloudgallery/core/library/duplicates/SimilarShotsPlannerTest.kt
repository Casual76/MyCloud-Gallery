package dev.pampa.mycloudgallery.core.library.duplicates

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.Random
import org.junit.Test

/**
 * I doppioni simili, parte pura (Fase 7, P8, D25, §2.1.1; E-U3): finestra di 10 minuti, stesso giorno, proporzione, "nel dubbio non si unisce",
 * la migliore da tenere e le foto protette (preferite, in un album). Con vettori finti: la somiglianza la decide il test.
 */
class SimilarShotsPlannerTest {

  private val minute = 60_000L
  private val t0 = 1_760_000_000_000L
  private val day = 20_251_009

  private fun id(n: Int) = MediaId("00000000-0000-4000-8000-%012d".format(n))

  private fun shot(n: Int, atMinutes: Double, localDay: Int = day, aspect: Int = 1333): Shot = Shot(id(n), t0 + (atMinutes * minute).toLong(), localDay, aspect)

  /** Chi e' simile a chi, deciso dal test: le coppie dichiarate sono SIMILAR, le altre DIFFERENT (o UNKNOWN se uno dei due e' in [unknown]). */
  private fun affinity(similar: Set<Pair<Int, Int>>, unknown: Set<Int> = emptySet()) = ShotAffinity { a, b ->
    val x = a.media.value.takeLast(12).toInt()
    val y = b.media.value.takeLast(12).toInt()
    when {
      x in unknown || y in unknown -> Affinity.UNKNOWN
      (x to y) in similar || (y to x) in similar -> Affinity.SIMILAR
      else -> Affinity.DIFFERENT
    }
  }

  private fun idsOf(cluster: List<Shot>) = cluster.map { it.media.value.takeLast(12).toInt() }

  @Test
  fun treScattiVicinaFraLoroFormanoUnGruppo() {
    val shots = listOf(shot(1, 0.0), shot(2, 0.03), shot(3, 0.07))
    val plan = SimilarShotsPlanner.plan(shots, affinity(setOf(1 to 2, 2 to 3, 1 to 3)))

    assertThat(plan.clusters).hasSize(1)
    assertThat(idsOf(plan.clusters.single())).containsExactly(1, 2, 3).inOrder()
  }

  @Test
  fun unScattoDiversoRestaFuori() {
    val shots = listOf(shot(1, 0.0), shot(2, 0.03), shot(3, 0.07))
    val plan = SimilarShotsPlanner.plan(shots, affinity(setOf(1 to 2)))

    assertThat(plan.clusters.map(::idsOf)).containsExactly(listOf(1, 2))
  }

  @Test
  fun laFinestraEDiDieciMinuti() {
    val inside = listOf(shot(1, 0.0), shot(2, 10.0))
    val outside = listOf(shot(3, 0.0), shot(4, 10.01))
    val similar = affinity(setOf(1 to 2, 3 to 4))

    assertThat(SimilarShotsPlanner.plan(inside, similar).clusters).hasSize(1)
    assertThat(SimilarShotsPlanner.plan(outside, similar).clusters).isEmpty()
  }

  @Test
  fun unaCatenaPiuLungaDellaFinestraSiSpezzaAlTempo() {
    // 1 -- 2 -- 3 -- 4: ogni coppia vicina e' simile, ma da 1 a 4 passano 24 minuti.
    val shots = listOf(shot(1, 0.0), shot(2, 8.0), shot(3, 16.0), shot(4, 24.0))
    val plan = SimilarShotsPlanner.plan(shots, affinity(setOf(1 to 2, 2 to 3, 3 to 4)))

    val windows = plan.clusters.map { cluster -> cluster.last().takenAt - cluster.first().takenAt }
    assertThat(windows.all { it <= SimilarShotsRules.WINDOW_MS }).isTrue()
    // Nessuna foto e' in due gruppi.
    assertThat(plan.clusters.flatten().map { it.media }.distinct()).hasSize(plan.clusters.sumOf { it.size })
    assertThat(plan.clusters).isNotEmpty()
  }

  @Test
  fun giorniDiversiNonSiRaggruppanoNemmenoAMezzanotte() {
    val shots = listOf(shot(1, 0.0, localDay = 20_251_009), shot(2, 1.0, localDay = 20_251_010))
    assertThat(SimilarShotsPlanner.plan(shots, affinity(setOf(1 to 2))).clusters).isEmpty()
  }

  @Test
  fun proporzioniIncompatibiliNonSiRaggruppano() {
    val shots = listOf(shot(1, 0.0, aspect = 1333), shot(2, 0.1, aspect = 750), shot(3, 0.2, aspect = 1340))
    val plan = SimilarShotsPlanner.plan(shots, affinity(setOf(1 to 2, 2 to 3, 1 to 3)))

    assertThat(plan.clusters.map(::idsOf)).containsExactly(listOf(1, 3))
  }

  @Test
  fun nelDubbioNonSiUnisce_ESiConta() {
    val shots = listOf(shot(1, 0.0), shot(2, 0.1), shot(3, 0.2))
    val plan = SimilarShotsPlanner.plan(shots, affinity(setOf(1 to 2), unknown = setOf(3)))

    assertThat(plan.clusters.map(::idsOf)).containsExactly(listOf(1, 2))
    // Il terzo ha compagni vicini ma non si e' potuto giudicare: lo dice, non lo unisce.
    assertThat(plan.undecided).isEqualTo(1)
  }

  @Test
  fun unoScattoSoloOSenzaCompagniNonFaNiente() {
    assertThat(SimilarShotsPlanner.plan(emptyList(), affinity(emptySet())).clusters).isEmpty()
    assertThat(SimilarShotsPlanner.plan(listOf(shot(1, 0.0)), affinity(emptySet())).clusters).isEmpty()
  }

  @Test
  fun ilCostoELineare_OgniScattoSiConfrontaConAlPiuQuaranta() {
    val shots = (1..300).map { shot(it, 0.0 + it * 0.001) }
    var calls = 0
    val counting = ShotAffinity { _, _ ->
      calls++
      Affinity.DIFFERENT
    }

    SimilarShotsPlanner.plan(shots, counting)

    assertThat(calls).isAtMost(300 * SimilarShotsRules.MAX_NEIGHBORS)
    assertThat(calls).isGreaterThan(300)
  }

  // --- La migliore e le foto protette -----------------------------------------------------------------------------------------

  private fun details(vararg entries: Pair<Int, ShotDetails>): Map<MediaId, ShotDetails> = entries.associate { id(it.first) to it.second }

  private fun cluster(vararg ids: Int) = ids.mapIndexed { index, n -> shot(n, index * 0.05) }

  @Test
  fun laMiglioreSegueLOrdineDellaProposta() {
    val shots = cluster(1, 2, 3, 4, 5)
    val sharp = ShotDetails(pixels = 12_000_000, bytes = 5_000_000)
    val small = ShotDetails(pixels = 8_000_000, bytes = 5_000_000)

    // La piu' grande vince a parita' di tutto.
    assertThat(SimilarShotsPlanner.best(shots, details(1 to small, 2 to sharp, 3 to small, 4 to small, 5 to small))).isEqualTo(id(2))
    // A pari pixel, la meno compressa (piu' byte per pixel).
    val light = ShotDetails(pixels = 12_000_000, bytes = 3_000_000)
    assertThat(SimilarShotsPlanner.best(shots, details(1 to light, 2 to sharp, 3 to light, 4 to light, 5 to light))).isEqualTo(id(2))
    // In un album batte i pixel.
    assertThat(SimilarShotsPlanner.best(shots, details(1 to sharp, 2 to ShotDetails(inAlbum = true, pixels = 1), 3 to sharp, 4 to sharp, 5 to sharp))).isEqualTo(id(2))
    // Modificata batte l'album.
    assertThat(
      SimilarShotsPlanner.best(shots, details(1 to ShotDetails(inAlbum = true), 2 to ShotDetails(edited = true), 3 to sharp, 4 to sharp, 5 to sharp)),
    ).isEqualTo(id(2))
    // Preferita batte tutto.
    assertThat(
      SimilarShotsPlanner.best(shots, details(1 to ShotDetails(edited = true, inAlbum = true, pixels = 99), 2 to ShotDetails(favorite = true), 3 to sharp, 4 to sharp, 5 to sharp)),
    ).isEqualTo(id(2))
    // A parita' di tutto, l'identita' minore: una sola risposta.
    assertThat(SimilarShotsPlanner.best(shots, emptyMap())).isEqualTo(id(1))
  }

  @Test
  fun e_u3_LaPreferitaELaFotoInUnAlbumSonoDaTenere_MaiPreselezionatePerLEliminazione() {
    val shots = cluster(1, 2, 3, 4)
    val d = details(
      1 to ShotDetails(pixels = 12_000_000, bytes = 4_000_000),
      2 to ShotDetails(favorite = true, pixels = 8_000_000, bytes = 2_000_000),
      3 to ShotDetails(inAlbum = true, pixels = 8_000_000, bytes = 2_000_000),
      4 to ShotDetails(pixels = 8_000_000, bytes = 2_000_000),
    )
    val cluster = SimilarShotsPlanner.cluster(shots, d)

    // La migliore e' la preferita; la foto in un album e' protetta; solo l'ultima (e la piu' grande, non protetta) e' proposta.
    assertThat(cluster.best).isEqualTo(id(2))
    assertThat(cluster.keepByDefault).containsExactly(id(2), id(3))
    assertThat(cluster.removeByDefault).containsExactly(id(1), id(4))
    assertThat(d.getValue(id(2)).isProtected).isTrue()
    assertThat(d.getValue(id(3)).isProtected).isTrue()
    assertThat(cluster.removeByDefault).containsNoneIn(listOf(id(2), id(3)))
  }

  @Test
  fun seSonoTutteProtetteNienteESelezionatoPerLEliminazione() {
    val shots = cluster(1, 2, 3)
    val d = details(1 to ShotDetails(favorite = true), 2 to ShotDetails(inAlbum = true), 3 to ShotDetails(favorite = true, inAlbum = true))
    val cluster = SimilarShotsPlanner.cluster(shots, d)

    assertThat(cluster.removeByDefault).isEmpty()
    assertThat(cluster.keepByDefault).containsExactly(id(1), id(2), id(3))
  }

  @Test
  fun laMiglioreEsempreDaTenere() {
    val shots = cluster(1, 2)
    val cluster = SimilarShotsPlanner.cluster(shots, details(1 to ShotDetails(pixels = 5), 2 to ShotDetails(pixels = 9)))
    assertThat(cluster.keepByDefault).contains(cluster.best)
    assertThat(cluster.removeByDefault).doesNotContain(cluster.best)
  }

  // --- Proprieta' -------------------------------------------------------------------------------------------------------------

  /** 300 giri di scatti casuali: ogni gruppo ha due o piu' scatti dello stesso giorno, dentro la finestra, e nessuno scatto e' in due gruppi. */
  @Test
  fun proprieta_iGruppiRispettanoSempreGiornoFinestraEUnicita() {
    val random = Random(10)
    repeat(300) { round ->
      val count = 2 + random.nextInt(60)
      val shots = (0 until count).map { n ->
        Shot(id(n + 1), t0 + random.nextInt(40 * 60_000), if (random.nextInt(6) == 0) day + 1 else day, 1333 + random.nextInt(3))
      }
      val chance = random.nextInt(30) + 5
      val verdicts = HashMap<Pair<Int, Int>, Affinity>()
      val affinity = ShotAffinity { a, b ->
        val x = a.media.value.takeLast(12).toInt()
        val y = b.media.value.takeLast(12).toInt()
        verdicts.getOrPut(minOf(x, y) to maxOf(x, y)) {
          when (random.nextInt(100)) {
            in 0 until chance -> Affinity.SIMILAR
            in chance until chance + 5 -> Affinity.UNKNOWN
            else -> Affinity.DIFFERENT
          }
        }
      }
      val plan = SimilarShotsPlanner.plan(shots, affinity)
      val message = "giro $round"
      assertWithMessage(message).that(plan.clusters.all { it.size >= 2 }).isTrue()
      assertWithMessage(message).that(plan.clusters.all { c -> c.map { it.localDay }.distinct().size == 1 }).isTrue()
      assertWithMessage(message).that(plan.clusters.all { c -> c.maxOf { it.takenAt } - c.minOf { it.takenAt } <= SimilarShotsRules.WINDOW_MS }).isTrue()
      val all = plan.clusters.flatten().map { it.media }
      assertWithMessage(message).that(all.distinct()).hasSize(all.size)
    }
  }
}
