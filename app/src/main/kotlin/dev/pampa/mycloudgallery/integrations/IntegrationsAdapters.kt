package dev.pampa.mycloudgallery.integrations

import android.content.ContentResolver
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.EditSession
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionId
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionState
import dev.pampa.mycloudgallery.core.backup.contract.EditTarget
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.entity.EditSessionEntity
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.mediastore.android.ContentUriMediaBytes
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import dev.pampa.mycloudgallery.core.nasmedia.detect.NasMediaBytes
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.security.lock.BiometricPromptAuthenticator
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryGate
import dev.pampa.mycloudgallery.feature.integrations.edit.EditCatalog
import dev.pampa.mycloudgallery.feature.integrations.edit.EditCurrent
import dev.pampa.mycloudgallery.feature.integrations.edit.EditMedia
import dev.pampa.mycloudgallery.feature.integrations.edit.EditNasCopies
import dev.pampa.mycloudgallery.feature.integrations.edit.EditSessionDraft
import dev.pampa.mycloudgallery.feature.integrations.edit.EditSessionStore
import dev.pampa.mycloudgallery.feature.integrations.edit.EditSnapshot
import dev.pampa.mycloudgallery.feature.integrations.edit.GalleryDownload
import dev.pampa.mycloudgallery.feature.integrations.edit.GalleryDownloads
import dev.pampa.mycloudgallery.feature.integrations.edit.NasCopy
import dev.pampa.mycloudgallery.feature.integrations.edit.StoredEditSession
import dev.pampa.mycloudgallery.feature.integrations.picker.EntryUnlock
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.provider.MediaFileInfo
import dev.pampa.mycloudgallery.feature.integrations.provider.NasFolder
import dev.pampa.mycloudgallery.feature.integrations.provider.NasFolderKey
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import dev.pampa.mycloudgallery.feature.integrations.receive.DestinationChoice
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingDestinations
import dev.pampa.mycloudgallery.feature.integrations.receive.ReceiveUnlock
import dev.pampa.mycloudgallery.feature.integrations.share.OpenedForShare
import dev.pampa.mycloudgallery.feature.integrations.share.ShareOpen
import dev.pampa.mycloudgallery.feature.integrations.share.ShareSource
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadFailure
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadState
import dev.pampa.mycloudgallery.feature.viewer.download.PhoneDownload
import dev.pampa.mycloudgallery.nas.NasShareArea
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Le PORTE delle integrazioni (Fase 5B, P14; docs/fase5-progetto.md §2.7, D23, D24, D25, D26, E-X1, E-X7, E-H1, C17): solo TRADUZIONI fra le
// porte di feature-integrations e cio' che `:app` ha gia' (catalogo, MediaStore, NAS, blocco app). Le decisioni restano nei moduli: la vista
// della Libreria e' l'unico elenco (E-H1) e chi usa queste porte ricontrolla ogni id; i byte del NAS si leggono a intervalli con le
// scadenze di core-nas. Nei log solo codici: mai nomi, percorsi, share, account o id.

/**
 * Il cancello delle activity d'ingresso (C17, E-X7): la famiglia pronta e il blocco **valutato adesso** (`AppLockController.isUnlockedNow`,
 * nota di P10: prima il blocco si richiudeva solo al ritorno della `MainActivity`, e con lei in secondo piano restava aperto per le altre app).
 */
class AppEntryGate(private val lock: AppLockController, private val familyReady: () -> Boolean) : EntryGate {
  override fun decide(): EntryDecision = when {
    !familyReady() -> EntryDecision.NOT_READY
    lock.isUnlockedNow() -> EntryDecision.ALLOWED
    else -> EntryDecision.NEEDS_UNLOCK
  }
}

/**
 * Lo sblocco di un'activity d'ingresso (selettore, ricezione): la credenziale di sistema (`BiometricPromptAuthenticator`), poi
 * `endEntryAuthentication` (con la `MainActivity` fuori dal primo piano vale la tolleranza di due minuti). Falso se uno sblocco e' gia' in corso
 * o non c'e' niente da sbloccare.
 */
class AppEntryUnlock(private val lock: AppLockController) : EntryUnlock, ReceiveUnlock {
  override suspend fun unlock(activity: FragmentActivity): Boolean {
    if (lock.isUnlockedNow()) return true
    if (!lock.beginAuthentication()) return lock.isUnlockedNow()
    var ok = false
    try {
      val text = AuthPromptText(
        title = activity.getString(dev.pampa.mycloudgallery.core.security.R.string.core_security_prompt_title),
        subtitle = activity.getString(dev.pampa.mycloudgallery.core.security.R.string.core_security_prompt_subtitle),
      )
      ok = BiometricPromptAuthenticator(activity).authenticate(text) == AuthResult.Success
    } finally {
      lock.endEntryAuthentication(ok)
    }
    return ok && lock.isUnlockedNow()
  }
}

/** Le cartelle dei media del NAS note al catalogo, per le Cartelle NAS del provider e per "Salva sul NAS". Solo lettura del database. */
class CatalogNasFolders(private val database: GalleryDatabase) {

  /** I nomi delle sottocartelle dirette di [folder] (null = la radice) di [share] per [account], dai media presenti (mai `Versioni/`). */
  suspend fun children(account: AccountId, share: String, folder: SharePath?): List<String> = withContext(Dispatchers.IO) {
    val prefix = folder?.value?.let { "$it/" }.orEmpty()
    val names = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
    runCatching {
      database.openHelper.readableDatabase.query(
        "SELECT MIN(nas_path) FROM media_location WHERE location_type = 'nas' AND state = 'present' AND account_id = ? AND share_key = ? GROUP BY folder_key LIMIT ?",
        arrayOf<Any?>(account.value, Location.Nas.shareKeyOf(share), FOLDER_ROWS),
      ).use { c ->
        while (c.moveToNext()) {
          val path = c.getString(0) ?: continue
          val parent = path.substringBeforeLast('/', "")
          if (parent.isEmpty() || (prefix.isNotEmpty() && !parent.startsWith(prefix, ignoreCase = true))) continue
          val rest = parent.substring(prefix.length)
          if (rest.isEmpty()) continue
          val name = rest.substringBefore('/')
          val child = runCatching { (folder?.child(name) ?: SharePath.of(name)) }.getOrNull() ?: continue
          if (VersionPaths.isInVersions(child) || VersionPaths.isVersionsRoot(child)) continue
          names += name
        }
      }
    }
    names.toList()
  }

  /** I media con una copia presente direttamente in [folder] di [share] (al piu' [limit]); il chiamante li ricontrolla con la vista. */
  suspend fun mediaIn(account: AccountId, share: String, folder: SharePath?, limit: Int): List<MediaId> = withContext(Dispatchers.IO) {
    val key = Location.Nas.folderKey(account, share, folder?.let { runCatching { NasPath.of(it.value) }.getOrNull() })
    runCatching {
      database.openHelper.readableDatabase.query(
        "SELECT DISTINCT v.media_id FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
          "WHERE l.location_type = 'nas' AND l.state = 'present' AND l.folder_key = ? LIMIT ?",
        arrayOf<Any?>(key, limit.coerceAtLeast(0)),
      ).use { c -> buildList { while (c.moveToNext()) runCatching { MediaId(c.getString(0)) }.getOrNull()?.let(::add) } }
    }.getOrDefault(emptyList())
  }

  private companion object {
    const val FOLDER_ROWS = 20_000
  }
}

/** I nomi da mostrare per le aree e per la cartella delle ricevute (stringhe dell'app, mai dati). */
class IntegrationLabels(val privateArea: String, val commonArea: String, val received: String)

/**
 * La Libreria per le altre app (porta `IntegrationLibrary` di P10). [items] e' la vista della Libreria **senza i nascosti** e con l'accesso
 * parziale (`GalleryGraph.viewerItems(ALL)`): l'unico elenco da cui un media esce (E-H1). Le miniature hanno un caricatore **proprio**
 * (la finestra delle richieste e' per caricatore). I byte del NAS si leggono a intervalli (mai il file intero in memoria), con la prova del NAS
 * prima di consegnare (E-X1).
 */
class AppIntegrationLibrary(
  override val items: ViewerItems,
  override val thumbnails: ThumbnailLoader,
  override val placeholders: PlaceholderCache,
  private val intents: PhoneMediaIntents,
  private val resolver: ContentResolver,
  private val catalog: suspend () -> MediaCatalog?,
  private val reader: () -> NasMediaReader?,
  private val reach: suspend () -> NasReach,
  private val account: () -> AccountId?,
  private val areas: () -> List<NasShareArea>,
  private val folders: CatalogNasFolders,
  private val labels: IntegrationLabels,
) : IntegrationLibrary {

  override fun phoneUri(ref: MediaRef.Phone): Uri = intents.uriOf(ref)

  override suspend fun fileInfos(ids: List<MediaId>): Map<MediaId, MediaFileInfo> {
    val cat = catalog() ?: return emptyMap()
    val out = LinkedHashMap<MediaId, MediaFileInfo>()
    for (id in ids.distinct()) {
      val media = cat.media(id) ?: continue
      out[id] = MediaFileInfos.of(media, cat.versionMetadata(media.currentVersionId)?.mimeType) ?: continue
    }
    return out
  }

  override suspend fun openPhone(ref: MediaRef.Phone): DocumentReader? = withContext(Dispatchers.IO) {
    try {
      PhoneDocumentReader(ContentUriMediaBytes.open(resolver, intents.uriOf(ref)))
    } catch (_: FileNotFoundException) {
      null
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  override suspend fun openNas(ref: MediaRef.Nas): DocumentReader? {
    val file = NasOpen.open(catalog, reader, ref.locationId) ?: return null
    return NasDocumentReader(file)
  }

  override suspend fun nasReach(): NasReach = reach()

  override suspend fun nasFolders(parent: NasFolderKey?): List<NasFolder> {
    val me = account() ?: return emptyList()
    val mine = areas()
    if (parent == null) {
      return mine.map { area ->
        NasFolder(FolderKeys.of(area.share, null), if (area.area == NasArea.PRIVATE) labels.privateArea else labels.commonArea)
      }
    }
    val (share, folder) = FolderKeys.parse(parent) ?: return emptyList()
    if (mine.none { it.share.equals(share, ignoreCase = true) }) return emptyList()
    return folders.children(me, share, folder).mapNotNull { name ->
      val child = runCatching { folder?.child(name) ?: SharePath.of(name) }.getOrNull() ?: return@mapNotNull null
      NasFolder(FolderKeys.of(share, child), name)
    }
  }

  override suspend fun mediaIn(folder: NasFolderKey, limit: Int): List<MediaId> {
    val me = account() ?: return emptyList()
    val (share, path) = FolderKeys.parse(folder) ?: return emptyList()
    if (areas().none { it.share.equals(share, ignoreCase = true) }) return emptyList()
    return folders.mediaIn(me, share, path, limit)
  }
}

/** La chiave opaca di una cartella del NAS (il provider la sostituisce con un token: non esce mai). */
internal object FolderKeys {
  private const val SEP = '\u001F'

  fun of(share: String, folder: SharePath?): NasFolderKey = NasFolderKey(share + SEP + (folder?.value ?: ""))

  fun parse(key: NasFolderKey): Pair<String, SharePath?>? {
    val share = key.value.substringBefore(SEP, "").ifEmpty { return null }
    val path = key.value.substringAfter(SEP, "")
    return share to if (path.isEmpty()) null else runCatching { SharePath.of(path) }.getOrNull() ?: return null
  }
}

/** Nome, tipo, dimensione e data di un media come li vede un'altra app: la copia del telefono se c'e', altrimenti quella del NAS. */
internal object MediaFileInfos {
  fun of(media: Media, mime: String?): MediaFileInfo? {
    val current = media.currentVersionId
    val here = media.locations.filter { it.versionId == current && it.isPresent }
    val phone = here.firstOrNull { it.isPhone }
    val nas = here.firstOrNull { it.isNas }
    val place = phone ?: nas ?: return null
    val name = when (val l = place.location) {
      is Location.Phone -> l.displayName
      is Location.Nas -> l.path.value.substringAfterLast('/')
      else -> null
    } ?: return null
    return MediaFileInfo(
      displayName = name,
      mime = mime ?: fallbackMime(media.kind),
      size = media.currentVersion.fingerprint.size,
      modifiedAtMillis = place.stamp.modifiedAtMillis,
    )
  }

  fun fallbackMime(kind: MediaKind): String = if (kind == MediaKind.VIDEO) "video/*" else "image/*"
}

/** Apre la copia del NAS di una posizione del catalogo, con la scadenza di apertura (E-X1): null se il NAS non risponde o il file non c'e'. */
internal object NasOpen {
  const val OPEN_TIMEOUT_MS: Long = 5_000L

  suspend fun open(catalog: suspend () -> MediaCatalog?, reader: () -> NasMediaReader?, locationId: Long): NasRandomFile? {
    val cat = catalog() ?: return null
    val nas = cat.nasLocation(LocationId(locationId)) ?: return null
    val path = runCatching { SharePath.parseMediaPath(nas.path.value) }.getOrNull() ?: return null
    val r = reader() ?: return null
    val result = withTimeoutOrNull(OPEN_TIMEOUT_MS) { r.open(nas.share, path, Purpose.INTERACTIVE) } ?: return null
    return (result as? NasResult.Ok)?.value
  }
}

/** Un documento del telefono letto a intervalli (il descrittore del MediaStore). */
private class PhoneDocumentReader(private val bytes: ContentUriMediaBytes) : DocumentReader {
  override val size: Long get() = bytes.size

  override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
    if (len <= 0 || offset >= size) return 0
    val chunk = bytes.read(offset until minOf(size, offset + len))
    chunk.copyInto(buf, off)
    return chunk.size
  }

  override fun close() = bytes.close()
}

/** Un documento del NAS letto a intervalli: ogni lettura chiede solo il pezzo (mai il file intero), la scadenza la mette il provider. */
private class NasDocumentReader(private val file: NasRandomFile) : DocumentReader {
  override val size: Long get() = file.size

  override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
    if (len <= 0 || offset >= size) return 0
    val n = file.read(offset, buf, off, len)
    return if (n < 0) 0 else n
  }

  override fun close() = file.close()
}

/**
 * Le cartelle di "Salva sul NAS" (D24): per ogni area dell'account la cartella delle ricevute (sotto la radice di prova di D13 se c'e'), poi
 * le sottocartelle che la Libreria conosce. Funziona senza NAS (E-X8): le cartelle vengono dal catalogo locale.
 */
class AppIncomingDestinations(
  private val account: () -> AccountId?,
  private val areas: () -> List<NasShareArea>,
  private val fenceRoot: () -> SharePath?,
  private val folders: CatalogNasFolders,
  private val labels: IntegrationLabels,
  private val reachable: suspend () -> Boolean,
) : IncomingDestinations {

  override suspend fun roots(): List<DestinationChoice> {
    val base = fenceRoot()
    val received = base?.child(labels.received) ?: SharePath.of(labels.received)
    return areas().sortedBy { if (it.area == NasArea.PRIVATE) 0 else 1 }.map { area ->
      val dest = NasDestination(area.share, received, if (area.area == NasArea.PRIVATE) DestinationArea.PRIVATE else DestinationArea.COMMON)
      val areaName = if (area.area == NasArea.PRIVATE) labels.privateArea else labels.commonArea
      DestinationChoice(dest, "$areaName · ${labels.received}")
    }
  }

  override suspend fun children(parent: DestinationChoice): List<DestinationChoice> {
    val me = account() ?: return emptyList()
    val dest = parent.destination
    return folders.children(me, dest.share, dest.folder).mapNotNull { name ->
      val child = runCatching { dest.folder.child(name) }.getOrNull() ?: return@mapNotNull null
      DestinationChoice(NasDestination(dest.share, child, dest.area), name)
    }
  }

  override suspend fun nasReachable(): Boolean = runCatching { reachable() }.getOrDefault(false)
}

/**
 * Il catalogo visto dalle sessioni di "Modifica con…" (porta `EditCatalog` di P11) sopra `MediaCatalog`. Le decisioni (cosa e' una versione,
 * cosa una copia) restano nel catalogo e in `DefaultEditSessions`; qui solo la forma dei dati.
 */
class CatalogEditCatalog(
  private val catalog: suspend () -> MediaCatalog?,
  private val account: () -> AccountId?,
  private val refresh: suspend () -> Unit,
) : EditCatalog {

  override suspend fun media(id: MediaId): EditMedia? {
    val cat = catalog() ?: return null
    val media = cat.media(id) ?: return null
    val me = account()
    val current = media.currentVersionId
    val here = media.locations.filter { it.versionId == current && it.isPresent }
    val phones = here.mapNotNull { l ->
      val p = l.location as? Location.Phone ?: return@mapNotNull null
      val msId = p.mediaStoreId ?: return@mapNotNull null
      val version = p.mediaStoreVersion ?: return@mapNotNull null
      PhoneRef(p.volume, msId, version)
    }
    val nas = here.any { l -> (l.location as? Location.Nas)?.account?.let { it == me } == true }
    return EditMedia(
      mediaId = media.id,
      mine = me != null && media.owner == me,
      isVideo = media.kind == MediaKind.VIDEO,
      currentVersion = current,
      phoneRefs = phones,
      hasNasCopy = nas,
      takenAtUtcMs = cat.versionMetadata(current)?.captureTime?.utcMillis,
    )
  }

  override suspend fun refreshPhone() = refresh()

  override suspend fun current(id: MediaId): EditCurrent? {
    val media = catalog()?.media(id) ?: return null
    return EditCurrent(media.currentVersionId, media.currentVersion.kind)
  }

  override suspend fun mediaOfPhoneRow(ref: PhoneRef): MediaId? = catalog()?.mediaOfPhoneRow(ref.volume, ref.mediaStoreId, ref.mediaStoreVersion)

  override suspend fun linkCopyAsVersion(into: MediaId, copy: MediaId, parent: VersionId): VersionId? =
    catalog()?.linkCopyAsVersion(into, copy, parent)

  override suspend fun addEditedVersion(into: MediaId, parent: VersionId, sha256: Sha256, quickHash: QuickHash, size: Long): VersionId? =
    catalog()?.addEditedVersion(into, parent, sha256, quickHash, size)
}

/** Le sessioni di "Modifica con…" nella tabella `edit_session` (schema v4): sopravvivono alla morte del processo (D31). */
class RoomEditSessionStore(private val database: GalleryDatabase) : EditSessionStore {
  private val dao get() = database.editSessionDao()

  override suspend fun create(draft: EditSessionDraft): EditSession {
    val entity = EditSessionEntity(
      mediaId = draft.mediaId.value,
      versionId = draft.versionId.value,
      target = draft.target.code,
      state = EditSessionState.OPEN.code,
      phoneVolume = draft.phone?.volume,
      phoneMediaStoreId = draft.phone?.mediaStoreId,
      phoneMediaStoreVersion = draft.phone?.mediaStoreVersion,
      startSize = draft.snapshot.startSize,
      startModifiedAt = draft.snapshot.startModifiedMs,
      startGeneration = draft.snapshot.startGeneration,
      relativePath = draft.snapshot.relativePath,
      takenAtUtc = draft.snapshot.takenAtUtcMs,
      cacheName = draft.cacheName,
      cacheSha256 = draft.snapshot.cacheSha256?.hex,
      startedAt = draft.startedAtNasMillis,
      expiresAt = draft.expiresAtNasMillis,
    )
    val id = dao.insert(entity)
    return checkNotNull(toStored(entity.copy(sessionId = id))).session
  }

  override suspend fun find(id: EditSessionId): StoredEditSession? = dao.get(id.value)?.let(::toStored)

  override suspend fun setState(id: EditSessionId, state: EditSessionState) {
    val row = dao.get(id.value) ?: return
    if (row.state != state.code) dao.update(row.copy(state = state.code))
  }

  override suspend fun open(): List<StoredEditSession> = dao.inState(EditSessionState.OPEN.code).mapNotNull(::toStored)

  private fun toStored(e: EditSessionEntity): StoredEditSession? {
    val target = EditTarget.entries.firstOrNull { it.code == e.target } ?: return null
    val state = EditSessionState.entries.firstOrNull { it.code == e.state } ?: return null
    val volume = e.phoneVolume
    val msId = e.phoneMediaStoreId
    val msVersion = e.phoneMediaStoreVersion
    val phone = if (volume != null && msId != null && msVersion != null) runCatching { PhoneRef(volume, msId, msVersion) }.getOrNull() else null
    val session = runCatching {
      EditSession(
        id = EditSessionId(e.sessionId),
        mediaId = MediaId(e.mediaId),
        versionId = VersionId(e.versionId),
        target = target,
        state = state,
        phone = phone,
        cacheName = e.cacheName,
        startedAtNasMillis = e.startedAt,
        expiresAtNasMillis = e.expiresAt,
      )
    }.getOrNull() ?: return null
    val snapshot = EditSnapshot(
      startSize = e.startSize,
      startModifiedMs = e.startModifiedAt,
      startGeneration = e.startGeneration,
      relativePath = e.relativePath,
      takenAtUtcMs = e.takenAtUtc,
      cacheSha256 = e.cacheSha256?.let { runCatching { Sha256(it) }.getOrNull() },
    )
    return StoredEditSession(session, snapshot)
  }
}

/**
 * La copia in cache di un media solo-NAS per "Modifica con…" (E-E3): la versione attuale dal NAS con `readAll` (in ordine, con lo SHA-256 mentre
 * arriva e il sorvegliante per blocco di core-nas), scritta in [target]. Un NAS che non risponde all'apertura e' `Unreachable` subito (E-X1).
 */
class AppEditNasCopies(
  private val catalog: suspend () -> MediaCatalog?,
  private val reader: () -> NasMediaReader?,
) : EditNasCopies {

  override suspend fun copyCurrent(mediaId: MediaId, target: File): NasCopy {
    val cat = catalog() ?: return NasCopy.Unavailable
    val ref = cat.nasRefs(listOf(mediaId))[mediaId] ?: return NasCopy.Unavailable
    val r = reader() ?: return NasCopy.Unreachable
    val file = NasOpen.open(catalog, reader, ref.locationId) ?: return NasCopy.Unreachable
    return try {
      withContext(Dispatchers.IO) {
        target.outputStream().use { out ->
          when (val read = r.readAll(file, sink = { buf, n -> out.write(buf, 0, n) })) {
            is NasResult.Ok -> NasCopy.Done(read.value, file.size)
            is NasResult.Err -> {
              target.delete()
              NasCopy.Unreachable
            }
          }
        }
      }
    } catch (e: CancellationException) {
      target.delete()
      throw e
    } catch (_: IOException) {
      target.delete()
      if ((target.parentFile?.usableSpace ?: 0L) < file.size) NasCopy.Full else NasCopy.Unavailable
    } finally {
      runCatching { file.close() }
    }
  }
}

/**
 * "Scarica e modifica in Galleria" (E-E7) sopra "Scarica sul telefono" della Fase 4 (F4-D20): il download legato al media del NAS, poi la riga
 * nuova di `Pictures/MyCloud Gallery` dal catalogo (il collegamento della 4 la attacca alla stessa versione prima di renderla visibile).
 */
class AppGalleryDownloads(
  private val catalog: suspend () -> MediaCatalog?,
  private val download: () -> PhoneDownload?,
  private val waitMs: Long = WAIT_MS,
) : GalleryDownloads {

  override suspend fun downloadForEdit(mediaId: MediaId): GalleryDownload {
    val cat = catalog() ?: return GalleryDownload.Unavailable
    val ref = cat.nasRefs(listOf(mediaId))[mediaId] ?: return GalleryDownload.Unavailable
    val downloader = download() ?: return GalleryDownload.Unavailable
    downloader.start(ref)
    val end = withTimeoutOrNull(waitMs) { downloader.states.first { states -> states[ref.locationId]?.isTerminal == true || states[ref.locationId] is DownloadState.NeedsMobileData } }
      ?.get(ref.locationId)
    downloader.acknowledge(ref.locationId)
    return when (end) {
      is DownloadState.Done -> phoneRefOf(cat, mediaId)?.let { GalleryDownload.Saved(it) } ?: GalleryDownload.Unavailable
      is DownloadState.NeedsMobileData -> GalleryDownload.Unreachable
      is DownloadState.Failed -> when (end.reason) {
        DownloadFailure.NAS_UNREACHABLE, DownloadFailure.NOT_ALLOWED_HERE -> GalleryDownload.Unreachable
        DownloadFailure.PHONE_FULL -> GalleryDownload.Full
        DownloadFailure.NOT_FOUND, DownloadFailure.INTEGRITY, DownloadFailure.OTHER -> GalleryDownload.Unavailable
      }
      else -> GalleryDownload.Unreachable
    }
  }

  private suspend fun phoneRefOf(cat: MediaCatalog, id: MediaId): PhoneRef? {
    val media = cat.media(id) ?: return null
    return media.locations.filter { it.versionId == media.currentVersionId && it.isPresent }.firstNotNullOfOrNull { l ->
      val p = l.location as? Location.Phone ?: return@firstNotNullOfOrNull null
      val msId = p.mediaStoreId ?: return@firstNotNullOfOrNull null
      val version = p.mediaStoreVersion ?: return@firstNotNullOfOrNull null
      PhoneRef(p.volume, msId, version)
    }
  }

  private companion object {
    const val WAIT_MS: Long = 15 * 60_000L
  }
}

/**
 * Da dove vengono i byte di "Condividi" (porta `ShareSource` di P11): la copia del telefono (il suo indirizzo del MediaStore) o, per un
 * solo-NAS, il file del NAS letto a intervalli. Un NAS che non risponde all'apertura ferma subito la condivisione (E-X1).
 */
class AppShareSource(
  private val catalog: suspend () -> MediaCatalog?,
  private val intents: PhoneMediaIntents,
  private val resolver: ContentResolver,
  private val reader: () -> NasMediaReader?,
) : ShareSource {

  override suspend fun open(mediaId: MediaId): ShareOpen {
    val cat = catalog() ?: return ShareOpen.Gone
    val media = cat.media(mediaId) ?: return ShareOpen.Gone
    val meta = cat.versionMetadata(media.currentVersionId)
    val info = MediaFileInfos.of(media, meta?.mimeType)
    val mime = info?.mime ?: MediaFileInfos.fallbackMime(media.kind)
    cat.phoneRefs(listOf(mediaId))[mediaId]?.let { ref ->
      val uri = intents.uriOf(ref)
      val bytes = withContext(Dispatchers.IO) {
        try {
          ContentUriMediaBytes.open(resolver, uri)
        } catch (_: IOException) {
          null
        } catch (_: SecurityException) {
          null
        } catch (_: IllegalArgumentException) {
          null
        }
      }
      if (bytes != null) return ShareOpen.Opened(OpenedForShare(bytes, mime, info?.displayName, uri, onClose = bytes::close))
    }
    val ref = cat.nasRefs(listOf(mediaId))[mediaId] ?: return ShareOpen.Gone
    val file = NasOpen.open(catalog, reader, ref.locationId) ?: return ShareOpen.NasUnreachable
    return ShareOpen.Opened(OpenedForShare(NasMediaBytes(file), mime, info?.displayName, null, onClose = file::close))
  }
}
