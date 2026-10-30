package dev.pampa.mycloudgallery.core.memories.pick

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.DropReason
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import org.junit.Test

/** Le esclusioni di D5 una per una (E-MEM2, E-MEM18), il "Mai" di D11 e i loro conteggi: solo numeri per motivo, mai gli id. */
class PickExclusionsTest {

  private val picker = MemoryPhotoPicker()
  private val cells = Cells()

  private fun pick(candidates: List<CandidateCell>, kind: MemoryKind = MemoryKind.ON_THIS_DAY, signals: PickSignals = PickSignals()) =
    picker.pick(candidates, MemoryRules.spec(kind), signals)

  /** Dieci foto buone, abbastanza per far nascere un ricordo "anni fa" (minimo 6), a un quarto d'ora l'una: mai oltre 4 all'ora. */
  private fun good() = cells.spaced(10, from = 600, every = 15)

  private fun excludedOnce(reason: DropReason, bad: CandidateCell, kind: MemoryKind = MemoryKind.ON_THIS_DAY, signals: PickSignals = PickSignals()) {
    val result = pick(good() + bad, kind, signals)
    assertThat(result.accepted).isTrue()
    assertThat(result.members).doesNotContain(bad.id)
    assertThat(result.dropped).containsExactly(reason, 1)
  }

  @Test
  fun gliScreenshotEscono() = excludedOnce(DropReason.SCREENSHOT, cells.cell(1, source = MediaSource.SCREENSHOT, flags = CellFlags.FAVORITE))

  @Test
  fun iDocumentiEscono() = excludedOnce(DropReason.DOCUMENT, cells.cell(2, flags = CellFlags.DOCUMENT or CellFlags.FAVORITE))

  @Test
  fun leImmaginiAnimateEscono() = excludedOnce(DropReason.ANIMATED, cells.cell(3, kind = MediaKind.ANIMATED_IMAGE, flags = CellFlags.FAVORITE))

  @Test
  fun iRitagliEscono() = excludedOnce(DropReason.CUTOUT, cells.cell(4, cutout = true, flags = CellFlags.FAVORITE))

  @Test
  fun iMediaIlleggibiliEscono() = excludedOnce(DropReason.UNREADABLE, cells.cell(5, flags = CellFlags.UNREADABLE or CellFlags.FAVORITE))

  @Test
  fun laDataStimataEsceNeiRicordiLegatiAUnaData() {
    val estimated = cells.cell(6, flags = CellFlags.DATE_ESTIMATED or CellFlags.FAVORITE)
    for (kind in listOf(MemoryKind.ON_THIS_DAY, MemoryKind.TRIP, MemoryKind.EVENT, MemoryKind.MONTH)) {
      assertThat(MemoryRules.spec(kind).dateBound).isTrue()
    }
    excludedOnce(DropReason.DATE_ESTIMATED, estimated)
  }

  /** Una persona non e' legata a una data: la data stimata non esclude (D5). */
  @Test
  fun laDataStimataNonEsceNeiRicordiDiUnaPersona() {
    assertThat(MemoryRules.spec(MemoryKind.PERSON).dateBound).isFalse()
    // Quindici foto in tre giorni, dentro i tetti di una persona (6 al giorno, 4 all'ora).
    val estimated = List(15) { cells.cell((it % 5) * 90, on = cells.day.plusDays((it / 5).toLong()), flags = CellFlags.DATE_ESTIMATED) }
    val result = pick(estimated, MemoryKind.PERSON)
    assertThat(result.accepted).isTrue()
    assertThat(result.dropped).doesNotContainKey(DropReason.DATE_ESTIMATED)
  }

  /** Un giorno che non e' una data (dato rovinato) non entra mai, nemmeno per una persona. */
  @Test
  fun unGiornoCheNonEUnaDataEsce() {
    val broken = cells.cell(7).copy(day = 20_251_340)
    val result = pick(good() + broken)
    assertThat(result.members).doesNotContain(broken.id)
    assertThat(result.dropped[DropReason.DATE_ESTIMATED]).isEqualTo(1)
    assertThat(pick(good() + broken, MemoryKind.PERSON).dropped[DropReason.DATE_ESTIMATED]).isEqualTo(1)
  }

  @Test
  fun iPeriodiMaiEscono() {
    val never = cells.day.plusDays(2)
    val bad = cells.cell(8, on = never, flags = CellFlags.FAVORITE)
    val choices = MemoriesChoices(neverDays = setOf(MemoryDays.of(never.minusDays(1), never.plusDays(1))))
    excludedOnce(DropReason.NEVER_DAYS, bad, signals = PickSignals(choices = choices))
  }

  @Test
  fun lePersoneMaiEscono() {
    val anna = person(1)
    val bad = cells.cell(9, flags = CellFlags.FAVORITE)
    val signals = PickSignals(people = mapOf(bad.id to setOf(anna)), choices = MemoriesChoices(people = mapOf(anna to MemoryLessLevel.NEVER)))
    excludedOnce(DropReason.NEVER_PERSON, bad, signals = signals)
  }

  /** "Mostra meno" non esclude la foto (puo' comparire in un gruppo): toglie solo la copertina. */
  @Test
  fun mostraMenoNonEsclude() {
    val marco = person(2)
    val withLess = cells.cell(10, flags = CellFlags.FAVORITE or CellFlags.EDITED)
    val signals = PickSignals(people = mapOf(withLess.id to setOf(marco)), choices = MemoriesChoices(people = mapOf(marco to MemoryLessLevel.LESS)))
    val result = pick(good() + withLess, signals = signals)
    assertThat(result.members).contains(withLess.id)
    assertThat(result.dropped).isEmpty()
    assertThat(result.cover).isNotEqualTo(withLess.id)
  }

  /** Il giorno del "Mai" conta anche per un viaggio e per una persona: vale per tutti i tipi. */
  @Test
  fun ilMaiVaPerTuttiITipi() {
    val never = cells.day
    val choices = MemoriesChoices(neverDays = setOf(MemoryDays.of(never, never)))
    for (kind in MemoryKind.entries) {
      val result = pick(good(), kind, PickSignals(choices = choices))
      assertThat(result.accepted).isFalse()
      assertThat(result.dropped[DropReason.NEVER_DAYS]).isEqualTo(10)
    }
  }

  /** Un id ripetuto vale una volta sola e non si conta come escluso. */
  @Test
  fun unIdRipetutoVaUnaVolta() {
    val photos = good()
    val result = pick(photos + photos.take(3))
    assertThat(result.members).containsNoDuplicates()
    assertThat(result.dropped).isEmpty()
  }

  /** Con tutte le esclusioni insieme: ogni cella conta una volta sola, per il primo motivo che la tocca. */
  @Test
  fun ognunaContaPerUnSoloMotivo() {
    val both = cells.cell(11, source = MediaSource.SCREENSHOT, flags = CellFlags.DOCUMENT or CellFlags.UNREADABLE or CellFlags.DATE_ESTIMATED)
    val result = pick(good() + both)
    assertThat(result.dropped).containsExactly(DropReason.SCREENSHOT, 1)
  }

  /** Giorni fatti solo di screenshot, documenti o WhatsApp (E-MEM2): niente ricordo, e il perche' e' un conteggio. */
  @Test
  fun unGiornoDiSoliScreenshotNonFaUnRicordo() {
    val only = List(30) { cells.cell(it * 10, source = MediaSource.SCREENSHOT) }
    val result = pick(only)
    assertThat(result.accepted).isFalse()
    assertThat(result.dropped).containsExactly(DropReason.SCREENSHOT, 30, DropReason.TOO_FEW, 1)
  }

  /**
   * Proprieta': su ingressi casuali nessun escluso entra mai, e i conteggi sono quelli di un conto fatto a mano (indipendente da
   * `MemoryExclusions`), uno per cella e per il primo motivo nell'ordine di D5.
   */
  @Test
  fun proprietaNessunEsclusoEntraEIConteggiTornano() {
    for (seed in 1L..300L) {
      val input = RandomPick.of(seed)
      val spec = MemoryRules.spec(input.kind)
      val result = picker.pick(input.candidates, spec, input.signals)
      val expected = HashMap<DropReason, Int>()
      val excluded = HashSet<MediaId>()
      for (cell in input.candidates) {
        val reason = when {
          cell.source == MediaSource.SCREENSHOT -> DropReason.SCREENSHOT
          cell.flags and CellFlags.DOCUMENT != 0 -> DropReason.DOCUMENT
          cell.kind == MediaKind.ANIMATED_IMAGE -> DropReason.ANIMATED
          cell.cutout -> DropReason.CUTOUT
          cell.flags and CellFlags.UNREADABLE != 0 -> DropReason.UNREADABLE
          spec.dateBound && cell.flags and CellFlags.DATE_ESTIMATED != 0 -> DropReason.DATE_ESTIMATED
          input.signals.choices.neverDays.any { cell.day in it } -> DropReason.NEVER_DAYS
          input.signals.people[cell.id].orEmpty().any { input.signals.choices.people[it] == MemoryLessLevel.NEVER } -> DropReason.NEVER_PERSON
          else -> null
        }
        if (reason != null) {
          expected.merge(reason, 1, Int::plus)
          excluded += cell.id
        }
      }
      assertThat(result.members.toSet().intersect(excluded)).isEmpty()
      for ((reason, count) in expected) assertThat(result.dropped[reason]).isEqualTo(count)
      assertThat(result.dropped.values.all { it > 0 }).isTrue()
    }
  }
}
