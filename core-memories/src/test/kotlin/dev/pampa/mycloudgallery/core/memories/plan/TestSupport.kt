package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCandidate
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPlanner
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePlanner
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticMemoryLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow

// Gli attrezzi comuni delle prove di P6: l'adattatore della visibilita' (nei test si nominano i contratti dei nascosti, in produzione li nomina
// l'adattatore di :app, P13) e il generatore delle persone di P8 sostituito dal riferimento.

/** La visibilita' del motore sopra i due contratti dei nascosti (quello che fara' :app, P13). */
internal fun visibilityOf(visible: VisibleMedia, mask: HiddenMask): MemoryVisibility = object : MemoryVisibility {
  override val snapshot: StateFlow<TimelineSnapshot?> get() = visible.snapshot

  override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = visible.visibleOrThrow(ids)

  override suspend fun hiddenOrThrow(ids: Collection<MediaId>): Set<MediaId> = mask.hiddenAmongOrThrow(ids)
}

internal fun visibilityOf(lib: SyntheticMemoryLibrary): MemoryVisibility = visibilityOf(lib.visibility, lib.visibility)

/**
 * I ricordi di persone finche' il generatore di P8 (`plan/people/`) non c'e': il riferimento a forza bruta, **solo** per il tipo `PERSON`. Con
 * questo innesto il generatore di P6 e' provato sulle stesse suite del riferimento, persone comprese; in produzione l'innesto di serie e'
 * nessuno e lo riempie P8.
 */
internal object ReferencePeople : MemoryPlanner {
  override fun plan(input: PlanInput, today: LocalDate): List<MemoryCandidate> =
    ReferencePlanner().plan(input, today).filter { it.key.kind == MemoryKind.PERSON }
}

/** Un candidato come valore confrontabile (la classe vera non ha `equals`): tutto cio' che decide cosa si pubblica. */
internal data class CandidateShape(
  val key: String,
  val priority: Int,
  val cells: List<Int>,
  val yearsAgo: Int?,
  val days: Pair<LocalDate, LocalDate>,
  val label: String?,
  val places: List<Int>,
  val person: Boolean,
  val month: String?,
  val origin: Boolean,
  val rediscovery: Boolean,
)

internal fun shapeOf(c: MemoryCandidate) = CandidateShape(
  c.key.text, c.priority, c.cells.toList(), c.yearsAgo, c.days.from to c.days.to, c.tripLabel, c.tripPlaces, c.person != null,
  c.month?.toString(), c.origin != null, c.rediscovery,
)
