package dev.pampa.mycloudgallery.feature.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.memories.contract.LessSheetState
import dev.pampa.mycloudgallery.feature.memories.contract.UndoKind
import dev.pampa.mycloudgallery.feature.memories.contract.UndoOffer
import dev.pampa.mycloudgallery.feature.memories.sheets.LessFlow
import java.time.LocalDate
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * I fogli "Mostra meno" e "Non ricordarmi questo periodo" e l'"Annulla" per 10 secondi (Fase 9, P9; §2.3, D11, E-MEM9, E-MEM11, E-MEM12, E-MEM22), sul
 * servizio dei ricordi di riferimento. Il tempo e' virtuale: nessuna attesa scritta a mano. Gli id delle persone sono casuali (mai un nome).
 */
class LessFlowTest {

  private val anna = PersonId.fromBytes(ByteArray(16) { (it + 31).toByte() })

  private fun levelOf(rig: MemoriesRig, person: PersonId): LessLevel? =
    rig.memories.lessChoices.value.firstOrNull { (it.target as? LessTarget.Person)?.person == person }?.level

  @Test
  fun mostraMenoSiSalvaSubitoEOffreAnnullaPerDieciSecondi() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val flow = LessFlow(rig.memories, backgroundScope)

    flow.openPerson(anna, "Anna")
    assertThat(flow.sheet.value).isEqualTo(LessSheetState.Person(anna, "Anna", null))
    flow.choose(LessLevel.LESS)
    runCurrent()
    // Fatto subito (E-MEM22), il foglio si chiude e c'e' "Annulla".
    assertThat(levelOf(rig, anna)).isEqualTo(LessLevel.LESS)
    assertThat(flow.sheet.value).isInstanceOf(LessSheetState.Done::class.java)
    assertThat(flow.undo.value).isEqualTo(UndoOffer(UndoKind.LESS_PERSON))

    flow.undo()
    runCurrent()
    assertThat(levelOf(rig, anna)).isNull()
    assertThat(flow.sheet.value).isEqualTo(LessSheetState.Undone)
    assertThat(flow.undo.value).isNull()
  }

  @Test
  fun passatiDieciSecondiLaScelta_Resta() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val flow = LessFlow(rig.memories, backgroundScope)
    flow.openPerson(anna, null)
    flow.choose(LessLevel.NEVER)
    runCurrent()
    advanceTimeBy(UndoOffer.WINDOW_SECONDS * 1_000L - 1)
    runCurrent()
    assertThat(flow.undo.value).isNotNull()
    advanceTimeBy(2)
    runCurrent()
    assertThat(flow.undo.value).isNull()
    assertThat(flow.sheet.value).isNull()
    // Annulla non fa piu' niente: la scelta e' rimasta.
    flow.undo()
    runCurrent()
    assertThat(levelOf(rig, anna)).isEqualTo(LessLevel.NEVER)
  }

  @Test
  fun ilFoglioMostraLaScelta_diAdesso_eTogliLaCancella() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    rig.memories.setLess(LessTarget.Person(anna), LessLevel.LESS)
    val flow = LessFlow(rig.memories, backgroundScope)
    flow.openPerson(anna, "Anna")
    assertThat((flow.sheet.value as LessSheetState.Person).level).isEqualTo(LessLevel.LESS)
    // Da "meno" a "mai": cambia, e "Annulla" riporta "meno".
    flow.choose(LessLevel.NEVER)
    runCurrent()
    assertThat(levelOf(rig, anna)).isEqualTo(LessLevel.NEVER)
    flow.undo()
    runCurrent()
    assertThat(levelOf(rig, anna)).isEqualTo(LessLevel.LESS)
    // "Togli la scelta": nessun "Annulla" (non c'e' una scelta da annullare), la persona torna come prima.
    flow.openPerson(anna, "Anna")
    flow.choose(null)
    runCurrent()
    assertThat(levelOf(rig, anna)).isNull()
    assertThat(flow.undo.value).isNull()
  }

  @Test
  fun scegliereLaStessaCosaNonFaNiente() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    rig.memories.setLess(LessTarget.Person(anna), LessLevel.LESS)
    val flow = LessFlow(rig.memories, backgroundScope)
    flow.openPerson(anna, "Anna")
    flow.choose(LessLevel.LESS)
    runCurrent()
    assertThat(flow.sheet.value).isNull()
    assertThat(flow.undo.value).isNull()
  }

  @Test
  fun unPeriodoSiSalvaEOffreAnnulla_ePuoCambiareDiUnGiornoAllaVolta() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val flow = LessFlow(rig.memories, backgroundScope)
    val trip = DayRange(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 6))
    flow.openDays(trip)
    assertThat((flow.sheet.value as LessSheetState.Days).range).isEqualTo(trip)
    flow.adjustDays(-1, 2)
    assertThat((flow.sheet.value as LessSheetState.Days).range).isEqualTo(DayRange(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 8)))
    flow.onlyFirstDay()
    assertThat((flow.sheet.value as LessSheetState.Days).range).isEqualTo(DayRange.of(LocalDate.of(2024, 2, 29)))
    flow.resetDays(trip)
    flow.confirmDays()
    runCurrent()
    val saved = rig.memories.lessChoices.value.single()
    assertThat(saved.target).isEqualTo(LessTarget.Days(trip))
    assertThat(saved.level).isEqualTo(LessLevel.NEVER)
    assertThat(flow.undo.value).isEqualTo(UndoOffer(UndoKind.NEVER_DAYS))
    flow.undo()
    runCurrent()
    assertThat(rig.memories.lessChoices.value).isEmpty()
  }

  @Test
  fun unPeriodoNonSuperaMaiI366Giorni_nePuoEssereVuoto() {
    val year = DayRange(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))
    assertThat(year.days).isEqualTo(366)
    // Un ricordo piu' lungo di un anno parte ridotto a 366 giorni.
    val long = DayRange(LocalDate.of(2020, 1, 1), LocalDate.of(2024, 12, 31))
    assertThat(LessFlow.clip(long).days).isEqualTo(LessTarget.Days.MAX_DAYS)
    assertThat(LessFlow.clip(long).from).isEqualTo(long.from)
    // Allargare oltre il tetto sposta l'altro estremo; stringere sotto un giorno lo ferma a uno.
    assertThat(LessFlow.adjust(year, 0, 1).days).isEqualTo(LessTarget.Days.MAX_DAYS)
    assertThat(LessFlow.adjust(year, -1, 0).days).isEqualTo(LessTarget.Days.MAX_DAYS)
    val one = DayRange.of(LocalDate.of(2024, 5, 5))
    assertThat(LessFlow.adjust(one, 0, -1).days).isEqualTo(1)
    assertThat(LessFlow.adjust(one, 3, 0).days).isEqualTo(1)
  }

  @Test
  fun nonMostrareQuestoRicordo_Annulla_ilRicordoRitorna_conLoStessoId() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val card = rig.card(MemoryKind.ON_THIS_DAY, 1)
    val flow = LessFlow(rig.memories, backgroundScope)
    rig.memories.dismiss(card.id)
    flow.offerDismissUndo(card.id)
    assertThat(rig.cards().map { it.id }).doesNotContain(card.id)
    assertThat(flow.undo.value).isEqualTo(UndoOffer(UndoKind.DISMISS))
    flow.undo()
    runCurrent()
    assertThat(rig.cards().map { it.id }).contains(card.id)
    advanceTimeBy(UndoOffer.WINDOW_SECONDS * 1_000L)
    runCurrent()
    assertThat(flow.undo.value).isNull()
  }
}
