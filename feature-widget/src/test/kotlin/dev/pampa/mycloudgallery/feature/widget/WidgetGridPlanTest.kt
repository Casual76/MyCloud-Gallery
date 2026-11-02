package dev.pampa.mycloudgallery.feature.widget

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.feature.widget.ui.WidgetGridPlan
import org.junit.Test

/** La disposizione delle celle (Fase 10, P3, D3): ogni cella compare una volta sola, in ordine, per ogni misura e ogni numero di celle; nessun buco, nessun segnaposto. */
class WidgetGridPlanTest {

  @Test
  fun leTreMisureConTuttiIPostiPieni() {
    assertThat(WidgetGridPlan.groups(WidgetSize.SMALL, 1)).isEqualTo(listOf(listOf(listOf(0))))
    assertThat(WidgetGridPlan.groups(WidgetSize.WIDE, 4)).isEqualTo(listOf(listOf(listOf(0, 1, 2, 3))))
    // La grande: la copertina da sola (mezza altezza) e le altre sei in due righe da tre.
    assertThat(WidgetGridPlan.groups(WidgetSize.LARGE, 7)).isEqualTo(listOf(listOf(listOf(0)), listOf(listOf(1, 2, 3), listOf(4, 5, 6))))
  }

  @Test
  fun ogniNumeroDiCelleDaUnaDisposizioneSenzaBuchiNeRipetizioni() {
    for (size in WidgetSize.entries) {
      for (count in 0..size.cells) {
        val groups = WidgetGridPlan.groups(size, count)
        assertThat(WidgetGridPlan.flatten(groups)).isEqualTo((0 until count).toList())
        // Nessuna riga vuota, nessun gruppo vuoto.
        assertThat(groups.all { group -> group.isNotEmpty() && group.all { it.isNotEmpty() } }).isTrue()
      }
    }
    assertThat(WidgetGridPlan.groups(WidgetSize.LARGE, 0)).isEmpty()
    // Un numero fuori misura si riporta alla misura: mai una cella che non c'e'.
    assertThat(WidgetGridPlan.flatten(WidgetGridPlan.groups(WidgetSize.SMALL, 5))).isEqualTo(listOf(0))
    assertThat(WidgetGridPlan.flatten(WidgetGridPlan.groups(WidgetSize.LARGE, 99))).isEqualTo((0 until 7).toList())
  }

  @Test
  fun leRigheCorteDellaGrandeTengonoLeColonneAllineate() {
    // Cinque celle: la copertina e quattro (tre e una): la riga corta si completa con un posto vuoto per tenere le tre colonne.
    val groups = WidgetGridPlan.groups(WidgetSize.LARGE, 5)
    assertThat(groups[1]).isEqualTo(listOf(listOf(1, 2, 3), listOf(4)))
    assertThat(WidgetGridPlan.paddingOf(WidgetSize.LARGE, 1, 1)).isEqualTo(2)
    assertThat(WidgetGridPlan.paddingOf(WidgetSize.LARGE, 1, 3)).isEqualTo(0)
    // La copertina e le righe di altre misure non si completano: la larga con tre celle le allarga.
    assertThat(WidgetGridPlan.paddingOf(WidgetSize.LARGE, 0, 1)).isEqualTo(0)
    assertThat(WidgetGridPlan.paddingOf(WidgetSize.WIDE, 0, 3)).isEqualTo(0)
  }
}
