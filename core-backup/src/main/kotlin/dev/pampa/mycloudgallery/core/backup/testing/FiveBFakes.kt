package dev.pampa.mycloudgallery.core.backup.testing

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.ConfirmablePlan
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionFailure
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionOutcome
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionPlan
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRequest
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRoute
import dev.pampa.mycloudgallery.core.backup.contract.Corrections
import dev.pampa.mycloudgallery.core.backup.contract.EditBegin
import dev.pampa.mycloudgallery.core.backup.contract.EditOutcome
import dev.pampa.mycloudgallery.core.backup.contract.EditSession
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionId
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionRules
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionState
import dev.pampa.mycloudgallery.core.backup.contract.EditSessions
import dev.pampa.mycloudgallery.core.backup.contract.EditTarget
import dev.pampa.mycloudgallery.core.backup.contract.EditUnavailable
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpace
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceItem
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRun
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunId
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.IncomingItem
import dev.pampa.mycloudgallery.core.backup.contract.IncomingLimits
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import dev.pampa.mycloudgallery.core.backup.contract.IncomingReject
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSaveOutcome
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSavePlan
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStage
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStageId
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.contract.IncomingUploads
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.PendingTask
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskId
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskState
import dev.pampa.mycloudgallery.core.backup.contract.PendingTasks
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationCheck
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationIssuer
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.core.backup.contract.PhoneKeptReason
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewrite
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewriter
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RestoreOutcome
import dev.pampa.mycloudgallery.core.backup.contract.TrashBatch
import dev.pampa.mycloudgallery.core.backup.contract.TrashItem
import dev.pampa.mycloudgallery.core.backup.contract.TrashMode
import dev.pampa.mycloudgallery.core.backup.contract.TrashOutcome
import dev.pampa.mycloudgallery.core.backup.contract.VersionArea
import dev.pampa.mycloudgallery.core.backup.contract.VersionEntry
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.core.backup.contract.VersionPlace
import dev.pampa.mycloudgallery.core.backup.contract.VersionRole
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 5B (P0-B, docs/fase5-progetto.md §1.2, §5.1): i fake dei servizi della seconda meta', per i test di P8-P13 e per la UI di P12 (stati
// scelti dal test, nessun NAS). **Si comportano come il contratto**, non come un copione cieco: controllano la conferma contro il piano
// ricalcolato adesso, rifiutano cio' che il contratto rifiuta, e fanno uscire un elemento da "Libera spazio" quando la sua copia non torna o
// cambia. Non sono l'implementazione vera (niente diario, niente NAS): quella e' di P8 (versioni, correzioni, attivita'), P9 ("Libera spazio"),
// P11 (arrivi, sessioni). Mai nella release: tools/check.ps1 vieta i package `testing` nel dex.

/**
 * Le conferme per i test: fuori dalla UI le costruisce solo chi sta in `testing/` o in un test (`Phase5BArchitectureTest`). Il tempo e'
 * quello monotono dei fake ([FakeBackupClock]).
 */
object TestConfirmations {

  /** La conferma di [plan] con tutti i tocchi richiesti, all'istante monotono [nowMonotonicMs]. */
  fun confirm(plan: ConfirmablePlan, nowMonotonicMs: Long = FakeBackupClock().monotonicMs()): PhoneConfirmation =
    PhoneConfirmationIssuer.issue(plan, plan.requiredConfirmations, nowMonotonicMs)

  /** Una conferma con meno tocchi di quelli richiesti non si puo' costruire; questa e' una conferma data per **un altro** piano. */
  fun confirmForOtherPlan(other: ConfirmablePlan, nowMonotonicMs: Long = FakeBackupClock().monotonicMs()): PhoneConfirmation = confirm(other, nowMonotonicMs)
}

/** Un [UUID] canonico e deterministico dal numero [n] (i fake non usano il caso: stessa sequenza, stessi id). */
internal fun uuidOf(n: Long): String = UUID(0x5B5B5B5B5B5B5B5BL, n).toString()

// --- Versioni ------------------------------------------------------------------------------------------------------------------

/**
 * Il custode delle versioni di riferimento: i piani li sceglie il test ([setPlan]); "Ripristina originale" fa i controlli del contratto (piano
 * ricalcolato, conferma contro quel piano, permesso di scrittura per le righe del telefono) e, riuscito, **scambia** nel piano l'originale e
 * l'attuale come farebbe lo `SWAP` (l'originale diventa attuale e al solito percorso, l'attuale finisce in `Versioni/`). Non carica niente.
 */
class InMemoryVersionKeeper(private val clock: BackupClock = FakeBackupClock()) : VersionKeeper {

  private val plans = HashMap<MediaId, NasVersionPlan>()

  /** Le righe del telefono che "Ripristina originale" deve riscrivere (E-E10), per media: servono il dialogo di sistema e il suo permesso. */
  val phoneRowsToRewrite: MutableMap<MediaId, List<PhoneRef>> = HashMap()

  /** I media su cui "Ripristina originale" e' partito. */
  val restored: MutableList<MediaId> = ArrayList()

  fun setPlan(plan: NasVersionPlan) {
    plans[plan.mediaId] = plan
  }

  override suspend fun plan(mediaId: MediaId): NasVersionPlan =
    plans[mediaId] ?: NasVersionPlan(mediaId, emptyList(), VersionArea.OWN_PRIVATE, RestoreAvailability.ONLY_ONE_VERSION)

  override suspend fun restoreOriginal(mediaId: MediaId, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): RestoreOutcome {
    val plan = plan(mediaId)
    if (plan.restore != RestoreAvailability.AVAILABLE) return RestoreOutcome.Unavailable(plan.restore)
    val check = PhoneConfirmationRules.check(plan, confirmation, clock.monotonicMs())
    if (check != PhoneConfirmationCheck.Valid) return RestoreOutcome.Rejected(check)
    val rows = phoneRowsToRewrite[mediaId].orEmpty()
    // Come il custode vero (contratto di `PhoneWriteGrant`, P14): null = il dialogo non e' stato chiesto, niente si tocca; un permesso che
    // non copre le righe = l'utente ha detto no: lo scambio sul NAS si fa e il telefono tiene la modifica.
    if (rows.isNotEmpty() && writeGrant == null) return RestoreOutcome.NeedsPhoneWrite(rows)
    restored += mediaId
    plans[mediaId] = swapped(plan)
    if (rows.isNotEmpty() && !writeGrant!!.coversAll(rows)) return RestoreOutcome.PhoneNotRewritten(PhoneKeptReason.PERMISSION_DENIED)
    return RestoreOutcome.Queued
  }

  /** Dopo lo scambio: l'originale e' l'attuale (al solito percorso), la versione di prima e' in `Versioni/` e il ripristino non serve piu'. */
  private fun swapped(plan: NasVersionPlan): NasVersionPlan {
    val versions = plan.versions.map { v ->
      when {
        v.role == VersionRole.ORIGINAL -> v.copy(isCurrent = true, places = (v.places - VersionPlace.NAS_VERSIONS_FOLDER) + VersionPlace.NAS_USUAL_PATH)
        v.isCurrent -> v.copy(isCurrent = false, places = (v.places - VersionPlace.NAS_USUAL_PATH) + VersionPlace.NAS_VERSIONS_FOLDER)
        else -> v
      }
    }
    return NasVersionPlan(plan.mediaId, versions, plan.area, RestoreAvailability.ALREADY_ORIGINAL)
  }
}

// --- Correzioni ----------------------------------------------------------------------------------------------------------------

/**
 * Le correzioni per la UI e i test: la strada, le righe da riscrivere e se si puo' fare li sceglie il test; `apply` fa i controlli del
 * contratto nell'ordine giusto (piano invariato, formato e prontezza, conferma, permesso di scrittura) e, riuscito, registra il piano e restituisce
 * una versione nuova.
 */
class ScriptedCorrections(private val clock: BackupClock = FakeBackupClock()) : Corrections {

  var readiness: CorrectionReadiness = CorrectionReadiness.READY
  var route: CorrectionRoute = CorrectionRoute.PHONE
  var phoneRows: List<PhoneRef> = emptyList()
  var baseVersion: VersionId? = VersionId(uuidOf(1))

  /** Se valorizzato, `apply` finisce con questo guasto (una volta sola). */
  var failNext: CorrectionFailure? = null

  /** I piani eseguiti, nell'ordine. */
  val applied: MutableList<CorrectionPlan> = ArrayList()

  private var counter = 100L

  override suspend fun plan(mediaId: MediaId, change: CorrectionRequest): CorrectionPlan =
    CorrectionPlan(mediaId, change, baseVersion, route, readiness, if (route == CorrectionRoute.PHONE) phoneRows else emptyList())

  override suspend fun apply(plan: CorrectionPlan, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): CorrectionOutcome {
    val fresh = plan(plan.mediaId, plan.request)
    if (fresh.hash != plan.hash) return CorrectionOutcome.PlanChanged
    when (fresh.readiness) {
      CorrectionReadiness.READY -> Unit
      CorrectionReadiness.NEEDS_SAFE_FIRST -> return CorrectionOutcome.NeedsSafeFirst
      CorrectionReadiness.FORMAT_NOT_WRITABLE -> return CorrectionOutcome.FormatNotWritable
      else -> return CorrectionOutcome.Unavailable(fresh.readiness)
    }
    val check = PhoneConfirmationRules.check(fresh, confirmation, clock.monotonicMs())
    if (check != PhoneConfirmationCheck.Valid) return CorrectionOutcome.Rejected(check)
    if (fresh.phoneRows.isNotEmpty() && (writeGrant == null || !writeGrant.coversAll(fresh.phoneRows))) {
      return CorrectionOutcome.NeedsWriteGrant(fresh.phoneRows)
    }
    failNext?.let {
      failNext = null
      return CorrectionOutcome.Failed(it)
    }
    applied += fresh
    return CorrectionOutcome.Done(VersionId(uuidOf(counter++)))
  }
}

/** La riscrittura del telefono finta (porta di core-backup): permessi e riga scelti dal test, SHA-256 controllato come il contratto. */
class FakePhoneRewriter : PhoneRewriter {

  /** Le righe per cui il dialogo di sistema ha dato il permesso. */
  val granted: MutableSet<PhoneRef> = HashSet()

  /** Le righe che non ci sono piu'. */
  val gone: MutableSet<PhoneRef> = HashSet()

  /** Se valorizzato, la prossima riscrittura finisce cosi' (una volta sola). */
  var failNext: PhoneRewrite.Failed? = null

  val replaced: MutableList<PhoneRef> = ArrayList()

  override suspend fun replace(ref: PhoneRef, mime: String?, file: File, expectedSha256: Sha256): PhoneRewrite {
    val bytes = try {
      file.readBytes()
    } catch (_: java.io.IOException) {
      return PhoneRewrite.SourceMismatch
    }
    if (Sha256.of(bytes) != expectedSha256) return PhoneRewrite.SourceMismatch
    if (ref in gone) return PhoneRewrite.Gone
    if (ref !in granted) return PhoneRewrite.NotGranted
    failNext?.let {
      failNext = null
      return it
    }
    replaced += ref
    return PhoneRewrite.Replaced(expectedSha256, bytes.size.toLong())
  }
}

// --- Libera spazio -------------------------------------------------------------------------------------------------------------

/**
 * "Libera spazio" di riferimento, per la UI di P12 e i test di P9: il catalogo ([candidates]) e i guasti li sceglie il test; il resto segue il
 * contratto. Il piano esclude per filtro, per "ultimi 30 giorni" e per motivo; **la lista nasce dai candidati locali e non cresce mai** (E-S11);
 * `verify` controlla la conferma contro il piano ricalcolato, fa uscire le copie che non tornano alla rilettura ([failVerification]) e fissa
 * i 6 ore di validita' sull'orologio monotono; `nextBatch` ricontrolla il timbro di ogni copia ([changeAfterVerification]) e dà lotti da
 * 2.000; `recordOutcome` conta solo cio' che Android ha confermato. Una sola tappa fa tutto: il tempo di una verifica vera lo simula `budget` = 0
 * solo nei test di P9.
 *
 * @param todayLocalDay oggi come `aaaammgg`, per i filtri "ultimi 30 giorni" e "piu' vecchi di un anno" (mai l'orologio del telefono).
 */
class InMemoryFreeSpace(
  private val clock: BackupClock = FakeBackupClock(),
  private val todayLocalDay: Int = 20_261_019,
) : FreeSpace {

  /** I media che il catalogo locale considera liberabili (con la copia privata gia' decisa dal catalogo). */
  val candidates: MutableList<FreeSpaceItem> = ArrayList()

  /** I media esclusi e perche' (non verificato, in errore, versione scoperta...). Non entrano nel piano. */
  val excludedMedia: MutableMap<MediaId, FreeSpaceExclusion> = HashMap()

  /** I media con la sola copia comune: il guardiano parte prima e, se va bene, entrano. */
  val needsGuardian: MutableSet<MediaId> = HashSet()

  /** I media la cui copia privata non torna alla rilettura della verifica sul momento (E-B8). */
  val failVerification: MutableSet<MediaId> = HashSet()

  /** I media la cui copia cambia (o sparisce) dopo la verifica e prima del dialogo (L39). */
  val changeAfterVerification: MutableSet<MediaId> = HashSet()

  /** Se valorizzato, nessun giro parte: area congelata (E-B22) o NAS irraggiungibile (E-B9). */
  var block: FreeSpaceBlock? = null

  /** I lotti presentati, nell'ordine. */
  val presented: MutableList<TrashBatch> = ArrayList()

  private class RunData(
    val plan: FreeSpacePlan,
    var run: FreeSpaceRun,
    var ready: MutableList<FreeSpaceItem>,
    val dropped: MutableMap<FreeSpaceExclusion, Int>,
    var inDoubt: Int = 0,
  )

  private val runs = HashMap<Long, RunData>()
  private var nextRunId = 1L

  override suspend fun plan(filter: FreeSpaceFilter, permanent: Boolean): FreeSpacePlan {
    val excluded = HashMap<FreeSpaceExclusion, Int>()
    fun skip(reason: FreeSpaceExclusion) {
      excluded[reason] = (excluded[reason] ?: 0) + 1
    }
    val items = ArrayList<FreeSpaceItem>()
    for (item in candidates) {
      if (filter.selection != null && item.mediaId !in filter.selection) continue
      if (filter.kind == FreeSpaceKind.VIDEOS_ONLY && !item.isVideo) continue
      if (filter.kind == FreeSpaceKind.OLDER_THAN_A_YEAR && !isOlderThanAYear(item.localDay)) continue
      val reason = excludedMedia[item.mediaId]
      when {
        reason != null -> skip(reason)
        isRecent(item.localDay, filter.excludeRecentDays) -> skip(FreeSpaceExclusion.RECENT)
        else -> items += item
      }
    }
    val guardians = items.count { it.mediaId in needsGuardian }
    return FreeSpacePlan(filter, permanent, items, excluded, needsGuardian = guardians)
  }

  override suspend fun verify(plan: FreeSpacePlan, confirmation: PhoneConfirmation, budget: Duration): FreeSpaceRun {
    val fresh = plan(plan.filter, plan.permanent)
    val reason = block
    if (reason != null) return blockedRun(plan, reason)
    if (PhoneConfirmationRules.check(fresh, confirmation, clock.monotonicMs()) != PhoneConfirmationCheck.Valid) {
      return blockedRun(plan, FreeSpaceBlock.CONFIRMATION_REJECTED)
    }
    val dropped = HashMap<FreeSpaceExclusion, Int>()
    val ready = ArrayList<FreeSpaceItem>()
    for (item in fresh.items) {
      if (item.mediaId in failVerification) dropped[FreeSpaceExclusion.NOT_VERIFIED] = (dropped[FreeSpaceExclusion.NOT_VERIFIED] ?: 0) + 1 else ready += item
    }
    val id = FreeSpaceRunId(nextRunId++)
    val batches = (ready.size + FreeSpaceRules.MAX_BATCH - 1) / FreeSpaceRules.MAX_BATCH
    val run = FreeSpaceRun(
      id = id,
      planHash = fresh.hash,
      state = if (ready.isEmpty()) FreeSpaceRunState.DONE else FreeSpaceRunState.READY,
      permanent = fresh.permanent,
      guardedDone = fresh.needsGuardian,
      guardedTotal = fresh.needsGuardian,
      verifiedDone = fresh.items.size,
      verifiedTotal = fresh.items.size,
      verifiedBytes = fresh.totalBytes,
      readyItems = ready.size,
      readyBytes = ready.sumOf { it.size },
      batchesTotal = batches,
      batchesDone = 0,
      freedItems = 0,
      freedBytes = 0,
      dropped = dropped,
      validUntilMonotonicMs = clock.monotonicMs() + FreeSpaceRules.VERIFICATION_VALID_MS,
    )
    runs[id.value] = RunData(fresh, run, ready, dropped)
    return run
  }

  override suspend fun resume(run: FreeSpaceRun, budget: Duration): FreeSpaceRun {
    val data = runs[run.id.value] ?: return run
    return expireIfNeeded(data)
  }

  override suspend fun nextBatch(run: FreeSpaceRun): TrashBatch? {
    val data = runs[run.id.value] ?: return null
    if (expireIfNeeded(data).state != FreeSpaceRunState.READY) return null
    // Il controllo finale prima del dialogo: una copia cambiata o sparita esce, mai un elemento nuovo entra.
    val kept = ArrayList<FreeSpaceItem>()
    for (item in data.ready) {
      if (item.mediaId in changeAfterVerification) {
        data.dropped[FreeSpaceExclusion.COPY_CHANGED] = (data.dropped[FreeSpaceExclusion.COPY_CHANGED] ?: 0) + 1
      } else {
        kept += item
      }
    }
    data.ready = kept
    if (kept.isEmpty()) {
      data.run = data.run.copy(state = FreeSpaceRunState.DONE, readyItems = 0, readyBytes = 0, dropped = data.dropped.toMap())
      return null
    }
    val chunk = kept.take(FreeSpaceRules.MAX_BATCH)
    val mode = if (data.plan.permanent) TrashMode.DELETE_NOW else TrashMode.ANDROID_TRASH
    val total = data.run.batchesDone + (kept.size + FreeSpaceRules.MAX_BATCH - 1) / FreeSpaceRules.MAX_BATCH
    val batch = TrashBatch(
      runId = data.run.id,
      index = data.run.batchesDone + 1,
      of = total,
      mode = mode,
      items = chunk.map { TrashItem(it.mediaId, it.versionId, it.phone, it.mime, it.size) },
    )
    data.run = data.run.copy(batchesTotal = total, readyItems = kept.size, readyBytes = kept.sumOf { it.size }, dropped = data.dropped.toMap())
    presented += batch
    return batch
  }

  override suspend fun recordOutcome(batch: TrashBatch, outcome: TrashOutcome): FreeSpaceRun {
    val data = checkNotNull(runs[batch.runId.value]) { "Giro sconosciuto." }
    val inBatch = batch.items.map { it.phone }.toSet()
    data.ready = data.ready.filter { it.phone !in inBatch }.toMutableList()
    var run = data.run
    when (outcome) {
      is TrashOutcome.Confirmed -> {
        val freed = batch.items.filter { it.phone in outcome.confirmed }
        run = run.copy(
          freedItems = run.freedItems + freed.size,
          freedBytes = run.freedBytes + freed.sumOf { it.size },
          kept = run.kept + batch.items.size - freed.size,
        )
      }
      TrashOutcome.Refused -> run = run.copy(state = FreeSpaceRunState.CANCELLED, kept = run.kept + batch.items.size)
      TrashOutcome.Unknown -> {
        data.inDoubt += batch.items.size
        run = run.copy(inDoubt = run.inDoubt + batch.items.size)
      }
    }
    val done = run.batchesDone + 1
    val state = when {
      run.state == FreeSpaceRunState.CANCELLED -> FreeSpaceRunState.CANCELLED
      data.ready.isEmpty() -> FreeSpaceRunState.DONE
      else -> run.state
    }
    data.run = run.copy(batchesDone = done, state = state, readyItems = data.ready.size, readyBytes = data.ready.sumOf { it.size })
    return data.run
  }

  private fun expireIfNeeded(data: RunData): FreeSpaceRun {
    val until = data.run.validUntilMonotonicMs
    if (data.run.isActive && until != null && clock.monotonicMs() > until) data.run = data.run.copy(state = FreeSpaceRunState.EXPIRED)
    return data.run
  }

  private fun blockedRun(plan: FreeSpacePlan, reason: FreeSpaceBlock): FreeSpaceRun = FreeSpaceRun(
    id = FreeSpaceRunId(nextRunId++),
    planHash = plan.hash,
    state = FreeSpaceRunState.BLOCKED,
    permanent = plan.permanent,
    guardedDone = 0, guardedTotal = 0, verifiedDone = 0, verifiedTotal = 0, verifiedBytes = 0,
    readyItems = 0, readyBytes = 0, batchesTotal = 0, batchesDone = 0, freedItems = 0, freedBytes = 0,
    blocked = reason,
  )

  private fun daysAgo(localDay: Int): Long? = try {
    ChronoUnit.DAYS.between(toDate(localDay), toDate(todayLocalDay))
  } catch (_: java.time.DateTimeException) {
    null
  }

  private fun isRecent(localDay: Int?, days: Int): Boolean = localDay != null && (daysAgo(localDay) ?: Long.MAX_VALUE) < days

  private fun isOlderThanAYear(localDay: Int?): Boolean = localDay != null && (daysAgo(localDay) ?: 0L) >= FreeSpaceRules.OLDER_THAN_DAYS

  private fun toDate(day: Int): LocalDate = LocalDate.of(day / 10_000, day / 100 % 100, day % 100)
}

// --- Attivita' in sospeso ------------------------------------------------------------------------------------------------------

/** Le attivita' in sospeso in memoria: [add] le crea, `tasks` mostra solo le aperte (come "Da decidere"), `dismiss` e `complete` le chiudono. */
class InMemoryPendingTasks : PendingTasks {

  private val all = ArrayList<PendingTask>()
  private val flow = MutableStateFlow<List<PendingTask>>(emptyList())
  private var counter = 1L

  override val tasks: StateFlow<List<PendingTask>> get() = flow

  /** Tutte le attivita', di ogni stato, per i test. */
  val everything: List<PendingTask> get() = all.toList()

  /** Aggiunge un'attivita' aperta (la crea P8 dal cammino `VERSION` di un video). */
  fun add(task: (PendingTaskId) -> PendingTask): PendingTask {
    val created = task(PendingTaskId(counter++))
    all += created
    publish()
    return created
  }

  override suspend fun dismiss(id: PendingTaskId) = set(id, PendingTaskState.KEPT)

  override suspend fun complete(id: PendingTaskId) = set(id, PendingTaskState.DONE)

  private fun set(id: PendingTaskId, state: PendingTaskState) {
    val at = all.indexOfFirst { it.id == id }
    if (at < 0) return
    all[at] = all[at].copy(state = state)
    publish()
  }

  private fun publish() {
    flow.value = all.filter { it.state == PendingTaskState.OPEN }.sortedBy { it.createdAtNasMillis }
  }
}

// --- Arrivi --------------------------------------------------------------------------------------------------------------------

/**
 * I file in arrivo di riferimento: applica i tetti di [IncomingLimits] (anche sulle dimensioni dichiarate, cosi' un test non deve scrivere 2 GiB),
 * rifiuta `file://` e provider dell'app **senza aprire il flusso**, legge il tipo dai primi byte (un `.jpg` falso e' rifiutato), calcola l'hash e
 * deriva il nome da li'. Non scrive su disco: tiene gli elementi in memoria. `save` controlla la conferma contro il piano e rifiuta le destinazioni
 * in sola lettura; non carica niente.
 */
class InMemoryIncomingUploads(private val clock: BackupClock = FakeBackupClock()) : IncomingUploads {

  /** I byte liberi del telefono (per il tetto "almeno 1 GiB libero dopo la copia"). */
  var freeBytes: Long = Long.MAX_VALUE

  /** Le share in sola lettura (E-N9): salvare li' non e' ammesso. */
  val readOnlyShares: MutableSet<String> = HashSet()

  private val stages = HashMap<String, IncomingStage>()
  private var counter = 1L

  /** Cio' che `save` ha messo in coda, nell'ordine: (destinazione, elementi). */
  val saved: MutableList<Pair<NasDestination, List<IncomingItem>>> = ArrayList()

  /** Le condivisioni scartate. */
  val discarded: MutableList<IncomingStageId> = ArrayList()

  override suspend fun stage(streams: List<IncomingStream>): IncomingStage {
    val rejected = HashMap<IncomingReject, Int>()
    fun reject(reason: IncomingReject) {
      rejected[reason] = (rejected[reason] ?: 0) + 1
    }
    val accepted = ArrayList<IncomingItem>()
    var total = 0L
    for (stream in streams) {
      when {
        stream.origin == IncomingOrigin.FILE_SCHEME -> reject(IncomingReject.FILE_SCHEME)
        stream.origin == IncomingOrigin.OWN_PROVIDER -> reject(IncomingReject.OWN_PROVIDER)
        accepted.size >= IncomingLimits.MAX_ITEMS -> reject(IncomingReject.TOO_MANY)
        (stream.declaredSize ?: 0L) > IncomingLimits.MAX_FILE_BYTES -> reject(IncomingReject.TOO_LARGE)
        else -> readOne(stream, total)?.let { read ->
          when {
            read.size > IncomingLimits.MAX_FILE_BYTES -> reject(IncomingReject.TOO_LARGE)
            total + read.size > IncomingLimits.MAX_STAGE_BYTES -> reject(IncomingReject.STAGE_TOO_BIG)
            freeBytes - (total + read.size) < IncomingLimits.MIN_FREE_AFTER_BYTES -> reject(IncomingReject.NO_SPACE)
            else -> {
              accepted += read
              total += read.size
            }
          }
        } ?: reject(IncomingReject.UNREADABLE)
      }
    }
    // Un flusso non media: rifiutato dopo aver letto i primi byte (il tipo e' dei byte, non del nome).
    val media = accepted.filter { it.mime != NOT_MEDIA }
    repeat(accepted.size - media.size) { reject(IncomingReject.NOT_MEDIA) }
    val stage = IncomingStage(IncomingStageId("stage-${counter++}"), media, rejected)
    stages[stage.id.value] = stage
    return stage
  }

  override suspend fun save(stage: IncomingStage, dest: NasDestination, confirmation: PhoneConfirmation): IncomingSaveOutcome {
    val known = stages[stage.id.value] ?: return IncomingSaveOutcome.StageGone
    val plan = IncomingSavePlan(known, dest)
    val check = PhoneConfirmationRules.check(plan, confirmation, clock.monotonicMs())
    if (check != PhoneConfirmationCheck.Valid) return IncomingSaveOutcome.Rejected(check)
    if (dest.share in readOnlyShares) return IncomingSaveOutcome.DestinationNotAllowed
    saved += dest to known.accepted
    stages.remove(stage.id.value)
    return IncomingSaveOutcome.Queued(known.accepted.size, known.needsUserInitiatedJob)
  }

  override suspend fun discard(stage: IncomingStageId) {
    stages.remove(stage.value)
    discarded += stage
  }

  /** Legge il flusso (fino al tetto), ne calcola hash e tipo. Null se non si apre. */
  private fun readOne(stream: IncomingStream, alreadyStaged: Long): IncomingItem? = try {
    val digest = MessageDigest.getInstance("SHA-256")
    val head = ByteArray(HEAD)
    var headLen = 0
    var size = 0L
    stream.open().use { input ->
      val buffer = ByteArray(8 * 1024)
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (headLen < HEAD) {
          val take = minOf(n, HEAD - headLen)
          System.arraycopy(buffer, 0, head, headLen, take)
          headLen += take
        }
        digest.update(buffer, 0, n)
        size += n
        // Il tetto vale mentre si legge: un flusso che non finisce non si legge fino in fondo.
        if (size > IncomingLimits.MAX_FILE_BYTES || alreadyStaged + size > IncomingLimits.MAX_STAGE_BYTES) break
      }
    }
    val hash = Sha256(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    IncomingItem(name = hash.hex.take(32), sha256 = hash, size = size, mime = sniff(head.copyOf(headLen)), displayName = stream.displayName)
  } catch (_: java.io.IOException) {
    null
  }

  /** Il tipo dai primi byte: i formati che l'app tratta (JPEG, PNG, WebP, HEIC, MP4/MOV), altrimenti [NOT_MEDIA]. */
  private fun sniff(head: ByteArray): String {
    fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xff else -1
    fun tag(from: Int, text: String) = text.indices.all { at(from + it) == text[it].code }
    return when {
      at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
      at(0) == 0x89 && tag(1, "PNG") -> "image/png"
      tag(0, "RIFF") && tag(8, "WEBP") -> "image/webp"
      tag(4, "ftyp") && (tag(8, "heic") || tag(8, "heix") || tag(8, "mif1")) -> "image/heic"
      tag(4, "ftyp") && tag(8, "qt") -> "video/quicktime"
      tag(4, "ftyp") -> "video/mp4"
      else -> NOT_MEDIA
    }
  }

  private companion object {
    const val HEAD = 16
    const val NOT_MEDIA = "application/x-not-media"
  }
}

// --- Sessioni di modifica ------------------------------------------------------------------------------------------------------

/**
 * Le sessioni di "Modifica con…" di riferimento: `begin` apre una sessione di 30 minuti (ora del NAS), `settle` restituisce l'esito che il test
 * ha scelto ([outcomeOf]) o [EditOutcome.Unchanged]; dopo la scadenza e' [EditOutcome.Expired] (E-E5: oltre non e' piu' attribuita alla sessione).
 */
class InMemoryEditSessions(private val clock: BackupClock = FakeBackupClock()) : EditSessions {

  /** I media per cui `begin` non puo' aprire una sessione e perche'. */
  val unavailable: MutableMap<MediaId, EditUnavailable> = HashMap()

  /** La riga del telefono da aprire in Samsung Gallery, per media. */
  val phoneRows: MutableMap<MediaId, PhoneRef> = HashMap()

  /** La versione di partenza, per media (di default una derivata dal media). */
  val baseVersions: MutableMap<MediaId, VersionId> = HashMap()

  private val outcomes = HashMap<Long, EditOutcome>()
  private val sessions = HashMap<Long, EditSession>()
  private var counter = 1L

  /** Le sessioni aperte, per i test. */
  val all: List<EditSession> get() = sessions.values.sortedBy { it.id.value }

  /** L'esito che `settle` restituira' per la sessione [id] (cio' che l'editor ha fatto). */
  fun outcomeOf(id: EditSessionId, outcome: EditOutcome) {
    outcomes[id.value] = outcome
  }

  override suspend fun begin(mediaId: MediaId, target: EditTarget): EditBegin {
    unavailable[mediaId]?.let { return EditBegin.Unavailable(it) }
    val now = clock.nasNowMs() ?: return EditBegin.Unavailable(EditUnavailable.NAS_UNREACHABLE)
    val id = counter++
    val session = EditSession(
      id = EditSessionId(id),
      mediaId = mediaId,
      versionId = baseVersions[mediaId] ?: VersionId(uuidOf(id)),
      target = target,
      state = EditSessionState.OPEN,
      phone = if (target == EditTarget.NAS_CACHE) null else phoneRows[mediaId],
      cacheName = if (target == EditTarget.NAS_CACHE) "edit-$id" else null,
      startedAtNasMillis = now,
      expiresAtNasMillis = now + EditSessionRules.WINDOW_MS,
    )
    sessions[id] = session
    return EditBegin.Started(session)
  }

  override suspend fun settle(session: EditSession): EditOutcome {
    val current = sessions[session.id.value] ?: return EditOutcome.Expired
    if (current.state.terminal) return if (current.state == EditSessionState.SETTLED) EditOutcome.Unchanged else EditOutcome.Expired
    val now = clock.nasNowMs()
    if (now != null && now > current.expiresAtNasMillis) {
      sessions[current.id.value] = current.copy(state = EditSessionState.EXPIRED)
      return EditOutcome.Expired
    }
    val outcome = outcomes[current.id.value] ?: EditOutcome.Unchanged
    if (outcome != EditOutcome.Unchanged && outcome != EditOutcome.Unreadable) {
      sessions[current.id.value] = current.copy(state = EditSessionState.SETTLED)
    }
    return outcome
  }
}
