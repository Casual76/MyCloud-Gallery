package dev.pampa.mycloudgallery.core.backup.safety

import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.GuardCandidate
import dev.pampa.mycloudgallery.core.backup.contract.GuardReport
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.GuardSource
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.ops.backup.BackupTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException

/**
 * Il **guardiano delle copie private** (Fase 5A, P3; `docs/fase5-progetto.md` D9, §2.3, Q4 (b), E-B13, E-B14, E-D12, E-D13, E-S11).
 *
 * Per un media **proprio** la cui sola copia sul NAS e' nell'area comune (`ONLY_COMMON_COPY`) crea la copia privata:
 * 1. **solo dal catalogo locale** ([BackupCatalog.onlyCommonCopies]): nessun dato letto dal NAS avvia una copia (E-S11);
 * 2. l'hash di riferimento e' quello della versione; se il catalogo non lo sa, si rilegge qui la sorgente per intero e vale solo se
 *    timbro e `qh1` sono quelli della versione (e restano tali fino alla fine della lettura): l'hash entra nel catalogo
 *    ([BackupCatalog.recordFullHash]);
 * 3. una copia identica gia' nell'area privata (D8) si collega senza crearne un'altra;
 * 4. **copia lato server** (`copyToTemp`, 124-148 MB/s) dalla copia comune a un temporaneo `-bk-` dell'area privata; se il server la
 *    rifiuta (E-D12) i byte del telefono, se la versione c'e' ancora, altrimenti la sorgente letta e scritta dall'app;
 * 5. **verifica completa** del temporaneo contro l'hash di riferimento (D4): diverso = si rifa' (al massimo 3 volte, poi `FAILED` con
 *    `VERIFY_FAILED`); la sorgente cambiata a meta' (E-D13) non pubblica mai niente;
 * 6. **pubblicazione senza sostituire** in `Backup/Area comune/AAAA/MM/` (nome occupato da un file identico = collegato, da uno diverso =
 *    "nome (n)"), poi la copia verificata nel catalogo: il media e' "al sicuro".
 *
 * Ogni passo passa dal **diario** ([BackupStore], `UploadKind.GUARD_COPY`) come nel motore di riferimento: il temporaneo prima della
 * copia, il nome finale prima della pubblicazione, "da capo" prima di togliere un temporaneo che non torna. Legge solo la sorgente e
 * l'area privata, scrive **solo** nell'area privata e toglie solo i propri temporanei (D2: `NasBackupOps` non sa fare altro).
 *
 * **Quando** (risposta Q4 (b)): con [GuardScope.Media] per i media chiesti (prima di "Libera spazio" e di "Elimina solo dal telefono",
 * 5B), anche se il telefono li ha ancora; con [GuardScope.All] (il lavoro dopo la lettura del telefono, E-B13) solo i media che il
 * telefono **ha avuto** e la cui versione **non** e' piu' sul telefono: non subito dopo uno spostamento nell'area comune, mai per un media
 * che non e' mai stato sul telefono (campo, gruppo A, D2: le 76 copie di `campioni`), **subito** per uno che il telefono non ha gia' piu'
 * (la condizione di Q4 (b) e' gia' vera, Fase 7 D28).
 *
 * Tetto di [capBytes] (20 GB) per giro: un elemento si comincia solo se ci sta; un solo media piu' grande del tetto parte da solo, come
 * primo del giro (altrimenti non partirebbe mai). Il budget e' di tempo, con l'orologio monotono. Nessuna eccezione per un errore del NAS
 * o del telefono: il giro si ferma con il suo [RunStop]; la morte del processo (un `Error`) non si cattura mai.
 *
 * **Un errore inatteso di un solo media non ferma gli altri** (R1 della revisione 5A, difesa in profondita'): l'elemento si parcheggia
 * (`FAILED` con [BackupProblemCode.UNEXPECTED], il media porta il segno e il guardiano non lo ritenta finche' l'utente non tocca "Riprova")
 * e il giro continua. Un elemento di un'altra share (l'area privata e' cambiata, R7) aspetta; la pausa dell'utente (R4, [BackupGateState.PAUSED])
 * ferma il giro con [RunStop.STOPPED]. Il nome "nome (n)" di una copia rispetta i 255 byte ([SafetyCommon.nameFor]).
 */
class BackupPrivateCopyGuardian(
  private val deviceId: String,
  private val ops: NasBackupOps,
  private val reader: NasMediaReader,
  private val store: BackupStore,
  private val catalog: BackupCatalog,
  private val phone: PhoneBytes,
  private val gate: BackupGate,
  private val clock: BackupClock,
  /** L'area privata del proprietario (`LocalDeviceProfile.privateShare`), null = nessuna. */
  private val privateShare: () -> String?,
  /** L'area comune della famiglia: se nota, la sorgente deve stare li'; in ogni caso mai nell'area privata. */
  private val commonShare: () -> String? = { null },
  /** D13: la radice delle destinazioni nella build debug con gli account di prova (`mcg-campo`), null = nessuna recinzione. */
  private val destinationRoot: () -> SharePath? = { null },
  private val capBytes: Long = DEFAULT_CAP_BYTES,
  private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
  private val purpose: Purpose = Purpose.BACKGROUND,
) : PrivateCopyGuardian {

  init {
    require(capBytes > 0 && chunkBytes > 0) { "Misure del guardiano non valide." }
    require(ops.ownerDeviceId == deviceId) { "Le primitive devono essere di questo dispositivo." }
  }

  private val rereader = NasRereader(reader, purpose)

  /** I conteggi di un giro. */
  private inner class Tally(val deadline: Long) {
    var created = 0
    var linked = 0
    var skipped = 0
    var failed = 0
    var bytesCopied = 0L
    var bytesRead = 0L

    /** Le dimensioni degli elementi cominciati nel giro (il tetto). */
    var committed = 0L

    fun fits(size: Long): Boolean = committed == 0L || committed + size <= capBytes

    fun report(stop: RunStop, problem: BackupProblemCode? = null) = GuardReport(created, linked, skipped, failed, bytesCopied, stop, problem)

    fun overtime(): Boolean = clock.monotonicMs() >= deadline
  }

  /** L'esito di un passo. */
  private sealed interface Step {
    class Next(val item: BackupItem) : Step

    /** L'elemento aspetta il prossimo giro (file aperto dal PC, ora del NAS assente...). */
    data object Wait : Step

    class Stop(val stop: RunStop, val problem: BackupProblemCode? = null) : Step
  }

  /** Byte riletti dall'ultimo giro (verifiche, riferimento, deduplica): soli numeri, per il traffico e la Diagnostica. */
  @Volatile var lastRoundBytesRead: Long = 0
    private set

  override suspend fun ensure(scope: GuardScope, budget: Duration): GuardReport {
    val tally = Tally(clock.monotonicMs() + budget.inWholeMilliseconds.coerceAtLeast(0))
    try {
      return run(scope, tally)
    } finally {
      lastRoundBytesRead = tally.bytesRead
    }
  }

  private suspend fun run(scope: GuardScope, tally: Tally): GuardReport {
    val share = privateShare() ?: return tally.report(RunStop.GATE)
    closedReport(tally, gate.stateOf(share))?.let { return it }

    // 1. Ripresa: gli elementi del guardiano rimasti aperti nel diario (dopo un riavvio, una tappa finita, un errore del NAS).
    val open = store.itemsToResume(RESUME_SCAN).filter { it.kind == UploadKind.GUARD_COPY && inScope(it.mediaId, scope) }
    for (item in open) {
      if (tally.overtime()) return tally.report(RunStop.BUDGET)
      // R7: l'area privata e' cambiata dopo che l'elemento e' nato: niente si scrive nella share di prima, ma gli altri vanno avanti.
      if (!SafetyCommon.sameShare(item.share, share)) {
        tally.skipped++
        GalleryLog.w(TAG) { "elemento di un'altra area: aspetta (la share non e' piu' l'area privata)" }
        continue
      }
      closedReport(tally, gate.stateOf(item.share))?.let { return it }
      if (!tally.fits(item.size)) return tally.report(RunStop.BUDGET)
      tally.committed += item.size
      val step = driveGuarded(item, tally)
      if (step is Step.Stop) return tally.report(step.stop, step.problem)
    }

    // 2. I media propri con la sola copia comune, dal catalogo locale.
    val seen = HashSet<MediaId>()
    while (true) {
      if (tally.overtime()) return tally.report(RunStop.BUDGET)
      val limit = seen.size + PLAN_PAGE
      val page = catalog.onlyCommonCopies(scope, limit)
      val fresh = page.filter { it.common.mediaId !in seen }
      if (fresh.isEmpty()) return tally.report(RunStop.COMPLETE)
      for (candidate in fresh) {
        seen += candidate.common.mediaId
        if (tally.overtime()) return tally.report(RunStop.BUDGET)
        closedReport(tally, gate.stateOf(share))?.let { return it }
        val item = try {
          val planned = plan(candidate, share, scope)
          if (planned == null) {
            tally.skipped++
            continue
          }
          if (!tally.fits(planned.size)) return tally.report(RunStop.BUDGET)
          store.create(listOf(planned)).single()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // R1: un media che non si lascia nemmeno pianificare non ferma gli altri.
          parkCandidate(candidate, tally, e)
          continue
        }
        if (item.kind != UploadKind.GUARD_COPY || item.state.terminal) {
          tally.skipped++
          continue
        }
        tally.committed += item.size
        val step = driveGuarded(item, tally)
        if (step is Step.Stop) return tally.report(step.stop, step.problem)
      }
      if (page.size < limit) return tally.report(RunStop.COMPLETE)
    }
  }

  /** Il cancello e' chiuso: la fermata del giro (la pausa dell'utente e' un "Ferma", ogni altra ragione e' del cancello); null se e' aperto. */
  private fun closedReport(tally: Tally, state: BackupGateState): GuardReport? =
    if (state.open) null else tally.report(gateStop(state))

  private fun gateStop(state: BackupGateState): RunStop = if (state == BackupGateState.PAUSED) RunStop.STOPPED else RunStop.GATE

  private fun inScope(mediaId: MediaId, scope: GuardScope): Boolean = when (scope) {
    GuardScope.All -> true
    is GuardScope.Media -> mediaId in scope.ids
  }

  // --- Piano -------------------------------------------------------------------------------------------------------------------

  /** L'elemento da scrivere nel diario per [candidate], o null se non tocca al guardiano adesso. */
  private suspend fun plan(candidate: GuardCandidate, share: String, scope: GuardScope): NewBackupItem? {
    val source = candidate.common
    val versionId = source.versionId ?: return null
    if (candidate.size <= 0) return null
    // Legge solo la sorgente: mai l'area privata stessa, e se l'area comune e' nota solo quella.
    if (SafetyCommon.sameShare(source.share, share)) return null
    commonShare()?.let { if (!SafetyCommon.sameShare(source.share, it)) return null }
    if (source.stamp != null && source.stamp.size != candidate.size) return null
    // Senza hash della versione e senza `qh1` non si potrebbe dire che la sorgente e' la versione.
    if (candidate.fullHash == null && candidate.quickHash == null) return null
    // Un altro elemento aperto (anche del caricamento) protegge gia' questa versione: nessun doppione.
    if (store.openFor(versionId) != null) return null
    // R1: un media gia' parcheggiato per un errore inatteso non si ritenta a ogni giro: lo rimette in gioco "Riprova".
    if (catalog.mediaSafety(source.mediaId)?.problem == BackupProblemCode.UNEXPECTED) return null
    // Q4 (b): con l'ambito "tutti" solo i media che non sono piu' sul telefono.
    if (scope == GuardScope.All && phoneStillHas(source.mediaId, versionId)) return null
    return NewBackupItem(
      kind = UploadKind.GUARD_COPY,
      mediaId = source.mediaId,
      versionId = versionId,
      size = candidate.size,
      phone = null,
      expected = null,
      share = share,
      planned = destination(candidate),
      quickHash = candidate.quickHash,
      sha256 = candidate.fullHash,
      guardSource = GuardSource(source.share, source.path, source.stamp),
    )
  }

  /** La versione e' ancora sul telefono? Riga rinumerata = si'; permessi ritirati = non si sa, e nel dubbio si protegge. */
  private suspend fun phoneStillHas(mediaId: MediaId, versionId: VersionId): Boolean {
    val ref = catalog.phoneRefOf(mediaId, versionId) ?: return false
    return when (phone.lookup(ref)) {
      is PhoneLookup.Present, PhoneLookup.Stale -> true
      PhoneLookup.Gone, is PhoneLookup.Denied -> false
    }
  }

  /** `Backup/Area comune/AAAA/MM/<nome>` (sotto la radice di D13 se c'e'); `Senza data/` per una data assente o assurda (E-B17). */
  private fun destination(candidate: GuardCandidate): SharePath {
    var dir = destinationRoot()?.child(BACKUP_FOLDER) ?: SharePath.of(BACKUP_FOLDER)
    dir = dir.child(COMMON_FOLDER)
    val month = monthFolder(candidate.localDay)
    dir = if (month == null) dir.child(NO_DATE_FOLDER) else dir.child(month.first).child(month.second)
    val name = candidate.fileName.takeIf { SharePath.isValidComponent(it) } ?: candidate.common.path.name
    return dir.child(name)
  }

  private fun monthFolder(day: Int?): Pair<String, String>? {
    if (day == null) return null
    val year = day / 10_000
    val month = day / 100 % 100
    val dayOfMonth = day % 100
    if (year < MIN_YEAR || month !in 1..12 || dayOfMonth !in 1..31) return null
    // "Oltre domani" (E-B17), con l'ora del NAS e un giorno di margine per i fusi.
    clock.nasNowMs()?.let { now ->
      val limit = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate().plusDays(2)
      if (day > limit.year * 10_000 + limit.monthValue * 100 + limit.dayOfMonth) return null
    }
    return String.format(Locale.ROOT, "%04d", year) to String.format(Locale.ROOT, "%02d", month)
  }

  // --- La macchina di un elemento ------------------------------------------------------------------------------------------------

  /**
   * [drive] con la difesa in profondita' di R1: un'eccezione inattesa (non l'annullamento, non un `Error`) parcheggia **questo** elemento
   * e il giro continua con gli altri.
   */
  private suspend fun driveGuarded(start: BackupItem, tally: Tally): Step = try {
    drive(start, tally)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    parkOnError(start, tally, e)
  }

  /**
   * L'elemento si parcheggia: `FAILED` con [BackupProblemCode.UNEXPECTED] e il segno sul media; il proprio temporaneo si toglie senza
   * insistere (la pulizia di D33 lo toglie comunque dopo un giorno). Se nemmeno il diario scrive il parcheggio l'errore risale.
   */
  private suspend fun parkOnError(start: BackupItem, tally: Tally, error: Exception): Step {
    GalleryLog.e(TAG) { "errore inatteso (${error::class.java.simpleName}): elemento fermo con ${BackupProblemCode.UNEXPECTED.code}" }
    val stored = store.item(start.id)
    if (stored != null && stored.state.terminal) return Step.Next(stored)
    val item = stored ?: start
    val temp = item.temp
    if (temp != null && item.state == UploadState.COPYING) {
      try {
        ops.deleteOwnTemp(item.share, temp, purpose)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        GalleryLog.w(TAG) { "temporaneo non tolto dopo l'errore: lo togliera' la pulizia" }
      }
    }
    markUnexpected(item.mediaId)
    store.update(item.copy(state = UploadState.FAILED, problem = BackupProblemCode.UNEXPECTED))
    tally.failed++
    return Step.Wait
  }

  /** Un media che non si e' lasciato pianificare: nessun elemento da parcheggiare, solo il segno (e il conto). */
  private suspend fun parkCandidate(candidate: GuardCandidate, tally: Tally, error: Exception) {
    GalleryLog.e(TAG) { "errore inatteso (${error::class.java.simpleName}) nel piano: media fermo con ${BackupProblemCode.UNEXPECTED.code}" }
    markUnexpected(candidate.common.mediaId)
    tally.failed++
  }

  private suspend fun markUnexpected(mediaId: MediaId) {
    try {
      catalog.markProblem(mediaId, BackupProblemCode.UNEXPECTED)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      GalleryLog.w(TAG) { "segno del problema non scritto" }
    }
  }

  /** Porta avanti un elemento finche' non e' terminale, aspetta o ferma il giro. */
  private suspend fun drive(start: BackupItem, tally: Tally): Step {
    var item = start
    var steps = 0
    while (true) {
      if (tally.overtime()) return Step.Stop(RunStop.BUDGET)
      val gateState = gate.stateOf(item.share)
      if (!gateState.open) return Step.Stop(gateStop(gateState))
      if (++steps > MAX_STEPS_PER_ITEM) return Step.Wait
      val step = advance(item, tally)
      if (step !is Step.Next) return step
      item = step.item
      if (item.state.terminal) {
        when (item.state) {
          UploadState.DONE -> tally.created++
          UploadState.LINKED_EXISTING -> tally.linked++
          UploadState.GONE -> tally.skipped++
          UploadState.FAILED -> tally.failed++
          else -> Unit
        }
        return step
      }
    }
  }

  private suspend fun advance(item: BackupItem, tally: Tally): Step = when (item.state) {
    UploadState.PLANNED -> planned(item, tally)
    UploadState.COPYING -> copying(item, tally)
    UploadState.COPIED -> verify(item, tally)
    UploadState.VERIFIED ->
      save(item.copy(state = UploadState.PUBLISHING, finalPath = item.finalPath ?: SafetyCommon.nameFor(item.planned, item.nameAttempt)))
    UploadState.PUBLISHING -> publish(item, tally)
    UploadState.PUBLISHED -> link(item, tally)
    UploadState.LINKED -> save(item.copy(state = UploadState.DONE))
    // Fuori dal cammino del guardiano: niente si tocca (il diario non lo permetterebbe comunque).
    else -> Step.Wait
  }

  private suspend fun saveItem(item: BackupItem): BackupItem {
    store.update(item)
    return item
  }

  private suspend fun save(item: BackupItem): Step = Step.Next(saveItem(item))

  /** Un errore del NAS ferma il giro, non l'elemento (§2.2 "Rete"): si riprende al prossimo, dal diario. */
  private fun stopFor(error: NasError): Step = Step.Stop(RunStop.NAS, SafetyCommon.problemFor(error))

  /** Toglie il proprio temporaneo: null se non c'e' piu', altrimenti il passo con cui fermarsi. */
  private suspend fun removeTemp(share: String, temp: SharePath): Step? = when (val d = ops.deleteOwnTemp(share, temp, purpose)) {
    is NasResult.Err -> stopFor(d.error)
    is NasResult.Ok -> if (d.value == DeleteOutcome.Locked) Step.Wait else null
  }

  /** `GONE`: la copia comune non c'e' piu' o non e' piu' la versione. Prima si toglie il proprio temporaneo (citato finche' aperto). */
  private suspend fun gone(item: BackupItem, problem: BackupProblemCode? = null): Step {
    val temp = item.temp
    if (temp != null && item.state == UploadState.COPYING) removeTemp(item.share, temp)?.let { return it }
    if (problem != null) catalog.markProblem(item.mediaId, problem)
    return save(item.copy(state = UploadState.GONE, problem = problem))
  }

  /**
   * La copia comune e' **sparita** (un familiare l'ha cancellata, E-B13) prima della copia privata. Se la versione non e' nemmeno piu' sul
   * telefono e il media non ha una copia privata, per il backup e' persa (L44): `COMMON_COPY_LOST` sul media, con "Recuperalo dal
   * cestino del NAS" (30 giorni). Se il telefono ce l'ha (o non si sa: permessi ritirati), niente si segna: la caricera' il backup.
   */
  private suspend fun sourceMissing(item: BackupItem): Step {
    val lost = phoneSurelyLacks(item.mediaId, item.versionId) && !hasPrivateCopy(item.mediaId)
    return gone(item, if (lost) BackupProblemCode.COMMON_COPY_LOST else null)
  }

  /** La versione non e' sul telefono, di sicuro: nessuna riga nel catalogo, o la riga non c'e' piu'. Permessi ritirati = non si sa. */
  private suspend fun phoneSurelyLacks(mediaId: MediaId, versionId: VersionId): Boolean {
    val ref = catalog.phoneRefOf(mediaId, versionId) ?: return true
    return phone.lookup(ref) == PhoneLookup.Gone
  }

  /** Il catalogo sa di una copia privata del media (verificata o no): allora non e' perso. */
  private suspend fun hasPrivateCopy(mediaId: MediaId): Boolean =
    when (catalog.mediaSafety(mediaId)?.status) {
      SafetyStatus.SAFE, SafetyStatus.PRIVATE_COPY_UNVERIFIED -> true
      else -> false
    }

  // --- PLANNED: riferimento, deduplica, spazio ---------------------------------------------------------------------------------

  private suspend fun planned(item: BackupItem, tally: Tally): Step {
    val sha = item.sha256 ?: when (val ref = reference(item, tally)) {
      is Reference.Ok -> ref.sha
      is Reference.Exit -> return ref.step
    }
    when (
      val found = PrivateCopyFinder.findIdentical(
        catalog, rereader, item.share, item.size, item.quickHash, sha, DEDUP_LIMIT,
      ) { tally.bytesRead += it }
    ) {
      is PrivateCopyFinder.Identical.Stop -> return stopFor(found.error)
      is PrivateCopyFinder.Identical.Copy -> {
        val at = clock.nasNowMs() ?: return Step.Wait
        catalog.attachVerifiedCopies(listOf(VerifiedCopy(item.mediaId, item.versionId, found.copy.share, found.copy.path, found.stamp, at)))
        return save(item.copy(state = UploadState.LINKED_EXISTING, sha256 = sha, finalPath = found.copy.path, finalStamp = found.stamp))
      }
      PrivateCopyFinder.Identical.None -> Unit
    }
    // §2.2 "Spazio": oltre 1 GB lo spazio deve bastare con 1 GiB di margine (tocca i dischi: solo qui).
    if (item.size > SPACE_CHECK_BYTES) {
      when (val space = ops.space(item.share, purpose)) {
        is NasResult.Err -> return stopFor(space.error)
        is NasResult.Ok -> if (space.value.callerFreeBytes < item.size + SPACE_MARGIN_BYTES) {
          return Step.Stop(RunStop.NAS, if (space.value.quotaLimited) BackupProblemCode.QUOTA else BackupProblemCode.NAS_FULL)
        }
      }
    }
    return save(item.copy(state = UploadState.COPYING, temp = NasBackupPaths.tempFor(deviceId, item.id.value), resumeAt = 0, sha256 = sha))
  }

  private sealed interface Reference {
    class Ok(val sha: Sha256) : Reference

    class Exit(val step: Step) : Reference
  }

  /**
   * L'hash della versione non e' nel catalogo: si rilegge la sorgente qui, e vale solo se timbro e `qh1` sono quelli della versione
   * (§2.3) e il timbro resta lo stesso fino alla fine. Sorgente sparita o cambiata (E-D13) = `GONE`, niente si copia.
   */
  private suspend fun reference(item: BackupItem, tally: Tally): Reference {
    val source = item.guardSource ?: return Reference.Exit(Step.Wait)
    val quick = item.quickHash ?: return Reference.Exit(gone(item))
    val read = rereader.reread(
      source.share, source.path, expectStamp = source.stamp, expectSize = item.size, expectQuick = quick,
      onBytes = { tally.bytesRead += it },
    )
    val sha = when (read) {
      is Reread.Fail -> return Reference.Exit(stopFor(read.error))
      Reread.Busy -> return Reference.Exit(Step.Wait)
      Reread.Missing -> return Reference.Exit(sourceMissing(item))
      Reread.Changed, Reread.Different -> return Reference.Exit(gone(item))
      is Reread.Ok -> read.sha
    }
    return when (catalog.recordFullHash(item.mediaId, item.versionId, sha)) {
      HashRecord.RECORDED, HashRecord.SAME -> Reference.Ok(sha)
      // Il catalogo sa un altro hash per la versione, o la versione non c'e' piu': la sorgente non e' da proteggere come tale.
      HashRecord.CONFLICT, HashRecord.UNKNOWN_VERSION -> Reference.Exit(gone(item))
    }
  }

  // --- COPYING: copia lato server, o il ripiego (E-D12) -------------------------------------------------------------------------

  private suspend fun copying(item: BackupItem, tally: Tally): Step {
    val temp = item.temp ?: return save(item.copy(temp = NasBackupPaths.tempFor(deviceId, item.id.value), resumeAt = 0))
    val source = item.guardSource ?: return Step.Wait
    if (item.sha256 == null) return Step.Wait
    val r = ops.copyToTemp(source.share, source.path, item.share, temp, source.stamp, purpose)
    if (r is NasResult.Err) return stopFor(r.error)
    return when (val o = (r as NasResult.Ok).value) {
      is CopyOutcome.Copied -> {
        tally.bytesCopied += o.bytes
        // Una lunghezza diversa: la sorgente non e' (piu') la versione.
        if (o.bytes == item.size) save(item.copy(state = UploadState.COPIED, resumeAt = item.size)) else gone(item)
      }
      // E-B13, L44: la copia comune non c'e' piu'.
      CopyOutcome.SourceMissing -> sourceMissing(item)
      // E-D13: la sorgente e' cambiata (modificata dal PC): niente si pubblica.
      is CopyOutcome.SourceChanged -> gone(item)
      // Un temporaneo rimasto da un tentativo interrotto: e' nostro (citato dal diario), si toglie e si rifa'.
      CopyOutcome.TempExists -> removeTemp(item.share, temp) ?: Step.Next(item)
      CopyOutcome.Locked -> Step.Wait
      CopyOutcome.NotSupported -> fallback(item, temp, source, tally)
    }
  }

  /** L'esito della scrittura di un temporaneo dall'app. */
  private sealed interface Written {
    class Ok(val sha: Sha256) : Written

    /** La sorgente dei byte non si legge (telefono: I/O; NAS: file sparito o occupato). */
    data object Unreadable : Written

    class Exit(val step: Step) : Written
  }

  /**
   * E-D12: la copia lato server non c'e' o e' rifiutata. Prima i byte del telefono, se la versione c'e' ancora (solo scrittura, e lo
   * SHA-256 dei byte letti deve essere quello di riferimento); altrimenti la sorgente letta dal NAS e scritta dall'app, con il timbro
   * uguale prima e dopo (E-D13). In entrambi i casi la verifica completa del temporaneo viene dopo.
   */
  private suspend fun fallback(item: BackupItem, temp: SharePath, source: GuardSource, tally: Tally): Step {
    val expected = item.sha256 ?: return Step.Wait
    val ref = catalog.phoneRefOf(item.mediaId, item.versionId)
    if (ref != null) {
      val opened = phone.open(ref)
      if (opened is PhoneOpen.Opened) {
        val written = opened.file.use { f ->
          if (f.stamp.size != item.size) {
            Written.Unreadable
          } else {
            writeTemp(item, temp, tally) { at, n -> f.bytes.read(at until at + n) }
          }
        }
        when (written) {
          is Written.Exit -> return written.step
          is Written.Ok -> if (written.sha == expected) return save(item.copy(state = UploadState.COPIED, resumeAt = item.size))
          Written.Unreadable -> Unit
        }
        // I byte del telefono non sono la versione (o non si leggono): il temporaneo si toglie e si passa alla sorgente.
        removeTemp(item.share, temp)?.let { return it }
      }
    }
    val file = when (val o = reader.open(source.share, source.path, purpose)) {
      is NasResult.Err -> return when (o.error) {
        NasError.NotFound -> sourceMissing(item)
        NasError.FileBusy -> Step.Wait
        else -> stopFor(o.error)
      }
      is NasResult.Ok -> o.value
    }
    val written = file.use { f ->
      if ((source.stamp != null && !f.stamp.sameAs(source.stamp)) || f.size != item.size) {
        null
      } else {
        writeTemp(item, temp, tally) { at, n -> f.readRange(at until at + n) }
      }
    } ?: return gone(item)
    when (written) {
      is Written.Exit -> return written.step
      Written.Unreadable -> {
        removeTemp(item.share, temp)?.let { return it }
        return Step.Wait
      }
      is Written.Ok -> Unit
    }
    // E-D13: la sorgente deve essere la stessa alla fine della lettura, altrimenti i byte scritti non dimostrano niente.
    val after = when (val st = rereader.stamp(source.share, source.path)) {
      is NasResult.Err -> return stopFor(st.error)
      is NasResult.Ok -> st.value
    }
    if (after == null || !after.sameAs(file.stamp)) return gone(item)
    return save(item.copy(state = UploadState.COPIED, resumeAt = item.size))
  }

  /**
   * Scrive il temporaneo [temp] da capo con i byte di [read] (pezzi da [chunkBytes], mai il file intero in memoria) e ne calcola lo
   * SHA-256. Il temporaneo e' gia' nel diario (stato `COPYING`); uno rimasto da un tentativo interrotto si toglie prima.
   */
  private suspend fun writeTemp(item: BackupItem, temp: SharePath, tally: Tally, read: suspend (Long, Int) -> ByteArray): Written {
    val writer: BackupTempWriter = when (val opened = ops.openTemp(item.share, temp, purpose)) {
      is NasResult.Ok -> opened.value
      is NasResult.Err -> {
        if (opened.error != NasError.Exists) return Written.Exit(stopFor(opened.error))
        removeTemp(item.share, temp)?.let { return Written.Exit(it) }
        when (val again = ops.openTemp(item.share, temp, purpose)) {
          is NasResult.Ok -> again.value
          is NasResult.Err -> return Written.Exit(if (again.error == NasError.Exists) Step.Wait else stopFor(again.error))
        }
      }
    }
    writer.use { w ->
      val md = MessageDigest.getInstance("SHA-256")
      var offset = 0L
      while (offset < item.size) {
        if (tally.overtime()) return Written.Exit(Step.Stop(RunStop.BUDGET))
        val n = minOf(chunkBytes.toLong(), item.size - offset).toInt()
        val bytes = try {
          read(offset, n)
        } catch (e: NasIoException) {
          return when (e.error) {
            NasError.NotFound, NasError.FileBusy -> Written.Unreadable
            else -> Written.Exit(stopFor(e.error))
          }
        } catch (_: IOException) {
          return Written.Unreadable
        }
        if (bytes.size != n) return Written.Unreadable
        md.update(bytes)
        when (val wr = w.write(offset, bytes, 0, n)) {
          is NasResult.Err -> return Written.Exit(stopFor(wr.error))
          is NasResult.Ok -> Unit
        }
        tally.bytesCopied += n
        offset += n
      }
      when (val f = w.finish(item.size)) {
        is NasResult.Err -> return Written.Exit(stopFor(f.error))
        is NasResult.Ok -> if (f.value is TempFinish.SizeMismatch) return Written.Unreadable
      }
      return Written.Ok(Sha256(md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }))
    }
  }

  // --- COPIED: verifica completa del temporaneo (D4) -----------------------------------------------------------------------------

  private suspend fun verify(item: BackupItem, tally: Tally): Step {
    val temp = item.temp ?: return Step.Wait
    val expected = item.sha256 ?: return Step.Wait
    val read = rereader.reread(item.share, temp, stableAfter = false, onBytes = { tally.bytesRead += it })
    return when (read) {
      // Il temporaneo non c'e' piu': si rifa', senza contare un tentativo.
      Reread.Missing -> save(item.copy(state = UploadState.COPYING, resumeAt = 0))
      Reread.Busy, Reread.Changed, Reread.Different -> Step.Wait
      is Reread.Fail -> stopFor(read.error)
      is Reread.Ok -> if (read.sha == expected && read.stamp.size == item.size) {
        save(item.copy(state = UploadState.VERIFIED, tempStamp = read.stamp))
      } else {
        mismatch(item, temp)
      }
    }
  }

  /** Il temporaneo non ha i byte della versione (sorgente cambiata a meta', E-D13, o il NAS che restituisce altro, E-B18). */
  private suspend fun mismatch(item: BackupItem, temp: SharePath): Step {
    val attempts = item.verifyAttempts + 1
    if (attempts >= UploadStateMachine.MAX_VERIFY_ATTEMPTS) {
      val counted = saveItem(item.copy(verifyAttempts = attempts))
      removeTemp(item.share, temp)?.let { return it }
      catalog.markProblem(item.mediaId, BackupProblemCode.VERIFY_FAILED)
      return save(counted.copy(state = UploadState.FAILED, problem = BackupProblemCode.VERIFY_FAILED))
    }
    // "Da capo" nel diario prima di togliere il temporaneo che non torna.
    val again = saveItem(item.copy(state = UploadState.COPYING, resumeAt = 0, verifyAttempts = attempts))
    removeTemp(item.share, temp)?.let { return it }
    return Step.Next(again)
  }

  // --- PUBLISHING: pubblicazione senza sostituire (E-B3) -------------------------------------------------------------------------

  private suspend fun publish(item: BackupItem, tally: Tally): Step {
    val temp = item.temp ?: return Step.Wait
    val final = item.finalPath ?: return save(item.copy(finalPath = SafetyCommon.nameFor(item.planned, item.nameAttempt)))
    val r = ops.publishTemp(item.share, temp, final, purpose)
    if (r is NasResult.Err) return stopFor(r.error)
    return when (val o = (r as NasResult.Ok).value) {
      is PublishOutcome.Published -> {
        val stamp = o.stamp ?: when (val st = rereader.stamp(item.share, final)) {
          is NasResult.Err -> return stopFor(st.error)
          is NasResult.Ok -> st.value ?: return Step.Wait
        }
        save(item.copy(state = UploadState.PUBLISHED, finalStamp = stamp))
      }
      PublishOutcome.Locked -> Step.Wait
      PublishOutcome.Collision -> when (val existing = rereader.reread(item.share, final, onBytes = { tally.bytesRead += it })) {
        Reread.Missing -> Step.Next(item)
        Reread.Busy -> Step.Wait
        is Reread.Fail -> stopFor(existing.error)
        is Reread.Ok -> if (existing.sha == item.sha256 && existing.stamp.size == item.size) {
          // Lo stesso file c'era gia': si collega e il proprio temporaneo si toglie (prima, finche' il diario lo cita).
          removeTemp(item.share, temp)?.let { return it }
          val at = clock.nasNowMs() ?: return Step.Wait
          catalog.attachVerifiedCopies(listOf(VerifiedCopy(item.mediaId, item.versionId, item.share, final, existing.stamp, at)))
          save(item.copy(state = UploadState.LINKED_EXISTING, finalStamp = existing.stamp))
        } else {
          nextName(item)
        }
        // Un altro file, o uno che cambia mentre lo leggiamo: un altro nome. Mai una sostituzione.
        Reread.Changed, Reread.Different -> nextName(item)
      }
      // La risposta persa (D3): temporaneo assente + finale con i byte giusti = pubblicato.
      PublishOutcome.TempMissing -> when (val existing = rereader.reread(item.share, final, onBytes = { tally.bytesRead += it })) {
        Reread.Busy -> Step.Wait
        is Reread.Fail -> stopFor(existing.error)
        is Reread.Ok -> if (existing.sha == item.sha256 && existing.stamp.size == item.size) {
          save(item.copy(state = UploadState.PUBLISHED, finalStamp = existing.stamp))
        } else {
          save(item.copy(state = UploadState.COPYING, resumeAt = 0))
        }
        Reread.Missing, Reread.Changed, Reread.Different -> save(item.copy(state = UploadState.COPYING, resumeAt = 0))
      }
    }
  }

  private suspend fun nextName(item: BackupItem): Step {
    val attempt = item.nameAttempt + 1
    return save(item.copy(nameAttempt = attempt, finalPath = SafetyCommon.nameFor(item.planned, attempt)))
  }

  // --- PUBLISHED: timbro del finale, poi il catalogo -----------------------------------------------------------------------------

  private suspend fun link(item: BackupItem, tally: Tally): Step {
    val final = item.finalPath ?: return Step.Wait
    var stamp = when (val st = rereader.stamp(item.share, final)) {
      is NasResult.Err -> return stopFor(st.error)
      // Sparito fra la pubblicazione e il collegamento (il PC, o spostato da una versione nuova: F2): si cerca, altrimenti si rifa'.
      is NasResult.Ok -> st.value ?: return relinkOrRedo(item, tally, nextName = false)
    }
    if (item.tempStamp?.let { stamp.sameAs(it) } != true) {
      // Toccato dopo la rinomina: si rilegge tutto; byte diversi = non sono i nostri: si cerca la copia, altrimenti si rifa' con un altro nome.
      when (val read = rereader.reread(item.share, final, onBytes = { tally.bytesRead += it })) {
        Reread.Missing -> return relinkOrRedo(item, tally, nextName = false)
        Reread.Busy -> return Step.Wait
        is Reread.Fail -> return stopFor(read.error)
        is Reread.Ok -> if (read.sha == item.sha256 && read.stamp.size == item.size) {
          stamp = read.stamp
        } else {
          return relinkOrRedo(item, tally, nextName = true)
        }
        Reread.Changed, Reread.Different -> return Step.Wait
      }
    }
    val at = clock.nasNowMs() ?: return Step.Wait
    catalog.attachVerifiedCopies(listOf(VerifiedCopy(item.mediaId, item.versionId, item.share, final, stamp, at)))
    return save(item.copy(state = UploadState.LINKED, finalStamp = stamp))
  }

  /**
   * F2 della prova della 5B (`docs/prova-500.md` §C6): al nome finale la copia pubblicata non c'e' piu', o ci sono altri byte. Prima di rifarla si
   * guarda se e' stata **spostata**: l'elemento puo' averla gia' attaccata al catalogo e il processo essere morto prima di `LINKED`, e nel frattempo
   * qualcuno l'ha spostata seguita dal catalogo (una versione nuova del media la archivia in `Versioni/`). Come nel piano (D8) si cercano nell'area
   * privata le copie con la stessa dimensione, **rilette qui per intero**: una con i byte della versione e' la copia di questo elemento, si collega
   * li' (il nome finale diventa il suo) e niente si rifa', nessun doppione. Altrimenti si rifa' la copia come prima: [nextName] se il nome finale e'
   * occupato da altri byte (mai sostituiti).
   */
  private suspend fun relinkOrRedo(item: BackupItem, tally: Tally, nextName: Boolean): Step {
    val sha = item.sha256
    if (sha != null) {
      when (
        val found = PrivateCopyFinder.findIdentical(
          catalog, rereader, item.share, item.size, item.quickHash, sha, DEDUP_LIMIT,
        ) { tally.bytesRead += it }
      ) {
        is PrivateCopyFinder.Identical.Stop -> return stopFor(found.error)
        is PrivateCopyFinder.Identical.Copy -> {
          val at = clock.nasNowMs() ?: return Step.Wait
          catalog.attachVerifiedCopies(listOf(VerifiedCopy(item.mediaId, item.versionId, found.copy.share, found.copy.path, found.stamp, at)))
          return save(item.copy(state = UploadState.LINKED, finalPath = found.copy.path, finalStamp = found.stamp))
        }
        PrivateCopyFinder.Identical.None -> Unit
      }
    }
    if (!nextName) return save(item.copy(state = UploadState.COPYING, resumeAt = 0))
    val attempt = item.nameAttempt + 1
    return save(
      item.copy(state = UploadState.COPYING, resumeAt = 0, nameAttempt = attempt, finalPath = SafetyCommon.nameFor(item.planned, attempt)),
    )
  }

  companion object {
    /** D9: 20 GB per giro. */
    const val DEFAULT_CAP_BYTES: Long = 20_000_000_000L

    /** Scritture da 1 MiB nel ripiego (§2.2). */
    const val DEFAULT_CHUNK_BYTES: Int = 1 shl 20

    /** Oltre 1 GB si controlla lo spazio prima di copiare (§2.2, E-R9). */
    const val SPACE_CHECK_BYTES: Long = 1_000_000_000L

    const val SPACE_MARGIN_BYTES: Long = 1L shl 30

    /** La cartella delle copie private dei media dell'area comune (D9). */
    const val BACKUP_FOLDER: String = "Backup"
    const val COMMON_FOLDER: String = "Area comune"
    const val NO_DATE_FOLDER: String = "Senza data"

    private const val TAG = "BackupGuardian"
    private const val MIN_YEAR = 1990
    private const val PLAN_PAGE = 100
    private const val RESUME_SCAN = 100_000
    private const val DEDUP_LIMIT = 20

    /** Un elemento che gira a vuoto (temporaneo che ricompare, nome sempre occupato) aspetta il prossimo giro. */
    private const val MAX_STEPS_PER_ITEM = 64
  }
}
