package dev.pampa.mycloudgallery.core.memories.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticMemoryLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import java.time.LocalDate
import java.time.LocalTime
import java.util.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * **Dopo la pubblicazione, lo scaffale si puo' solo ripulire** (docs/fase9-progetto.md D9, §2.3, E-MEM5-E-MEM7, D31): proprieta' su **10.000
 * storie** di nascosti, rimessi visibili, eliminati, foto che arrivano dopo e giorni che passano. Dopo ogni passo, per ogni ricordo che c'era e
 * c'e' ancora: stesso id, stessa chiave, stesso titolo, stesso giorno di pubblicazione, elementi **in meno** e nello stesso ordine (mai uno in
 * piu', mai riordinati), copertina com'era o il primo rimasto; e **un nascosto o eliminato non c'e' mai**, ne' sul file ne' nella sezione ne' nella
 * pagina del ricordo. Una pulizia non aggiunge mai un ricordo; un giro ne aggiunge solo di oggi. Gli elementi di un ricordo vengono sempre e solo
 * da quando e' nato (le foto arrivate dopo e quelle rimesse visibili non entrano).
 */
class ShelfOnlyRemoveTest {

  private class Counters {
    var trimmed = 0
    var removed = 0
    var arrivals = 0
    var published = 0
    var checks = 0
  }

  private fun isSubsequence(small: List<MediaId>, big: List<MediaId>): Boolean {
    var at = 0
    for (id in small) {
      while (at < big.size && big[at] != id) at++
      if (at == big.size) return false
      at++
    }
    return true
  }

  /** Il ricordo di prima e quello di adesso: solo togliere. */
  private fun checkOnlyRemoved(before: Memory, after: Memory, context: String) {
    assertWithMessage("$context: chiave").that(after.key).isEqualTo(before.key)
    assertWithMessage("$context: titolo fisso").that(after.title).isEqualTo(before.title)
    assertWithMessage("$context: giorno di pubblicazione").that(after.publishedDay).isEqualTo(before.publishedDay)
    assertWithMessage("$context: origine").that(after.origin).isEqualTo(before.origin)
    assertWithMessage("$context: elementi solo in meno e nello stesso ordine").that(isSubsequence(after.members, before.members)).isTrue()
    assertWithMessage("$context: la copertina e' uno degli elementi").that(after.cover in after.members).isTrue()
    val expectedCover = if (before.cover in after.members) before.cover else after.members.first()
    assertWithMessage("$context: copertina com'era o il primo rimasto").that(after.cover).isEqualTo(expectedCover)
  }

  private fun runStory(seed: Long, counters: Counters) {
    val random = Random(seed)
    val lib = SyntheticMemoryLibrary(seed)
    val anchors = listOf(LocalDate.of(2025, 11, 3), LocalDate.of(2024, 11, 3), LocalDate.of(2023, 11, 3))
    lib.batch { for (anchor in anchors) lib.photos(anchor, 6 + random.nextInt(8), place = 3_000_000 + anchor.year) }
    val rig = EngineRig(visibility = lib.visibility, today = LocalDate.of(2026, 11, 3))
    try {
      runBlocking {
        rig.refresh()
        counters.published += rig.engine.current()!!.memories.size
        // Gli elementi di ogni ricordo alla nascita: da li' non si esce, e' il solo insieme da cui un ricordo puo' pescare.
        val atBirth = HashMap<MemoryId, List<MediaId>>()
        for (memory in rig.engine.current()!!.memories) atBirth[memory.id] = memory.members
        repeat(7) { step ->
          val before = rig.engine.current()!!
          val context = "storia $seed, passo $step"
          val roll = random.nextInt(100)
          val members = before.memories.flatMap { it.members }
          val operation: String
          when {
            roll < 22 && members.isNotEmpty() -> {
              operation = "nascondi"
              lib.hide(members[random.nextInt(members.size)])
            }
            roll < 32 && lib.visibility.hidden.isNotEmpty() -> {
              operation = "rimetti"
              val hidden = lib.visibility.hidden.sortedBy { it.value }
              lib.unhide(hidden[random.nextInt(hidden.size)])
            }
            roll < 44 && members.isNotEmpty() -> {
              operation = "elimina"
              lib.delete(members[random.nextInt(members.size)])
            }
            roll < 58 -> {
              operation = "arrivi"
              lib.photos(anchors[random.nextInt(anchors.size)], 1 + random.nextInt(5), start = LocalTime.of(20, 0))
              counters.arrivals++
            }
            roll < 68 -> {
              operation = "giorno"
              // Ogni tanto passa un mese: i ricordi di piu' di 30 giorni escono.
              rig.clock.today = rig.clock.today.plusDays(if (random.nextInt(5) == 0) 31L else 1L + random.nextInt(3))
            }
            roll < 84 -> {
              operation = "pulizia"
              val report = rig.engine.prune()
              counters.trimmed += report.trimmed
              counters.removed += report.removed
            }
            else -> {
              operation = "giro"
              val report = rig.refresh()
              counters.trimmed += report.trimmed
              counters.removed += report.removed
              counters.published += report.published
            }
          }
          if (operation == "pulizia" || operation == "giro") {
            val after = rig.engine.current()!!
            val ids = after.memories.map { it.id }
            assertWithMessage("$context ($operation): un ricordo una volta sola").that(ids.toSet()).hasSize(ids.size)
            for (memory in after.memories) {
              val old = before.memories.firstOrNull { it.id == memory.id }
              if (old != null) {
                checkOnlyRemoved(old, memory, "$context ($operation)")
              } else {
                assertWithMessage("$context: una pulizia non aggiunge ricordi").that(operation).isEqualTo("giro")
                assertWithMessage("$context: un ricordo nuovo e' di oggi").that(memory.publishedDay).isEqualTo(MemoryDays.dayOf(rig.clock.today))
                atBirth[memory.id] = memory.members
              }
              assertWithMessage("$context ($operation): gli elementi vengono solo dalla nascita del ricordo")
                .that(atBirth.getValue(memory.id).containsAll(memory.members)).isTrue()
              // Un nascosto o eliminato non c'e' mai: ne' fra gli elementi, ne' come copertina.
              for (id in memory.members) {
                assertWithMessage("$context ($operation): nascosto fra gli elementi").that(id in lib.visibility.hidden).isFalse()
                assertWithMessage("$context ($operation): eliminato fra gli elementi").that(id in lib.visibility.present).isTrue()
              }
            }
            counters.checks++
          }
          // La sezione e la pagina non mostrano mai un nascosto, ne' una copertina nascosta, ne' meno di 3 elementi (anche prima di una pulizia).
          for (card in rig.engine.shelf.value.items) {
            assertWithMessage("$context ($operation): copertina nascosta").that(card.cover in lib.visibility.hidden).isFalse()
            assertThat(card.count).isAtLeast(Memory.MIN_MEMBERS)
            val view = rig.engine.memory(card.id).first() ?: continue
            for (id in view.memory.members) if (id in lib.visibility.hidden) assertThat(view.view.viewIndexOf(id)).isEqualTo(-1)
          }
          assertThat(rig.engine.current()!!.memories.size).isAtMost(MemoryRules.SHELF_MAX)
        }
      }
    } finally {
      rig.close()
    }
  }

  @Test
  fun dopoLaPubblicazioneSiPuoSoloTogliere_su_10000_storie() {
    val counters = Counters()
    for (seed in 1L..10_000L) runStory(seed, counters)
    // Il confronto non e' vuoto: ci sono stati elementi tolti, ricordi usciti, foto arrivate dopo e giri nuovi.
    assertWithMessage("elementi tolti").that(counters.trimmed).isAtLeast(2_000)
    assertWithMessage("ricordi usciti").that(counters.removed).isAtLeast(200)
    assertWithMessage("foto arrivate dopo").that(counters.arrivals).isAtLeast(5_000)
    assertWithMessage("ricordi pubblicati").that(counters.published).isAtLeast(10_000)
    assertWithMessage("passi controllati").that(counters.checks).isAtLeast(15_000)
  }
}
