package dev.pampa.mycloudgallery.debugtools

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClock
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.plan.IndexedPlanner
import dev.pampa.mycloudgallery.core.memories.plan.MemoryEngine
import dev.pampa.mycloudgallery.core.memories.plan.people.PeoplePlanner
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryMemoriesPersonalSettings
import dev.pampa.mycloudgallery.debugtools.eval.memories.EvalMemory
import dev.pampa.mycloudgallery.debugtools.eval.memories.MemoriesEvalDays
import dev.pampa.mycloudgallery.debugtools.eval.memories.MemoriesEvalEnvironment
import dev.pampa.mycloudgallery.debugtools.eval.memories.MemoriesEvalKinds
import dev.pampa.mycloudgallery.memories.MemoriesGraph
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first

/**
 * SOLO debug (Fase 9, P13 lega la porta di P11): lo strumento "Valutazione dei ricordi" sopra i ricordi montati, in **sola lettura**. Segue il contratto scritto
 * nella porta (`MemoriesEvalEnvironment`, la corsa `FamilyRig.run` del banco): per ogni giorno della finestra "oggi" e' quel giorno e un **motore suo** fa il giro
 * di notte, con lo scaffale in memoria (parte vuoto), le scelte "Mostra meno", "Mai" e "Ricordi con le persone" **copiate** (mai i visti ne' gli scartati), le
 * **stesse porte** del motore vero (viaggi fino al giorno simulato, persone, vettori, pixel, ritagli, scelta delle foto con il filtro finale dei Nascosti) e la
 * visibilita' vera. Niente si scrive (ne' lo scaffale vero ne' i dati personali), niente nei log, mai rete.
 *
 * Il titolo e' quello che l'utente vedrebbe (tipo, periodo, luoghi, il testo del viaggio) ma **mai il nome di una persona** ("Con una persona"): il file dei giudizi
 * lo salva. Sta fuori da `debugtools/eval/memories/` di proposito: quella cartella non nomina mai `core.memories` (`MemoriesEvalSourceParityTest`).
 */
class AppMemoriesEvalEnvironment(private val graph: MemoriesGraph) : MemoriesEvalEnvironment {

  override val today: LocalDate get() = graph.today()

  override suspend fun generate(days: Int): List<EvalMemory> {
    val live = graph.ensureLive() ?: return emptyList()
    val choices = live.personal.current()
    val personal = InMemoryMemoriesPersonalSettings().apply {
      for ((person, level) in choices.people) setLess(person, level)
      for (period in choices.neverDays) setNeverDays(period, true)
      setPeopleMemories(choices.peopleMemories)
    }
    val clock = MovingClock(graph.today())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val engine = MemoryEngine(
        library = graph.visibility, personal = personal, store = InMemoryShelfStore(), clock = clock, scope = scope,
        planner = IndexedPlanner(PeoplePlanner()), trips = live.trips, people = live.people, cutouts = live.cutouts, choice = live.choice,
      )
      val seen = HashSet<String>()
      val out = ArrayList<EvalMemory>()
      for (day in MemoriesEvalDays.window(clock.day, days)) {
        clock.day = day
        engine.refresh(RefreshReason.NIGHTLY, 60.seconds)
        for (card in engine.shelf.value.items) {
          if (!seen.add(card.id.value)) continue
          val memory = engine.memory(card.id).first()?.memory ?: continue
          out += EvalMemory(memory.id.value, memory.kind.code, day, memory.cover.value, memory.members.map { it.value }, describe(memory))
        }
      }
      return out
    } finally {
      scope.cancel()
    }
  }

  override suspend fun visibleAmong(ids: Collection<String>): Set<String> {
    val media = ids.mapNotNull { runCatching { MediaId(it) }.getOrNull() }
    if (media.isEmpty()) return emptySet()
    return graph.visibility.visibleOrThrow(media).mapTo(HashSet()) { it.value }
  }

  override suspend fun thumbnail(id: MediaId): ImageBitmap? = graph.localThumbnail(id, THUMB_PX)?.asImageBitmap()

  /** Il titolo come lo vedrebbe l'utente, senza il nome di una persona. */
  private fun describe(memory: Memory): String {
    val title = memory.title
    val parts = ArrayList<String>()
    parts += if (memory.kind == MemoryKind.PERSON) "Con una persona" else MemoriesEvalKinds.label(memory.kind.code)
    title.yearsAgo?.let { parts += if (it == 1) "un anno fa" else "$it anni fa" }
    parts += if (title.days.from == title.days.to) DAY.format(title.days.from) else "${DAY.format(title.days.from)} - ${DAY.format(title.days.to)}"
    title.tripLabel?.takeIf { memory.kind != MemoryKind.PERSON }?.let { parts += it }
    val places = title.placeIds.mapNotNull(graph.placeLabel)
    if (places.isNotEmpty()) parts += places.take(3).joinToString(", ")
    return parts.joinToString(" · ").take(EvalMemory.MAX_TITLE)
  }

  /** L'orologio della corsa: "oggi" si sposta sul giorno simulato; l'ora e' mezzogiorno di quel giorno. */
  private class MovingClock(var day: LocalDate) : MemoryClock {
    override fun today(): LocalDate = day

    override fun nowLocal(): LocalDateTime = day.atTime(12, 0)

    override fun monotonicMs(): Long = android.os.SystemClock.elapsedRealtime()
  }

  private companion object {
    const val THUMB_PX = 256
    val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)
  }
}
