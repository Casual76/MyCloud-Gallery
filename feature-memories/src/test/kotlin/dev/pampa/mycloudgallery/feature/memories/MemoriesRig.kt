package dev.pampa.mycloudgallery.feature.memories

import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClock
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPeople
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMemories
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticMemoryLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryMemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first

/**
 * Un dispositivo finto con il servizio dei ricordi di riferimento (`InMemoryMemories`, la stessa suite di contratto del motore vero) sopra una
 * Libreria sintetica con **solo date relative a oggi** (3 novembre di un anno finto) e nessun dato personale: due "anni fa" con nove foto ognuno
 * e un ottobre pieno (il meglio del mese). Le schermate si provano sopra questo, con le loro porte finte.
 */
internal class MemoriesRig(
  val today: LocalDate = LocalDate.of(2026, 11, 3),
  seeded: Boolean = true,
  /** Le scelte personali vere al posto di quelle in memoria (K5: `SyncedMemoriesSettings` sopra un registro che non si scrive). */
  personalSettings: MemoriesPersonalSettings? = null,
) {
  val lib = SyntheticMemoryLibrary().apply {
    if (seeded) {
      batch {
        for (year in listOf(2025, 2024)) photos(LocalDate.of(year, today.monthValue, today.dayOfMonth), 9, place = 3_000_000 + year)
        for (d in 1..15) photos(LocalDate.of(2026, 10, d), 3)
      }
    }
  }
  val clock = FakeMemoryClock(today)
  val register = MemoriesRegister()
  val personal = InMemoryMemoriesPersonalSettings(register)
  val store = InMemoryShelfStore()
  val people = FakeMemoryPeople(available = true) { ids -> lib.visibility.visible(ids) }
  val memories = InMemoryMemories(lib.visibility, lib.visibility, personalSettings ?: personal, store, clock, people = people)

  suspend fun refresh() = memories.refresh(RefreshReason.NIGHTLY, 60.seconds)

  fun cards(): List<MemoryCard> = memories.shelf.value.items

  fun card(kind: MemoryKind, yearsAgo: Int? = null): MemoryCard = cards().first { it.kind == kind && (yearsAgo == null || it.title.yearsAgo == yearsAgo) }

  /** Gli elementi di un ricordo, nell'ordine della sua vista. */
  suspend fun members(id: MemoryId): List<MediaId> {
    val view = checkNotNull(memories.memory(id).first())
    return (0 until view.view.size).map { view.view.snapshot.idAt(view.view.snapshotIndex(it)) }
  }
}
