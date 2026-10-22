package dev.pampa.mycloudgallery.core.backup.corrections

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionFailure
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionOutcome
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionPlan
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRequest
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRoute
import dev.pampa.mycloudgallery.core.backup.contract.Corrections
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationCheck
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewrite
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewriter
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.upload.PhoneReading
import dev.pampa.mycloudgallery.core.backup.versions.FileStagedBytes
import dev.pampa.mycloudgallery.core.backup.versions.NasVersionEngine
import dev.pampa.mycloudgallery.core.backup.versions.StagedVersionFiles
import dev.pampa.mycloudgallery.core.backup.versions.VersionCatalog
import dev.pampa.mycloudgallery.core.backup.versions.VersionFacts
import dev.pampa.mycloudgallery.core.backup.versions.VersionJournal
import dev.pampa.mycloudgallery.core.backup.versions.VersionPlanning
import dev.pampa.mycloudgallery.core.backup.versions.VersionedMedia
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataWriter
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import dev.pampa.mycloudgallery.core.metadata.contract.WriteOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.WriteUnsupported
import dev.pampa.mycloudgallery.core.metadata.write.WrittenCheck
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Le correzioni di data, luogo e rotazione dall'app (Fase 5B, P8; docs/fase5-progetto.md §2.4 "Correzioni dall'app", D19, E-L8, E-E8,
 * E-E9, Q6 (a), L40; contratto [Corrections] di P0-B). Il risultato e' una **versione nuova** (`VersionKind.CORRECTED`) scritta nel file,
 * mai una sostituzione: l'originale resta al sicuro in `Versioni/` (D17).
 *
 * La sequenza di [apply] (ogni passo solo se il precedente e' andato bene; **niente si tocca sul telefono ne' sul NAS finche' la copia
 * corretta non e' stata riletta e decodificata**):
 *  1. il piano si ricalcola: se non e' quello confermato (il file e' cambiato dopo il foglio), [CorrectionOutcome.PlanChanged]; poi la
 *     conferma (contro il piano di adesso) e, per un media del telefono, il permesso del dialogo di sistema per le sue righe;
 *  2. i byte dell'attuale in un file locale, letti dal telefono (o dal NAS per un solo-NAS) con lo **SHA-256 della versione**: un media del
 *     telefono si corregge solo se e' **al sicuro** (la versione ha una copia privata verificata: l'originale e' sul NAS, D19);
 *  3. la copia corretta con lo scrittore ([writer]: JPEG, PNG, WebP), **riletta con l'estrattore e decodificata** ([check]): una copia
 *     rovinata si scarta e l'originale resta intatto ([CorrectionFailure.RESULT_NOT_VALID]);
 *  4. il catalogo: la versione nuova con l'impronta dei byte corretti, **prima** che la lettura del telefono veda il cambio (F4-D20: la
 *     riconoscera' come ritorno a una versione nota, non come una modifica sconosciuta);
 *  5. il lavoro `VERSION` dal file preparato (la versione andra' al solito percorso, l'attuale in `Versioni/`), scritto **prima** di
 *     toccare il telefono: se il processo muore adesso, la versione arriva comunque sul NAS;
 *  6. la riga del telefono riscritta con la copia corretta (`PhoneRewriter`: scrive, rilegge, ripristina se non torna);
 *  7. il lavoro va avanti subito; quello che non finisce lo riprende il backup.
 *
 * Q6 (a): HEIC, RAW e video non si scrivono nel file: la correzione resta **nell'app e nell'indice** ([VersionCatalog.correctInApp]) e il file
 * non si tocca ([CorrectionOutcome.FormatNotWritable]). Un media dell'area comune o di un'area in sola lettura (D35) non si corregge da qui.
 */
class DefaultCorrections(
  private val share: String,
  private val catalog: VersionCatalog,
  private val engine: NasVersionEngine,
  private val journal: VersionJournal,
  private val gate: BackupGate,
  private val clock: BackupClock,
  private val phone: PhoneBytes,
  private val reader: NasMediaReader,
  private val writer: MetadataWriter,
  private val check: WrittenFileCheck,
  private val rewriter: PhoneRewriter,
  private val staged: StagedVersionFiles,
  /** Dove gira lo scrittore (bloccante: file locali). */
  private val io: CoroutineContext = Dispatchers.IO,
  /** Quanto si porta avanti il lavoro `VERSION` dentro la chiamata (il resto lo fa il backup). */
  private val budget: Duration = 2.minutes,
  private val purpose: Purpose = Purpose.INTERACTIVE,
) : Corrections {

  override suspend fun plan(mediaId: MediaId, change: CorrectionRequest): CorrectionPlan {
    val media = catalog.media(mediaId)
      ?: return CorrectionPlan(mediaId, change, null, CorrectionRoute.NAS_ONLY, CorrectionReadiness.NOT_AVAILABLE, emptyList())
    val current = media.current
    val rows = phoneRows(media)
    val route = if (rows.isNotEmpty()) CorrectionRoute.PHONE else CorrectionRoute.NAS_ONLY
    return CorrectionPlan(mediaId, change, current.versionId, route, readiness(media, route, change), if (route == CorrectionRoute.PHONE) rows else emptyList())
  }

  private suspend fun readiness(media: VersionedMedia, route: CorrectionRoute, change: CorrectionRequest): CorrectionReadiness {
    if (!media.mine) return CorrectionReadiness.NOT_MINE
    val mime = media.mime
    // Q6 (a): solo JPEG, PNG e WebP si scrivono nel file; un video o un tipo ignoto mai. VE-4: la rotazione di un formato che non si scrive non
    // si fa nemmeno "nell'app": miniature e viewer leggono l'orientamento dai byte del file e la foto resterebbe storta dopo un "fatto".
    if (media.kind.isVideo || mime == null || !writer.canWrite(mime)) {
      return if (change.change.orientation != null) CorrectionReadiness.ROTATION_NOT_WRITABLE else CorrectionReadiness.FORMAT_NOT_WRITABLE
    }
    val current = media.current
    val ownCopies = current.nas.filter { VersionPlanning.isOwnPrivate(it, share) }
    // D35: un media che sta solo nell'area comune (o di un'area senza copia propria) vuole il foglio "la vedranno tutti".
    if (ownCopies.isEmpty() && media.versions.any { v -> v.nas.any { it.present && it.area == NasArea.COMMON } }) {
      return CorrectionReadiness.COMMON_AREA_NOTICE
    }
    when (gate.stateOf(share)) {
      BackupGateState.OPEN -> Unit
      BackupGateState.FROZEN, BackupGateState.WRITES_STOPPED -> return CorrectionReadiness.FROZEN
      BackupGateState.READ_ONLY_AREA -> return CorrectionReadiness.READ_ONLY_AREA
      // VE-7: la pausa e il backup in prova hanno la loro frase (prima tutto era "non e' ancora pronta").
      BackupGateState.PAUSED -> return CorrectionReadiness.PAUSED
      BackupGateState.TEST_ONLY -> return CorrectionReadiness.TEST_ONLY
      else -> return CorrectionReadiness.NOT_AVAILABLE
    }
    // D19: prima al sicuro (l'originale sul NAS, riletto qui): la copia privata verificata della versione e il suo SHA-256.
    if (current.fullHash == null || ownCopies.none { it.verifiedAtMillis != null }) return CorrectionReadiness.NEEDS_SAFE_FIRST
    // Campo, gruppo A (D3): una riga del telefono che tiene un'altra versione (piu' vecchia: modificata dal PC, E-E6) si riscrive con la correzione
    // solo se anche quella versione e' al sicuro (hash noto e una copia privata verificata, anche in `Versioni/`): mai l'unica copia di qualcosa.
    if (media.versions.any { v -> v.versionId != current.versionId && v.phone.any { it.present } && !isSafe(v) }) return CorrectionReadiness.NEEDS_SAFE_FIRST
    if (usualCopy(current) == null) return CorrectionReadiness.NOT_AVAILABLE
    // Un'altra versione dello stesso media in corso: prima finisce quella (VE-7: lo dice).
    if (journal.forMedia(media.mediaId).any { !it.state.terminal }) return CorrectionReadiness.BUSY
    return if (route == CorrectionRoute.PHONE || ownCopies.isNotEmpty()) CorrectionReadiness.READY else CorrectionReadiness.NOT_AVAILABLE
  }

  /**
   * Campo, gruppo A (D3, F36): le righe del telefono del media, di **ogni** versione, quelle dell'attuale per prime. Prima contavano solo quelle
   * dell'attuale: un media il cui telefono tiene la versione di prima (l'attuale e' una modifica fatta dal PC, o le copie identiche dei fixture)
   * passava per "solo sul NAS", la correzione andava solo sul NAS senza il dialogo di Android e il telefono restava com'era.
   */
  private fun phoneRows(media: VersionedMedia): List<PhoneRef> {
    val current = media.current
    val others = media.versions.filter { it.versionId != current.versionId }
    return (current.phone.filter { it.present } + others.flatMap { v -> v.phone.filter { it.present } }).map { it.ref }.distinct()
  }

  /** La versione ha l'hash e una copia privata propria verificata e presente (anche in `Versioni/`). */
  private fun isSafe(version: VersionFacts): Boolean =
    version.fullHash != null && version.nas.any { VersionPlanning.isOwnPrivate(it, share) && it.present && it.verifiedAtMillis != null }

  /** Lo SHA-256 dei byte che la riga [ref] deve avere adesso (quelli della sua versione), null se la riga non e' del media. */
  private fun heldBy(media: VersionedMedia, ref: PhoneRef): Sha256? =
    media.versions.firstOrNull { v -> v.phone.any { it.present && it.ref == ref } }?.fullHash

  private fun usualCopy(version: VersionFacts) =
    version.nas.firstOrNull { VersionPlanning.isOwnPrivate(it, share) && !VersionPaths.isInVersions(it.path) && it.verifiedAtMillis != null }

  override suspend fun apply(plan: CorrectionPlan, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): CorrectionOutcome {
    val fresh = when (val prepared = prepare(plan, confirmation, writeGrant)) {
      is Prepared.Stop -> return prepared.outcome
      is Prepared.InApp -> return inAppOnly(prepared.plan, confirmation)
      is Prepared.Go -> prepared.plan
    }
    val media = catalog.media(fresh.mediaId) ?: return CorrectionOutcome.PlanChanged
    val current = media.current
    val expected = current.fullHash ?: return CorrectionOutcome.NeedsSafeFirst
    val mime = media.mime ?: return CorrectionOutcome.FormatNotWritable
    val original = staged.newWorkFile()
    var corrected: File? = null
    try {
      // 2. I byte dell'attuale, con lo SHA-256 della versione: dal telefono se il telefono ha l'attuale, altrimenti dalla copia privata verificata
      //    (campo, gruppo A, D3: il telefono puo' tenere solo una versione di prima, che si riscrive al passo 6).
      val phoneCurrent = current.phone.firstOrNull { it.present && it.ref in fresh.phoneRows }
      val copied = if (fresh.route == CorrectionRoute.PHONE && phoneCurrent != null) {
        copyFromPhone(current, phoneCurrent.ref, original)
      } else {
        copyFromNas(current, original)
      }
      when (copied) {
        is Copied.Ok -> if (copied.sha256 != expected) return CorrectionOutcome.PlanChanged
        Copied.Changed -> return CorrectionOutcome.PlanChanged
        Copied.Unreadable -> return CorrectionOutcome.Failed(CorrectionFailure.SOURCE_UNREADABLE)
        Copied.Full -> return CorrectionOutcome.Failed(CorrectionFailure.PHONE_FULL)
      }
      // 3. La copia corretta, riletta e decodificata.
      val out = staged.newWorkFile().also { corrected = it }
      when (val w = withContext(io) { writer.apply(original, out, fresh.request.change) }) {
        is WriteOutcome.Written -> Unit
        is WriteOutcome.Unsupported -> return if (w.reason == WriteUnsupported.FORMAT) inAppOnly(fresh, confirmation) else CorrectionOutcome.Unavailable(CorrectionReadiness.NOT_AVAILABLE)
        is WriteOutcome.Failed -> return CorrectionOutcome.Failed(
          when (w.reason) {
            WriteFailure.SOURCE_UNREADABLE -> CorrectionFailure.SOURCE_UNREADABLE
            WriteFailure.IO, WriteFailure.DESTINATION_EXISTS -> CorrectionFailure.PHONE_FULL
          },
        )
      }
      val verdict = withContext(io) { this@DefaultCorrections.check.check(out, mime, fresh.request.change) }
      if (verdict != WrittenCheck.VALID) {
        GalleryLog.w(TAG) { "copia corretta scartata alla rilettura (${verdict.code}): l'originale resta com'era" }
        return CorrectionOutcome.Failed(CorrectionFailure.RESULT_NOT_VALID)
      }
      val facts = hashOf(out) ?: return CorrectionOutcome.Failed(CorrectionFailure.RESULT_NOT_VALID)
      val kept = try {
        staged.keep(out, facts.sha256, fresh.mediaId)
      } catch (_: IOException) {
        return CorrectionOutcome.Failed(CorrectionFailure.PHONE_FULL)
      }
      corrected = null
      // 4. La versione nuova nel catalogo, prima che il telefono cambi (F4-D20).
      val newVersion = catalog.correct(fresh.mediaId, fresh.request.fields, facts.size, facts.quickHash, facts.sha256)
      if (newVersion == null) {
        staged.discard(facts.sha256)
        return CorrectionOutcome.PlanChanged
      }
      // Campo, gruppo A (D3): i metadati della versione nuova dai byte corretti, subito (la Libreria mostra la data nuova).
      try {
        catalog.readCorrected(fresh.mediaId, newVersion, kept, mime)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "metadati della versione corretta non letti (${e.javaClass.simpleName}): li rilegge la lettura dopo" }
      }
      // 5. Il lavoro VERSION, prima di toccare il telefono.
      val job = engine.planStaged(fresh.mediaId, newVersion, facts.sha256, facts.size)
      if (job == null) GalleryLog.w(TAG) { "versione corretta senza solito percorso: resta nell'app finche' il backup non la porta" }
      // 6. Il telefono, con il permesso del dialogo di sistema.
      var outcome: CorrectionOutcome = CorrectionOutcome.Done(newVersion)
      if (fresh.route == CorrectionRoute.PHONE) {
        for (ref in fresh.phoneRows) {
          // R3: ogni riga (non solo la prima, gia' riletta) deve avere ancora i byte della versione al sicuro che tiene: [expected] per l'attuale,
          // quelli della sua versione per una riga che tiene una versione di prima (D3; la prontezza ha gia' voluto anche quella al sicuro).
          val held = heldBy(media, ref) ?: expected
          val failure = rewrite(ref, mime, kept, facts.sha256, held) ?: continue
          outcome = CorrectionOutcome.Failed(failure)
          break
        }
      }
      // 7. Il lavoro va avanti adesso; il resto lo fa il backup.
      job?.let { engine.runJob(it.id, budget) }
      return outcome
    } finally {
      original.delete()
      corrected?.delete()
    }
  }

  /** VE-8: piano, prontezza, conferma e permesso del telefono, senza toccare niente; null = si procede (con il turno del backup). */
  override suspend fun precheck(plan: CorrectionPlan, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): CorrectionOutcome? =
    (prepare(plan, confirmation, writeGrant) as? Prepared.Stop)?.outcome

  private sealed interface Prepared {
    class Stop(val outcome: CorrectionOutcome) : Prepared

    /** Q6 (a): la correzione resta nell'app (il catalogo, con la conferma): nel turno del backup come il resto. */
    class InApp(val plan: CorrectionPlan) : Prepared

    class Go(val plan: CorrectionPlan) : Prepared
  }

  /** La parte di [apply] che non tocca niente (passo 1): il piano ricalcolato e confrontato, la prontezza, la conferma, il permesso. */
  private suspend fun prepare(plan: CorrectionPlan, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): Prepared {
    val fresh = plan(plan.mediaId, plan.request)
    if (fresh.hash != plan.hash) return Prepared.Stop(CorrectionOutcome.PlanChanged)
    when (fresh.readiness) {
      CorrectionReadiness.READY -> Unit
      CorrectionReadiness.NEEDS_SAFE_FIRST -> return Prepared.Stop(CorrectionOutcome.NeedsSafeFirst)
      CorrectionReadiness.FORMAT_NOT_WRITABLE -> return Prepared.InApp(fresh)
      else -> return Prepared.Stop(CorrectionOutcome.Unavailable(fresh.readiness))
    }
    val check = PhoneConfirmationRules.check(fresh, confirmation, clock.monotonicMs())
    if (check != PhoneConfirmationCheck.Valid) return Prepared.Stop(CorrectionOutcome.Rejected(check))
    if (fresh.route == CorrectionRoute.PHONE && (writeGrant == null || !writeGrant.coversAll(fresh.phoneRows))) {
      return Prepared.Stop(CorrectionOutcome.NeedsWriteGrant(fresh.phoneRows))
    }
    return Prepared.Go(fresh)
  }

  /** Q6 (a): la correzione resta nell'app e nell'indice, il file non si tocca. Anche questa vuole la conferma dell'utente. */
  private suspend fun inAppOnly(plan: CorrectionPlan, confirmation: PhoneConfirmation): CorrectionOutcome {
    // VE-4: una rotazione solo nell'app non la mostra nessuno: non si registra e non si dice "fatto".
    if (plan.request.change.orientation != null) return CorrectionOutcome.Unavailable(CorrectionReadiness.ROTATION_NOT_WRITABLE)
    val check = PhoneConfirmationRules.check(plan, confirmation, clock.monotonicMs())
    if (check != PhoneConfirmationCheck.Valid) return CorrectionOutcome.Rejected(check)
    val base = plan.baseVersion ?: return CorrectionOutcome.Unavailable(CorrectionReadiness.NOT_AVAILABLE)
    if (!catalog.correctInApp(plan.mediaId, base, plan.request.change)) return CorrectionOutcome.Unavailable(CorrectionReadiness.NOT_AVAILABLE)
    return CorrectionOutcome.FormatNotWritable
  }

  /**
   * Una riga del telefono riscritta con la copia corretta, solo se ha ancora i byte [current] della versione al sicuro (R3): null = fatto (o la
   * riga non c'e' piu'), altrimenti il guasto.
   */
  private suspend fun rewrite(ref: PhoneRef, mime: String, file: File, sha256: Sha256, current: Sha256): CorrectionFailure? =
    when (val r = rewriter.replace(ref, mime, file, sha256, current)) {
      is PhoneRewrite.Replaced -> null
      PhoneRewrite.Gone -> null
      PhoneRewrite.RowChanged -> CorrectionFailure.PHONE_CHANGED
      PhoneRewrite.NotGranted, PhoneRewrite.SourceMismatch -> CorrectionFailure.PHONE_WRITE_FAILED
      is PhoneRewrite.Failed -> {
        GalleryLog.w(TAG) { "riscrittura del telefono non riuscita (${r.code}, ripristinata ${r.restored})" }
        if (r.restored) CorrectionFailure.PHONE_WRITE_FAILED else CorrectionFailure.PHONE_NOT_RESTORED
      }
    }

  private sealed interface Copied {
    class Ok(val sha256: Sha256) : Copied

    /** Il file non e' piu' quello del piano (cambiato, sparito, rinumerato): si ricalcola. */
    data object Changed : Copied

    data object Unreadable : Copied

    data object Full : Copied
  }

  /** I byte della riga del telefono in [target] (foto con `setRequireOriginal`, dalla porta), con il timbro che il catalogo conosce. */
  private suspend fun copyFromPhone(current: VersionFacts, ref: PhoneRef, target: File): Copied {
    val known = current.phone.firstOrNull { it.ref == ref } ?: return Copied.Changed
    val file = when (val opened = phone.open(ref)) {
      PhoneOpen.Gone, PhoneOpen.Stale -> return Copied.Changed
      is PhoneOpen.Denied -> return Copied.Unreadable
      is PhoneOpen.Opened -> opened.file
    }
    try {
      if (file.stamp != known.stamp || file.bytes.size != current.size) return Copied.Changed
      val sha = copyBytes(file.bytes, current.size, target) ?: return Copied.Unreadable
      return Copied.Ok(sha)
    } catch (_: IOException) {
      return if (target.usableSpace in 0 until current.size) Copied.Full else Copied.Unreadable
    } finally {
      file.close()
    }
  }

  /** Copia [size] byte in [target] a blocchi calcolando lo SHA-256; null se la lettura si accorcia. */
  private suspend fun copyBytes(bytes: MediaBytes, size: Long, target: File): Sha256? {
    val digest = MessageDigest.getInstance("SHA-256")
    target.outputStream().use { out ->
      var at = 0L
      while (at < size) {
        val n = minOf(CHUNK.toLong(), size - at)
        val chunk = bytes.read(at until at + n)
        if (chunk.isEmpty()) return null
        digest.update(chunk)
        out.write(chunk)
        at += chunk.size
      }
    }
    return Sha256(PhoneReading.hex(digest.digest()))
  }

  /** I byte della copia privata verificata della versione in [target] (un solo-NAS: l'originale letto in cache con l'hash). */
  private suspend fun copyFromNas(current: VersionFacts, target: File): Copied {
    val copy = usualCopy(current) ?: return Copied.Changed
    val file = when (val opened = reader.open(copy.share, copy.path, purpose)) {
      is NasResult.Err -> return Copied.Unreadable
      is NasResult.Ok -> opened.value
    }
    return try {
      file.use { f ->
        if (f.stamp.size != current.size) return@use Copied.Changed
        target.outputStream().use { out ->
          when (val r = reader.readAll(f, { buf, n -> out.write(buf, 0, n) }, null)) {
            is NasResult.Ok -> Copied.Ok(r.value)
            is NasResult.Err -> Copied.Unreadable
          }
        }
      }
    } catch (_: IOException) {
      Copied.Unreadable
    }
  }

  private class Facts(val sha256: Sha256, val quickHash: dev.pampa.mycloudgallery.core.model.QuickHash, val size: Long)

  /** SHA-256 e `qh1` della copia corretta (la sua impronta nel catalogo). */
  private suspend fun hashOf(file: File): Facts? = try {
    FileStagedBytes(file).use { bytes ->
      PhoneReading.read(bytes, bytes.size, CHUNK, keepInMemory = false)?.let { Facts(it.sha256, it.quickHash, bytes.size) }
    }
  } catch (_: IOException) {
    null
  }

  private companion object {
    const val TAG = "BackupCorrections"
    const val CHUNK = 1 shl 20
  }
}
