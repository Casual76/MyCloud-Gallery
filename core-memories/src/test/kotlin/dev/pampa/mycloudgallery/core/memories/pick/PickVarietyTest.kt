package dev.pampa.mycloudgallery.core.memories.pick

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.DropReason
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PickSpec
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticVectors
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import org.junit.Test

/** La varieta' (D7): massima rilevanza marginale con lambda 0,5, tetti per giorno e per ora, almeno una foto per giorno in un viaggio. */
class PickVarietyTest {

  private val picker = MemoryPhotoPicker()
  private val cells = Cells()

  /** Con i vettori la scelta preferisce cio' che e' diverso da cio' che ha gia': venti concetti diversi battono quaranta foto dello stesso. */
  @Test
  fun laVarietaBatteLaTavolata() {
    val cluster = SyntheticVectors.concept(7)
    val tablefuls = cells.spaced(40, from = 0, every = 20)
    val others = cells.spaced(20, from = 1_000, every = 20)
    val vectors = HashMap<MediaId, FloatArray>()
    tablefuls.forEachIndexed { i, c -> vectors[c.id] = SyntheticVectors.withCosine(cluster, 0.85, 100L + i) }
    others.forEachIndexed { i, c -> vectors[c.id] = SyntheticVectors.concept(1_000L + i) }
    val result = picker.pick(tablefuls + others, MemoryRules.spec(MemoryKind.ON_THIS_DAY), PickSignals(vectors = vectors))
    // Piu' tetti per ora e per giorno: al massimo 24 elementi, 3 all'ora.
    assertThat(result.members).containsAtLeastElementsIn(others.map { it.id })
    assertThat(result.members.count { id -> tablefuls.any { it.id == id } }).isAtMost(24 - others.size)
  }

  /** Senza vettori non c'e' penalita' di somiglianza: decide il punteggio, a parita' lo scatto piu' vecchio. */
  @Test
  fun senzaVettoriDecideIlPunteggioEPoiLOraDiScatto() {
    val spec = PickSpec(MemoryKind.ON_THIS_DAY, minSize = 3, maxSize = 3, perDayMin = 0, perDayMax = 24, perHourMax = 24, dateBound = true)
    val photos = cells.spaced(10, from = 100)
    val result = picker.pick(photos.reversed(), spec, PickSignals())
    assertThat(result.members).containsExactlyElementsIn(photos.take(3).map { it.id }).inOrder()
    assertThat(result.dropped).containsExactly(DropReason.CAPPED, 7)
  }

  /** Al piu' 4 nella stessa ora (ricordo "anni fa"), anche se il punteggio le vorrebbe tutte. */
  @Test
  fun alPiuQuattroNellaStessaOra() {
    // Sei minuti l'una dall'altra (non 5): la finestra dei doppioni e' di 5 minuti (P11) e due scatti a 5 minuti esatti sarebbero doppioni.
    val best = List(10) { cells.cell(it * 6, flags = CellFlags.FAVORITE) }
    val rest = List(8) { cells.cell(120 + it * 60) }
    val result = picker.pick(best + rest, MemoryRules.spec(MemoryKind.ON_THIS_DAY), PickSignals())
    val hour = (best + rest).associate { it.id to it.timeMs / 3_600_000 }
    assertThat(result.members.groupBy { hour.getValue(it) }.values.maxOf { it.size }).isAtMost(4)
    assertThat(result.members.count { id -> best.any { it.id == id } }).isEqualTo(4)
    assertThat(result.dropped[DropReason.CAPPED]).isEqualTo(6)
  }

  /** Un viaggio: almeno una foto per giorno (anche se il giorno e' brutto) e al piu' 8 per giorno. */
  @Test
  fun unViaggioHaOgniGiornoEAlPiuOttoPerGiorno() {
    val days = (0 until 10).map { cells.day.plusDays(it.toLong()) }
    val all = days.flatMapIndexed { d, day ->
      // Il giorno 0 e' bellissimo (20 preferiti), gli altri hanno tre foto qualunque.
      if (d == 0) List(20) { cells.cell(it * 30, on = day, flags = CellFlags.FAVORITE or CellFlags.EDITED) } else cells.spaced(3, from = 600, on = day, every = 30)
    }
    val result = picker.pick(all, MemoryRules.spec(MemoryKind.TRIP), PickSignals())
    val dayOf = all.associate { it.id to it.day }
    val perDay = result.members.groupBy { dayOf.getValue(it) }.mapValues { it.value.size }
    assertThat(perDay.keys).hasSize(10)
    assertThat(perDay.values.max()).isAtMost(8)
    assertThat(perDay.getValue(dayOf.getValue(all.first().id))).isEqualTo(8)
    assertThat(result.members.size).isAtMost(40)
  }

  /** Un viaggio con piu' giorni del massimo: il massimo resta il massimo e le foto vanno ai giorni piu' vecchi (a parita'), mai oltre 40. */
  @Test
  fun ilMassimoNonSiSupera() {
    val all = (0 until 60).flatMap { cells.spaced(2, from = 0, on = cells.day.plusDays(it.toLong()), every = 30) }
    val result = picker.pick(all, MemoryRules.spec(MemoryKind.TRIP), PickSignals())
    assertThat(result.members).hasSize(40)
    assertThat(result.dropped[DropReason.CAPPED]).isEqualTo(80)
  }

  /** Il mese: per giorno, poi per ora (l'ordine dell'esito e' sempre quello di scatto). */
  @Test
  fun gliElementiSonoInOrdineDiScatto() {
    val photos = (0 until 12).flatMap { cells.spaced(5, from = 0, on = cells.day.plusDays(it.toLong()), every = 45) }
    val result = picker.pick(photos.shuffled(java.util.Random(5)), MemoryRules.spec(MemoryKind.MONTH), PickSignals())
    val time = photos.associate { it.id to it.timeMs }
    assertThat(result.accepted).isTrue()
    assertThat(result.members.map { time.getValue(it) }).isInOrder()
  }

  /** WhatsApp: al piu' un quarto del ricordo, anche se ha il punteggio migliore (preferiti). */
  @Test
  fun whatsappAlPiuUnQuarto() {
    val whatsapp = List(20) { cells.cell(it * 10, source = MediaSource.WHATSAPP, flags = CellFlags.FAVORITE) }
    val camera = cells.spaced(20, from = 400, every = 20)
    val result = picker.pick(whatsapp + camera, MemoryRules.spec(MemoryKind.ON_THIS_DAY), PickSignals())
    val wa = whatsapp.map { it.id }.toSet()
    assertThat(result.members.count { it in wa } * 4).isAtMost(result.members.size)
    assertThat(result.members.count { it in wa }).isEqualTo(6)
  }

  /** Quando WhatsApp e' quasi tutto, la quota si fa scendendo: si tolgono le WhatsApp peggiori e il ricordo nasce se arriva al minimo. */
  @Test
  fun laQuotaDiWhatsappTogliLePeggiori() {
    val whatsapp = List(4) { cells.cell(it * 10, source = MediaSource.WHATSAPP, flags = if (it == 0) CellFlags.FAVORITE else 0) }
    val camera = List(8) { cells.cell(400 + it * 20) }
    val result = picker.pick(whatsapp + camera, MemoryRules.spec(MemoryKind.ON_THIS_DAY), PickSignals())
    // Dodici scelte: un quarto sono tre, ma con quattro WhatsApp si scende finche' il conto torna (due su dieci): restano la migliore e una.
    assertThat(result.members.count { id -> whatsapp.any { it.id == id } }).isEqualTo(2)
    assertThat(result.dropped[DropReason.WHATSAPP_SHARE]).isEqualTo(2)
    assertThat(result.members).contains(whatsapp[0].id)
  }

  /** Sotto il minimo del tipo il ricordo non nasce e il perche' e' un conteggio. */
  @Test
  fun sottoIlMinimoNonNasce() {
    val spec = MemoryRules.spec(MemoryKind.MONTH)
    val result = picker.pick(cells.spaced(spec.minSize - 1, from = 0, every = 20), spec, PickSignals())
    assertThat(result.accepted).isFalse()
    assertThat(result.members).isEmpty()
    assertThat(result.cover).isNull()
    assertThat(result.dropped[DropReason.TOO_FEW]).isEqualTo(1)
  }

  /** Proprieta': su ingressi casuali i tetti valgono sempre. */
  @Test
  fun proprietaITettiValgonoSempre() {
    var accepted = 0
    for (seed in 1L..400L) {
      val input = RandomPick.of(seed)
      val spec = MemoryRules.spec(input.kind)
      val result = picker.pick(input.candidates, spec, input.signals)
      if (!result.accepted) {
        assertThat(result.members).isEmpty()
        continue
      }
      accepted++
      val byId = input.candidates.associateBy { it.id }
      val chosen = result.members.map { byId.getValue(it) }
      assertThat(chosen.size).isAtLeast(spec.minSize)
      assertThat(chosen.size).isAtMost(spec.maxSize)
      assertThat(chosen.groupBy { it.day }.values.maxOf { it.size }).isAtMost(spec.perDayMax)
      assertThat(chosen.groupBy { it.timeMs / 3_600_000 }.values.maxOf { it.size }).isAtMost(spec.perHourMax)
      assertThat(chosen.count { it.source == MediaSource.WHATSAPP } * 4).isAtMost(chosen.size)
      assertThat(chosen.map { it.timeMs }).isInOrder()
      assertThat(chosen.map { it.id }.toSet()).hasSize(chosen.size)
    }
    assertThat(accepted).isGreaterThan(100)
  }
}
