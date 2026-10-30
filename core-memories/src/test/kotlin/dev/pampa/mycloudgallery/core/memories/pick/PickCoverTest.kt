package dev.pampa.mycloudgallery.core.memories.pick

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.DropReason
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PhotoScore
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import org.junit.Test

/** La copertina (D5, D6, E-MEM11): mai un video, mai WhatsApp, mai una persona con "Mostra meno" o "Mai"; la migliore, meglio larga. */
class PickCoverTest {

  private val picker = MemoryPhotoPicker()
  private val cells = Cells()
  private val spec = MemoryRules.spec(MemoryKind.ON_THIS_DAY)

  @Test
  fun laCopertinaEIlMigliorElementoIdoneo() {
    val best = cells.cell(300, flags = CellFlags.FAVORITE or CellFlags.EDITED)
    val result = picker.pick(cells.spaced(8, from = 600) + best + cells.cell(310, flags = CellFlags.FAVORITE), spec, PickSignals())
    assertThat(result.cover).isEqualTo(best.id)
  }

  /** Meglio orizzontale o quadrata (>= 1000 millesimi): una verticale vince solo se non c'e' altro. */
  @Test
  fun meglioOrizzontaleOQuadrata() {
    val portrait = cells.cell(300, flags = CellFlags.FAVORITE, aspect = 750)
    val square = cells.cell(310, aspect = 1_000)
    val withWide = picker.pick(cells.spaced(7, from = 600, every = 20).map { it.copy(aspectMilli = 750) } + portrait + square, spec, PickSignals())
    assertThat(withWide.cover).isEqualTo(square.id)
    val onlyPortraits = picker.pick(cells.spaced(7, from = 600, every = 20).map { it.copy(aspectMilli = 750) } + portrait, spec, PickSignals())
    assertThat(onlyPortraits.cover).isEqualTo(portrait.id)
  }

  @Test
  fun maiUnVideo() {
    val video = cells.cell(300, kind = MediaKind.VIDEO, flags = CellFlags.FAVORITE or CellFlags.EDITED, durationMs = 10_000)
    val result = picker.pick(cells.spaced(7, from = 600) + video, spec, PickSignals())
    assertThat(result.members).contains(video.id)
    assertThat(result.cover).isNotEqualTo(video.id)
  }

  @Test
  fun maiWhatsapp() {
    val chat = cells.cell(300, source = MediaSource.WHATSAPP, flags = CellFlags.FAVORITE or CellFlags.EDITED)
    val result = picker.pick(cells.spaced(7, from = 600) + chat, spec, PickSignals())
    assertThat(result.members).contains(chat.id)
    assertThat(result.cover).isNotEqualTo(chat.id)
  }

  /** "Mostra meno" toglie solo la copertina; la foto di gruppo puo' restare nel ricordo (E-MEM11). */
  @Test
  fun maiUnaPersonaConMostraMeno() {
    val marco = person(3)
    val withMarco = cells.cell(300, flags = CellFlags.FAVORITE or CellFlags.EDITED)
    val signals = PickSignals(people = mapOf(withMarco.id to setOf(marco)), choices = MemoriesChoices(people = mapOf(marco to MemoryLessLevel.LESS)))
    val result = picker.pick(cells.spaced(7, from = 600) + withMarco, spec, signals)
    assertThat(result.members).contains(withMarco.id)
    assertThat(result.cover).isNotEqualTo(withMarco.id)
  }

  /** Se tutte le copertine possibili sono vietate il ricordo non nasce (non si inventa una copertina). */
  @Test
  fun senzaCopertinaIdoneaNonNasce() {
    val videos = List(8) { cells.cell(it * 20, kind = MediaKind.VIDEO, durationMs = 10_000) }
    val result = picker.pick(videos, spec, PickSignals())
    assertThat(result.accepted).isFalse()
    assertThat(result.cover).isNull()
    assertThat(result.members).isEmpty()
    assertThat(result.dropped[DropReason.NO_COVER]).isEqualTo(1)
  }

  /** Il video conta fra gli elementi (e' un ricordo, non solo foto) ma non regge da solo la copertina. */
  @Test
  fun unaSolaCopertinaPossibile() {
    val videos = List(7) { cells.cell(it * 20, kind = MediaKind.VIDEO, durationMs = 10_000) }
    val photo = cells.cell(400)
    val result = picker.pick(videos + photo, spec, PickSignals())
    assertThat(result.cover).isEqualTo(photo.id)
    assertThat(result.members).hasSize(8)
  }

  /** Proprieta': ogni copertina e' fra gli elementi, e' idonea, ed e' la migliore delle idonee (larghe se ce ne sono). */
  @Test
  fun proprietaLaCopertinaEIdonea() {
    var covers = 0
    for (seed in 1L..400L) {
      val input = RandomPick.of(seed)
      val result = picker.pick(input.candidates, MemoryRules.spec(input.kind), input.signals)
      if (!result.accepted) {
        assertThat(result.cover).isNull()
        continue
      }
      covers++
      val byId = input.candidates.associateBy { it.id }
      val cover = byId.getValue(result.cover!!)
      assertThat(result.members).contains(cover.id)
      assertThat(cover.kind).isNotEqualTo(MediaKind.VIDEO)
      assertThat(cover.source).isNotEqualTo(MediaSource.WHATSAPP)
      assertThat(input.signals.hasLessPerson(cover.id)).isFalse()
      val eligible = result.members.map { byId.getValue(it) }
        .filter { it.kind != MediaKind.VIDEO && it.source != MediaSource.WHATSAPP && !input.signals.hasLessPerson(it.id) }
      val pool = eligible.filter { it.aspectMilli >= 1_000 }.ifEmpty { eligible }
      val bestScore = pool.maxOf { PhotoScore.of(it, input.signals) }
      assertThat(PhotoScore.of(cover, input.signals)).isEqualTo(bestScore)
    }
    assertThat(covers).isGreaterThan(100)
  }
}
