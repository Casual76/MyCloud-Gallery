package dev.pampa.mycloudgallery.release

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import java.time.Duration
import java.time.Instant

/** Lo stato del manifest come costante per l'esportazione (`ReleaseStatus` e' sigillato, non un enum). */
enum class ReleaseLevel {
  NORMAL,
  READ_ONLY,
  STOPPED,
  ;

  companion object {
    fun of(status: ReleaseStatus): ReleaseLevel = when (status) {
      ReleaseStatus.Normal -> NORMAL
      is ReleaseStatus.ReadOnly -> READ_ONLY
      is ReleaseStatus.Stopped -> STOPPED
    }
  }
}

/**
 * La sezione "Versione e aggiornamenti" della Diagnostica (Fase 10, P13; docs/fase10-progetto.md §2.13, D39): `RELEASE_STATE` (versione, canale, `seq` e giorni dal manifest,
 * stato, quanti indirizzi aggiuntivi, le offerte di questo processo per esito), `RELEASE_MANIFEST_REJECTED` (i rifiuti per motivo: un `SIGNATURE` o `UNKNOWN_KEY` e' un
 * avviso da segnalare all'admin) e `RELEASE_BLOCKED` (il blocco in vigore: livello e codice del messaggio). **Solo numeri e costanti**: mai il contenuto del manifest oltre ai
 * numeri, mai gli indirizzi dei modelli. I rifiuti vivono in memoria (come in core-release): ripartono a zero a ogni processo.
 */
class ReleaseDiagnostics(
  private val release: ReleaseGraph,
  private val now: () -> Instant = { Instant.now() },
) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.RELEASE

  override suspend fun collect(): List<DiagItem> {
    val diag = release.diag.value
    val status = release.status.value
    val items = ArrayList<DiagItem>()
    val state = linkedMapOf<String, ExportValue>(
      "versionCode" to ExportValue.Num(release.version.code.toLong()),
      "channel" to ExportValue.Enum.of(release.version.channel),
      "seq" to ExportValue.Num(diag.seq),
      "state" to ExportValue.Enum.of(ReleaseLevel.of(status)),
      "extraUrls" to ExportValue.Num(diag.extraUrls.toLong()),
      "loaded" to ExportValue.Flag(release.isLoaded),
    )
    diag.issued?.let { state["issuedDays"] = ExportValue.Num(Duration.between(it, now()).toDays().coerceAtLeast(0)) }
    release.offerCounts().filterValues { it > 0 }.forEach { (result, n) -> state["offer.${result.code}"] = ExportValue.Num(n.toLong()) }
    items += item(DiagCodes.RELEASE_STATE, state)
    val rejected = diag.rejected.filterValues { it > 0 }
    if (rejected.isNotEmpty()) {
      // Una firma non valida o una chiave sconosciuta: qualcuno ha messo sul NAS un avviso che non viene dall'admin (o le chiavi sono cambiate). Da segnalare.
      val suspicious = (rejected[RejectReason.SIGNATURE] ?: 0) > 0 || (rejected[RejectReason.UNKNOWN_KEY] ?: 0) > 0
      items += item(
        DiagCodes.RELEASE_MANIFEST_REJECTED,
        rejected.entries.associate { (reason, n) -> "rejected.${reason.code}" to ExportValue.Num(n.toLong()) },
        if (suspicious) DiagSeverity.WARNING else null,
      )
    }
    val message = when (status) {
      is ReleaseStatus.ReadOnly -> status.message
      is ReleaseStatus.Stopped -> status.message
      ReleaseStatus.Normal -> null
    }
    if (message != null) {
      items += item(DiagCodes.RELEASE_BLOCKED, mapOf("state" to ExportValue.Enum.of(ReleaseLevel.of(status)), "releaseMessage" to ExportValue.Enum.of(message)))
    }
    return items
  }

  private fun item(code: DiagCode, export: Map<String, ExportValue>, severity: DiagSeverity? = null) = DiagItem(
    code = code,
    section = section,
    severity = severity ?: DiagCatalog.entry(code)?.severity ?: DiagSeverity.INFO,
    titleRes = DiagCatalog.titleOf(code),
    export = export,
  )
}
