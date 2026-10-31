package dev.pampa.mycloudgallery.feature.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesListState
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.list.AllMemoriesController
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Tutti i ricordi" (Fase 9, P9; §1.5 `MemoriesListState`, §2.5, D14, D31, E-H8): lo scaffale intero per mese, **istantaneo** (il primo fotogramma e' lo scaffale com'e',
 * senza copertine) e poi con il filtro finale dei Nascosti, che consegna la Libreria da cui disegnare le copertine solo dopo aver tolto i nascosti dell'ultimo momento.
 */
class AllMemoriesControllerTest {

  private fun controller(rig: MemoriesRig, scope: CoroutineScope, mask: Boolean = true) =
    AllMemoriesController(rig.memories, MemoryNames(rig.people, NoMemoryPlaceNames), FinalFilter(if (mask) rig.lib.visibility else NothingHidden), scope)

  @Test
  fun ilPrimoFotogrammaELoScaffaleComeE_senzaCopertine_poiLeCopertine() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val all = controller(rig, backgroundScope)
    // Subito: il mese di oggi con i tre ricordi, nessuna copertina ancora (arrivano dopo il filtro finale dei Nascosti).
    val first = all.ui.value
    assertThat(first.snapshot).isNull()
    val month = (first.state as MemoriesListState.Ready).byMonth.single()
    assertThat(month.month).isEqualTo(YearMonth.of(2026, 11))
    assertThat(month.cards).hasSize(3)
    runCurrent()
    // Poi le copertine: la Libreria da cui si disegnano.
    assertThat(all.ui.value.snapshot).isNotNull()
    assertThat((all.ui.value.state as MemoriesListState.Ready).byMonth.single().cards).hasSize(3)
  }

  @Test
  fun unNascostoDellUltimoMomentoNonEnellaCopertina_ilContoScende() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val card = rig.card(MemoryKind.ON_THIS_DAY, 1)
    val members = rig.members(card.id)
    rig.lib.visibility.hidden += card.cover
    val all = controller(rig, backgroundScope)
    runCurrent()
    val refined = (all.ui.value.state as MemoriesListState.Ready).byMonth.flatMap { it.cards }.first { it.id == card.id }
    assertThat(refined.cover).isNotEqualTo(card.cover)
    assertThat(refined.cover).isIn(members)
    assertThat(refined.count).isEqualTo(members.size - 1)
  }

  @Test
  fun unRicordoScartatoNonCe_eUnoSenzaElementiVisibiliNeppure() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val dismissed = rig.card(MemoryKind.MONTH)
    rig.memories.dismiss(dismissed.id)
    val card = rig.card(MemoryKind.ON_THIS_DAY, 1)
    rig.members(card.id).take(7).forEach { rig.lib.visibility.hidden += it }
    val all = controller(rig, backgroundScope)
    runCurrent()
    val ids = (all.ui.value.state as MemoriesListState.Ready).byMonth.flatMap { it.cards }.map { it.id }
    assertThat(ids).doesNotContain(dismissed.id)
    assertThat(ids).doesNotContain(card.id)
    assertThat(ids).hasSize(1)
  }

  @Test
  fun senzaRicordiEVuoto_eSenzaMascheraValeIlServizio() = runTest {
    val empty = MemoriesRig(today = java.time.LocalDate.of(2026, 11, 3))
    val none = controller(empty, backgroundScope)
    runCurrent()
    // Mai un giro: lo scaffale e' "in generazione" e non ha voci.
    assertThat(none.ui.value.state).isEqualTo(MemoriesListState.Empty)
    val rig = MemoriesRig()
    rig.refresh()
    val plain = controller(rig, backgroundScope, mask = false)
    runCurrent()
    assertThat((plain.ui.value.state as MemoriesListState.Ready).byMonth.flatMap { it.cards }).hasSize(3)
  }
}
