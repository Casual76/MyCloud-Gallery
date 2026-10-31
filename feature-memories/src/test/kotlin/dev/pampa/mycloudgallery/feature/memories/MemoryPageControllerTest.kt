package dev.pampa.mycloudgallery.feature.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.noop.NoMemories
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesMessage
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryAction
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageState
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.UndoKind
import dev.pampa.mycloudgallery.feature.memories.contract.UndoOffer
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageController
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La pagina del ricordo (Fase 9, P9; §1.5 `MemoryPageState`, §2.4, D9, D10, D19, D31, E-MEM5, E-MEM9, E-MEM13, E-H8) sul servizio dei ricordi di
 * riferimento: il primo fotogramma ha gia' il titolo, "visto" si segna qui e solo qui, un nascosto esce subito (anche quello dell'ultimo momento, dal
 * filtro finale), sotto 3 elementi "non c'e' piu'", e "Non mostrare" ha il suo "Annulla".
 */
class MemoryPageControllerTest {

  private fun controller(rig: MemoriesRig, id: MemoryId, scope: CoroutineScope, mask: FakeVisibility? = rig.lib.visibility) =
    MemoryPageController(id, rig.memories, MemoryNames(rig.people, NoMemoryPlaceNames), FinalFilter(mask ?: NothingHidden), scope)

  @Test
  fun ilPrimoFotogrammaHaGiaIlTitoloEIlVistoSiSegnaSoloAprendoLaPagina() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val card = rig.card(MemoryKind.ON_THIS_DAY, 1)
    // Solo guardare la sezione non segna niente (D10).
    assertThat(rig.memories.shelf.value.unseen).isEqualTo(3)
    val page = controller(rig, card.id, backgroundScope)
    // Prima che qualcosa giri: la voce dello scaffale e' gia' li' (istantaneo).
    assertThat(page.ui.value.page).isEqualTo(MemoryPageState.Loading)
    assertThat(page.ui.value.preview).isEqualTo(card)
    runCurrent()
    val ready = page.ui.value.page as MemoryPageState.Ready
    assertThat(ready.header.kind).isEqualTo(MemoryKind.ON_THIS_DAY)
    assertThat(ready.view.size).isEqualTo(9)
    assertThat(page.ui.value.preview).isNull()
    assertThat(ready.actions).containsExactly(MemoryAction.PLAY, MemoryAction.NEVER_DAYS, MemoryAction.DISMISS)
    // Aprire la pagina ha segnato "visto" (una volta sola, viaggia con il giorno).
    assertThat(rig.memories.shelf.value.unseen).isEqualTo(2)
    assertThat(rig.card(MemoryKind.ON_THIS_DAY, 1).isNew).isFalse()
  }

  @Test
  fun unNascostoEsceSubito_eSottoTreElementiNonCePiu() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val card = rig.card(MemoryKind.ON_THIS_DAY, 1)
    val members = rig.members(card.id)
    val page = controller(rig, card.id, backgroundScope)
    runCurrent()
    rig.lib.hide(members[0])
    runCurrent()
    val after = page.ui.value.page as MemoryPageState.Ready
    assertThat(after.view.size).isEqualTo(8)
    assertThat(after.cover).isNotEqualTo(members[0])
    // Si nascondono quasi tutti: restano due, sotto 3 (D19): "Questo ricordo non c'e' piu'".
    members.drop(1).take(6).forEach { rig.lib.hide(it) }
    runCurrent()
    assertThat(page.ui.value.page).isEqualTo(MemoryPageState.Gone)
  }

  /** D31: un media nascosto un attimo fa (il catalogo lo sa, l'istantanea ancora no) non compare, nemmeno come copertina. */
  @Test
  fun unNascostoDellUltimoMomentoNonCompare_ilFiltroFinaleLoToglie() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val card = rig.card(MemoryKind.ON_THIS_DAY, 1)
    val members = rig.members(card.id)
    // Nascosto nel catalogo ma l'istantanea e il servizio non lo sanno ancora: si scrive solo nel contratto dei nascosti.
    rig.lib.visibility.hidden += members[0]
    val page = controller(rig, card.id, backgroundScope)
    runCurrent()
    val ready = page.ui.value.page as MemoryPageState.Ready
    val shown = (0 until ready.view.size).map { ready.view.snapshot.idAt(ready.view.snapshotIndex(it)) }
    assertThat(shown).doesNotContain(members[0])
    assertThat(ready.cover).isNotEqualTo(members[0])
    assertThat(shown).hasSize(8)
  }

  @Test
  fun senzaMaschera_valeIlSoloFiltroDelServizio() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val card = rig.card(MemoryKind.ON_THIS_DAY, 1)
    val page = controller(rig, card.id, backgroundScope, mask = null)
    runCurrent()
    assertThat((page.ui.value.page as MemoryPageState.Ready).view.size).isEqualTo(9)
  }

  @Test
  fun nonMostrareQuestoRicordo_GonePoiAnnullaLoRiporta() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val card = rig.card(MemoryKind.ON_THIS_DAY, 1)
    val page = controller(rig, card.id, backgroundScope)
    runCurrent()
    page.dismissMemory()
    runCurrent()
    assertThat(page.ui.value.page).isEqualTo(MemoryPageState.Gone)
    assertThat(page.ui.value.undo).isEqualTo(UndoOffer(UndoKind.DISMISS))
    // Annulla entro 10 secondi: il ricordo e' di nuovo li', con lo stesso id (E-MEM9).
    page.undo()
    runCurrent()
    assertThat(page.ui.value.page).isInstanceOf(MemoryPageState.Ready::class.java)
    assertThat(rig.cards().map { it.id }).contains(card.id)
    // Senza annullare, dopo 10 secondi la scelta resta e il ricordo non torna.
    page.dismissMemory()
    runCurrent()
    advanceTimeBy(UndoOffer.WINDOW_SECONDS * 1_000L + 1)
    runCurrent()
    assertThat(page.ui.value.undo).isNull()
    assertThat(page.ui.value.page).isEqualTo(MemoryPageState.Gone)
  }

  @Test
  fun nonNeSuUnRicordoCheNonCeMai_NotFound_eUnoUscitoGone() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val ghost = dev.pampa.mycloudgallery.core.memories.contract.MemoryIds.of(
      dev.pampa.mycloudgallery.core.memories.contract.MemoryKey.OnThisDay(java.time.LocalDate.of(1999, 1, 1)),
    )
    val page = controller(rig, ghost, backgroundScope)
    runCurrent()
    assertThat(page.ui.value.page).isEqualTo(MemoryPageState.NotFound)
    // Senza il servizio (null-object) la pagina non crolla: non c'e'.
    val none = MemoryPageController(ghost, NoMemories, MemoryNames(null, NoMemoryPlaceNames), FinalFilter(NothingHidden), backgroundScope)
    runCurrent()
    assertThat(none.ui.value.page).isEqualTo(MemoryPageState.NotFound)
  }

  @Test
  fun neiNumeriDellaPaginaNonCiSonoTitoliNeId() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val card = rig.card(MemoryKind.ON_THIS_DAY, 1)
    val page = controller(rig, card.id, backgroundScope)
    runCurrent()
    val text = page.ui.value.toString() + (page.ui.value.page as MemoryPageState.Ready).toString() + page.ui.value.page.hashCode()
    assertThat(text).doesNotContain(card.id.value)
    assertThat(MemoriesMessage.entries.map { it.code }).containsExactly("gone", "failed")
  }

  private fun idsOf(view: dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView): List<MediaId> =
    (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
}
