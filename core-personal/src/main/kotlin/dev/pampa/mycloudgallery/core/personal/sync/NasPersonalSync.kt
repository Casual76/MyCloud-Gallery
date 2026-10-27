package dev.pampa.mycloudgallery.core.personal.sync

import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalHeadsView
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalLimits
import dev.pampa.mycloudgallery.core.personal.contract.PersonalPublication
import dev.pampa.mycloudgallery.core.personal.contract.PersonalRules
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStop
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSync
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncReport
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncStatus
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalUnavailable
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValidity
import dev.pampa.mycloudgallery.core.personal.contract.PersonalVerdict
import dev.pampa.mycloudgallery.core.personal.contract.PrePublish
import dev.pampa.mycloudgallery.core.personal.contract.PrePublishHook
import dev.pampa.mycloudgallery.core.personal.noop.NoPrePublishHook
import dev.pampa.mycloudgallery.core.personal.remote.PersonalHeadFetch
import dev.pampa.mycloudgallery.core.personal.remote.PersonalHeadView
import dev.pampa.mycloudgallery.core.personal.remote.PersonalRemote
import dev.pampa.mycloudgallery.core.personal.remote.PersonalSegmentFetch
import dev.pampa.mycloudgallery.core.personal.rules.PersonalRulesFactory
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** I tetti di lettura di un giro (default: [PersonalLimits]); parametrizzati solo per provarli senza scaricare centinaia di MiB. */
data class SyncLimits(
  val maxBytesPerDevice: Long = PersonalLimits.MAX_BYTES_PER_DEVICE_ROUND,
  val maxBytesPerRound: Long = PersonalLimits.MAX_BYTES_PER_ROUND,
  val maxEntriesPerDevice: Int = PersonalLimits.MAX_ENTRIES_PER_DEVICE_ROUND,
)

/**
 * La sincronizzazione dei dati personali fra i dispositivi di UN account (D26, docs/fase4-progetto.md §2.9). Un giro:
 *
 * 1. **Prima di partire**: registro accettato, area privata dichiarata diversa dalla comune (L23), cancello delle scritture
 *    (D31: sulle share vere i dati restano sul telefono e non parte nessuna richiesta), ora del NAS.
 * 2. **Revisione**: le voci gia' accettate il cui autore e' stato revocato nel frattempo si tolgono dallo stato; le modifiche locali
 *    del catalogo che non hanno una voce si adottano; le modifiche "da datare" prendono l'ora del NAS (non con un salto dell'ora:
 *    senza ora fidata non si data niente, D32).
 * 3. **Lettura**: per ogni dispositivo dell'account (questo compreso, per ricostruire dopo la perdita del registro locale) la testa
 *    `personal` piu' recente; ogni voce dei suoi segmenti si giudica con [PersonalValidity] (autore ammesso e non revocato, ora non
 *    nel futuro) e le valide si fondono con [dev.pampa.mycloudgallery.core.personal.contract.PersonalMerge]. La testa si ricorda
 *    (anti-rollback) solo se e' stata letta per intero e senza voci "dal futuro", che potrebbero valere al giro dopo.
 * 4. **Pubblicazione**: lo stato unito diviso in segmenti stabili, poi una testa nuova, al massimo una ogni 5 minuti (M3) e solo
 *    se lo stato e' cambiato. Non con un congelamento, una sospensione, un'area in sola lettura o un dispositivo non approvato: si legge soltanto.
 *    **Fase 8 (D30, P8)**: subito prima di scrivere, il gancio [prePublish] riceve lo stato che sta per uscire e puo' fermare quel giro
 *    ([PrePublish.Hold]): `:app` lo lega a `FacesSwitch.reconcile`, cosi' una testa che dice `faces` = `off|G` (o un'altra generazione)
 *    esce solo dopo che i dati dei volti di `G` su questo dispositivo sono tolti.
 * 5. **Fase 8 (D30, §2.8 passo 10)**: di ogni testa letta per intero (e della propria appena pubblicata) si ricordano le voci guardate in
 *    [heads] (la `PersonalHeadsView` che legge "Disattiva e cancella"), **prima** di ricordarne il segno anti-rollback: se il ricordo non
 *    si scrive, la testa si rilegge al giro dopo.
 *
 * Nessun orologio del telefono: l'ora e' [clock] (NAS) e la durata si misura con [monotonicMillis].
 */
class NasPersonalSync(
  /** La share privata dichiarata localmente per l'account (M7): l'unica da cui si legge e in cui si scrive. */
  private val share: String,
  /** L'account del NAS di questo dispositivo: decide di chi sono i dati. */
  private val account: String,
  private val remote: PersonalRemote,
  private val store: PersonalSyncStore,
  private val identity: IndexIdentity,
  private val clock: NasClock,
  private val gate: IndexWriteGate,
  private val monotonicMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
  private val limits: SyncLimits = SyncLimits(),
  /** Fase 8 (D30): il gancio prima di ogni pubblicazione; di serie si pubblica sempre. */
  private val prePublish: PrePublishHook = NoPrePublishHook,
  /** Fase 8 (D30, §2.8 passo 10): cosa dice l'ultima testa di ogni dispositivo dell'account (durevole se `:app` gli da' un file). */
  private val heads: PersonalHeadsRecord = PersonalHeadsRecord(),
) : PersonalSync {

  /** Fase 8: la vista delle teste che questo motore aggiorna ("Disattiva e cancella" la legge). */
  val headsView: PersonalHeadsView get() = heads

  private val holdState = MutableStateFlow<String?>(null)

  /** Fase 8: il codice dell'ultimo giro che il gancio ha fermato prima di pubblicare (null = l'ultimo ha potuto). Per la Diagnostica. */
  val lastHold: StateFlow<String?> = holdState

  private val lock = Mutex()
  private val state = MutableStateFlow<PersonalSyncStatus>(PersonalSyncStatus.Idle)
  override val status: StateFlow<PersonalSyncStatus> = state

  /** Una testa con voci "dal futuro": finche' l'ora del NAS non arriva a [retryAtNas] riscaricarla non cambia niente. */
  private class FutureHold(val version: Long, val retryAtNas: Long)

  private val futureHold = HashMap<String, FutureHold>()

  override suspend fun sync(budget: Duration): PersonalSyncReport = lock.withLock {
    state.value = PersonalSyncStatus.Syncing
    try {
      round(budget)
    } finally {
      if (state.value == PersonalSyncStatus.Syncing) state.value = PersonalSyncStatus.Idle
    }
  }

  /** I conti di un giro. */
  private class Tally {
    val rejected = HashMap<PersonalVerdict, Int>()
    var applied = 0
    var rejectedSegments = 0
    var rejectedHeads = 0
    var notPublishable = 0
    var bytes = 0L

    fun reject(verdict: PersonalVerdict) {
      rejected.merge(verdict, 1, Int::plus)
    }
  }

  private suspend fun round(budget: Duration): PersonalSyncReport {
    val started = monotonicMillis()
    fun overBudget(): Boolean = monotonicMillis() - started >= budget.inWholeMilliseconds
    val tally = Tally()

    // --- 1. Prima di partire ---------------------------------------------------------------------------------------------------
    val registry = identity.registry() ?: return halted(PersonalUnavailable.NOT_TRUSTED, tally)
    // L23: area privata uguale alla comune = nessuna area privata. I dati personali non entrano mai nell'area comune.
    if (registry.registry.config.commonShare.equals(share, ignoreCase = true)) return halted(PersonalUnavailable.NO_PRIVATE_AREA, tally)
    val block = gate.blockFor(share)
    if (block == WriteBlock.LOCAL_ONLY) {
      state.value = PersonalSyncStatus.LocalOnly
      return report(tally, 0, PersonalStop.UNAVAILABLE)
    }
    val nasNow = try {
      clock.nowMillis()
    } catch (e: IOException) {
      return halted(PersonalUnavailable.NAS_UNREACHABLE, tally)
    }

    // --- 2. Revisione dello stato locale ---------------------------------------------------------------------------------------
    val rules = PersonalRulesFactory.from(registry, account, nasNow)
    store.purgeInvalid(rules)
    store.adoptLocal()
    if (block != WriteBlock.CLOCK_JUMP) store.stamp(nasNow)

    // --- 3. Lettura ------------------------------------------------------------------------------------------------------------
    remote.startRound()
    val devices = when (val result = remote.devices()) {
      is RemoteResult.Ok -> result.value
      is RemoteResult.Failed -> return halted(unavailableFor(result.failure), tally)
    }
    val marks = store.marks()
    var stop = PersonalStop.COMPLETE
    var abort: PersonalUnavailable? = null
    for (device in devices.sorted()) {
      if (overBudget()) {
        stop = PersonalStop.BUDGET
        break
      }
      val fetch = when (val result = remote.latestHead(device, marks[device])) {
        is RemoteResult.Ok -> result.value
        is RemoteResult.Failed -> {
          if (isFatal(result.failure)) {
            abort = unavailableFor(result.failure)
            break
          }
          continue
        }
      }
      when (fetch) {
        PersonalHeadFetch.NoChange, PersonalHeadFetch.None -> Unit
        is PersonalHeadFetch.Rejected -> {
          tally.rejectedHeads++
          PersonalRulesFactory.verdictFor(fetch.reason)?.let(tally::reject)
        }
        is PersonalHeadFetch.Accepted -> {
          val hold = futureHold[device]
          if (hold != null && hold.version == fetch.head.version && nasNow < hold.retryAtNas) continue
          val read = readHead(fetch.head, rules, tally, nasNow, ::overBudget)
          if (read.abort != null) {
            abort = read.abort
            break
          }
          var mark = if (read.complete && !read.hasFuture) fetch.head.mark else null
          // D30: cio' che dice la testa si ricorda PRIMA del segno; se non si ricorda, il segno non si salva e la testa si rilegge.
          if (mark != null && !heads.record(device, fetch.head.createdAtNasMillis, read.valid)) mark = null
          if (read.hasFuture) futureHold[device] = FutureHold(fetch.head.version, read.retryAtNas) else futureHold.remove(device)
          tally.applied += store.applyFrom(device, read.valid, mark).applied
          // La testa che questo dispositivo ha gia' pubblicato (un registro locale perso, una reinstallazione con la stessa chiave): da
          // qui si sa il suo numero (la prossima non collide) e la sua ora (M3 vale anche per la prima dopo la perdita).
          if (mark != null && device == identity.deviceId && (store.published()?.version ?: 0L) < fetch.head.version) {
            val head = fetch.head
            store.setPublished(PersonalPublication(head.version, head.mark, head.createdAtNasMillis, head.segments.mapTo(LinkedHashSet()) { it.hash }, read.valid.size))
          }
          if (read.budgetHit) {
            stop = PersonalStop.BUDGET
            break
          }
        }
      }
    }
    val unresolved = store.reproject()

    // --- 4. Pubblicazione ------------------------------------------------------------------------------------------------------
    val blockReason = block?.let { blockedBy(it) }
    var published = 0
    var retryAt: Long? = null
    var publishFailure: PersonalUnavailable? = null
    if (abort == null && stop == PersonalStop.COMPLETE && blockReason == null && !overBudget()) {
      when (val outcome = publish(nasNow)) {
        is Published -> published = outcome.entries
        is TooSoon -> retryAt = outcome.retryAtNas
        is PublishFailed -> publishFailure = outcome.reason
        // D30: il gancio ha fermato la pubblicazione (i dati dei volti non sono ancora tolti): questo giro legge soltanto, il prossimo riprova.
        is Held, Unchanged -> Unit
      }
      tally.notPublishable = planned?.skipped ?: 0
    }

    val finalStatus: PersonalSyncStatus = when {
      abort != null -> PersonalSyncStatus.Unavailable(abort)
      publishFailure != null -> PersonalSyncStatus.Unavailable(publishFailure)
      blockReason != null -> PersonalSyncStatus.Unavailable(blockReason)
      stop == PersonalStop.COMPLETE -> PersonalSyncStatus.UpToDate(nasNow)
      else -> PersonalSyncStatus.Idle
    }
    state.value = finalStatus
    return report(tally, published, if (abort != null) PersonalStop.UNAVAILABLE else stop, retryAt, unresolved)
  }

  // --- Lettura di una testa ----------------------------------------------------------------------------------------------------

  private class HeadReading(
    val valid: List<PersonalEntry>,
    /** Tutti i segmenti sono stati considerati: nessun errore di rete o di tempo che potrebbe non ripetersi. */
    val complete: Boolean,
    val hasFuture: Boolean,
    val retryAtNas: Long,
    val budgetHit: Boolean,
    val abort: PersonalUnavailable?,
  )

  private suspend fun readHead(
    head: PersonalHeadView,
    rules: PersonalRules,
    tally: Tally,
    nasNow: Long,
    overBudget: () -> Boolean,
  ): HeadReading {
    val valid = ArrayList<PersonalEntry>()
    var complete = true
    var hasFuture = false
    var retryAt = Long.MAX_VALUE
    var budgetHit = false
    var deviceBytes = 0L
    var deviceEntries = 0
    for (ref in head.segments) {
      if (overBudget()) {
        budgetHit = true
        complete = false
        break
      }
      // Un dispositivo (o una testa ostile) non fa scaricare piu' di cosi' in un giro: oltre, la testa si considera letta.
      if (deviceBytes + ref.size > limits.maxBytesPerDevice || deviceEntries >= limits.maxEntriesPerDevice) {
        tally.rejectedSegments++
        continue
      }
      if (tally.bytes + ref.size > limits.maxBytesPerRound) {
        // Il tetto e' di tutto il giro, non di questa testa: si riprende al prossimo.
        complete = false
        break
      }
      when (val result = remote.segment(head, ref)) {
        is RemoteResult.Failed -> {
          if (isFatal(result.failure)) return HeadReading(valid, false, hasFuture, retryAt, budgetHit, unavailableFor(result.failure))
          complete = false
        }
        is RemoteResult.Ok -> when (val fetch = result.value) {
          PersonalSegmentFetch.Missing -> tally.rejectedSegments++
          is PersonalSegmentFetch.Rejected -> {
            // Anche uno scartato e' stato scaricato (fino alla dimensione dichiarata dalla testa firmata): costa come gli altri,
            // altrimenti una testa con migliaia di segmenti di spazzatura farebbe scaricare gigabyte.
            deviceBytes += ref.size
            tally.bytes += ref.size
            tally.rejectedSegments++
            PersonalRulesFactory.verdictFor(fetch.reason)?.let(tally::reject)
          }
          is PersonalSegmentFetch.Ok -> {
            deviceBytes += ref.size
            tally.bytes += ref.size
            deviceEntries += fetch.entries.size
            for (entry in fetch.entries) {
              val verdict = PersonalValidity.check(entry, rules, inDeclaredPrivateArea = true)
              if (verdict == PersonalVerdict.VALID) {
                valid += entry
              } else {
                tally.reject(verdict)
                if (verdict == PersonalVerdict.FROM_FUTURE) {
                  hasFuture = true
                  retryAt = minOf(retryAt, entry.atNas - TrustLimits.MAX_FUTURE_SKEW_MILLIS)
                }
              }
            }
          }
        }
      }
    }
    return HeadReading(valid, complete, hasFuture, retryAt, budgetHit, null)
  }

  // --- Pubblicazione -----------------------------------------------------------------------------------------------------------

  private sealed interface PublishOutcome

  private class Published(val entries: Int) : PublishOutcome

  private class TooSoon(val retryAtNas: Long) : PublishOutcome

  private class PublishFailed(val reason: PersonalUnavailable) : PublishOutcome

  private class Held(val code: String) : PublishOutcome

  private data object Unchanged : PublishOutcome

  /** L'ultimo stato di cui si e' calcolato il piano: solo i nomi dei segmenti (i byte servono solo a chi pubblica). */
  private class PlannedState(val state: Map<PersonalKey, PersonalEntry>, val names: Set<String>, val skipped: Int)

  private var planned: PlannedState? = null

  private suspend fun publish(nasNow: Long): PublishOutcome {
    val merged = store.state()
    val last: PersonalPublication? = store.published()
    // Lo stesso stato di prima, gia' pubblicato: niente da ricalcolare (con 100.000 voci sono ~400 ms a ogni giro).
    val cached = planned
    if (cached != null && cached.state === merged && last != null && last.segments == cached.names) return Unchanged
    val plan = PersonalSegments.plan(merged.values)
    planned = PlannedState(merged, plan.names, plan.skipped)
    if (plan.entries == 0) return Unchanged
    // Lo stato pubblicato e' gia' questo: niente testa nuova (le teste non si accumulano per niente).
    if (last != null && last.segments == plan.names) return Unchanged
    // M3: una testa ogni 5 minuti, ora del NAS. Un'ora salvata nel futuro (l'ora del NAS e' tornata indietro) non blocca.
    if (last != null && last.publishedAtNas <= nasNow) {
      val next = last.publishedAtNas + TrustLimits.HEAD_MIN_INTERVAL_MILLIS
      if (nasNow < next) return TooSoon(next)
    }
    // D30 (Fase 8, P8): il gancio vede lo stato che sta per uscire e puo' fermare il giro. Un gancio che lancia e' un gancio che ferma.
    val verdict = try {
      prePublish.beforePublish(merged)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      PrePublish.Hold(HOOK_FAILED)
    }
    if (verdict is PrePublish.Hold) {
      holdState.value = verdict.code
      return Held(verdict.code)
    }
    holdState.value = null
    val knownVersion = store.marks()[identity.deviceId]?.version ?: 0L
    val after = maxOf(last?.version ?: 0L, knownVersion)
    return when (val result = remote.publish(plan, last?.segments.orEmpty(), after, nasNow)) {
      is RemoteResult.Ok -> {
        val head = result.value
        store.setPublished(PersonalPublication(head.version, head.mark, nasNow, plan.names, plan.entries))
        // La testa che abbiamo scritto e' gia' dentro il nostro stato: non serve rileggerla (se pero' non si e' potuto ricordare cosa
        // dice, il segno non si salva e la si rilegge al giro dopo, D30).
        if (heads.record(identity.deviceId, nasNow, merged.values)) store.applyFrom(identity.deviceId, emptyList(), head.mark)
        Published(plan.entries)
      }
      is RemoteResult.Failed -> PublishFailed(unavailableFor(result.failure))
    }
  }

  // --- Esiti -------------------------------------------------------------------------------------------------------------------

  private suspend fun halted(reason: PersonalUnavailable, tally: Tally): PersonalSyncReport {
    state.value = PersonalSyncStatus.Unavailable(reason)
    return report(tally, 0, PersonalStop.UNAVAILABLE)
  }

  private suspend fun report(tally: Tally, published: Int, stop: PersonalStop, retryAt: Long? = null, unresolved: Int = 0): PersonalSyncReport =
    PersonalSyncReport(
      published = published,
      applied = tally.applied,
      rejected = tally.rejected.toMap(),
      stillUndated = store.undated().size,
      stoppedBy = stop,
      rejectedSegments = tally.rejectedSegments,
      rejectedHeads = tally.rejectedHeads,
      notPublishable = tally.notPublishable,
      publishRetryAtNas = retryAt,
      unresolved = unresolved,
    )

  /** Un guasto che ferma il giro (non si legge piu' niente): il NAS non risponde o l'area non e' leggibile. */
  private fun isFatal(failure: RemoteFailure): Boolean = when (failure) {
    RemoteFailure.UNREACHABLE, RemoteFailure.ACCESS_DENIED, RemoteFailure.SHARE_MISSING, RemoteFailure.NOT_ALLOWED -> true
    else -> false
  }

  private fun unavailableFor(failure: RemoteFailure): PersonalUnavailable = when (failure) {
    RemoteFailure.SHARE_MISSING -> PersonalUnavailable.NO_PRIVATE_AREA
    RemoteFailure.DISK_FULL, RemoteFailure.QUOTA -> PersonalUnavailable.NAS_FULL
    RemoteFailure.UNREACHABLE, RemoteFailure.ACCESS_DENIED, RemoteFailure.BUSY, RemoteFailure.NOT_ALLOWED, RemoteFailure.OTHER ->
      PersonalUnavailable.NAS_UNREACHABLE
  }

  /** Il motivo per cui si legge ma non si pubblica. */
  private fun blockedBy(block: WriteBlock): PersonalUnavailable? = when (block) {
    WriteBlock.LOCAL_ONLY -> null
    WriteBlock.FROZEN, WriteBlock.EMERGENCY_STOP -> PersonalUnavailable.WRITES_BLOCKED
    WriteBlock.CLOCK_JUMP -> PersonalUnavailable.CLOCK_JUMP
    WriteBlock.READ_ONLY_AREA -> PersonalUnavailable.READ_ONLY
    WriteBlock.NOT_TRUSTED -> PersonalUnavailable.NOT_TRUSTED
  }

  private companion object {
    const val NANOS_PER_MILLI = 1_000_000L

    /** Il codice di un gancio che ha lanciato invece di rispondere: si ferma la pubblicazione (D30). */
    const val HOOK_FAILED = "PRE_PUBLISH_FAILED"
  }
}
