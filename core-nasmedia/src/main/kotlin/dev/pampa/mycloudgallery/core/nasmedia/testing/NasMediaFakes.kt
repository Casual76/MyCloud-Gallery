package dev.pampa.mycloudgallery.core.nasmedia.testing

import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.WorkErrorCode
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.model.domain.WorkRules
import dev.pampa.mycloudgallery.core.nasmedia.contract.Freeze
import dev.pampa.mycloudgallery.core.nasmedia.contract.FreezeState
import dev.pampa.mycloudgallery.core.nasmedia.contract.KnownNasFile
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasKnownElsewhere
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMetaResult
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMetadataApply
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMove
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanCursor
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStore
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanner
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasSeen
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanReport
import dev.pampa.mycloudgallery.core.nasmedia.contract.UserGesture
import dev.pampa.mycloudgallery.core.nasmedia.contract.headlineCount
import java.text.Normalizer
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow

// I fake di core-nasmedia (P0, completati da P5): in src/main per i test di P5, P7, P9 e :app. Mai nella release
// (tools/check.ps1).

/**
 * La porta del catalogo per la scansione, sopra un [MediaCatalog] **vero** (di solito in memoria, con Robolectric:
 * `GalleryDatabase.build(context, name = null)`): le decisioni di collegamento sono quelle vere (`LinkingRules`, E-N1,
 * E-N2, E-N7), mentre cursore, timbri noti e "visto a" stanno qui in memoria finche' P2 non aggiunge lo schema v3. Le
 * assenze passano dal catalogo (`markVanished`), cosi' la Libreria le vede.
 *
 * @param nowMillis l'ora con cui scrivere gli stati di lavoro (di solito l'ora del NAS finto del test).
 */
class CatalogNasScanStore(
  private val catalog: MediaCatalog,
  private val nowMillis: () -> Long = { 0L },
  /** Falso nei test di scala: i metadati si ricordano in [appliedMetadata] ma non si scrivono nel catalogo (costa una lettura per media). */
  private val writeMetadata: Boolean = true,
) : NasScanStore {

  private class Entry(
    var location: Location.Nas,
    var locationId: LocationId,
    var mediaId: MediaId?,
    var stamp: NasStamp,
    var state: LocationState,
    var seenAt: Long,
    /** R6: l'impronta adottata dall'indice, non letta qui (null = letta qui). */
    var adoptedQuickHash: QuickHash? = null,
  )

  /** Un'adozione d'identita' chiesta dalla scansione (D7). */
  data class Adoption(val local: MediaId, val remoteMedia: MediaId, val remoteVersion: VersionId)

  private val cursors = HashMap<Pair<AccountId, String>, NasScanCursor>()
  private val entries = LinkedHashMap<LocationKey, Entry>()
  private val byFolder = HashMap<String, LinkedHashMap<LocationKey, Entry>>()
  private val byId = HashMap<LocationId, Entry>()

  /** Quante volte e' stato chiesto di segnare assenze (per provare "congelata → nessuna assenza", D10). */
  @Volatile var vanishCalls: Int = 0
    private set

  /** Quante posizioni sono state segnate assenti in tutto da `markVanishedInShare`. */
  @Volatile var vanishedTotal: Int = 0
    private set

  /** Le posizioni registrate da `recordAll`, **nell'ordine in cui il rilevamento le ha consegnate** (i piu' recenti per primi). */
  val recordedOrder: MutableList<LocationKey> = mutableListOf()

  /** Le adozioni d'identita' chieste, in ordine. */
  val adoptions: MutableList<Adoption> = mutableListOf()

  /** I media remoti la cui adozione il catalogo rifiuta (D7: gia' legati qui a un contenuto diverso). */
  val refusedRemoteMedia: MutableSet<MediaId> = mutableSetOf()

  /** I metadati applicati, per media (l'ultimo vince). */
  val appliedMetadata: MutableMap<MediaId, NasMetaResult> = mutableMapOf()

  private fun shareKey(account: AccountId, share: String) = account to share.lowercase(Locale.ROOT)

  private fun inShare(entry: Entry, account: AccountId, share: String) =
    entry.location.account == account && entry.location.shareKey == share.lowercase(Locale.ROOT)

  private fun put(entry: Entry) {
    entries[entry.location.key] = entry
    byFolder.getOrPut(entry.location.folderKey) { LinkedHashMap() }[entry.location.key] = entry
    byId[entry.locationId] = entry
  }

  private fun remove(entry: Entry) {
    entries.remove(entry.location.key)
    byFolder[entry.location.folderKey]?.remove(entry.location.key)
    byId.remove(entry.locationId)
  }

  override suspend fun cursor(account: AccountId, share: String): NasScanCursor? = synchronized(this) { cursors[shareKey(account, share)] }

  override suspend fun saveCursor(cursor: NasScanCursor) {
    synchronized(this) { cursors[shareKey(cursor.account, cursor.share)] = cursor }
  }

  override suspend fun knownIn(account: AccountId, share: String, folder: NasPath?): Map<LocationKey, KnownNasFile> = synchronized(this) {
    val folderKey = Location.Nas(account, share, NasPath.of(((folder?.value?.let { "$it/" }) ?: "") + "x")).folderKey
    byFolder[folderKey].orEmpty().filterValues { inShare(it, account, share) && it.state != LocationState.ABSENT }
      .mapValues { (_, e) -> KnownNasFile(e.locationId, e.stamp, e.state, e.adoptedQuickHash) }
  }

  override suspend fun countPresent(account: AccountId, share: String): Int = synchronized(this) {
    entries.values.count { inShare(it, account, share) && it.state == LocationState.PRESENT }
  }

  override suspend fun recordAll(
    observations: List<Observation>,
    whenUndecided: MediaCatalog.WhenUndecided,
    cursor: NasScanCursor?,
    seenAt: Long,
    fingerprintsAdopted: Boolean,
  ): List<MediaCatalog.RecordOutcome> {
    val outcomes = catalog.recordAll(observations, whenUndecided, cursor = null, seenAt = seenAt, fingerprintAdopted = fingerprintsAdopted)
    synchronized(this) {
      observations.zip(outcomes).forEach { (observation, outcome) ->
        val location = observation.location as? Location.Nas ?: return@forEach
        val id = outcome.locationId ?: return@forEach
        recordedOrder += location.key
        // Uno spostamento (E-N1) porta la posizione in un altro posto: la chiave vecchia non vale piu'.
        byId[id]?.let { remove(it) }
        entries[location.key]?.let { remove(it) }
        put(
          Entry(
            location, id, outcome.mediaId, NasStamp(observation.stamp.size, observation.stamp.modifiedAtMillis, location.fileId),
            LocationState.PRESENT, seenAt, observation.fingerprint.quickHash.takeIf { fingerprintsAdopted },
          ),
        )
      }
      cursor?.let { cursors[shareKey(it.account, it.share)] = it }
    }
    return outcomes
  }

  override suspend fun confirmAllPresent(seen: List<NasSeen>, seenAt: Long, cursor: NasScanCursor?): List<Boolean> = synchronized(this) {
    val result = seen.map { s ->
      val entry = entries[s.location.key]
      // Dimensione e data: un NAS riavviato puo' cambiare i fileId senza che il file cambi (la conferma li aggiorna).
      val ok = entry != null && entry.state != LocationState.ABSENT &&
        entry.stamp.size == s.stamp.size && entry.stamp.modifiedAtMillis == s.stamp.modifiedAtMillis
      if (ok) {
        entry!!.seenAt = seenAt
        entry.state = LocationState.PRESENT
        s.stamp.fileId?.let { entry.stamp = entry.stamp.copy(fileId = it) }
      }
      ok
    }
    cursor?.let { cursors[shareKey(it.account, it.share)] = it }
    result
  }

  override suspend fun markVanishedInShare(account: AccountId, share: String, epoch: Long, cap: Int): Int {
    require(cap > 0) { "Il tetto e' positivo." }
    val chosen = synchronized(this) {
      vanishCalls++
      // Come il catalogo vero (P2): su una share congelata non segna nulla, anche se chi chiama se ne dimentica (D10).
      if (cursors[shareKey(account, share)]?.freeze != null) return 0
      entries.values.filter { inShare(it, account, share) && it.state != LocationState.ABSENT && it.seenAt < epoch }
        .sortedBy { it.seenAt }.take(cap)
    }
    for (entry in chosen) {
      catalog.markVanished(entry.location.key)
      synchronized(this) { entry.state = LocationState.ABSENT }
    }
    synchronized(this) { vanishedTotal += chosen.size }
    return chosen.size
  }

  override suspend fun markShareUnavailable(account: AccountId, share: String) {
    synchronized(this) {
      entries.values.filter { inShare(it, account, share) && it.state == LocationState.PRESENT }.forEach { it.state = LocationState.UNAVAILABLE }
    }
  }

  override suspend fun findByFileId(account: AccountId, share: String, fileIds: Collection<Long>): Map<Long, NasKnownElsewhere> = synchronized(this) {
    val wanted = fileIds.toHashSet()
    val out = LinkedHashMap<Long, NasKnownElsewhere>()
    for (e in entries.values) {
      val id = e.stamp.fileId ?: continue
      if (id !in wanted || id in out || !inShare(e, account, share)) continue
      out[id] = NasKnownElsewhere(e.locationId, e.location, e.stamp, e.state, e.seenAt)
    }
    out
  }

  override suspend fun relocateAll(moves: List<NasMove>, seenAt: Long, cursor: NasScanCursor?): List<Boolean> {
    val result = ArrayList<Boolean>(moves.size)
    for (move in moves) {
      val entry = synchronized(this) { byId[move.locationId] }
      val taken = synchronized(this) { entries[move.to.key]?.takeIf { it !== entry } }
      if (entry == null || taken != null) {
        result += false
        continue
      }
      val verifiedAt = entry.mediaId?.let { id ->
        catalog.media(id)?.locations?.firstOrNull { it.id == entry.locationId }?.verifiedAtMillis
      }
      val ok = try {
        catalog.relocate(move.locationId, move.to, move.stamp.toFileStamp(), verifiedAt)
        true
      } catch (_: RuntimeException) {
        false
      }
      if (ok) {
        synchronized(this) {
          remove(entry)
          entry.location = move.to
          entry.stamp = move.stamp
          entry.state = LocationState.PRESENT
          entry.seenAt = seenAt
          put(entry)
        }
      }
      result += ok
    }
    synchronized(this) { cursor?.let { cursors[shareKey(it.account, it.share)] = it } }
    return result
  }

  override suspend fun countUnseen(account: AccountId, share: String, epoch: Long): Int = synchronized(this) {
    entries.values.count { inShare(it, account, share) && it.state != LocationState.ABSENT && it.seenAt < epoch }
  }

  override suspend fun retireExcluded(account: AccountId, share: String, folders: List<NasPath>, keys: List<LocationKey>): Int {
    // Come il catalogo vero (R9): su una share congelata niente esce dalla Libreria.
    if (synchronized(this) { cursors[shareKey(account, share)]?.freeze != null }) return 0
    val prefixes = folders.map { fold(it.value) + "/" }
    val chosen = synchronized(this) {
      entries.values.filter { e ->
        inShare(e, account, share) && e.state != LocationState.ABSENT &&
          (e.location.key in keys || fold(e.location.path.value).let { p -> prefixes.any { p.startsWith(it) } })
      }
    }
    for (entry in chosen) {
      catalog.markVanished(entry.location.key)
      synchronized(this) { entry.state = LocationState.ABSENT }
    }
    return chosen.size
  }

  override suspend fun applyMetadata(items: List<NasMetadataApply>) {
    if (!writeMetadata) {
      synchronized(this) { items.forEach { appliedMetadata[it.mediaId] = it.result } }
      return
    }
    for (item in items) {
      val media = catalog.media(item.mediaId) ?: continue
      val location = media.locations.firstOrNull { it.id == item.locationId } ?: continue
      val state = catalog.workStates(media.id).firstOrNull { it.stage == WorkStage.META }
      when (val result = item.result) {
        is NasMetaResult.Read -> {
          catalog.updateMetadata(location.versionId, result.metadata, result.kind)
          if (state != null) catalog.updateWork(media.id, WorkRules.succeeded(state, location.versionId, nowMillis()))
        }
        is NasMetaResult.Failed ->
          if (state != null) catalog.updateWork(media.id, WorkRules.failed(state.copy(versionId = location.versionId), WorkErrorCode(result.code), nowMillis()))
        NasMetaResult.Unsupported ->
          if (state != null) catalog.updateWork(media.id, WorkRules.unsupported(state.copy(versionId = location.versionId), nowMillis()))
      }
      synchronized(this) { appliedMetadata[item.mediaId] = item.result }
    }
  }

  override suspend fun adoptIdentity(local: MediaId, remoteMedia: MediaId, remoteVersion: VersionId): Boolean {
    if (remoteMedia in refusedRemoteMedia) return false
    synchronized(this) { adoptions += Adoption(local, remoteMedia, remoteVersion) }
    return true
  }

  // --- Per i test ---------------------------------------------------------------------------------------------------------

  /** Le posizioni note della share (qualunque stato): per le asserzioni. */
  fun knownCount(account: AccountId, share: String): Int = synchronized(this) { entries.values.count { inShare(it, account, share) } }

  fun stateOf(key: LocationKey): LocationState? = synchronized(this) { entries[key]?.state }

  fun mediaIdAt(key: LocationKey): MediaId? = synchronized(this) { entries[key]?.mediaId }

  private fun fold(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
}

/** Il congelamento finto: [freeze] lo mette, [release] lo toglie solo con il conteggio giusto (doppia conferma). */
class FakeFreezeState(initial: Freeze? = null) : FreezeState {
  override val frozen: MutableStateFlow<Freeze?> = MutableStateFlow(initial)

  fun freeze(freeze: Freeze?) {
    frozen.value = freeze
  }

  override suspend fun release(gesture: UserGesture): Boolean {
    val current = frozen.value ?: return false
    if (gesture.confirmedCount != current.headlineCount) return false
    frozen.value = null
    return true
  }
}

/** Uno scanner finto per chi sta a valle (P7, P9, :app): restituisce [nextReport] e aggiorna lo stato. */
class FakeNasScanner(var nextReport: ScanReport? = null) : NasScanner {
  override val status: MutableStateFlow<NasScanStatus> = MutableStateFlow(NasScanStatus.Idle)

  val runs: MutableList<ScanMode> = mutableListOf()

  override suspend fun scan(mode: ScanMode, budget: Duration): ScanReport {
    runs += mode
    val report = nextReport ?: ScanReport(
      mode = mode, folders = 0, files = 0, unchanged = 0, adopted = 0, detected = 0, arriving = 0, skippedNames = 0,
      skippedSystem = 0, albumCovers = 0, notMedia = 0, busy = 0, vanished = 0, bytesRead = 0, complete = true, resumed = false,
    )
    status.value = report.freeze?.let { NasScanStatus.Frozen(it) } ?: NasScanStatus.Finished(report)
    return report
  }
}

/**
 * Un estrattore finto che riconosce i media dai **primi byte** come fa quello vero (E-M12): JPEG, PNG, GIF, WebP, MP4 e
 * Matroska sono media, tutto il resto (compreso un `.jpg` che dentro e' altro) no. Per un video legge anche gli ultimi
 * byte, come l'indice in coda di un MP4 (cosi' i test vedono letture reali oltre la testa). Date e dimensioni sono
 * quelle scelte dal test; la data di scatto e' esatta (EXIF) se [exactDates], altrimenti la data di modifica (stimata).
 */
class HeadSniffingExtractor(
  private val width: Int = 4000,
  private val height: Int = 3000,
  private val exactDates: Boolean = true,
) : MetadataExtractor {

  /** Quante estrazioni sono state chieste. */
  @Volatile var calls: Int = 0
    private set

  override suspend fun extract(bytes: MediaBytes, hints: ExtractHints): Extraction {
    calls++
    if (bytes.size <= 0L) return Extraction.NotMedia(ExtractCode.ZERO_BYTES)
    val head = bytes.read(0L until 16L)
    val kind = when {
      head.startsWith(0xFF, 0xD8, 0xFF) || head.startsWith(0x89, 0x50, 0x4E, 0x47) || head.startsWith('G'.code, 'I'.code, 'F'.code) -> MediaKind.PHOTO
      head.size >= 12 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() -> MediaKind.VIDEO
      head.startsWith(0x1A, 0x45, 0xDF, 0xA3) -> MediaKind.VIDEO
      else -> return Extraction.NotMedia(ExtractCode.NOT_A_MEDIA_FILE)
    }
    if (kind == MediaKind.VIDEO) {
      val from = maxOf(0L, bytes.size - 4096)
      bytes.read(from until bytes.size)
    }
    val taken = hints.msDateModifiedMs ?: 0L
    val capture = CaptureTime(
      utcMillis = taken,
      offsetMinutes = 0,
      confidence = if (exactDates) CaptureTimeConfidence.EXACT else CaptureTimeConfidence.ESTIMATED,
      source = if (exactDates) CaptureTimeSource.EXIF_WITH_OFFSET else CaptureTimeSource.FILE_MODIFIED,
    )
    val metadata = MediaMetadata(
      mimeType = hints.mime,
      width = if (kind == MediaKind.VIDEO) null else width,
      height = if (kind == MediaKind.VIDEO) null else height,
      captureTime = capture,
    )
    return Extraction.Read(metadata, kind)
  }

  private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }
}
