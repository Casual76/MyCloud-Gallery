package dev.pampa.mycloudgallery.memories

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.memories.contract.MemoriesStatus
import dev.pampa.mycloudgallery.core.memories.contract.NotifyRules
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Le consegne della notifica **in ritardo** (oltre H+1 h) negli ultimi [NotifyRules.LEDGER_DAYS] giorni (`NOTIFY_LATE`, D20: se piu' del 10% e' in ritardo
 * scatta il piano B). Un file locale (`memories/notify-late`) con una riga per consegna in ritardo: il giorno `aaaammgg` e basta, mai che ricordo. Esce dal
 * telefono **solo il conteggio**. Un file illeggibile vale zero.
 */
class LateDeliveries(private val file: File, private val today: () -> LocalDate) {

  @Synchronized
  fun record() {
    val now = dayOf(today())
    val kept = days().filter { it >= oldest() } + now
    try {
      StateFiles.writeText(file, kept.joinToString("\n"))
    } catch (e: IOException) {
      // Un conteggio perso: solo la Diagnostica ne risente.
    }
  }

  @Synchronized
  fun count(): Int = days().count { it >= oldest() }

  private fun oldest(): Int = dayOf(today().minusDays(NotifyRules.LEDGER_DAYS - 1L))

  private fun days(): List<Int> = try {
    StateFiles.readLines(file).orEmpty().mapNotNull { it.trim().toIntOrNull() }
  } catch (e: IOException) {
    emptyList()
  }

  companion object {
    const val FILE: String = "notify-late"

    fun dayOf(day: LocalDate): Int = day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth
  }
}

/**
 * La sezione "Ricordi" della Diagnostica (Fase 9, P13; docs/fase9-progetto.md §2.12, D33): lo stato (montati, ricordi sullo scaffale, nuovi, ultimo giro in
 * giorni, durata) e una voce per codice che ha qualcosa da dire. **Solo numeri, flag e codici**: mai titoli, luoghi, date, id dei ricordi, nomi o id di
 * persone, l'ora scelta per la notifica. I conti delle voci (esclusi, notifiche saltate, scaffali rigenerati, tolte, consegnate) sono degli **ultimi 14 giorni** e stanno su
 * file (DG-1, DG-2, 31/10): non ripartono da zero a ogni avvio del processo.
 */
class MemoriesDiagnostics(private val graph: MemoriesGraph, private val today: () -> LocalDate) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.MEMORIES

  override suspend fun collect(): List<DiagItem> {
    val c = graph.counters
    val live = graph.live.value
    val items = ArrayList<DiagItem>()
    val status = live?.engine?.status?.value
    val shelf = live?.engine?.shelf?.value
    val state = mutableMapOf<String, ExportValue>(
      "mounted" to ExportValue.Flag(live != null),
      "onShelf" to ExportValue.Num((shelf?.items?.size ?: 0).toLong()),
      "unseen" to ExportValue.Num((shelf?.unseen ?: 0).toLong()),
      "status" to ExportValue.Enum.of(stateOf(status)),
      "roundErrors" to ExportValue.Num(c.roundErrors.toLong()),
    )
    (status as? MemoriesStatus.Idle)?.lastDay?.takeIf { it > 0 }?.let { day -> daysAgo(day)?.let { state["lastRoundDays"] = ExportValue.Num(it) } }
    c.lastRoundMs?.let { state["roundMs"] = ExportValue.Dur(it) }
    if (c.mountFailed) state["failed"] = ExportValue.Flag(true)
    items += item(DiagCodes.MEMORY_STATE, state)
    // DG-1: i conti stanno su file per gli ultimi 14 giorni (gli stessi di `late`): letti qui, una volta per raccolta.
    val shelfResets = c.shelfResets
    val withoutVectors = c.withoutVectors
    val delivered = c.delivered
    val revoked = c.revoked
    if (shelfResets > 0) items += item(DiagCodes.MEMORY_SHELF_RESET, mapOf("count" to ExportValue.Num(shelfResets.toLong())))
    if (withoutVectors > 0) items += item(DiagCodes.MEMORY_NO_VECTORS, mapOf("count" to ExportValue.Num(withoutVectors.toLong())))
    val dropped = c.droppedByReason().filterValues { it > 0 }
    if (dropped.isNotEmpty()) items += item(DiagCodes.MEMORY_DROPPED, dropped.entries.associate { (reason, n) -> "dropped.${reason.code}" to ExportValue.Num(n.toLong()) })
    val skipped = c.skippedByReason().filterValues { it > 0 }
    if (skipped.isNotEmpty()) items += item(DiagCodes.NOTIFY_SKIPPED, skipped.entries.associate { (reason, n) -> "skip.${reason.code}" to ExportValue.Num(n.toLong()) })
    val late = c.late?.count() ?: 0
    // DG-2: `late` e `delivered` hanno la stessa base (le consegne degli ultimi 14 giorni, su file): dopo un riavvio il rapporto del piano B di D20 si legge ancora.
    if (late > 0) items += item(DiagCodes.NOTIFY_LATE, mapOf("late" to ExportValue.Num(late.toLong()), "delivered" to ExportValue.Num(delivered.toLong())))
    if (revoked > 0) items += item(DiagCodes.NOTIFY_REVOKED, mapOf("count" to ExportValue.Num(revoked.toLong())))
    return items
  }

  private fun daysAgo(day: Int): Long? = try {
    ChronoUnit.DAYS.between(LocalDate.parse(day.toString(), DateTimeFormatter.BASIC_ISO_DATE), today()).coerceAtLeast(0)
  } catch (e: RuntimeException) {
    null
  }

  private fun stateOf(status: MemoriesStatus?): MemoriesRunState = when (status) {
    null -> MemoriesRunState.UNMOUNTED
    is MemoriesStatus.Idle -> MemoriesRunState.IDLE
    MemoriesStatus.Working -> MemoriesRunState.WORKING
    is MemoriesStatus.Problem -> MemoriesRunState.PROBLEM
  }

  private fun item(code: DiagCode, export: Map<String, ExportValue>) =
    DiagItem(code = code, section = section, severity = DiagCatalog.entry(code)?.severity ?: DiagSeverity.INFO, titleRes = DiagCatalog.titleOf(code), export = export)
}

/** Lo stato del motore nella Diagnostica (una costante, mai un dato). */
enum class MemoriesRunState { UNMOUNTED, IDLE, WORKING, PROBLEM }
