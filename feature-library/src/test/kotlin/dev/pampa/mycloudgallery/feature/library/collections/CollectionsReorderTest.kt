package dev.pampa.mycloudgallery.feature.library.collections

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Test

/** Il trascinamento delle sezioni (logica pura, su JVM): quando due righe scambiano posto, cosa si salva e quando, i limiti. */
class CollectionsReorderTest {

  private val commits = mutableListOf<List<String>>()

  private fun state(order: List<String>, heights: Map<String, Int>): ReorderState {
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    return ReorderState(order, scope, settle = { _, apply -> apply(0f) }) { commits += it }.also { s ->
      heights.forEach { (code, height) -> s.reportHeight(code, height) }
    }
  }

  private val equal = mapOf("a" to 100, "b" to 100, "c" to 100)

  @Test
  fun unTrascinamentoCortoNonScambiaNienteESiSalvaUnaVoltaSola() {
    val s = state(listOf("a", "b", "c"), equal)
    s.begin("a")
    s.dragBy(40f)
    s.dragBy(30f)
    assertThat(s.order).containsExactly("a", "b", "c").inOrder()
    assertThat(s.dragging).isEqualTo("a")
    assertThat(s.dragOffset).isEqualTo(70f)

    s.end()

    assertThat(commits).containsExactly(listOf("a", "b", "c"))
    assertThat(s.dragging).isNull()
    assertThat(s.dragOffset).isEqualTo(0f)
  }

  @Test
  fun passareIlCentroDellaVicinaScambiaIPostiELaRigaRestaSottoIlDito() {
    val s = state(listOf("a", "b", "c"), equal)
    s.begin("a")

    s.dragBy(101f)

    assertThat(s.order).containsExactly("b", "a", "c").inOrder()
    // La riga e' ora nel posto dopo: e' 1 px sotto il centro del suo posto, non 101 px sopra.
    assertThat(s.dragOffset).isEqualTo(1f)
    s.end()
    assertThat(commits.single()).containsExactly("b", "a", "c").inOrder()
  }

  @Test
  fun unGestoLungoAttraversaPiuRighe() {
    val s = state(listOf("a", "b", "c"), equal)
    s.begin("a")

    s.dragBy(260f)

    assertThat(s.order).containsExactly("b", "c", "a").inOrder()
    // In fondo la riga non scende oltre il suo posto.
    assertThat(s.dragOffset).isEqualTo(0f)

    s.dragBy(-260f)
    assertThat(s.order).containsExactly("a", "b", "c").inOrder()
    assertThat(s.dragOffset).isEqualTo(0f)
  }

  @Test
  fun conRigheDiAltezzeDiverseIlPassaggioDelCentroTienePresenteLeAltezze() {
    val s = state(listOf("a", "b"), mapOf("a" to 100, "b" to 200))
    s.begin("a")

    s.dragBy(149f)
    assertThat(s.order).containsExactly("a", "b").inOrder()
    s.dragBy(2f)

    assertThat(s.order).containsExactly("b", "a").inOrder()
    assertThat(s.dragOffset).isEqualTo(-49f)
  }

  @Test
  fun inCimaEInFondoLaRigaNonEsceDallElenco() {
    val s = state(listOf("a", "b", "c"), equal)
    s.begin("a")
    s.dragBy(-500f)
    assertThat(s.order).containsExactly("a", "b", "c").inOrder()
    assertThat(s.dragOffset).isEqualTo(0f)
    s.end()

    val last = state(listOf("a", "b", "c"), equal)
    last.begin("c")
    last.dragBy(500f)
    assertThat(last.dragOffset).isEqualTo(0f)
  }

  @Test
  fun salireESCendereSiPuoRipensareSenzaLasciareIlDito() {
    val s = state(listOf("a", "b", "c"), equal)
    s.begin("b")
    s.dragBy(130f)
    assertThat(s.order).containsExactly("a", "c", "b").inOrder()
    s.dragBy(-130f)
    assertThat(s.order).containsExactly("a", "b", "c").inOrder()
    s.end()
    assertThat(commits.single()).containsExactly("a", "b", "c").inOrder()
  }

  @Test
  fun unGestoCheNonEIniziatoOGiaFinitoNonFaNiente() {
    val s = state(listOf("a", "b", "c"), equal)
    s.dragBy(500f)
    s.end()
    assertThat(commits).isEmpty()
    assertThat(s.order).containsExactly("a", "b", "c").inOrder()

    s.begin("zzz")
    assertThat(s.dragging).isNull()
    s.begin("a")
    s.begin("b")
    assertThat(s.dragging).isEqualTo("a")
    s.end()
    s.end()
    s.cancel()
    assertThat(commits).hasSize(1)
  }

  @Test
  fun l_OrdineDiFuoriSiSeguePrimaEDopoMaNonMentreSiTrascina() {
    val s = state(listOf("a", "b", "c"), equal)
    s.follow(listOf("c", "a", "b"))
    assertThat(s.order).containsExactly("c", "a", "b").inOrder()

    s.begin("a")
    s.follow(listOf("a", "b", "c"))
    assertThat(s.order).containsExactly("c", "a", "b").inOrder()
    s.end()
    s.follow(listOf("a", "b", "c"))
    assertThat(s.order).containsExactly("a", "b", "c").inOrder()
  }

  @Test
  fun lAzioneDiTalkBackSpostaDiUnPostoEDiceSeNonSiPuo() {
    val s = state(listOf("a", "b", "c"), equal)

    assertThat(s.moveBy("a", -1)).isNull()
    assertThat(s.moveBy("c", +1)).isNull()
    assertThat(commits).isEmpty()

    assertThat(s.moveBy("b", -1)).containsExactly("b", "a", "c").inOrder()
    assertThat(s.order).containsExactly("b", "a", "c").inOrder()
    assertThat(s.moveBy("a", +1)).containsExactly("b", "c", "a").inOrder()
    assertThat(commits).containsExactly(listOf("b", "a", "c"), listOf("b", "c", "a")).inOrder()
  }

  @Test
  fun durantIlTrascinamentoLeAzioniDiTalkBackNonFannoNiente() {
    val s = state(listOf("a", "b", "c"), equal)
    s.begin("a")
    assertThat(s.moveBy("b", -1)).isNull()
    assertThat(commits).isEmpty()
  }
}
