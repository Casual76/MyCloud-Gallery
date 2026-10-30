package dev.pampa.mycloudgallery.feature.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.core.memories.contract.ShelfState
import dev.pampa.mycloudgallery.core.memories.contract.ShelfView
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesListState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSectionState
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesEntryTarget
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionStates
import dev.pampa.mycloudgallery.feature.memories.testing.MemoriesSamples
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Test

/**
 * La sezione Ricordi delle Raccolte e "Tutti i ricordi" come funzioni pure (Fase 9, P9; §1.5, §2.5, D14, E-MEM1): ogni caso, l'ordine (prima i non
 * visti), il tetto di 6 voci, i mesi di "Tutti i ricordi" e i codici stabili delle voci.
 */
class MemoriesSectionStatesTest {

  private fun card(day: Int, isNew: Boolean, seed: Int): MemoryCard {
    val key = MemoryKey.OnThisDay(LocalDate.of(2025, 1, 1).plusDays(seed.toLong()))
    return MemoryCard(
      MemoryIds.of(key), MemoryKind.ON_THIS_DAY, MemoryTitle(1, DayRange.of(key.day), emptyList(), null, null, null), MemoriesSamples.media(seed), 12, day, isNew,
    )
  }

  private fun ready(items: List<MemoryCard>) = ShelfView(items, items.count { it.isNew }, ShelfState.Ready)

  @Test
  fun inGenerazioneDiceSoloPrepara() {
    assertThat(MemoriesSectionStates.section(ShelfView.WORKING)).isEqualTo(MemoriesSectionState.Working)
  }

  @Test
  fun daVuotaDiceChePocheFoto_eLibreriaNonPronta() {
    assertThat(MemoriesSectionStates.section(ShelfView.EMPTY)).isEqualTo(MemoriesSectionState.Empty(ShelfEmptyReason.NOT_ENOUGH_PHOTOS))
    val notReady = ShelfView(emptyList(), 0, ShelfState.Empty(ShelfEmptyReason.LIBRARY_NOT_READY))
    assertThat(MemoriesSectionStates.section(notReady)).isEqualTo(MemoriesSectionState.Empty(ShelfEmptyReason.LIBRARY_NOT_READY))
  }

  /** Lo scaffale pronto ma con tutte le voci tolte dal filtro finale dei Nascosti: vuota, con "poche foto" (E-MEM1). */
  @Test
  fun tuttoNascostoDopoIlFiltroFinaleEVuota() {
    val view = ready(listOf(card(20261103, true, 1)))
    assertThat(MemoriesSectionStates.section(view, cards = emptyList())).isEqualTo(MemoriesSectionState.Empty(ShelfEmptyReason.NOT_ENOUGH_PHOTOS))
  }

  @Test
  fun primaINonVistiPoiIPiuRecenti_alPiuSei() {
    // Lo scaffale e' dal piu' recente: 8 voci, i non visti sono la 3a, la 5a e la 7a.
    val items = (1..8).map { card(20261120 - it, isNew = it in setOf(3, 5, 7), seed = it) }
    val state = MemoriesSectionStates.section(ready(items)) as MemoriesSectionState.Ready
    assertThat(state.cards).hasSize(MemoriesSectionState.Ready.MAX_CARDS)
    assertThat(state.cards.take(3).all { it.isNew }).isTrue()
    assertThat(state.cards.drop(3).none { it.isNew }).isTrue()
    // A pari condizione l'ordine dello scaffale (dal piu' recente) resta.
    assertThat(state.cards.take(3).map { it.publishedDay }).isEqualTo(listOf(20261117, 20261115, 20261113))
    assertThat(state.cards.drop(3).map { it.publishedDay }).isEqualTo(listOf(20261119, 20261118, 20261116))
    // I non visti contano tutti, non solo quelli in vista.
    assertThat(state.unseen).isEqualTo(3)
  }

  @Test
  fun tuttiIRicordiPerMeseDiPubblicazione_ilPiuRecentePerPrimo() {
    val items = listOf(card(20261103, true, 1), card(20261029, false, 2), card(20261003, false, 3), card(20260912, false, 4))
    val list = MemoriesSectionStates.list(items) as MemoriesListState.Ready
    assertThat(list.byMonth.map { it.month }).containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 10), YearMonth.of(2026, 9)).inOrder()
    assertThat(list.byMonth[1].cards.map { it.publishedDay }).containsExactly(20261029, 20261003).inOrder()
    assertThat(MemoriesSectionStates.list(emptyList())).isEqualTo(MemoriesListState.Empty)
  }

  @Test
  fun unGiornoRovinatoNonFaCrollare() {
    assertThat(MemoriesSectionStates.monthOf(0)).isEqualTo(YearMonth.of(1970, 1))
    assertThat(MemoriesSectionStates.monthOf(20261399)).isEqualTo(YearMonth.of(1970, 1))
  }

  /** D14: `m_<16 caratteri>`, stabile (stesso id = stesso codice), senza dati dell'utente, e le voci si riconoscono. */
  @Test
  fun iCodiciDelleVociSonoStabiliERiconosciuti() {
    val a = card(20261103, true, 1)
    val code = MemoriesSectionStates.codeOf(a.id)
    assertThat(code).startsWith("m_")
    assertThat(code).hasLength(2 + 16)
    assertThat(MemoriesSectionStates.codeOf(a.id)).isEqualTo(code)
    assertThat(code).isNotEqualTo(MemoriesSectionStates.codeOf(card(20261103, true, 2).id))
    assertThat(MemoriesEntryTarget.parse(code)).isEqualTo(MemoriesEntryTarget.Memory(code))
    assertThat(MemoriesEntryTarget.parse(MemoriesEntryTarget.ALL)).isEqualTo(MemoriesEntryTarget.All)
    for (inert in listOf(MemoriesEntryTarget.EMPTY, MemoriesEntryTarget.LIBRARY, MemoriesEntryTarget.WORKING)) assertThat(MemoriesEntryTarget.parse(inert)).isEqualTo(MemoriesEntryTarget.Inert)
    assertThat(MemoriesEntryTarget.parse("m_corto")).isNull()
    assertThat(MemoriesEntryTarget.parse("people/all")).isNull()
    assertThat(MemoriesEntryTarget.Memory(code).toString()).doesNotContain(code)
  }
}
