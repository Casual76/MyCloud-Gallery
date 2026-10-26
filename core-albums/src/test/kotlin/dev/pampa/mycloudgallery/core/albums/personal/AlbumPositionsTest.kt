package dev.pampa.mycloudgallery.core.albums.personal

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

/**
 * Le posizioni a intervalli (D21, §2.1 "Ordine"): un inserimento in mezzo cambia **una** voce e non muove gli altri; solo quando fra due
 * elementi non c'e' piu' posto si ricompatta, a intervalli, nello stesso ordine. Pura: niente catalogo.
 */
class AlbumPositionsTest {

  private fun items(vararg pairs: Pair<String, Long>) = pairs.map { PositionedItem(it.first, it.second) }

  private fun order(list: Collection<PositionedItem>) = AlbumPositions.sorted(list).map { it.media }

  /** Applica un piano a una sequenza: le voci scritte sostituiscono o aggiungono. */
  private fun apply(current: List<PositionedItem>, plan: PositionPlan): List<PositionedItem> {
    val byMedia = current.associateBy { it.media }.toMutableMap()
    plan.changes.forEach { byMedia[it.media] = it }
    return byMedia.values.toList()
  }

  @Test
  fun leAggiunteInCodaVannoAIntervalli() {
    val plan = AlbumPositions.append(emptyList(), listOf("a", "b", "c"))
    assertThat(plan.recompacted).isFalse()
    assertThat(plan.changes).containsExactly(PositionedItem("a", 1_000), PositionedItem("b", 2_000), PositionedItem("c", 3_000)).inOrder()
    val next = AlbumPositions.append(plan.changes, listOf("d"))
    assertThat(next.changes).containsExactly(PositionedItem("d", 4_000))
  }

  @Test
  fun inserireInMezzoScriveUnaVoceSolaELasciaGliAltriDoveSono() {
    val current = items("a" to 1_000, "b" to 2_000, "c" to 3_000)
    val plan = AlbumPositions.insertAfter(current, "x", "a")
    assertThat(plan.recompacted).isFalse()
    assertThat(plan.changes).containsExactly(PositionedItem("x", 1_500))
    assertThat(order(apply(current, plan))).containsExactly("a", "x", "b", "c").inOrder()
  }

  @Test
  fun inTestaEInCodaSenzaRicompattare() {
    val current = items("a" to 1_000, "b" to 2_000)
    assertThat(AlbumPositions.insertAfter(current, "x", null).changes).containsExactly(PositionedItem("x", 0))
    assertThat(AlbumPositions.insertAfter(current, "x", "b").changes).containsExactly(PositionedItem("x", 3_000))
    assertThat(AlbumPositions.insertAfter(emptyList(), "x", null).changes).containsExactly(PositionedItem("x", 1_000))
  }

  @Test
  fun spostareUnElementoLoRimetteDopoIlVicinoSceltoESpostaSoloLui() {
    val current = items("a" to 1_000, "b" to 2_000, "c" to 3_000, "d" to 4_000)
    val plan = AlbumPositions.insertAfter(current, "a", "c")
    assertThat(plan.changes).containsExactly(PositionedItem("a", 3_500))
    assertThat(order(apply(current, plan))).containsExactly("b", "c", "a", "d").inOrder()
  }

  @Test
  fun senzaPostoFraDueVicinoSiRicompattaTuttoNelloStessoOrdine() {
    val current = items("a" to 1_000, "b" to 1_001, "c" to 5_000)
    val plan = AlbumPositions.insertAfter(current, "x", "a")
    assertThat(plan.recompacted).isTrue()
    assertThat(plan.changes.map { it.media }).containsExactly("a", "x", "b", "c").inOrder()
    assertThat(plan.changes.map { it.position }).containsExactly(1_000L, 2_000L, 3_000L, 4_000L).inOrder()
  }

  @Test
  fun dueElementiConLaStessaPosizioneSiOrdinanoPerIdEPerInserirvisiInMezzoSiRicompatta() {
    // Due dispositivi hanno aggiunto insieme e hanno scelto la stessa posizione: l'ordine resta quello per id su ogni dispositivo.
    val current = items("b" to 2_000, "a" to 2_000, "c" to 3_000)
    assertThat(order(current)).containsExactly("a", "b", "c").inOrder()
    val plan = AlbumPositions.insertAfter(current, "x", "a")
    assertThat(plan.recompacted).isTrue()
    assertThat(order(apply(current, plan))).containsExactly("a", "x", "b", "c").inOrder()
  }

  @Test
  fun aParitaDiPosizioneDecideUnDatoDellElementoNonLIdDelMedia() {
    // Due dispositivi con id diversi per gli stessi file (E-I18) devono ordinare uguale: decide l'impronta, poi l'id.
    val onPhone = listOf(PositionedItem("p-9", 2_000, "aaa"), PositionedItem("p-1", 2_000, "bbb"), PositionedItem("p-5", 1_000, "zzz"))
    val onTablet = listOf(PositionedItem("t-1", 2_000, "aaa"), PositionedItem("t-9", 2_000, "bbb"), PositionedItem("t-5", 1_000, "zzz"))
    assertThat(AlbumPositions.sorted(onPhone).map { it.tie }).containsExactly("zzz", "aaa", "bbb").inOrder()
    assertThat(AlbumPositions.sorted(onTablet).map { it.tie }).containsExactly("zzz", "aaa", "bbb").inOrder()
  }

  @Test
  fun unaVoceDellaFase4SenzaPostoNonFaSbordare() {
    // Una voce vecchia (flag acceso) vale Long.MAX_VALUE: aggiungere in coda o inserire dopo non somma, ricompatta.
    val current = items("a" to 1_000, "old" to Long.MAX_VALUE)
    val append = AlbumPositions.append(current, listOf("n"))
    assertThat(append.recompacted).isTrue()
    assertThat(append.changes.map { it.media }).containsExactly("a", "old", "n").inOrder()
    val after = AlbumPositions.insertAfter(current, "x", "old")
    assertThat(after.recompacted).isTrue()
    assertThat(after.changes.map { it.media }).containsExactly("a", "old", "x").inOrder()
    // In mezzo fra un posto normale e quello enorme la distanza non sborda.
    val between = AlbumPositions.insertAfter(current, "x", "a")
    assertThat(between.recompacted).isFalse()
    assertThat(order(apply(current, between))).containsExactly("a", "x", "old").inOrder()
  }

  @Test
  fun unElementoNonVaDopoSeStessoNeDopoUnoCheNonC() {
    val current = items("a" to 1_000)
    runCatching { AlbumPositions.insertAfter(current, "a", "a") }.also { assertThat(it.isFailure).isTrue() }
    runCatching { AlbumPositions.insertAfter(current, "x", "zzz") }.also { assertThat(it.isFailure).isTrue() }
  }

  /** Il modello: una lista. Dopo migliaia di spostamenti a caso l'ordine delle posizioni e' quello della lista, e senza ricompattare cambia una voce. */
  @Test
  fun dopoMigliaiaDiSpostamentiAcasoLOrdineEQuelloVoluto() {
    for (seed in 1..8) {
      val random = Random(seed)
      var model = (0 until 12).map { "m$it" }
      var current = AlbumPositions.append(emptyList(), model).changes
      var recompactions = 0
      var next = 100
      repeat(1_500) {
        val moved = if (random.nextInt(4) == 0) "m${next++}" else model[random.nextInt(model.size)]
        val candidates = model.filter { it != moved }
        val after = if (candidates.isEmpty() || random.nextInt(6) == 0) null else candidates[random.nextInt(candidates.size)]
        val plan = AlbumPositions.insertAfter(current, moved, after)
        val rest = model.filter { it != moved }
        model = rest.take(if (after == null) 0 else rest.indexOf(after) + 1) + moved + rest.drop(if (after == null) 0 else rest.indexOf(after) + 1)
        val before = current.associate { it.media to it.position }
        current = apply(current, plan)
        if (plan.recompacted) {
          recompactions++
        } else {
          assertThat(plan.changes).hasSize(1)
          // Gli altri non si sono mossi.
          current.filter { it.media != moved }.forEach { assertThat(before[it.media]).isEqualTo(it.position) }
        }
        assertThat(order(current)).isEqualTo(model)
      }
      // Inserire sempre nello stesso punto esaurisce i posti: prima o poi si ricompatta, e succede di rado.
      assertThat(recompactions).isLessThan(400)
    }
  }

  @Test
  fun inserireSempreNelloStessoPuntoRicompattaDiTantoInTanto() {
    var current = AlbumPositions.append(emptyList(), listOf("a", "z")).changes
    var recompactions = 0
    for (i in 0 until 200) {
      val plan = AlbumPositions.insertAfter(current, "n$i", "a")
      if (plan.recompacted) recompactions++
      current = apply(current, plan)
      assertThat(order(current).take(2)).containsExactly("a", "n$i").inOrder()
    }
    assertThat(recompactions).isGreaterThan(0)
    assertThat(recompactions).isLessThan(50)
    assertThat(order(current).last()).isEqualTo("z")
  }
}
