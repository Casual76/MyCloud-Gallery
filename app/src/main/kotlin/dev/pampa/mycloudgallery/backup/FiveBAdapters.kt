package dev.pampa.mycloudgallery.backup

import android.content.ContentResolver
import android.content.IntentSender
import android.provider.Settings
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRowNow
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewrite
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewriter
import dev.pampa.mycloudgallery.core.backup.contract.TrashBatch
import dev.pampa.mycloudgallery.core.backup.contract.TrashMode
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.free.BootCounter
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceCatalog
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceCursor
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceMedia
import dev.pampa.mycloudgallery.core.backup.versions.NasVersionCopy
import dev.pampa.mycloudgallery.core.backup.versions.PhoneVersionCopy
import dev.pampa.mycloudgallery.core.backup.versions.VersionCatalog
import dev.pampa.mycloudgallery.core.backup.versions.VersionFacts
import dev.pampa.mycloudgallery.core.backup.versions.VersionedMedia
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.AttachedCopy
import dev.pampa.mycloudgallery.core.database.CatalogPhoneCursor
import dev.pampa.mycloudgallery.core.database.FullHashRecord
import dev.pampa.mycloudgallery.core.database.InAppCorrection
import dev.pampa.mycloudgallery.core.database.InAppGeo
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaRewriter
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteResult
import dev.pampa.mycloudgallery.core.metadata.contract.GeoChange
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.backup.free.PhoneSystemDialogs
import java.io.File

// Le PORTE della 5B sopra il catalogo, il telefono e i dialoghi di sistema (Fase 5B, P14; docs/fase5-progetto.md §1.4, §2.4, §2.5, D13, D19,
// D21): solo TRADUZIONI di tipi, campo per campo, piu' la recinzione di debug (D13) dove si scrive o si toglie una riga del telefono. Le
// decisioni restano nei servizi di core-backup (P8, P9). Nessun nome, percorso, share, account o id nei log: solo codici.

/**
 * [VersionCatalog] (P8) sul catalogo vero: `media` ← `MediaCatalog.media` + `versionLocations` (le aree delle copie sul NAS) + il MIME
 * dell'attuale; `relocate` ← `relocateKeepingVerification` (**la verifica resta**: una rinomina non cambia i byte, nota di P9);
 * `correct` ← `correct` senza posti; `correctInApp` ← la correzione solo nell'app (Q6 (a), sopravvive alla rilettura di META).
 */
class CatalogVersionCatalog(
  private val catalog: MediaCatalog,
  private val account: () -> AccountId?,
) : VersionCatalog {

  override suspend fun media(mediaId: MediaId): VersionedMedia? {
    val media = catalog.media(mediaId) ?: return null
    val areas = catalog.versionLocations(media.id).flatMap { it.locations }.associate { it.location.id to it.area }
    val me = account()
    val versions = media.versions.sortedWith(compareBy({ it.createdAtMillis }, { it.id.value })).map { v ->
      val located = media.locations.filter { it.versionId == v.id }
      VersionFacts(
        versionId = v.id,
        kind = v.kind,
        parentId = v.parentId,
        size = v.fingerprint.size,
        fullHash = v.fingerprint.fullHash,
        quickHash = v.fingerprint.quickHash,
        nas = located.mapNotNull { l ->
          val nas = l.location as? Location.Nas ?: return@mapNotNull null
          val path = runCatching { SharePath.parse(nas.path.value) }.getOrNull() ?: return@mapNotNull null
          NasVersionCopy(
            locationId = l.id,
            share = nas.share,
            path = path,
            stamp = NasStamp(l.stamp.size, l.stamp.modifiedAtMillis, nas.fileId),
            area = areas[l.id],
            verifiedAtMillis = l.verifiedAtMillis,
            present = l.state == LocationState.PRESENT,
          )
        },
        phone = located.mapNotNull { l ->
          val phone = l.location as? Location.Phone ?: return@mapNotNull null
          val id = phone.mediaStoreId ?: return@mapNotNull null
          val version = phone.mediaStoreVersion ?: return@mapNotNull null
          PhoneVersionCopy(l.id, PhoneRef(phone.volume, id, version), l.stamp, present = l.state == LocationState.PRESENT)
        },
      )
    }
    return VersionedMedia(
      mediaId = media.id,
      mine = me != null && media.owner == me,
      kind = media.kind,
      mime = catalog.versionMetadata(media.currentVersionId)?.mimeType,
      currentVersionId = media.currentVersionId,
      versions = versions,
    )
  }

  override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord =
    when (catalog.recordFullHash(mediaId, versionId, sha256)) {
      FullHashRecord.RECORDED -> HashRecord.RECORDED
      FullHashRecord.SAME -> HashRecord.SAME
      FullHashRecord.CONFLICT -> HashRecord.CONFLICT
      FullHashRecord.UNKNOWN_VERSION -> HashRecord.UNKNOWN_VERSION
    }

  override suspend fun attachVerifiedCopy(copy: VerifiedCopy): Boolean {
    val owner = account() ?: return false
    val path = runCatching { NasPath.of(copy.path.value) }.getOrNull() ?: return false
    return try {
      catalog.attachCopies(
        listOf(
          AttachedCopy(
            mediaId = copy.mediaId,
            versionId = copy.versionId,
            location = Location.Nas(owner, copy.share, path, copy.stamp.fileId),
            stamp = FileStamp(copy.stamp.size, copy.stamp.modifiedAtMillis, null),
            verifiedAtMillis = copy.verifiedAtMillis,
          ),
        ),
      ) == 1
    } catch (_: IllegalArgumentException) {
      GalleryLog.w(TAG) { "il catalogo ha rifiutato una copia verificata di una versione" }
      false
    }
  }

  override suspend fun relocate(locationId: LocationId, share: String, path: SharePath, stamp: NasStamp) {
    val owner = account() ?: return
    val nasPath = runCatching { NasPath.of(path.value) }.getOrNull() ?: return
    val moved = catalog.relocateKeepingVerification(
      locationId,
      Location.Nas(owner, share, nasPath, stamp.fileId),
      FileStamp(stamp.size, stamp.modifiedAtMillis, null),
    )
    if (!moved) GalleryLog.w(TAG) { "posizione di una versione non spostata nel catalogo: la scansione la mette in pari" }
  }

  override suspend fun restoreOriginal(mediaId: MediaId) {
    catalog.restoreOriginal(mediaId)
  }

  override suspend fun correct(mediaId: MediaId, fields: Set<CorrectionField>, size: Long, quickHash: QuickHash, sha256: Sha256): VersionId? =
    try {
      catalog.correct(mediaId, fields, Fingerprint(size, quickHash, sha256), emptyMap(), null)
    } catch (_: IllegalStateException) {
      // Il media non c'e' piu' (unito, tolto): nessuna versione.
      null
    } catch (_: IllegalArgumentException) {
      null
    }

  override suspend fun correctInApp(mediaId: MediaId, versionId: VersionId, change: MetadataChange): Boolean {
    val orientation = change.orientation
    val correction = InAppCorrection(
      takenAtUtcMillis = change.takenAtUtcMillis,
      offsetMinutes = change.offsetMinutes,
      geo = when (val geo = change.geo) {
        GeoChange.Keep -> InAppGeo.Keep
        GeoChange.Clear -> InAppGeo.Clear
        is GeoChange.Set -> InAppGeo.Set(geo.point.latitude, geo.point.longitude)
      },
      orientationDegrees = orientation?.degrees,
      mirrored = orientation?.mirrored,
    )
    return catalog.correctInApp(mediaId, versionId, correction)
  }

  override suspend fun markProblem(mediaId: MediaId, problem: BackupProblemCode?) {
    catalog.markBackupProblem(mediaId, problem?.code, problem?.marksMedia ?: true)
  }

  private companion object {
    const val TAG = "BackupVersioni"
  }
}

/**
 * [FreeSpaceCatalog] (P9) sul catalogo vero: le pagine dei media con una copia sul telefono (`phoneMedia`, dall'indice della timeline), il
 * grafo di ogni media con il giorno, l'ordine, il problema del backup e il MIME dell'attuale; `markVerified` com'e'; `freedFromPhone` segna
 * assenti le righe che Android ha tolto (la cella resta "solo NAS", E-P11). **L'unica fonte degli elenchi** (E-S11).
 *
 * Correzioni 5B: LS-12, `phoneRowOf` con la stessa ricetta del backup ([backup], `CatalogBackupCatalog.phoneRowOf`): una riga consegnata ad
 * Android e rinumerata dal MediaStore si risolve con la lettura del telefono. LS-15: le pagine portano gia' giorno e ordine, e per loro la riga
 * dell'indice non si rilegge (una query in meno per media, a ogni piano).
 */
class CatalogFreeSpaceCatalog(private val catalog: MediaCatalog, private val backup: BackupCatalog? = null) : FreeSpaceCatalog {

  override suspend fun phoneMedia(after: FreeSpaceCursor?, limit: Int): List<FreeSpaceMedia> {
    val page = catalog.phoneMedia(after?.let { CatalogPhoneCursor(it.sortTime, it.mediaId) }, limit)
    return page.mapNotNull { row -> freeMedia(row.mediaId, Known(row.localDay?.takeIf { it != 0 }, row.sortTime)) }
  }

  override suspend fun media(ids: Collection<MediaId>): List<FreeSpaceMedia> = ids.distinct().mapNotNull { freeMedia(it, null) }

  override suspend fun phoneRowOf(mediaId: MediaId, versionId: VersionId, stale: PhoneRef): PhoneRowNow? = backup?.phoneRowOf(mediaId, versionId, stale)

  override suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean =
    catalog.markVerified(location, readBack, atMillis)

  override suspend fun freedFromPhone(locations: List<LocationId>) {
    if (locations.isNotEmpty()) catalog.markPhoneLocationsVanished(locations)
  }

  override suspend fun freedRowsFromPhone(rows: Map<LocationId, Long?>) {
    if (rows.isNotEmpty()) catalog.markPhoneRowsVanished(rows)
  }

  /** Giorno e ordine gia' letti dalla pagina del catalogo. */
  private class Known(val localDay: Int?, val sortTime: Long)

  private suspend fun freeMedia(id: MediaId, known: Known?): FreeSpaceMedia? {
    val media = catalog.media(id) ?: return null
    // LS-15: la riga dell'indice serve solo se la pagina non ha gia' detto giorno e ordine.
    val row = if (known == null) catalog.indexRows(listOf(media.id)).firstOrNull() else null
    val problem = catalog.mediaSafety(media.id)?.problem?.let(CatalogBackupCatalog::problemOf)
    return FreeSpaceMedia(
      media = media,
      localDay = if (known != null) known.localDay else row?.localDay?.takeIf { it != 0 },
      sortTime = known?.sortTime ?: row?.sortTime ?: 0L,
      problem = problem,
      mime = catalog.versionMetadata(media.currentVersionId)?.mimeType,
    )
  }
}

/**
 * Il conteggio dei riavvii del telefono (`Settings.Global.BOOT_COUNT`, API 24+): con l'orologio monotono dice se la verifica di "Libera
 * spazio" e' di questo avvio (D21, D31). Null se non si legge: la verifica vale allora solo nello stesso processo.
 */
class SettingsBootCounter(private val resolver: ContentResolver) : BootCounter {
  override fun current(): Long? = try {
    Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT).toLong()
  } catch (_: Settings.SettingNotFoundException) {
    null
  } catch (_: SecurityException) {
    null
  }
}

/**
 * D13 in un punto solo: una riga del telefono si puo' **riscrivere o togliere** solo se sta in una cartella ammessa dalla recinzione di debug
 * (con un account di prova: `mcg-prova`). Senza recinzione (release, account veri) tutto. Fail closed: con la recinzione e senza account, o
 * con una riga che il catalogo non conosce, niente.
 */
class PhoneRowFence(
  private val fence: BackupFence?,
  private val account: () -> String?,
  private val folderOf: suspend (volume: String, mediaStoreId: Long) -> String?,
) {
  suspend fun allows(volume: String, mediaStoreId: Long): Boolean {
    val f = fence ?: return true
    val me = account() ?: return false
    val folder = folderOf(volume, mediaStoreId) ?: return false
    return f.allowsPhoneFolder(me, folder)
  }
}

/**
 * [PhoneRewriter] (core-backup) sul riscrittore vero di core-mediastore (`MediaStorePhoneMediaRewriter`: copia di sicurezza, controllo
 * dell'impronta della riga (R3), diario (R9), scrittura senza troncare, rilettura, ripristino), **dietro la recinzione D13**: una riga fuori da
 * `mcg-prova` con un account di prova non si scrive (`NotGranted`, niente toccato). L'URI lo sceglie il riscrittore dal tipo della riga (VE-2),
 * lo stesso del dialogo di sistema.
 */
class AppPhoneRewriter(private val rewriter: PhoneMediaRewriter, private val fence: PhoneRowFence) : PhoneRewriter {
  override suspend fun replace(ref: PhoneRef, mime: String?, file: File, expectedSha256: Sha256, currentSha256: Sha256): PhoneRewrite {
    if (!fence.allows(ref.volume, ref.mediaStoreId)) return PhoneRewrite.NotGranted
    return when (val r = rewriter.replace(MediaRef.Phone(ref.volume, ref.mediaStoreId, mime), file, expectedSha256, currentSha256)) {
      is RewriteResult.Replaced -> PhoneRewrite.Replaced(r.sha256, r.size)
      RewriteResult.NotGranted -> PhoneRewrite.NotGranted
      RewriteResult.Gone -> PhoneRewrite.Gone
      RewriteResult.SourceMismatch -> PhoneRewrite.SourceMismatch
      RewriteResult.RowChanged -> PhoneRewrite.RowChanged
      is RewriteResult.Failed -> PhoneRewrite.Failed(r.code, r.restored)
    }
  }
}

/**
 * I dialoghi di sistema per le schermate della 5B (P12: "Libera spazio", "Ripristina originale", "Correggi"), su `PhoneMediaIntents` di
 * core-mediastore (Android 11+). L'app non toglie ne' riscrive mai da sola: ogni riga passa da qui dopo un tocco dell'utente, e la risposta e'
 * quella del sistema.
 *
 * **D13** (debug, account di prova): un lotto di "Libera spazio" arriva gia' filtrato da `SafeFreeSpace` (fuori da `mcg-prova` = escluso nel
 * piano e di nuovo al controllo finale, prima del dialogo); una richiesta di scrittura si controlla qui riga per riga ([rowFolder]: la cartella
 * letta dal MediaStore) e con una riga fuori recinto, o non leggibile, non c'e' dialogo (null). Senza recinzione (release) nessun controllo.
 *
 * **VE-2 della verifica funzionale 5B.** I riferimenti delle righe qui non hanno il MIME: e' l'adattatore di core-mediastore che sceglie la
 * collezione dal tipo della riga (immagini o video, mai `files/`), la stessa che apre la riscrittura; un rifiuto del sistema non esce mai da qui
 * (niente dialogo).
 */
class AppPhoneSystemDialogs(
  private val intents: PhoneMediaIntents,
  private val fence: BackupFence?,
  private val account: () -> String?,
  private val rowFolder: (PhoneRef) -> String?,
) : PhoneSystemDialogs {

  override val available: Boolean get() = intents.canTrash

  override fun removeRequest(batch: TrashBatch): IntentSender? {
    val refs = batch.items.map { MediaRef.Phone(it.phone.volume, it.phone.mediaStoreId, it.mime) }
    return safely {
      when (batch.mode) {
        TrashMode.ANDROID_TRASH -> intents.trashRequest(refs, trash = true)
        TrashMode.DELETE_NOW -> intents.deleteRequest(refs)
      }
    }
  }

  override fun writeRequest(rows: List<PhoneRef>): IntentSender? {
    if (rows.isEmpty()) return null
    fence?.let { f ->
      val me = account() ?: return null
      if (rows.any { row -> rowFolder(row)?.let { f.allowsPhoneFolder(me, it) } != true }) return null
    }
    return safely { intents.writeRequest(rows.map { MediaRef.Phone(it.volume, it.mediaStoreId, null) }) }
  }

  /** VE-2: qualunque rifiuto del sistema e' "niente dialogo": l'eccezione non arriva al tocco (prima: l'app si chiudeva). */
  private inline fun safely(block: () -> IntentSender?): IntentSender? = try {
    block()
  } catch (e: RuntimeException) {
    GalleryLog.w(TAG) { "richiesta di sistema non creata (${e.javaClass.simpleName})" }
    null
  }

  private companion object {
    const val TAG = "BackupDialoghi"
  }
}

/** La cartella (`RELATIVE_PATH`) di una riga del MediaStore, letta adesso; null se la riga non c'e' o non si legge. */
class MediaStoreRowFolders(private val resolver: ContentResolver) {
  fun folderOf(row: PhoneRef): String? = try {
    resolver.query(
      android.provider.MediaStore.Files.getContentUri(row.volume),
      arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH),
      "${android.provider.BaseColumns._ID} = ?",
      arrayOf(row.mediaStoreId.toString()),
      null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
  } catch (_: RuntimeException) {
    null
  }
}

/**
 * Il cancello dei **file in arrivo** ("Salva sul NAS", D24, P14): come quello del backup ([AppBackupGate]) ma per la share **di destinazione**,
 * che puo' essere l'area privata o l'area comune dell'account (mai un'altra share: `NO_PRIVATE_AREA`). Nell'ordine: famiglia non pronta →
 * share non dell'account → **D12** (share non di prova: `TEST_ONLY`) → **R2** (release con un account di prova: `TEST_ONLY`) → emergenza →
 * congelamento → sola lettura → pausa dell'utente (R4: "Ferma" ferma anche gli arrivi) → aperto.
 */
class IncomingGate(
  private val inputs: BackupGateInputs,
  private val commonShare: () -> String?,
) : BackupGate {
  override fun stateOf(share: String): BackupGateState {
    val mine = listOfNotNull(inputs.privateShare(), commonShare()).any { it.equals(share, ignoreCase = true) }
    return when {
      !inputs.familyReady() -> BackupGateState.NOT_READY
      !mine -> BackupGateState.NO_PRIVATE_AREA
      !inputs.flags.backupMayWriteTo(share) -> BackupGateState.TEST_ONLY
      !inputs.flags.backupMayWriteAs(inputs.account()) -> BackupGateState.TEST_ONLY
      inputs.writesStopped() -> BackupGateState.WRITES_STOPPED
      inputs.frozen(share) -> BackupGateState.FROZEN
      inputs.readOnly(share) -> BackupGateState.READ_ONLY_AREA
      inputs.paused() -> BackupGateState.PAUSED
      else -> BackupGateState.OPEN
    }
  }
}
