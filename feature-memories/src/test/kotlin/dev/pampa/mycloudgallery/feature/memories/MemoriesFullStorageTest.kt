package dev.pampa.mycloudgallery.feature.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.settings.SyncedMemoriesSettings
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesMessage
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageState
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.PeopleMemoriesRow
import dev.pampa.mycloudgallery.feature.memories.contract.UndoKind
import dev.pampa.mycloudgallery.feature.memories.contract.UndoOffer
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageController
import dev.pampa.mycloudgallery.feature.memories.settings.MemoriesSettingsController
import dev.pampa.mycloudgallery.feature.memories.sheets.LessFlow
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import dev.pampa.mycloudgallery.feature.memories.testing.FakeMusicLibrary
import dev.pampa.mycloudgallery.feature.memories.testing.FakeNotificationAccess
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMusicSettings
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifySettings
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * K5 (regressione di K3b): con la **memoria del telefono piena** il registro dei dati personali non si scrive, e le scelte sui ricordi non devono diventare un successo
 * muto. Le schermate sono quelle vere sopra le scelte vere (`SyncedMemoriesSettings` su `LedgerPersonalStore`, con un registro che rifiuta le scritture): uno scarto che
 * non si salva **non offre "Annulla"**, "Mostra meno" e "Non ricordarmi questo periodo" **non vanno avanti**, le impostazioni lo dicono. E l'app non si chiude: l'errore
 * si ferma nel controller. Prima di K5 le scritture rispondevano "fatto" e queste prove fallivano.
 */
class MemoriesFullStorageTest {

  private val settingsScope = CoroutineScope(Dispatchers.Unconfined + Job())
  private val ledger = InMemoryPersonalLedger()
  private val store = LedgerPersonalStore(Base32.encode(ByteArray(16) { 1 }), { 1_760_000_000_000L }, ledger)
  private val personal = SyncedMemoriesSettings(store, settingsScope)
  private val anna = PersonId.fromBytes(ByteArray(16) { (it + 31).toByte() })

  @After
  fun close() = settingsScope.cancel()

  private fun page(rig: MemoriesRig, id: MemoryId, scope: CoroutineScope) =
    MemoryPageController(id, rig.memories, MemoryNames(rig.people, NoMemoryPlaceNames), FinalFilter(rig.lib.visibility), scope)

  @Test
  fun unoScartoCheNonSiSalvaNonOffreAnnulla_eIlRicordoRestaLi() = runTest {
    val rig = MemoriesRig(personalSettings = personal)
    rig.refresh()
    val card = rig.card(MemoryKind.ON_THIS_DAY, 1)
    ledger.full = true
    val page = page(rig, card.id, backgroundScope)
    runCurrent()
    // Aprire la pagina a memoria piena non dice niente: il "visto" non e' un gesto dell'utente (si riscrive alla prossima apertura).
    assertThat(page.ui.value.page).isInstanceOf(MemoryPageState.Ready::class.java)
    assertThat(page.ui.value.message).isNull()

    page.dismissMemory()
    runCurrent()

    assertThat(page.ui.value.undo).isNull()
    assertThat(page.ui.value.message).isEqualTo(MemoriesMessage.FAILED)
    assertThat(page.ui.value.page).isInstanceOf(MemoryPageState.Ready::class.java)
    assertThat(rig.cards().map { it.id }).contains(card.id)
    assertThat(personal.current().dismissed).isEmpty()

    // Liberato lo spazio, lo stesso gesto riesce e "Annulla" c'e'.
    ledger.full = false
    page.dismissMessage()
    page.dismissMemory()
    runCurrent()
    assertThat(page.ui.value.page).isEqualTo(MemoryPageState.Gone)
    assertThat(page.ui.value.undo).isEqualTo(UndoOffer(UndoKind.DISMISS))
  }

  @Test
  fun mostraMenoCheNonSiSalvaNonVaAvanti() = runTest {
    val rig = MemoriesRig(personalSettings = personal)
    rig.refresh()
    var failures = 0
    val flow = LessFlow(rig.memories, backgroundScope, onFailure = { failures++ })
    ledger.full = true

    flow.openPerson(anna, "Anna")
    flow.choose(LessLevel.LESS)
    runCurrent()

    // Niente "Fatto", niente "Annulla": la scelta non c'e', e chi ospita il foglio lo sa.
    assertThat(flow.sheet.value).isNull()
    assertThat(flow.undo.value).isNull()
    assertThat(failures).isEqualTo(1)
    assertThat(rig.memories.lessChoices.value).isEmpty()
    assertThat(personal.current().people).isEmpty()
  }

  @Test
  fun nonRicordarmiQuestoPeriodoCheNonSiSalvaNonVaAvanti() = runTest {
    val rig = MemoriesRig(personalSettings = personal)
    rig.refresh()
    var failures = 0
    val flow = LessFlow(rig.memories, backgroundScope, onFailure = { failures++ })
    ledger.full = true

    flow.openDays(DayRange(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 6)))
    flow.confirmDays()
    runCurrent()

    assertThat(flow.sheet.value).isNull()
    assertThat(flow.undo.value).isNull()
    assertThat(failures).isEqualTo(1)
    assertThat(rig.memories.lessChoices.value).isEmpty()
    assertThat(personal.current().neverDays).isEmpty()
  }

  @Test
  fun unAnnullaCheNonSiSalvaNonDiceAnnullato() = runTest {
    val rig = MemoriesRig(personalSettings = personal)
    rig.refresh()
    var failures = 0
    val flow = LessFlow(rig.memories, backgroundScope, onFailure = { failures++ })
    flow.openPerson(anna, "Anna")
    flow.choose(LessLevel.NEVER)
    runCurrent()
    assertThat(flow.undo.value).isEqualTo(UndoOffer(UndoKind.LESS_PERSON))

    ledger.full = true
    flow.undo()
    runCurrent()

    // La scelta e' ancora quella salvata: dire "Annullato" sarebbe falso.
    assertThat(flow.sheet.value).isNotEqualTo(dev.pampa.mycloudgallery.feature.memories.contract.LessSheetState.Undone)
    assertThat(failures).isEqualTo(1)
    assertThat(personal.current().people).containsKey(anna)
  }

  @Test
  fun leImpostazioniDeiRicordiDiconoCheNonSiESalvato() = runTest {
    val rig = MemoriesRig(personalSettings = personal)
    rig.refresh()
    val controller = MemoriesSettingsController(
      rig.memories, InMemoryNotifySettings(), FakeNotificationAccess(), InMemoryMusicSettings(), FakeMusicLibrary(), MutableStateFlow(true), backgroundScope, emptyList(),
    )
    runCurrent()
    ledger.full = true

    controller.setPeople(false)
    runCurrent()

    assertThat(controller.ui.value.message).isEqualTo(MemoriesMessage.FAILED)
    assertThat(controller.ui.value.state.people).isEqualTo(PeopleMemoriesRow.ON)
  }
}
