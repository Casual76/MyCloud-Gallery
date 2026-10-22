package dev.pampa.mycloudgallery.core.ai.chaos

import dev.pampa.mycloudgallery.core.ai.compute.SimDone
import dev.pampa.mycloudgallery.core.ai.compute.SimFetcher
import dev.pampa.mycloudgallery.core.ai.compute.SimNas
import dev.pampa.mycloudgallery.core.ai.compute.SimPublisher
import dev.pampa.mycloudgallery.core.ai.compute.SimWorkUnits
import dev.pampa.mycloudgallery.core.index.ai.AiBufferSink
import dev.pampa.mycloudgallery.core.index.ai.AiDoneClasses
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.index.ai.AiIndexPublisher
import dev.pampa.mycloudgallery.core.index.ai.AiPendingCounts
import dev.pampa.mycloudgallery.core.index.ai.AnalysisFetcher
import dev.pampa.mycloudgallery.core.index.ai.FetchReport
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.UnitClaim
import dev.pampa.mycloudgallery.core.index.contract.UnitLease
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnits
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiBody
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// Le PORTE del giro verso il NAS finto di P5: guasti del `FaultPlan` del giro (rete prima e dopo, sessione scaduta, stallo) attorno a ogni
// chiamata al NAS, il registro delle prenotazioni (invariante 7) e un pubblicatore che sa dire cosa ha in fila e cosa pubblica (invariante 5).
// Dentro ci sono il NAS finto, le unita' e il pubblicatore di P5 (`SimNas`, `SimWorkUnits`, `SimPublisher`): non si riscrive niente, si avvolge.

/** I guasti del giro: il piano decide a quale chiamata al NAS (contate in ordine su tutto il giro) cade la rete, prima o dopo l'effetto. */
class NasFaultInjector(private val plan: AiRoundPlan?, private val stats: AiRoundStats?, private val nas: SimNas) {
  var calls = 0
    private set

  @Volatile var healthy: Boolean = plan == null

  suspend fun <T> call(effect: suspend () -> T): T {
    val n = ++calls
    val fault = if (healthy) null else plan?.nasFaults?.get(n)
    if (fault != null && fault.moment == FaultMoment.BEFORE) fire(fault)
    val value = effect()
    // "Dopo": il NAS ha applicato l'operazione, la risposta e' persa.
    if (fault != null && fault.moment == FaultMoment.AFTER) fire(fault)
    return value
  }

  private fun fire(fault: NasFault): Nothing {
    stats?.let { it.nasFaultsFired++ }
    // Lo stallo: il socket tace finche' non scade il sorvegliante (tempo del NAS che passa).
    if (fault.kind == FaultKind.STALL) nas.now += 90_000
    throw IOException("guasto simulato (${fault.kind})")
  }
}

/** Le prenotazioni concesse e rilasciate, per l'invariante 7. */
class LeaseLog {
  class Grant(val device: String, val resource: String, val at: Long, val until: Long)

  class Release(val device: String, val resource: String, val at: Long)

  companion object {
    /** Una prenotazione e' per (share, risorsa): la stessa risorsa ha lo stesso nome in share diverse. */
    fun idOf(claim: UnitClaim): String = claim.key.share.lowercase(java.util.Locale.ROOT) + "|" + claim.key.resource
  }

  val grants: MutableList<Grant> = mutableListOf()
  val releases: MutableList<Release> = mutableListOf()

  /** Unita' concesse mentre un altro dispositivo la teneva ancora (non scaduta, non rilasciata). */
  val doubleBooked: MutableList<String> = mutableListOf()
}

class FaultyUnits(
  private val inner: WorkUnits,
  private val inj: NasFaultInjector,
  private val log: LeaseLog,
  private val device: String,
  private val nas: SimNas,
) : WorkUnits {
  override suspend fun next(share: String, pass: WorkPass, priority: UnitPriority): UnitClaim? {
    nas.now += 1
    val before = HashMap(nas.of(share).leases)
    val claim = inj.call { inner.next(share, pass, priority) }
    note(claim)
    val prev = claim?.takeIf { it.lease is UnitLease.Held }?.let { before[it.key.resource] }
    if (prev != null && prev.first != device && prev.second > nas.now) log.doubleBooked += "unita' concessa mentre ${prev.first} la teneva ancora"
    return claim
  }

  override suspend fun keepAlive(claim: UnitClaim): KeepAliveResult {
    val result = try {
      inj.call { inner.keepAlive(claim) }
    } finally {
      // Anche con la risposta persa il NAS puo' aver rilasciato (nessun lavoro rimasto) o ceduto la prenotazione.
      noteRelease(claim)
    }
    if (result is KeepAliveResult.Renewed) note(result.claim)
    return result
  }

  override suspend fun finish(claim: UnitClaim, outcome: UnitOutcome) {
    try {
      inj.call { inner.finish(claim, outcome) }
    } finally {
      noteRelease(claim)
    }
  }

  /** Se la prenotazione non e' piu' di questo dispositivo nel NAS (rilasciata, scaduta e ripresa), la concessione e' finita. */
  private fun noteRelease(claim: UnitClaim) {
    if (claim.lease !is UnitLease.Held) return
    if (nas.shares[nas.key(claim.key.share)]?.leases?.get(claim.key.resource)?.first != device) {
      log.releases += LeaseLog.Release(device, LeaseLog.idOf(claim), nas.now)
    }
  }

  private fun note(claim: UnitClaim?) {
    val held = claim?.lease as? UnitLease.Held ?: return
    log.grants += LeaseLog.Grant(device, LeaseLog.idOf(claim), nas.now, held.untilNasMillis)
  }
}

class FaultyFetcher(private val inner: AnalysisFetcher, private val inj: NasFaultInjector) : AnalysisFetcher {
  override suspend fun fetch(share: String, keys: List<ContentKey>, classes: Set<SegmentClass>, into: AiBufferSink, budget: Duration): FetchReport =
    inj.call { inner.fetch(share, keys, classes, into, budget) }
}

class FaultyDone(private val inner: SimDone, private val inj: NasFaultInjector) : AiDoneClasses {
  override suspend fun done(share: String, keys: Collection<ContentKey>, recipe: Int): Map<ContentKey, Set<AiClass>> =
    inj.call { inner.done(share, keys, recipe) }
}

/**
 * Il pubblicatore del giro: il `SimPublisher` di P5 (che il NAS finto e le unita' conoscono) con sopra la cronaca di cio' che ha in fila e
 * cio' che pubblica. Regola dell'ordine (invariante 5, e il motivo dei difetti "pubblicare prima di salvare" e "salvare dopo aver
 * pubblicato"): una voce si mette in fila e si pubblica **solo** se ha gia' la sua riga nell'indice locale. Ogni violazione va in
 * [violations] con il momento in cui e' successa. I guasti del NAS cadono attorno a [flush] e [releasePending].
 */
class ChaosPublisher(
  private val inner: SimPublisher,
  private val inj: NasFaultInjector,
  private val index: ChaosLocalIndex,
  private val stats: AiRoundStats,
) : AiIndexPublisher {
  /** La fila come la vede il banco: per share, (chiave, classe) → corpo codificato. Rispecchia quella di [inner]. */
  private val outbox = LinkedHashMap<String, LinkedHashMap<Pair<ContentKey, AiClass>, ByteArray>>()
  private val held = LinkedHashMap<String, LinkedHashMap<Pair<ContentKey, AiClass>, ByteArray>>()
  val violations: MutableList<Pair<AiInvariant, String>> = mutableListOf()

  override val pending: StateFlow<AiPendingCounts> get() = inner.pending

  /** Le voci in attesa (share non pubblicabile) con il corpo con cui sono nate. */
  fun heldEntries(): Map<String, Map<Pair<ContentKey, AiClass>, ByteArray>> = held.mapValues { it.value.toMap() }

  fun waiting(share: String): Int = inner.waiting(share)

  override suspend fun stage(share: String, entries: List<AiEntry>) {
    for (e in entries) {
      if (!index.hasRow(e.contentKey, e.cls)) {
        violations += AiInvariant.COHERENT_DATA to "${e.cls.wire} messo in fila senza la riga locale (si salva prima di pubblicare)"
      }
      val body: AiBody = when (e) {
        is AiEntry.Vector -> e.body
        is AiEntry.Text -> e.body
      }
      outbox.getOrPut(key(share)) { LinkedHashMap() }[e.contentKey to e.cls] = AiSegments.encodeBody(body)
    }
    inner.stage(share, entries)
  }

  override suspend fun flush(share: String, reason: FlushReason): PublishOutcome {
    val before = outbox[key(share)]?.toMap().orEmpty()
    val outcome = try {
      inj.call { inner.flush(share, reason) }
    } catch (e: IOException) {
      // Rete caduta DOPO l'effetto: la risposta e' persa. Le code del pubblicatore dicono cosa e' successo davvero: voci passate in
      // attesa (share chiusa) oppure pubblicate (la testa e' sul NAS).
      val shadowHeld = held[key(share)]?.size ?: 0
      if (before.isNotEmpty() && inner.waiting(share) > shadowHeld) {
        held.getOrPut(key(share)) { LinkedHashMap() }.putAll(before)
        outbox[key(share)]?.clear()
      } else if (inner.pending.value.staged < outbox.values.sumOf { it.size }) {
        published(share, before)
      }
      throw e
    }
    when (outcome) {
      is PublishOutcome.Published -> published(share, before)
      is PublishOutcome.Blocked -> {
        // Come `SimPublisher`: sotto un blocco permanente l'outbox passa alle voci in attesa.
        if (before.isNotEmpty() && inner.waiting(share) > 0) {
          held.getOrPut(key(share)) { LinkedHashMap() }.putAll(before)
          outbox[key(share)]?.clear()
        }
      }
      else -> Unit
    }
    return outcome
  }

  private fun published(share: String, before: Map<Pair<ContentKey, AiClass>, ByteArray>) {
    for ((k, _) in before) {
      if (!index.hasRow(k.first, k.second)) {
        violations += AiInvariant.COHERENT_DATA to "${k.second.wire} pubblicato senza la riga locale (si salva prima di pubblicare)"
      }
    }
    stats.published += before.size
    outbox[key(share)]?.clear()
  }

  override suspend fun releasePending(share: String): Int {
    val moved = try {
      inj.call { inner.releasePending(share) }
    } catch (e: IOException) {
      // La risposta e' persa, ma le voci in attesa possono essere gia' tornate in fila.
      if (inner.waiting(share) == 0) outbox.getOrPut(key(share)) { LinkedHashMap() }.putAll(held.remove(key(share)).orEmpty())
      throw e
    }
    if (moved > 0) {
      stats.pendingReleased += moved
      val list = held.remove(key(share)).orEmpty()
      outbox.getOrPut(key(share)) { LinkedHashMap() }.putAll(list)
    }
    return moved
  }

  private fun key(share: String) = share.lowercase(java.util.Locale.ROOT)
}

/** Il numero di unita' prese contate dai test del banco. */
internal fun SimWorkUnits.claimedCount(): Int = claims.size
