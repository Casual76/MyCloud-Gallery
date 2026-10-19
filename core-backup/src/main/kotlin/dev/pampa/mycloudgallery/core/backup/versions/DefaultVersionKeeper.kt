package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationCheck
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewrite
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewriter
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RestoreOutcome
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.VersionArea
import dev.pampa.mycloudgallery.core.backup.contract.VersionEntry
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.core.backup.contract.VersionPlace
import dev.pampa.mycloudgallery.core.backup.contract.VersionRole
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException

/**
 * Le versioni di un media e "Ripristina originale" (D17, D18, D35, E-E2, E-E10; contratto [VersionKeeper] di P0-B).
 *
 * - [plan] dice, dal **catalogo locale**, le versioni dall'originale all'attuale, dove stanno (telefono, solito percorso, `Versioni/`, solo
 *   area comune), se sono al sicuro (una copia privata verificata) e se "Ripristina originale" si puo' fare adesso.
 * - [restoreOriginal] controlla la conferma contro il piano **ricalcolato adesso**, poi scrive nel diario lo **scambio** (`SWAP`: l'attuale
 *   in `Versioni/`, l'originale al solito percorso, nessun byte caricato) e lo porta avanti subito ([engine]); se il lavoro non finisce
 *   adesso (NAS lento, giro fermato), il backup lo riprende. Se la copia del telefono e' la versione modificata (E-E10) serve il permesso
 *   del dialogo di sistema: senza ([writeGrant] null) la risposta e' [RestoreOutcome.NeedsPhoneWrite] e niente si e' toccato; con un
 *   permesso che **non copre** le righe (l'utente ha detto no al dialogo) lo scambio sul NAS si fa lo stesso e la copia del telefono resta
 *   la versione modificata (il piano lo mostra: la versione sta sul telefono). Con il permesso, a scambio fatto, la riga del telefono si
 *   riscrive con l'originale **riletto dal NAS e verificato** con il suo SHA-256 (D19: solo con il dialogo di sistema e l'originale al sicuro).
 *
 * Non toglie niente: le versioni intermedie si eliminano solo dal foglio di `feature-nas` (cestino del NAS, D20).
 */
class DefaultVersionKeeper(
  private val share: String,
  private val catalog: VersionCatalog,
  private val journal: VersionJournal,
  private val engine: NasVersionEngine,
  private val gate: BackupGate,
  private val clock: BackupClock,
  private val reader: NasMediaReader,
  private val rewriter: PhoneRewriter,
  private val staged: StagedVersionFiles,
  /** Quanto si porta avanti lo scambio dentro la chiamata (il resto lo fa il backup). */
  private val budget: Duration = 2.minutes,
  private val purpose: Purpose = Purpose.INTERACTIVE,
) : VersionKeeper {

  override suspend fun plan(mediaId: MediaId): NasVersionPlan {
    val media = catalog.media(mediaId)
      ?: return NasVersionPlan(mediaId, emptyList(), VersionArea.OWN_PRIVATE, RestoreAvailability.ONLY_ONE_VERSION)
    val versions = media.versions.map { v -> entryOf(media, v) }
    return NasVersionPlan(mediaId, versions, areaOf(media), availability(media))
  }

  private fun entryOf(media: VersionedMedia, v: VersionFacts): VersionEntry {
    val places = LinkedHashSet<VersionPlace>()
    if (v.phone.any { it.present }) places += VersionPlace.PHONE
    val own = v.nas.filter { VersionPlanning.isOwnPrivate(it, share) }
    if (own.any { !VersionPaths.isInVersions(it.path) }) places += VersionPlace.NAS_USUAL_PATH
    if (own.any { VersionPaths.isInVersions(it.path) }) places += VersionPlace.NAS_VERSIONS_FOLDER
    if (own.isEmpty() && v.nas.any { it.present && it.area == NasArea.COMMON }) places += VersionPlace.NAS_COMMON_ONLY
    return VersionEntry(
      versionId = v.versionId,
      role = if (v.kind == VersionKind.ORIGINAL) VersionRole.ORIGINAL else VersionRole.EDITED,
      isCurrent = v.versionId == media.currentVersionId,
      size = v.size,
      savedAtNasMillis = own.minOfOrNull { it.stamp.modifiedAtMillis },
      places = places,
      safe = own.any { it.verifiedAtMillis != null },
    )
  }

  /** D35: dove sta il solito percorso del media (area privata propria, area comune, o nessuna copia propria). */
  private fun areaOf(media: VersionedMedia): VersionArea {
    val nas = media.versions.flatMap { it.nas }.filter { it.present }
    return when {
      nas.any { VersionPlanning.isOwnPrivate(it, share) } -> when (gate.stateOf(share)) {
        BackupGateState.READ_ONLY_AREA -> VersionArea.READ_ONLY_NEW_MEDIA
        else -> VersionArea.OWN_PRIVATE
      }
      nas.any { it.area == NasArea.COMMON } -> VersionArea.COMMON_NEEDS_NOTICE
      else -> VersionArea.OWN_PRIVATE
    }
  }

  private suspend fun availability(media: VersionedMedia): RestoreAvailability {
    if (!media.mine) return RestoreAvailability.NOT_MINE
    if (media.versions.size < 2) return RestoreAvailability.ONLY_ONE_VERSION
    val original = media.original ?: return RestoreAvailability.NOT_AVAILABLE
    if (media.currentVersionId == original.versionId) return RestoreAvailability.ALREADY_ORIGINAL
    when (gate.stateOf(share)) {
      BackupGateState.OPEN -> Unit
      BackupGateState.FROZEN, BackupGateState.WRITES_STOPPED -> return RestoreAvailability.FROZEN
      BackupGateState.READ_ONLY_AREA -> return RestoreAvailability.READ_ONLY_AREA
      else -> return RestoreAvailability.NOT_AVAILABLE
    }
    val originalSafe = original.nas.any { VersionPlanning.isOwnPrivate(it, share) && VersionPaths.isInVersions(it.path) && it.verifiedAtMillis != null }
    if (!originalSafe) return RestoreAvailability.ORIGINAL_NOT_SAFE
    if (VersionPlanning.swap(media, share) == null) return RestoreAvailability.NOT_AVAILABLE
    // Un'altra versione o un altro scambio di questo media gia' in corso: si aspetta che finisca.
    if (journal.forMedia(media.mediaId).any { !it.state.terminal }) return RestoreAvailability.NOT_AVAILABLE
    return RestoreAvailability.AVAILABLE
  }

  override suspend fun restoreOriginal(mediaId: MediaId, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): RestoreOutcome {
    val plan = plan(mediaId)
    if (plan.restore != RestoreAvailability.AVAILABLE) return RestoreOutcome.Unavailable(plan.restore)
    val check = PhoneConfirmationRules.check(plan, confirmation, clock.monotonicMs())
    if (check != PhoneConfirmationCheck.Valid) return RestoreOutcome.Rejected(check)
    val media = catalog.media(mediaId) ?: return RestoreOutcome.Unavailable(RestoreAvailability.NOT_AVAILABLE)
    // E-E10: le righe del telefono che hanno una versione diversa dall'originale (la modifica fatta sul telefono).
    val rows = phoneRowsToRewrite(media)
    if (rows.isNotEmpty() && writeGrant == null) return RestoreOutcome.NeedsPhoneWrite(rows)
    val job = engine.planSwap(mediaId) ?: return RestoreOutcome.Unavailable(RestoreAvailability.NOT_AVAILABLE)
    val after = engine.runJob(job.id, budget)
    if (after?.state == UploadState.DONE && rows.isNotEmpty() && writeGrant != null && writeGrant.coversAll(rows)) {
      rewritePhone(mediaId, rows)
    }
    return RestoreOutcome.Queued
  }

  private fun phoneRowsToRewrite(media: VersionedMedia): List<PhoneRef> {
    val original = media.original ?: return emptyList()
    return media.versions.filter { it.versionId != original.versionId }.flatMap { v -> v.phone.filter { it.present }.map { it.ref } }.distinct()
  }

  /**
   * E-E10: l'originale, ora al solito percorso, si **rilegge dal NAS** in un file locale e si confronta con il suo SHA-256 (quello della
   * versione nel catalogo); solo allora si riscrivono le righe (il riscrittore ricontrolla l'impronta, scrive, rilegge, ripristina se non
   * torna). Un guasto lascia il telefono com'era: la versione modificata resta li', il piano lo dice.
   */
  private suspend fun rewritePhone(mediaId: MediaId, rows: List<PhoneRef>) {
    val media = catalog.media(mediaId) ?: return
    val original = media.original ?: return
    val expected = original.fullHash ?: return
    val copy = original.nas.firstOrNull { VersionPlanning.isOwnPrivate(it, share) && !VersionPaths.isInVersions(it.path) } ?: return
    val work = staged.newWorkFile()
    try {
      if (!readInto(copy.share, copy, work, expected)) return
      for (ref in rows) {
        when (val r = rewriter.replace(ref, media.mime, work, expected)) {
          is PhoneRewrite.Replaced -> Unit
          is PhoneRewrite.Failed -> GalleryLog.w(TAG) { "riscrittura del telefono non riuscita (${r.code}, ripristinata ${r.restored})" }
          else -> GalleryLog.w(TAG) { "riscrittura del telefono non fatta (${r::class.java.simpleName})" }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "originale non riscritto sul telefono (${e::class.java.simpleName})" }
    } finally {
      work.delete()
    }
  }

  /** Legge per intero la copia in [target] calcolando lo SHA-256: vero se e' [expected]. */
  private suspend fun readInto(share: String, copy: NasVersionCopy, target: java.io.File, expected: Sha256): Boolean {
    val file = when (val opened = reader.open(share, copy.path, purpose)) {
      is NasResult.Err -> return false
      is NasResult.Ok -> opened.value
    }
    return try {
      file.use { f ->
        target.outputStream().use { out ->
          val r = reader.readAll(f, { buf, n -> out.write(buf, 0, n) }, null)
          r is NasResult.Ok && r.value == expected && target.length() == copy.stamp.size
        }
      }
    } catch (_: IOException) {
      false
    }
  }

  private companion object {
    const val TAG = "BackupVersions"
  }
}
