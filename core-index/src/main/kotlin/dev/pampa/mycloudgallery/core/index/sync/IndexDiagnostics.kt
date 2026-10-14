package dev.pampa.mycloudgallery.core.index.sync

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.index.contract.IndexDiag
import dev.pampa.mycloudgallery.core.index.contract.ReplicaQuality
import dev.pampa.mycloudgallery.core.index.contract.SyncReport
import dev.pampa.mycloudgallery.core.trust.RejectReason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * I contatori della replica e della sincronizzazione (solo numeri ed enumerati: mai share, percorsi, id). Li scrive
 * [IndexSyncEngine], li legge [IndexDiagnostics]. Durano quanto il processo.
 */
class IndexCounters {
  private val rejected = ConcurrentHashMap<RejectReason, AtomicInteger>()

  /** E-I5: teste piu' vecchie di quella accettata o equivoche. */
  val rollbacks = AtomicInteger()

  /** E-I3: dati di una versione piu' nuova dell'app. */
  val newerFormat = AtomicInteger()

  /** D7, E-I18: adozioni rifiutate. */
  val identityConflicts = AtomicInteger()

  /** E-I8: share tolte dalla replica. */
  val sharesDropped = AtomicInteger()

  /** E-I15: dispositivi i cui dati sono stati tolti. */
  val devicesForgotten = AtomicInteger()

  /** E-I17: segmenti elencati ma assenti. */
  val segmentsMissing = AtomicInteger()

  @Volatile var lastReport: SyncReport? = null

  fun reject(reason: RejectReason) {
    rejected.getOrPut(reason) { AtomicInteger() }.incrementAndGet()
  }

  fun rejected(): Map<RejectReason, Int> = rejected.mapValues { it.value.get() }.filterValues { it > 0 }
}

/**
 * Il contributo della replica alla Diagnostica (sezione `NAS_LIBRARY`, codici di [IndexDiag]). I titoli sono risorse di
 * :app o di `feature-diagnostics` (P11): arrivano da [titleOf], cosi' questo modulo non ha stringhe proprie.
 */
class IndexDiagnostics(
  private val counters: IndexCounters,
  private val quality: ReplicaQuality?,
  private val titleOf: (DiagCode) -> Int,
) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.NAS_LIBRARY

  override suspend fun collect(): List<DiagItem> = buildList {
    val rejected = counters.rejected()
    if (rejected.isNotEmpty()) {
      val export = HashMap<String, ExportValue>()
      export["count"] = ExportValue.Num(rejected.values.sum().toLong())
      rejected.forEach { (reason, n) -> export["reason.${reason.name}"] = ExportValue.Num(n.toLong()) }
      add(item(IndexDiag.INDEX_REJECTED, DiagSeverity.WARNING, export))
    }
    count(IndexDiag.INDEX_ROLLBACK, counters.rollbacks.get(), DiagSeverity.WARNING)
    count(IndexDiag.INDEX_NEWER_FORMAT, counters.newerFormat.get(), DiagSeverity.WARNING)
    count(IndexDiag.INDEX_IDENTITY_CONFLICT, counters.identityConflicts.get(), DiagSeverity.WARNING)
    count(IndexDiag.INDEX_SHARE_DROPPED, counters.sharesDropped.get(), DiagSeverity.WARNING)
    quality?.state?.value?.let { state ->
      val export = mapOf(
        "level" to ExportValue.Enum.of(state.level),
        "bytes" to ExportValue.Num(state.currentBytes),
        "converging" to ExportValue.Flag(state.converging),
        "complete" to ExportValue.Flag(counters.lastReport?.complete ?: false),
      )
      add(item(IndexDiag.INDEX_REPLICA_STATE, DiagSeverity.INFO, export))
    }
  }

  private fun MutableList<DiagItem>.count(code: DiagCode, n: Int, severity: DiagSeverity) {
    if (n > 0) add(item(code, severity, mapOf("count" to ExportValue.Num(n.toLong()))))
  }

  private fun item(code: DiagCode, severity: DiagSeverity, export: Map<String, ExportValue>): DiagItem =
    DiagItem(code, section, severity, titleOf(code), export = export)
}
