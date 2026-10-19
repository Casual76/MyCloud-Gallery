package dev.pampa.mycloudgallery.core.backup.free

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
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
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationCheck
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhonePlanHash
import dev.pampa.mycloudgallery.core.backup.contract.PrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.TrashBatch
import dev.pampa.mycloudgallery.core.backup.contract.TrashItem
import dev.pampa.mycloudgallery.core.backup.contract.TrashMode
import dev.pampa.mycloudgallery.core.backup.contract.TrashOutcome
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.core.backup.safety.NasRereader
import dev.pampa.mycloudgallery.core.backup.safety.Reread
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasAreaLookup
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **"Libera spazio"** (Fase 5B, P9; `docs/fase5-progetto.md` §2.5, D21, D22, D31, E-B8, E-B9, E-B14, E-B22, E-S11, L39): l'unico flusso
 * dell'app che toglie copie dal telefono. **Nessun media lascia il telefono se la sua copia sul NAS non e' stata riletta per intero, qui e
 * da poco**: un hash dell'indice o un timbro non bastano (F4-D8, L28).
 *
 * I passi (uno per metodo del contratto [FreeSpace]):
 * 1. [plan]: dal **catalogo locale** ([FreeSpaceCatalog]) le copie del telefono liberabili, con gli esclusi per motivo ([FreeSpaceEvaluator]).
 *    Non legge il NAS (E-S11): il NAS fa solo da cancello, puo' togliere un elemento, mai aggiungerlo. Di default tutto il sicuro tranne
 *    gli ultimi 30 giorni (Q9);
 * 2. [verify]: la conferma ([PhoneConfirmation]) si controlla contro il piano **ricalcolato adesso** (impronta, 10 minuti, due tocchi per
 *    "Libera subito"); poi il giro si scrive nel diario ([FreeSpaceDiary]), con l'istante monotono e il conteggio dei riavvii (la validita');
 * 3. il guardiano ([PrivateCopyGuardian]) per i media con la sola copia comune (D9, E-B14); quelli che non riescono escono;
 * 4. la **verifica sul momento**: ogni copia privata da proteggere riletta per intero ([NasRereader]: SHA-256 adesso, timbro uguale prima e
 *    dopo), lo SHA-256 letto passato al catalogo ([FreeSpaceCatalog.markVerified], E-B8); una copia che non torna fa uscire l'elemento;
 * 5. [nextBatch]: **subito prima di ogni dialogo**, il timbro di ogni copia riletta (`stat`) e la riga del telefono (stessa riga, stesso
 *    timbro: un file modificato sul telefono e' una versione nuova, non al sicuro); un lotto di al massimo 2.000 elementi scritto nel diario
 *    come `IN_DIALOG` **prima** di arrivare alla UI;
 * 6. [recordOutcome]: cio' che Android ha confermato diventa `FREED` (il diario lo sa prima della lettura del telefono); un esito
 *    sconosciuto (app uccisa durante il dialogo) si risolve **solo con una prova**: la riga non c'e' piu' = liberata, c'e' = rimasta,
 *    altrimenti "in dubbio" finche' la lettura del telefono non lo dice. Mai un elemento dato per liberato senza prova.
 *
 * **La validita'** (D21, D31): un giro vale [FreeSpaceRules.VERIFICATION_VALID_MS] di orologio **monotono** dal suo inizio e solo nello
 * stesso avvio del telefono ([BootCounter]); se il conteggio dei riavvii non si legge, solo nello stesso processo. Mai l'orologio del
 * telefono; l'ora del NAS solo per l'istante scritto nel catalogo. Scaduto o riavviato: il giro e' `EXPIRED` e si rifa' dal piano.
 *
 * **Scritture sul telefono: nessuna.** Il telefono si legge soltanto ([PhoneBytes]); l'unico effetto e' un [TrashBatch] che la UI traduce nel
 * dialogo di sistema (`createTrashRequest`, o `createDeleteRequest` per "Libera subito"). **Sul NAS: solo letture** (il guardiano scrive
 * con le sue primitive in sola aggiunta).
 *
 * Nessuna eccezione per un errore del NAS o del telefono: il giro si ferma con il suo motivo. La morte del processo (un `Error`) non si
 * cattura mai: si riparte dal diario.
 */
class SafeFreeSpace(
  private val catalog: FreeSpaceCatalog,
  private val diary: FreeSpaceDiary,
  backupStore: BackupStore,
  versions: VersionKeeper,
  private val guardian: PrivateCopyGuardian,
  reader: NasMediaReader,
  private val phone: PhoneBytes,
  private val gate: BackupGate,
  private val clock: BackupClock,
  private val boot: BootCounter,
  /** L'account del dispositivo (proprietario dei media "miei"), null = nessuno. */
  private val account: () -> AccountId?,
  /** L'area privata del proprietario (`LocalDeviceProfile.privateShare`), null = nessuna. */
  private val privateShare: () -> String?,
  private val areas: () -> NasAreaLookup,
  /** D13: la recinzione del telefono (solo debug, account di prova); null = nessuna. */
  private val fence: BackupFence? = null,
  /** Android 11+: senza `createTrashRequest`/`createDeleteRequest` "Libera spazio" non esiste (D14). */
  private val systemDialogs: () -> Boolean = { true },
  /** Il fuso per "oggi" (Q9); l'istante e' sempre l'ora del NAS. */
  zone: ZoneId = ZoneId.systemDefault(),
  /** Oggi, per i filtri "ultimi 30 giorni" e "piu' vecchi di un anno": dall'ora del NAS, mai dall'orologio del telefono. */
  private val today: () -> LocalDate? = { clock.nasNowMs()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } },
  purpose: Purpose = Purpose.BACKGROUND,
  private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : FreeSpace {

  init {
    require(pageSize > 0) { "Pagina fuori misura." }
  }

  private val mutex = Mutex()
  private val rules = FreeSpaceEvaluator(backupStore, versions)
  private val rereader = NasRereader(reader, purpose)

  /**
   * I giri cominciati da questo processo e la loro validita': senza il conteggio dei riavvii (o con un diario che non conserva la validita')
   * la verifica vale solo qui.
   */
  private val liveRuns = HashMap<Long, FreeValidity>()

  /**
   * Cio' che questo processo ha scritto nel diario, per intero. Un diario che non conserva i campi facoltativi ([FreeItemRecord]: la riga e
   * il timbro del telefono, le copie rilette di tutte le versioni) li perde solo alla morte del processo: da li' il giro non va piu' avanti
   * (fallisce chiuso), ma la traccia delle righe consegnate ad Android resta.
   */
  private val written = HashMap<Long, HashMap<Int, FreeItemRecord>>()

  /** Perche' questo processo ha fermato un giro (un diario che non conserva il motivo lo perde solo alla morte del processo). */
  private val blockedRuns = HashMap<Long, FreeSpaceBlock>()

  /** Il lotto che questo processo ha consegnato alla UI e di cui aspetta la risposta, per giro. */
  private val pendingBatch = HashMap<Long, Int>()

  // --- 1. Il piano -------------------------------------------------------------------------------------------------------------

  /** Il piano non tocca lo stato del motore (solo il catalogo locale): non aspetta una tappa di verifica in corso. */
  override suspend fun plan(filter: FreeSpaceFilter, permanent: Boolean): FreeSpacePlan = draft(filter, permanent, scene()).plan

  /** Un elemento del piano con cio' che serve dopo (copie da rileggere, guardiano). */
  internal class DraftItem(val outcome: CopyOutcome.Ready) {
    val item: FreeSpaceItem get() = outcome.item
  }

  internal class Draft(val plan: FreeSpacePlan, val items: List<DraftItem>)

  private suspend fun draft(filter: FreeSpaceFilter, permanent: Boolean, scene: FreeSpaceScene): Draft {
    val items = ArrayList<DraftItem>()
    val excluded = LinkedHashMap<FreeSpaceExclusion, Int>()
    fun skip(reason: FreeSpaceExclusion) {
      excluded[reason] = (excluded[reason] ?: 0) + 1
    }
    val guardedMedia = HashSet<MediaId>()
    var guardBytes = 0L
    val handed = diary.handedPhoneLocations()

    suspend fun consider(fm: FreeSpaceMedia) {
      if (filter.kind == FreeSpaceKind.VIDEOS_ONLY && !fm.media.kind.isVideo) return
      if (filter.kind == FreeSpaceKind.OLDER_THAN_A_YEAR && !FreeSpaceEvaluator.isOlderThanAYear(fm.localDay, scene.today)) return
      val outcomes = rules.evaluate(fm, scene, filter.excludeRecentDays)
      // Un media scelto dall'utente senza una copia sul telefono: non c'e' niente da liberare, e lo si dice.
      if (outcomes.isEmpty() && filter.selection != null) skip(FreeSpaceExclusion.GONE_FROM_PHONE)
      for (outcome in outcomes) {
        when (outcome) {
          is CopyOutcome.Excluded -> skip(outcome.reason)
          is CopyOutcome.Ready -> {
            // Gia' consegnata ad Android in un altro giro (dialogo aperto, esito in dubbio): non due volte.
            if (outcome.phoneLocation in handed) {
              skip(FreeSpaceExclusion.IN_PROGRESS_OR_ERROR)
              continue
            }
            if (outcome.guardVersions.isNotEmpty() && fm.id !in guardedMedia) {
              // D9: il guardiano di un giro copia al massimo 20 GB; un solo media piu' grande parte da solo.
              if (guardBytes > 0 && guardBytes + outcome.guardBytes > FreeSpaceRules.GUARD_MAX_BYTES) {
                skip(if (outcome.item.versionId in outcome.guardVersions) FreeSpaceExclusion.NOT_VERIFIED else FreeSpaceExclusion.VERSION_NOT_SAFE)
                continue
              }
              guardedMedia += fm.id
              guardBytes += outcome.guardBytes
            }
            items += DraftItem(outcome)
          }
        }
      }
    }

    val selection = filter.selection
    if (selection != null) {
      // La scelta dell'utente: al massimo quei media (un alias o un gruppo non allargano mai la scelta, I9 della revisione 5A).
      val found = catalog.media(selection).filter { it.id in selection }
      repeat(selection.size - found.map { it.id }.toSet().size) { skip(FreeSpaceExclusion.GONE_FROM_PHONE) }
      for (fm in found.sortedWith(NEWEST_FIRST)) consider(fm)
    } else {
      var after: FreeSpaceCursor? = null
      while (true) {
        val page = catalog.phoneMedia(after, pageSize)
        for (fm in page) consider(fm)
        if (page.size < pageSize) break
        val next = FreeSpaceCursor(page.last().sortTime, page.last().id)
        if (next == after) break
        after = next
      }
    }
    val plan = FreeSpacePlan(
      filter = filter,
      permanent = permanent,
      items = items.map { it.item },
      excluded = excluded,
      needsGuardian = items.count { it.outcome.guardVersions.isNotEmpty() },
    )
    return Draft(plan, items)
  }

  // --- 2. La conferma, il giro, il guardiano e la verifica ------------------------------------------------------------------

  override suspend fun verify(plan: FreeSpacePlan, confirmation: PhoneConfirmation, budget: Duration): FreeSpaceRun = mutex.withLock {
    val deadline = deadlineOf(budget)
    val startedAt = clock.monotonicMs()
    if (!systemDialogs()) return@withLock blockedRun(plan, FreeSpaceBlock.NO_SYSTEM_DIALOG)
    val scene = scene()
    blockOf(scene)?.let { return@withLock blockedRun(plan, it) }
    // E-B9: senza l'ora del NAS nessuna sessione l'ha mai raggiunto, e una copia non si segna "verificata adesso".
    if (clock.nasNowMs() == null) return@withLock blockedRun(plan, FreeSpaceBlock.NAS_UNREACHABLE)
    // La conferma vale per il piano RICALCOLATO adesso: un elemento in piu', un timbro diverso, un altro filtro vogliono un altro tocco.
    val fresh = draft(plan.filter, plan.permanent, scene)
    if (PhoneConfirmationRules.check(fresh.plan, confirmation, startedAt) != PhoneConfirmationCheck.Valid) {
      return@withLock blockedRun(plan, FreeSpaceBlock.CONFIRMATION_REJECTED)
    }
    val records = fresh.items.mapIndexed { index, d ->
      val o = d.outcome
      FreeItemRecord(
        index = index,
        state = if (o.guardVersions.isEmpty()) FreeItemState.PLANNED else FreeItemState.GUARD,
        mediaId = o.item.mediaId,
        versionId = o.item.versionId,
        phoneLocation = o.phoneLocation,
        phone = o.item.phone,
        phoneStamp = o.phoneStamp,
        mime = o.item.mime,
        size = o.item.size,
        needsGuardian = o.guardVersions.isNotEmpty(),
        plannedCopy = o.item.safeCopy,
      )
    }
    val state = when {
      records.isEmpty() -> FreeSpaceRunState.DONE
      records.any { it.state == FreeItemState.GUARD } -> FreeSpaceRunState.GUARDING
      else -> FreeSpaceRunState.VERIFYING
    }
    // Un giro alla volta: uno ancora aperto (un foglio di ieri, un doppio tocco) si chiude. Un suo lotto gia' nel dialogo resta tracciato:
    // la risposta si registra lo stesso, o la risolve la prova del telefono.
    for (old in diary.runs()) if (!old.state.terminal) end(old, FreeSpaceRunState.EXPIRED)
    val validity = FreeValidity(startedAt, boot.current())
    val created = diary.create(
      NewFreeRun(state, fresh.plan.filter.kind, fresh.plan.filter.excludeRecentDays, fresh.plan.permanent, fresh.plan.hash, validity),
      records,
    )
    liveRuns[created.id.value] = validity
    remember(created.id, records)
    advance(created, deadline)
  }

  override suspend fun resume(run: FreeSpaceRun, budget: Duration): FreeSpaceRun = mutex.withLock {
    val deadline = deadlineOf(budget)
    val record = diary.run(run.id) ?: return@withLock unknownRun(run.id, run.planHash, run.permanent)
    advance(record, deadline)
  }

  private fun deadlineOf(budget: Duration): Long = clock.monotonicMs() + budget.inWholeMilliseconds.coerceAtLeast(0)

  /** Porta avanti il giro fino a [deadline]: guardiano, poi verifica. Restituisce il giro com'e'. */
  private suspend fun advance(start: FreeRunRecord, deadline: Long): FreeSpaceRun {
    var rec = settleForeignDialog(start)
    if (rec.state.terminal) return runOf(rec)
    if (!isValid(rec)) return runOf(end(rec, FreeSpaceRunState.EXPIRED))
    blockOf(scene())?.let { return runOf(end(rec, FreeSpaceRunState.BLOCKED, it)) }
    if (rec.state == FreeSpaceRunState.GUARDING) rec = guardStep(rec, deadline)
    if (rec.state == FreeSpaceRunState.VERIFYING) rec = verifyStep(rec, deadline)
    return runOf(rec)
  }

  /** 3. Il guardiano (D9, E-B14): poi ogni elemento che aspettava si rivaluta dal catalogo; chi non ha ancora la copia privata esce. */
  private suspend fun guardStep(rec: FreeRunRecord, deadline: Long): FreeRunRecord {
    val waiting = itemsOf(rec.id).filter { it.state == FreeItemState.GUARD }
    if (waiting.isNotEmpty()) {
      val left = deadline - clock.monotonicMs()
      if (left <= 0) return rec
      val report = guardian.ensure(GuardScope.Media(waiting.mapTo(LinkedHashSet()) { it.mediaId }), left.milliseconds)
      when (report.stoppedBy) {
        // La tappa e' finita: il guardiano riprende dal suo diario alla prossima.
        RunStop.BUDGET -> return rec
        RunStop.NAS -> return end(rec, FreeSpaceRunState.BLOCKED, FreeSpaceBlock.NAS_UNREACHABLE)
        // Finito, o il cancello non lo lascia scrivere (pausa, sola lettura): conta cio' che c'e' adesso nel catalogo.
        else -> Unit
      }
    }
    val scene = scene()
    val facts = catalog.media(waiting.map { it.mediaId }.toSet()).associateBy { it.id }
    val changed = waiting.map { item ->
      val ready = outcomeFor(item, facts[item.mediaId], scene)
      when {
        ready is CopyOutcome.Excluded -> item.dropped(ready.reason)
        ready !is CopyOutcome.Ready -> item.dropped(FreeSpaceExclusion.GONE_FROM_PHONE)
        !sameItem(item, ready) -> item.dropped(FreeSpaceExclusion.NOT_VERIFIED)
        // Il guardiano non ha fatto la copia privata: l'elemento resta sul telefono.
        ready.guardVersions.isNotEmpty() ->
          item.dropped(if (item.versionId in ready.guardVersions) FreeSpaceExclusion.NOT_VERIFIED else FreeSpaceExclusion.VERSION_NOT_SAFE)
        else -> item.copy(state = FreeItemState.PLANNED)
      }
    }
    val next = rec.copy(state = FreeSpaceRunState.VERIFYING)
    write(next, changed)
    return next
  }

  /** L'esito di una rilettura per la verifica sul momento. */
  private sealed interface CheckResult {
    class Passed(val check: FreeCheck) : CheckResult

    data object Failed : CheckResult

    data object NasDown : CheckResult
  }

  /** 4. La verifica sul momento: ogni copia privata da proteggere riletta per intero, un elemento alla volta, scritto subito nel diario. */
  private suspend fun verifyStep(start: FreeRunRecord, deadline: Long): FreeRunRecord {
    val rec = start
    val items = itemsOf(rec.id)
    // Le copie gia' rilette in questo giro (altre copie del telefono dello stesso media, E-L3) non si rileggono.
    val cache = HashMap<LocationId, CheckResult>()
    for (done in items) {
      if (done.state != FreeItemState.VERIFIED) continue
      done.checks.orEmpty().forEach { cache[it.location] = CheckResult.Passed(it) }
    }
    for (item in items) {
      if (item.state != FreeItemState.PLANNED) continue
      if (clock.monotonicMs() >= deadline) return rec
      if (!isValid(rec)) return end(rec, FreeSpaceRunState.EXPIRED)
      val scene = scene()
      blockOf(scene)?.let { return end(rec, FreeSpaceRunState.BLOCKED, it) }
      val nasNow = clock.nasNowMs() ?: return end(rec, FreeSpaceRunState.BLOCKED, FreeSpaceBlock.NAS_UNREACHABLE)
      // Le regole di nuovo, dal catalogo di adesso: un caricamento partito, un errore, una versione nuova fanno uscire l'elemento.
      val fm = catalog.media(listOf(item.mediaId)).firstOrNull { it.id == item.mediaId }
      val outcome = outcomeFor(item, fm, scene)
      val ready = outcome as? CopyOutcome.Ready
      val reason = when {
        outcome is CopyOutcome.Excluded -> outcome.reason
        ready == null -> FreeSpaceExclusion.GONE_FROM_PHONE
        !sameItem(item, ready) || ready.guardVersions.isNotEmpty() -> FreeSpaceExclusion.NOT_VERIFIED
        // La copia confermata dall'utente non e' piu' quella che il catalogo conosce: non e' la copia decisa.
        item.plannedCopy != null && ready.checks.none { it.versionId == item.versionId && it.stamp.sameAs(item.plannedCopy) } ->
          FreeSpaceExclusion.NOT_VERIFIED
        else -> null
      }
      if (reason != null) {
        write(rec, listOf(item.dropped(reason)))
        continue
      }
      val passed = ArrayList<FreeCheck>()
      var failed: FreeSpaceExclusion? = null
      for (check in ready!!.checks) {
        val result = cache[check.location] ?: reread(check, nasNow).also { cache[check.location] = it }
        when (result) {
          is CheckResult.Passed -> passed += result.check
          CheckResult.Failed -> {
            failed = if (check.versionId == item.versionId) FreeSpaceExclusion.NOT_VERIFIED else FreeSpaceExclusion.VERSION_NOT_SAFE
            break
          }
          // E-B9: il NAS non risponde. L'elemento non e' verificato e il giro si ferma: niente di questo giro arriva a un dialogo.
          CheckResult.NasDown -> return end(rec, FreeSpaceRunState.BLOCKED, FreeSpaceBlock.NAS_UNREACHABLE)
        }
      }
      val updated = if (failed != null) item.dropped(failed) else item.copy(state = FreeItemState.VERIFIED, checks = passed)
      write(rec, listOf(updated))
    }
    val after = itemsOf(rec.id)
    val next = rec.copy(state = if (after.any { it.state == FreeItemState.VERIFIED }) FreeSpaceRunState.READY else FreeSpaceRunState.DONE)
    write(next)
    return next
  }

  /**
   * La rilettura completa di una copia: il timbro all'apertura dev'essere quello del catalogo, la dimensione quella della versione, e dopo
   * la lettura il file non deve essere cambiato. Lo SHA-256 letto va al catalogo **sempre** (uguale: verificata adesso; diverso: fuori da
   * "al sicuro" e il backup la rifa', E-B8). Assente, aperta dal PC o cambiata: non si sa, quindi non e' verificata.
   */
  private suspend fun reread(check: PlannedCheck, nasNow: Long): CheckResult =
    when (val read = rereader.reread(check.share, check.path, expectStamp = check.stamp, expectSize = check.size, stableAfter = true)) {
      is Reread.Ok -> {
        val marked = catalog.markVerified(check.location, read.sha, nasNow)
        if (read.sha == check.expected && marked) CheckResult.Passed(FreeCheck(check.versionId, check.location, check.share, check.path, read.stamp)) else CheckResult.Failed
      }
      is Reread.Fail -> CheckResult.NasDown
      Reread.Missing, Reread.Busy, Reread.Changed, Reread.Different -> CheckResult.Failed
    }

  // --- 5. I lotti ------------------------------------------------------------------------------------------------------------

  override suspend fun nextBatch(run: FreeSpaceRun): TrashBatch? = mutex.withLock {
    val start = diary.run(run.id) ?: return@withLock null
    var rec = settleForeignDialog(start)
    if (rec.state != FreeSpaceRunState.READY) return@withLock null
    if (!isValid(rec)) {
      end(rec, FreeSpaceRunState.EXPIRED)
      return@withLock null
    }
    // Un lotto gia' consegnato da questo processo e senza risposta (la UI l'ha perso): si ricontrolla e si ripropone, mai due lotti aperti.
    var reoffer = pendingBatch[rec.id.value] == rec.batchesDone + 1
    while (true) {
      val index = rec.batchesDone + 1
      val scene = scene()
      blockOf(scene)?.let {
        end(rec, FreeSpaceRunState.BLOCKED, it)
        return@withLock null
      }
      val chunk = if (reoffer) reofferable(rec, index) else itemsOf(rec.id).filter { it.state == FreeItemState.VERIFIED }.take(FreeSpaceRules.MAX_BATCH)
      if (chunk.isEmpty()) {
        if (reoffer) {
          // Il lotto di prima non ha piu' righe da riproporre: e' chiuso, si passa al prossimo.
          reoffer = false
          pendingBatch.remove(rec.id.value)
          rec = rec.copy(batchesDone = index)
          write(rec)
          continue
        }
        rec = rec.copy(state = FreeSpaceRunState.DONE)
        write(rec)
        return@withLock null
      }
      val checked = finalCheck(chunk, scene)
      if (checked == null) {
        // E-B9 al controllo finale: nessun dialogo con copie non ricontrollate.
        end(rec, FreeSpaceRunState.BLOCKED, FreeSpaceBlock.NAS_UNREACHABLE)
        return@withLock null
      }
      val survivors = chunk.filter { it.index !in checked }.map { it.copy(state = FreeItemState.IN_DIALOG, batchIndex = index) }
      val drops = chunk.mapNotNull { item -> checked[item.index]?.let { item.copy(state = FreeItemState.DROPPED, reason = it, batchIndex = null) } }
      // Scritto PRIMA di consegnare il lotto: se il processo muore da qui in poi, il diario sa che queste righe sono (forse) nel dialogo.
      write(rec, drops + survivors)
      if (survivors.isEmpty()) continue
      pendingBatch[rec.id.value] = index
      val remaining = itemsOf(rec.id).count { it.state == FreeItemState.VERIFIED }
      return@withLock TrashBatch(
        runId = rec.id,
        index = index,
        of = index + batchesFor(remaining),
        mode = if (rec.permanent) TrashMode.DELETE_NOW else TrashMode.ANDROID_TRASH,
        items = survivors.map { TrashItem(it.mediaId, it.versionId, it.phone!!, it.mime, it.size) },
      )
    }
    @Suppress("UNREACHABLE_CODE")
    null
  }

  /**
   * Le righe del lotto [index] ancora da riproporre. Prima la prova del telefono: una riga che non c'e' piu' e' stata liberata da quel
   * dialogo (FREED, mai "uscita"); una riga che il telefono non sa dire resta in dubbio; solo quelle ancora presenti si ripropongono.
   */
  private suspend fun reofferable(rec: FreeRunRecord, index: Int): List<FreeItemRecord> {
    val open = itemsOf(rec.id).filter { it.state == FreeItemState.IN_DIALOG && it.batchIndex == index }
    val proofs = open.map { item -> item to item.phone?.let { phone.lookup(it) } }
    val settled = proofs.mapNotNull { (item, now) ->
      when (now) {
        PhoneLookup.Gone -> item.copy(state = FreeItemState.FREED)
        null, PhoneLookup.Stale, is PhoneLookup.Denied -> item.copy(state = FreeItemState.IN_DOUBT)
        is PhoneLookup.Present -> null
      }
    }
    if (settled.isNotEmpty()) {
      write(rec, settled)
      tellCatalog(settled)
    }
    return proofs.filter { it.second is PhoneLookup.Present }.map { it.first }
  }

  /**
   * 5. Il controllo finale di [chunk], subito prima del dialogo: per ogni elemento le regole dal catalogo di adesso, la riga del telefono
   * (stessa riga, stesso timbro) e il **timbro** di ogni copia riletta (`stat`: uguale a quello visto dalla rilettura). Restituisce gli
   * elementi che escono con il loro motivo, o null se il NAS non risponde (nessun dialogo).
   */
  private suspend fun finalCheck(chunk: List<FreeItemRecord>, scene: FreeSpaceScene): Map<Int, FreeSpaceExclusion>? {
    val out = HashMap<Int, FreeSpaceExclusion>()
    val facts = catalog.media(chunk.map { it.mediaId }.toSet()).associateBy { it.id }
    val stamps = HashMap<LocationId, Boolean>()
    for (item in chunk) {
      val reason = localReason(item, facts[item.mediaId], scene)
      if (reason != null) {
        out[item.index] = reason
        continue
      }
      val ref = item.phone!!
      val phoneReason = when (val now = phone.lookup(ref)) {
        // Il file del telefono e' cambiato dopo il piano (Samsung "Salva"): quei byte sono una versione nuova, non al sicuro.
        is PhoneLookup.Present -> if (item.phoneStamp != null && now.stamp.looksUnchangedFrom(item.phoneStamp)) null else FreeSpaceExclusion.NOT_VERIFIED
        PhoneLookup.Gone, PhoneLookup.Stale, is PhoneLookup.Denied -> FreeSpaceExclusion.GONE_FROM_PHONE
      }
      if (phoneReason != null) {
        out[item.index] = phoneReason
        continue
      }
      for (check in item.checks!!) {
        val same = stamps[check.location] ?: when (val st = rereader.stamp(check.share, check.path)) {
          is NasResult.Err -> return null
          is NasResult.Ok -> (st.value?.sameAs(check.stamp) == true).also { stamps[check.location] = it }
        }
        if (!same) {
          // L39: la copia e' cambiata (o sparita) dopo la rilettura.
          out[item.index] = FreeSpaceExclusion.COPY_CHANGED
          break
        }
      }
    }
    return out
  }

  /**
   * Le regole di adesso per un elemento verificato, dal catalogo: stessa copia del telefono e stessa versione, nessun caricamento o errore,
   * dentro la recinzione, e **ogni** versione da proteggere di adesso con una copia riletta in questo giro che il catalogo considera
   * ancora privata, presente e verificata (una versione nuova nata dopo la verifica fa uscire l'elemento). Null = puo' andare al controllo
   * del NAS.
   */
  private suspend fun localReason(item: FreeItemRecord, fm: FreeSpaceMedia?, scene: FreeSpaceScene): FreeSpaceExclusion? {
    val checks = item.checks
    if (item.phone == null || checks.isNullOrEmpty()) return FreeSpaceExclusion.NOT_VERIFIED
    return when (val outcome = outcomeFor(item, fm, scene)) {
      null -> FreeSpaceExclusion.GONE_FROM_PHONE
      is CopyOutcome.Excluded -> outcome.reason
      is CopyOutcome.Ready -> when {
        !sameItem(item, outcome) || outcome.guardVersions.isNotEmpty() -> FreeSpaceExclusion.NOT_VERIFIED
        outcome.required.any { v -> checks.none { c -> c.versionId == v && c.location in outcome.verifiedByVersion[v].orEmpty() } } ->
          if (checks.none { it.versionId == item.versionId && it.location in outcome.verifiedByVersion[item.versionId].orEmpty() }) {
            FreeSpaceExclusion.NOT_VERIFIED
          } else {
            FreeSpaceExclusion.VERSION_NOT_SAFE
          }
        else -> null
      }
    }
  }

  // --- "Elimina solo dal telefono" (D22, E-D14) -------------------------------------------------------------------------------

  /**
   * "Elimina solo dal telefono" per i media [ids]: che cosa propone il foglio per ognuno ([PhoneOnlyAssessment]), dalle stesse regole di
   * "Libera spazio" e dal catalogo locale (nessuna lettura del NAS). I media liberabili passano poi da [plan] con
   * [PhoneOnlyAssessment.freeFilter], [verify] e i lotti come ogni altro giro: guardiano, verifica sul momento, timbro, dialogo di sistema.
   */
  suspend fun assessPhoneOnly(ids: Set<MediaId>): PhoneOnlyAssessment {
    val scene = scene()
    val unavailable = blockOf(scene) == FreeSpaceBlock.NOT_AVAILABLE
    val found = catalog.media(ids).filter { it.id in ids }.associateBy { it.id }
    val handed = diary.handedPhoneLocations()
    val advice = LinkedHashMap<MediaId, PhoneOnlyAdvice>()
    for (id in ids) {
      val outcomes = found[id]?.let { rules.evaluate(it, scene, excludeRecentDays = 0) }.orEmpty()
        // Una copia gia' consegnata a un dialogo di sistema aspetta il suo esito.
        .map { if (it is CopyOutcome.Ready && it.phoneLocation in handed) CopyOutcome.Excluded(it.phoneLocation, FreeSpaceExclusion.IN_PROGRESS_OR_ERROR) else it }
      advice[id] = PhoneOnlyAssessment.adviceOf(outcomes, unavailable)
    }
    return PhoneOnlyAssessment(advice)
  }

  // --- 6. Le risposte di Android -----------------------------------------------------------------------------------------------

  override suspend fun recordOutcome(batch: TrashBatch, outcome: TrashOutcome): FreeSpaceRun = mutex.withLock {
    val rec = diary.run(batch.runId) ?: return@withLock unknownRun(batch.runId, null, batch.mode == TrashMode.DELETE_NOW)
    val items = itemsOf(rec.id)
    // Solo le righe che il diario sa nel dialogo di quel lotto: una risposta ripetuta, o con righe di un altro lotto, non conta.
    val open = items.filter { it.batchIndex == batch.index && (it.state == FreeItemState.IN_DIALOG || it.state == FreeItemState.IN_DOUBT) }
    if (open.isEmpty()) return@withLock runOf(rec)
    val changed = when (outcome) {
      is TrashOutcome.Confirmed -> open.map { item ->
        when {
          item.phone == null -> item.copy(state = FreeItemState.IN_DOUBT)
          item.phone in outcome.confirmed -> item.copy(state = FreeItemState.FREED)
          else -> item.copy(state = FreeItemState.KEPT)
        }
      }
      TrashOutcome.Refused -> open.map { it.copy(state = FreeItemState.KEPT) }
      TrashOutcome.Unknown -> open.map { proven(it) }
    }
    val after = items.map { item -> changed.firstOrNull { it.index == item.index } ?: item }
    val state = when {
      rec.state.terminal -> rec.state
      outcome == TrashOutcome.Refused -> FreeSpaceRunState.CANCELLED
      after.any { it.state == FreeItemState.VERIFIED } -> FreeSpaceRunState.READY
      else -> FreeSpaceRunState.DONE
    }
    val next = rec.copy(state = state, batchesDone = if (batch.index == rec.batchesDone + 1) rec.batchesDone + 1 else rec.batchesDone)
    write(next, changed)
    if (pendingBatch[rec.id.value] == batch.index) pendingBatch.remove(rec.id.value)
    tellCatalog(changed)
    runOf(next)
  }

  /**
   * Risolve gli esiti in dubbio di tutti i giri (dopo la lettura del telefono, o all'avvio): un elemento consegnato a un dialogo di un
   * processo morto, o "in dubbio", diventa liberato se la sua riga non c'e' piu', rimasto se c'e'; altrimenti resta in dubbio. Restituisce
   * quanti elementi ha risolto. Lo chiama `:app` (P14) dopo la lettura del telefono.
   */
  suspend fun settle(): Int = mutex.withLock {
    var settled = 0
    for (rec in diary.runs()) {
      val before = itemsOf(rec.id).count { it.state == FreeItemState.IN_DOUBT || it.state == FreeItemState.IN_DIALOG }
      if (before == 0) continue
      settleForeignDialog(rec, alsoInDoubt = true)
      settled += before - itemsOf(rec.id).count { it.state == FreeItemState.IN_DOUBT || it.state == FreeItemState.IN_DIALOG }
    }
    settled
  }

  /**
   * Gli elementi `IN_DIALOG` di un lotto che **questo** processo non ha consegnato (il processo di prima e' morto durante il dialogo o
   * subito dopo): si risolvono con la prova del telefono, mai dati per liberati senza.
   */
  private suspend fun settleForeignDialog(rec: FreeRunRecord, alsoInDoubt: Boolean = false): FreeRunRecord {
    val mine = pendingBatch[rec.id.value]
    val items = itemsOf(rec.id)
    val open = items.filter {
      (it.state == FreeItemState.IN_DIALOG && it.batchIndex != mine) || (alsoInDoubt && it.state == FreeItemState.IN_DOUBT)
    }
    if (open.isEmpty()) return rec
    // Un lotto consegnato da un processo morto e' chiuso: le righe che il telefono non sa dire passano a IN_DOUBT, mai piu' riproposte.
    val changed = open.map { proven(it) }.filter { new -> open.first { it.index == new.index }.state != new.state }
    val closesBatch = open.any { it.state == FreeItemState.IN_DIALOG && it.batchIndex == rec.batchesDone + 1 }
    val after = items.map { item -> changed.firstOrNull { it.index == item.index } ?: item }
    val state = if (rec.state == FreeSpaceRunState.READY && after.none { it.state == FreeItemState.VERIFIED }) FreeSpaceRunState.DONE else rec.state
    val next = rec.copy(state = state, batchesDone = if (closesBatch) rec.batchesDone + 1 else rec.batchesDone)
    if (changed.isEmpty() && next == rec) return rec
    write(next, changed)
    tellCatalog(changed)
    return next
  }

  /** La prova del telefono per un elemento dal destino sconosciuto: assente = liberato, presente = rimasto, altrimenti in dubbio. */
  private suspend fun proven(item: FreeItemRecord): FreeItemRecord {
    val ref = item.phone ?: return item.copy(state = FreeItemState.IN_DOUBT)
    return when (phone.lookup(ref)) {
      PhoneLookup.Gone -> item.copy(state = FreeItemState.FREED)
      is PhoneLookup.Present -> item.copy(state = FreeItemState.KEPT)
      PhoneLookup.Stale, is PhoneLookup.Denied -> item.copy(state = FreeItemState.IN_DOUBT)
    }
  }

  private suspend fun tellCatalog(changed: List<FreeItemRecord>) {
    val freed = changed.filter { it.state == FreeItemState.FREED }.map { it.phoneLocation }
    if (freed.isNotEmpty()) catalog.freedFromPhone(freed)
  }

  // --- Regole comuni -----------------------------------------------------------------------------------------------------------

  private fun scene(): FreeSpaceScene {
    val share = privateShare()
    return FreeSpaceScene(account(), share, areas(), share?.let { gate.stateOf(it) }, fence, today())
  }

  /** Perche' un giro non puo' andare avanti adesso, o null. */
  private fun blockOf(scene: FreeSpaceScene): FreeSpaceBlock? = when (scene.gate) {
    // E-B22: area congelata o scritture ferme, finche' dura.
    BackupGateState.FROZEN, BackupGateState.WRITES_STOPPED -> FreeSpaceBlock.AREA_FROZEN
    null, BackupGateState.NO_PRIVATE_AREA, BackupGateState.NOT_READY, BackupGateState.TEST_ONLY -> FreeSpaceBlock.NOT_AVAILABLE
    BackupGateState.OPEN, BackupGateState.PAUSED, BackupGateState.READ_ONLY_AREA -> if (scene.me == null) FreeSpaceBlock.NOT_AVAILABLE else null
  }

  /**
   * La verifica del giro vale ancora? Orologio **monotono** (mai indietro, al massimo 6 ore dall'inizio) e **stesso avvio**: lo stesso
   * conteggio dei riavvii, o, se non si legge, lo stesso processo. Senza validita' nel diario (un adattatore che non la conserva) no.
   */
  private fun isValid(rec: FreeRunRecord): Boolean {
    val validity = rec.validity ?: liveRuns[rec.id.value] ?: return false
    val now = clock.monotonicMs()
    if (now < validity.startMonotonicMs || now - validity.startMonotonicMs > FreeSpaceRules.VERIFICATION_VALID_MS) return false
    val bootNow = boot.current()
    return if (validity.bootCount != null && bootNow != null) validity.bootCount == bootNow else rec.id.value in liveRuns
  }

  /** Gli elementi del giro dal diario, completati con cio' che questo processo ha scritto (stesso stato, stesso lotto). */
  private suspend fun itemsOf(id: FreeSpaceRunId): List<FreeItemRecord> {
    val mine = written[id.value] ?: return diary.items(id)
    return diary.items(id).map { stored ->
      val full = mine[stored.index]?.takeIf { it.state == stored.state && it.batchIndex == stored.batchIndex } ?: return@map stored
      stored.copy(
        phone = stored.phone ?: full.phone,
        phoneStamp = stored.phoneStamp ?: full.phoneStamp,
        mime = stored.mime ?: full.mime,
        checks = if ((stored.checks?.size ?: 0) >= (full.checks?.size ?: 0)) stored.checks else full.checks,
        needsGuardian = stored.needsGuardian || full.needsGuardian,
        plannedCopy = full.plannedCopy ?: stored.plannedCopy,
      )
    }
  }

  /** Scrive nel diario (un passo solo) e ricorda per intero cio' che ha scritto. */
  private suspend fun write(run: FreeRunRecord, items: List<FreeItemRecord> = emptyList()) {
    diary.update(run, items)
    remember(run.id, items)
  }

  private fun remember(id: FreeSpaceRunId, items: List<FreeItemRecord>) {
    if (items.isEmpty()) return
    val map = written.getOrPut(id.value) { HashMap() }
    for (item in items) map[item.index] = item
  }

  private suspend fun outcomeFor(item: FreeItemRecord, fm: FreeSpaceMedia?, scene: FreeSpaceScene): CopyOutcome? {
    if (fm == null || fm.id != item.mediaId) return null
    return rules.evaluate(fm, scene, excludeRecentDays = 0).firstOrNull { it.phoneLocation == item.phoneLocation }
  }

  /** La copia del telefono e' ancora quella del piano: stessa versione e stessa riga del MediaStore. */
  private fun sameItem(item: FreeItemRecord, ready: CopyOutcome.Ready): Boolean =
    ready.item.versionId == item.versionId && (item.phone == null || ready.item.phone == item.phone)

  private fun FreeItemRecord.dropped(reason: FreeSpaceExclusion): FreeItemRecord = copy(state = FreeItemState.DROPPED, reason = reason, batchIndex = null)

  /** Chiude il giro con [state] (un lotto gia' nel dialogo resta tracciato: la sua risposta si registra lo stesso). */
  private suspend fun end(rec: FreeRunRecord, state: FreeSpaceRunState, blocked: FreeSpaceBlock? = null): FreeRunRecord {
    val next = rec.copy(state = state, blocked = blocked)
    write(next)
    if (blocked != null) blockedRuns[rec.id.value] = blocked
    return next
  }

  /** Un giro che non parte: nel diario anche lui (con il suo id), senza elementi. */
  private suspend fun blockedRun(plan: FreeSpacePlan, reason: FreeSpaceBlock): FreeSpaceRun {
    val created = diary.create(
      NewFreeRun(FreeSpaceRunState.BLOCKED, plan.filter.kind, plan.filter.excludeRecentDays, plan.permanent, plan.hash, validity = null, blocked = reason),
      emptyList(),
    )
    blockedRuns[created.id.value] = reason
    return runOf(created)
  }

  /** Un giro che il diario non conosce (azzeramento dell'app): fermo, niente da fare. */
  private fun unknownRun(id: FreeSpaceRunId, hash: PhonePlanHash?, permanent: Boolean): FreeSpaceRun = FreeSpaceRun(
    id = id,
    planHash = hash ?: PhonePlanHash.builder("free-space-unknown").build(),
    state = FreeSpaceRunState.BLOCKED,
    permanent = permanent,
    guardedDone = 0, guardedTotal = 0, verifiedDone = 0, verifiedTotal = 0, verifiedBytes = 0,
    readyItems = 0, readyBytes = 0, batchesTotal = 0, batchesDone = 0, freedItems = 0, freedBytes = 0,
    blocked = FreeSpaceBlock.NOT_AVAILABLE,
  )

  /** Il giro come lo vede la UI (§1.5 `FreeSpaceState`): soli numeri e codici. */
  private suspend fun runOf(rec: FreeRunRecord): FreeSpaceRun {
    val items = itemsOf(rec.id)
    val ready = items.filter { it.state == FreeItemState.VERIFIED }
    val inDialog = items.count { it.state == FreeItemState.IN_DIALOG && (it.batchIndex ?: 0) > rec.batchesDone }
    val passed = items.filter { it.state == FreeItemState.VERIFIED || it.state.handedToAndroid }
    val freed = items.filter { it.state == FreeItemState.FREED }
    return FreeSpaceRun(
      id = rec.id,
      planHash = rec.planHash,
      state = rec.state,
      permanent = rec.permanent,
      guardedDone = items.count { it.needsGuardian && it.state != FreeItemState.GUARD },
      guardedTotal = items.count { it.needsGuardian },
      verifiedDone = items.count { it.state != FreeItemState.GUARD && it.state != FreeItemState.PLANNED },
      verifiedTotal = items.size,
      verifiedBytes = passed.sumOf { it.size },
      readyItems = ready.size,
      readyBytes = ready.sumOf { it.size },
      batchesTotal = rec.batchesDone + batchesFor(ready.size + inDialog),
      batchesDone = rec.batchesDone,
      freedItems = freed.size,
      freedBytes = freed.sumOf { it.size },
      dropped = items.filter { it.state == FreeItemState.DROPPED }.groupingBy { it.reason!! }.eachCount(),
      validUntilMonotonicMs = rec.validity?.let { it.startMonotonicMs + FreeSpaceRules.VERIFICATION_VALID_MS },
      blocked = rec.blocked ?: blockedRuns[rec.id.value],
    )
  }

  companion object {
    const val DEFAULT_PAGE_SIZE: Int = 500

    private val NEWEST_FIRST: Comparator<FreeSpaceMedia> = compareByDescending<FreeSpaceMedia> { it.sortTime }.thenByDescending { it.id }

    private fun batchesFor(items: Int): Int = (items + FreeSpaceRules.MAX_BATCH - 1) / FreeSpaceRules.MAX_BATCH
  }
}
