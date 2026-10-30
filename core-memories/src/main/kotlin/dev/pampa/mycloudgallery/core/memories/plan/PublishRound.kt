package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.memories.contract.CellTable
import dev.pampa.mycloudgallery.core.memories.contract.DropReason
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCandidate
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClock
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.memories.shelf.ShelfCodec
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import java.util.EnumMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Cosa ha pubblicato un giro (docs/fase9-progetto.md §2.1, passi 5 e 6): i ricordi nuovi e i conti. */
internal class Published(
  val memories: List<Memory>,
  val dropped: Map<DropReason, Int>,
  val incomplete: Boolean,
  val withoutVectors: Boolean,
  val lastPublished: Map<MemoryKind, Int>,
  val lastRediscovery: Int?,
) {
  override fun toString(): String = "Published(${memories.size}, incompleto=$incomplete)"
}

/**
 * Dalla lista dei candidati ai ricordi nuovi (docs/fase9-progetto.md §2.1 passi 5 e 6, D3, D5-D7, D9, D31):
 *
 *  - i **segnali** e la scelta delle foto sono di [MemoryChoice] (una volta per ricordo, e solo se c'e' ancora tempo: finito il budget, quello che
 *    c'e' resta valido e il giro e' "incompleto");
 *  - si pubblicano al piu' [MemoryRules.NEW_PER_DAY] ricordi nuovi al giorno **piu'** quello del mese nel suo giorno (si contano anche quelli di
 *    un giro precedente dello stesso giorno), nell'ordine dei candidati (priorita', poi chiave);
 *  - ogni scelta passa dal filtro finale dei nascosti prima di entrare (un media nascosto un attimo fa non entra, D31); un guasto del filtro si
 *    propaga: non si pubblica niente su un "non lo so";
 *  - il titolo e' fatto di dati e fisso da qui in poi (D9): luoghi principali, anni, viaggio, persona (solo l'id), mese; l'etichetta di un
 *    viaggio e' limitata alla lunghezza che lo scaffale salva.
 */
internal class PublishRound(
  private val library: MemoryVisibility,
  private val choice: MemoryChoice,
  private val clock: MemoryClock,
) {

  suspend fun run(
    candidates: List<MemoryCandidate>,
    table: CellTable,
    choices: MemoriesChoices,
    cleaned: ShelfFile,
    todayDay: Int,
    deadline: Long,
  ): Published {
    val dropped = EnumMap<DropReason, Int>(DropReason::class.java)
    val lastPublished = cleaned.lastPublished.toMutableMap()
    var lastRediscovery = cleaned.lastRediscovery
    var newToday = cleaned.memories.count { it.publishedDay == todayDay && it.kind != MemoryKind.MONTH }
    var monthToday = cleaned.memories.any { it.publishedDay == todayDay && it.kind == MemoryKind.MONTH }
    val published = ArrayList<Memory>()
    val room = candidates.any { if (it.key.kind == MemoryKind.MONTH) !monthToday else newToday < MemoryRules.NEW_PER_DAY }
    val outOfTime = clock.monotonicMs() > deadline
    var incomplete = outOfTime && room
    var withoutVectors = false
    if (room && !outOfTime) {
      for (candidate in candidates) {
        if (clock.monotonicMs() > deadline) {
          incomplete = true
          break
        }
        val isMonth = candidate.key.kind == MemoryKind.MONTH
        val full = if (isMonth) monthToday else newToday >= MemoryRules.NEW_PER_DAY
        if (full) continue
        val memory = publish(candidate, table, choices, todayDay, deadline, dropped) { withoutVectors = withoutVectors || it } ?: continue
        published += memory
        lastPublished[candidate.key.kind] = todayDay
        if (candidate.rediscovery) lastRediscovery = todayDay
        if (isMonth) monthToday = true else newToday++
      }
    }
    return Published(published, dropped.toMap(), incomplete, withoutVectors, lastPublished, lastRediscovery)
  }

  private suspend fun publish(
    candidate: MemoryCandidate,
    table: CellTable,
    choices: MemoriesChoices,
    todayDay: Int,
    deadline: Long,
    dropped: MutableMap<DropReason, Int>,
    noteVectors: (Boolean) -> Unit,
  ): Memory? {
    val spec = MemoryRules.spec(candidate.key.kind)
    // I segnali si chiedono una volta per ricordo, dentro la scelta (P7 li limita: pixel solo per i finalisti, persone che falliscono chiuse).
    val chosen = choice.choose(candidate.cells.map { table.candidate(it) }, spec, choices, remaining(deadline))
    noteVectors(chosen.withoutVectors)
    val result = chosen.result
    for ((reason, count) in result.dropped) dropped[reason] = (dropped[reason] ?: 0) + count
    if (!result.accepted) return null
    // Il filtro finale (D31): un media nascosto un attimo fa non entra.
    val picked = result.members.distinct().take(Memory.MAX_MEMBERS)
    val hidden = library.hiddenOrThrow(picked)
    val members = picked.filter { it !in hidden }
    if (members.size < spec.minSize) return null
    val cover = result.cover!!.takeIf { it in members } ?: members.first()
    return Memory(candidate.id, candidate.key, title(candidate, members, table), members, cover, todayDay, candidate.origin)
  }

  private fun title(candidate: MemoryCandidate, members: List<MediaId>, table: CellTable): MemoryTitle {
    val places = if (candidate.tripPlaces.isNotEmpty()) {
      candidate.tripPlaces.filter { it > 0 }.distinct().take(MemoryTitle.MAX_PLACES)
    } else {
      MemoryTitle.topPlaces(members.map { id -> table.indexOf(id).let { if (it >= 0) table.placeAt(it) else 0 } })
    }
    return MemoryTitle(candidate.yearsAgo, candidate.days, places, candidate.tripLabel?.let { ShelfCodec.capLabel(it) }, candidate.person, candidate.month)
  }

  private fun remaining(deadline: Long): Duration = (deadline - clock.monotonicMs()).coerceAtLeast(0).milliseconds
}
