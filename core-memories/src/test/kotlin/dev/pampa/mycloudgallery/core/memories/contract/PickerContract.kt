package dev.pampa.mycloudgallery.core.memories.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Test

/**
 * La suite di contratto della scelta delle foto (Fase 9, P0; `docs/fase9-progetto.md` D5-D7, D11, E-MEM2, E-MEM11, E-MEM16, Q6 (a), §5.2). Gira
 * sul riferimento (`ReferencePicker`) e, da P7, sulla scelta vera di pick/. Le celle sono costruite a mano: giorno, ora, sorgente, flag.
 */
abstract class PickerContract {

  protected abstract fun picker(): PhotoPicker

  private var seq = 0L
  private val day = LocalDate.of(2025, 10, 27)

  private fun cell(
    minute: Int,
    on: LocalDate = day,
    source: MediaSource = MediaSource.CAMERA,
    flags: Int = 0,
    kind: MediaKind = MediaKind.PHOTO,
    aspect: Int = 1_333,
    durationMs: Long = 0,
    cutout: Boolean = false,
  ): CandidateCell {
    val id = MediaId(UUID(1L, ++seq).toString())
    val time = on.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + minute * 60_000L
    return CandidateCell(id, time, MemoryDays.dayOf(on), kind, source, flags, durationMs, aspect, null, cutout)
  }

  /** Celle a 10 minuti l'una dall'altra (mai doppioni per la finestra di 3 minuti). */
  private fun spaced(n: Int, from: Int = 0, on: LocalDate = day) = List(n) { cell(from + it * 10, on) }

  private fun pick(cells: List<CandidateCell>, kind: MemoryKind = MemoryKind.ON_THIS_DAY, signals: PickSignals = PickSignals()) =
    picker().pick(cells, MemoryRules.spec(kind), signals)

  /** D5, D11: ogni esclusione una per una, contata per motivo, mai fra gli elementi. */
  @Test
  fun leEsclusioniUnaPerUna() {
    val anna = PersonId.fromBytes(ByteArray(16) { 9 })
    val bad = listOf(
      cell(1, source = MediaSource.SCREENSHOT) to DropReason.SCREENSHOT,
      cell(2, flags = CellFlags.DOCUMENT) to DropReason.DOCUMENT,
      cell(3, kind = MediaKind.ANIMATED_IMAGE) to DropReason.ANIMATED,
      cell(4, cutout = true) to DropReason.CUTOUT,
      cell(5, flags = CellFlags.UNREADABLE) to DropReason.UNREADABLE,
      cell(6, flags = CellFlags.DATE_ESTIMATED) to DropReason.DATE_ESTIMATED,
      cell(7, on = day.plusDays(1)) to DropReason.NEVER_DAYS,
      cell(8) to DropReason.NEVER_PERSON,
    )
    val good = spaced(10, from = 100)
    val choices = MemoriesChoices(neverDays = setOf(MemoryDays.of(day.plusDays(1), day.plusDays(1))), people = mapOf(anna to MemoryLessLevel.NEVER))
    val signals = PickSignals(people = mapOf(bad.last().first.id to setOf(anna)), choices = choices)
    val result = pick(bad.map { it.first } + good, signals = signals)
    assertThat(result.accepted).isTrue()
    assertThat(result.members).containsNoneIn(bad.map { it.first.id })
    for ((_, reason) in bad) assertThat(result.dropped[reason]).isEqualTo(1)
  }

  /** Una persona non e' legata a una data: la data stimata non la esclude (D5). */
  @Test
  fun laDataStimataContaSoloPerIRicordiLegatiAUnaData() {
    // Quattro giorni da quattro foto, un'ora l'una dall'altra: dentro i tetti per giorno e per ora di una persona.
    val cells = List(16) { cell((it % 4) * 60, on = day.plusDays((it / 4).toLong()), flags = CellFlags.DATE_ESTIMATED) }
    assertThat(pick(cells, MemoryKind.ON_THIS_DAY).accepted).isFalse()
    assertThat(pick(cells, MemoryKind.PERSON).accepted).isTrue()
  }

  /** D7: sotto il minimo il ricordo non nasce (E-MEM1); al piu' il massimo; elementi in ordine di scatto. */
  @Test
  fun minimoMassimoEOrdine() {
    val few = pick(spaced(5))
    assertThat(few.accepted).isFalse()
    assertThat(few.dropped[DropReason.TOO_FEW]).isEqualTo(1)
    val cells = List(60) { cell(it * 4) }
    val many = pick(cells.shuffled(java.util.Random(3)))
    assertThat(many.members.size).isAtMost(24)
    assertThat(many.members.size).isAtLeast(6)
    assertThat(many.members).containsNoDuplicates()
    val time = cells.associate { it.id to it.timeMs }
    assertThat(many.members.map { time.getValue(it) }).isInOrder()
  }

  /** D6: i preferiti passano davanti quando il posto manca. */
  @Test
  fun iPreferitiPassanoDavanti() {
    val plain = List(30) { cell(it * 10) }
    val favorites = List(5) { cell(400 + it * 10, flags = CellFlags.FAVORITE) }
    val result = pick(plain + favorites)
    assertThat(result.members).containsAtLeastElementsIn(favorites.map { it.id })
  }

  /** D7: doppioni con i vettori (coseno >= 0,92): resta quello col punteggio piu' alto. */
  @Test
  fun doppioniConIVettori() {
    val cells = spaced(10)
    val twin = cells[0].copy(id = MediaId(UUID(2L, 1L).toString()), flags = CellFlags.FAVORITE, timeMs = cells[0].timeMs + 3_600_000)
    fun oneHot(i: Int) = FloatArray(16) { if (it == i) 1f else 0f }
    val vectors = cells.withIndex().associate { (i, c) -> c.id to oneHot(i) } + mapOf(cells[0].id to oneHot(15), twin.id to oneHot(15))
    val result = pick(cells + twin, signals = PickSignals(vectors = vectors))
    assertThat(result.members).contains(twin.id)
    assertThat(result.members).doesNotContain(cells[0].id)
    assertThat(result.dropped[DropReason.DUPLICATE]).isAtLeast(1)
  }

  /** E-MEM16: senza vettori la firma visiva; senza firma la finestra di 3 minuti. */
  @Test
  fun doppioniSenzaVettori() {
    val a = cell(0)
    val b = cell(1)
    val rest = spaced(8, from = 60)
    val byWindow = pick(listOf(a, b) + rest)
    assertThat(byWindow.members.count { it == a.id || it == b.id }).isEqualTo(1)
    val c = cell(200)
    val d = cell(400)
    val lookAlike = LookAlike { x, y -> if (setOf(x, y) == setOf(c.id, d.id)) true else null }
    val bySignature = pick(listOf(c, d) + rest, signals = PickSignals(lookAlike = lookAlike))
    assertThat(bySignature.members.count { it == c.id || it == d.id }).isEqualTo(1)
    val notSimilar = LookAlike { _, _ -> false }
    val apart = pick(listOf(a, b) + rest, signals = PickSignals(lookAlike = notSimilar))
    assertThat(apart.members).containsAtLeast(a.id, b.id)
  }

  /** D7: al piu' 4 nella stessa ora. */
  @Test
  fun alPiuQuattroNellaStessaOra() {
    val sameHour = List(12) { cell(5 * it) }
    val others = List(6) { cell(120 + it * 60) }
    val result = pick(sameHour + others)
    val hours = (sameHour + others).associate { it.id to it.timeMs / 3_600_000 }
    assertThat(result.members.groupBy { hours.getValue(it) }.values.maxOf { it.size }).isAtMost(4)
  }

  /** D7: un viaggio ha almeno una foto per giorno e al piu' 8 per giorno. */
  @Test
  fun unViaggioHaOgniGiornoEAlPiuOttoPerGiorno() {
    val first = List(30) { cell(it * 15, flags = CellFlags.FAVORITE) }
    val second = List(2) { cell(it * 30, on = day.plusDays(1)) }
    val third = List(20) { cell(it * 15, on = day.plusDays(2), flags = CellFlags.EDITED) }
    val result = pick(first + second + third, MemoryKind.TRIP)
    val dayOf = (first + second + third).associate { it.id to it.day }
    val perDay = result.members.groupBy { dayOf.getValue(it) }.mapValues { it.value.size }
    assertThat(perDay.keys).hasSize(3)
    assertThat(perDay.values.max()).isAtMost(8)
  }

  /** D5: WhatsApp al piu' un quarto, mai copertina. */
  @Test
  fun whatsappAlPiuUnQuartoEMaiCopertina() {
    val whatsapp = List(20) { cell(it * 10, source = MediaSource.WHATSAPP, flags = CellFlags.FAVORITE) }
    val camera = List(8) { cell(300 + it * 10) }
    val result = pick(whatsapp + camera)
    val wa = whatsapp.map { it.id }.toSet()
    assertThat(result.members.count { it in wa } * 4).isAtMost(result.members.size)
    assertThat(result.cover).isNotIn(wa)
  }

  /** D6, E-MEM11: la copertina non e' un video, non e' WhatsApp, non ha una persona "Mostra meno"; meglio orizzontale o quadrata. */
  @Test
  fun laCopertinaGiusta() {
    val marco = PersonId.fromBytes(ByteArray(16) { 3 })
    val video = cell(0, kind = MediaKind.VIDEO, flags = CellFlags.FAVORITE or CellFlags.EDITED, durationMs = 10_000)
    val withLess = cell(10, flags = CellFlags.FAVORITE or CellFlags.EDITED)
    val portrait = cell(20, flags = CellFlags.FAVORITE, aspect = 750)
    val landscape = cell(30, flags = CellFlags.EDITED)
    val rest = spaced(6, from = 100)
    val signals = PickSignals(people = mapOf(withLess.id to setOf(marco)), choices = MemoriesChoices(people = mapOf(marco to MemoryLessLevel.LESS)))
    val result = pick(listOf(video, withLess, portrait, landscape) + rest, signals = signals)
    assertThat(result.members).containsAtLeast(video.id, withLess.id)
    assertThat(result.cover).isEqualTo(landscape.id)
  }

  /** Q6 (a): nessun filtro sul contenuto (nessun segnale "triste"); e il risultato porta solo conteggi. */
  @Test
  fun nessunFiltroSulContenutoESoloConteggi() {
    val fields = PickSignals::class.java.declaredFields.map { it.name.lowercase() }
    assertThat(fields.none { "sad" in it || "trist" in it }).isTrue()
    val result = pick(spaced(10))
    assertThat(result.toString()).doesNotContain(result.members.first().value)
  }

  /** Puro e deterministico: lo stesso ingresso in un altro ordine da' lo stesso risultato. */
  @Test
  fun deterministico() {
    val cells = List(40) { cell(it * 7, flags = if (it % 5 == 0) CellFlags.FAVORITE else 0) }
    val a = pick(cells)
    val b = pick(cells.reversed())
    assertThat(a.members).isEqualTo(b.members)
    assertThat(a.cover).isEqualTo(b.cover)
  }
}

/** La suite sul riferimento (§5.2). */
class ReferencePickerContractTest : PickerContract() {
  override fun picker(): PhotoPicker = ReferencePicker()
}
