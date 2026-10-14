package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.database.AdoptOutcome
import dev.pampa.mycloudgallery.core.database.IdentityClaim
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.NasFreeze
import dev.pampa.mycloudgallery.core.database.NasMetadataResult
import dev.pampa.mycloudgallery.core.database.NasMetadataUpdate
import dev.pampa.mycloudgallery.core.database.NasRelocation
import dev.pampa.mycloudgallery.core.database.NasScanState
import dev.pampa.mycloudgallery.core.database.NasScanWindow
import dev.pampa.mycloudgallery.core.database.SeenNasFile
import dev.pampa.mycloudgallery.core.index.publish.CatalogFile
import dev.pampa.mycloudgallery.core.index.publish.ShareCatalog
import dev.pampa.mycloudgallery.core.index.replica.store.IdentityOutcome
import dev.pampa.mycloudgallery.core.index.replica.store.MergedFile
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.WorkErrorCode
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.model.domain.RemoteIdentity
import dev.pampa.mycloudgallery.core.nasmedia.contract.Freeze
import dev.pampa.mycloudgallery.core.nasmedia.contract.KnownNasFile
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasKnownElsewhere
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMetaResult
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMetadataApply
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMove
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanCursor
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStore
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasSeen
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanDiff
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanWindow
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Le PORTE del catalogo per la scansione (P5) e la replica (P4), sopra `MediaCatalog` (P11; docs/fase4-progetto.md §1.4).
// Come `gallery/CatalogPorts.kt` per la Fase 3: solo traduzioni di tipi, le decisioni restano nel catalogo (LinkingRules,
// IdentityRules di core-model) e nei moduli che usano le porte.

/**
 * [NasScanStore] sul catalogo vero: cursore in `nas_scan_state`, osservazioni con il cursore nella stessa transazione,
 * scorciatoia del timbro, assenze con tetto e mai su una share congelata (il catalogo lo rifiuta da se'), spostamenti dal
 * `FileId`, esclusioni, metadati in una transazione, adozione d'identita' (D7).
 */
class MediaCatalogScanStore(private val catalog: MediaCatalog) : NasScanStore {

  override suspend fun cursor(account: AccountId, share: String): NasScanCursor? = catalog.nasScanState(account, share)?.toCursor()

  override suspend fun saveCursor(cursor: NasScanCursor) = catalog.saveNasScanState(cursor.toState())

  override suspend fun knownIn(account: AccountId, share: String, folder: NasPath?): Map<LocationKey, KnownNasFile> =
    catalog.knownNasFiles(account, share, folder).mapValues { (_, known) ->
      KnownNasFile(known.locationId, known.stamp, known.state, known.adoptedQuickHash)
    }

  override suspend fun countPresent(account: AccountId, share: String): Int = catalog.countPresentNas(account, share)

  override suspend fun recordAll(
    observations: List<Observation>,
    whenUndecided: MediaCatalog.WhenUndecided,
    cursor: NasScanCursor?,
    seenAt: Long,
    fingerprintsAdopted: Boolean,
  ): List<MediaCatalog.RecordOutcome> =
    catalog.recordAll(observations, whenUndecided, seenAt = seenAt, nasCursor = cursor?.toState(), fingerprintAdopted = fingerprintsAdopted)

  override suspend fun confirmAllPresent(seen: List<NasSeen>, seenAt: Long, cursor: NasScanCursor?): List<Boolean> =
    catalog.confirmAllPresentNas(seen.map { SeenNasFile(it.location, it.stamp) }, seenAt, cursor?.toState())

  override suspend fun markVanishedInShare(account: AccountId, share: String, epoch: Long, cap: Int): Int =
    catalog.markVanishedInShare(account, share, epoch, cap)

  override suspend fun markShareUnavailable(account: AccountId, share: String) {
    // Con tetto per transazione: si ripete finche' il catalogo ne trova (60 mila posizioni non tengono il database).
    while (catalog.markShareUnavailable(account, share, MediaCatalog.DEFAULT_NAS_CAP) == MediaCatalog.DEFAULT_NAS_CAP) Unit
  }

  override suspend fun findByFileId(account: AccountId, share: String, fileIds: Collection<Long>): Map<Long, NasKnownElsewhere> =
    catalog.nasByFileIds(account, share, fileIds).mapValues { (_, info) ->
      NasKnownElsewhere(info.locationId, info.location, info.stamp, info.state, info.lastSeenAt.takeIf { it > 0 })
    }

  override suspend fun relocateAll(moves: List<NasMove>, seenAt: Long, cursor: NasScanCursor?): List<Boolean> =
    catalog.relocateNas(moves.map { NasRelocation(it.locationId, it.to, it.stamp) }, seenAt, cursor?.toState())

  override suspend fun countUnseen(account: AccountId, share: String, epoch: Long): Int = catalog.countUnseenNas(account, share, epoch)

  override suspend fun retireExcluded(account: AccountId, share: String, folders: List<NasPath>, keys: List<LocationKey>): Int =
    catalog.retireNas(account, share, folders, keys)

  override suspend fun applyMetadata(items: List<NasMetadataApply>) {
    if (items.isEmpty()) return
    catalog.applyNasMetadata(
      items.map { item ->
        val result = when (val r = item.result) {
          is NasMetaResult.Read -> NasMetadataResult.Read(r.metadata, r.kind)
          is NasMetaResult.Failed -> NasMetadataResult.Failed(WorkErrorCode(r.code))
          NasMetaResult.Unsupported -> NasMetadataResult.Unsupported
        }
        NasMetadataUpdate(item.locationId, result)
      },
    )
  }

  /**
   * D7: il media appena registrato ([local]) prende le identita' pubblicate da un altro dispositivo. La porta parla di
   * media, il catalogo di posti: si usa la posizione del NAS di [local] vista per ultima (quella appena registrata), con
   * l'impronta della sua versione (i byte che la scansione ha appena visto). Falso solo per un conflitto di D7.
   */
  override suspend fun adoptIdentity(local: MediaId, remoteMedia: MediaId, remoteVersion: VersionId): Boolean {
    val media = catalog.media(local) ?: return true
    val place = media.locations.filter { it.isNas && it.state != LocationState.ABSENT }.maxByOrNull { it.lastSeenAtMillis } ?: return true
    val fingerprint = media.version(place.versionId).fingerprint
    val outcome = catalog.adoptIdentity(place.location.key, RemoteIdentity(remoteMedia, remoteVersion, Fingerprint(fingerprint.size, fingerprint.quickHash)))
    return !(outcome is AdoptOutcome.Refused && outcome.reason.isConflict)
  }

  private fun NasScanState.toCursor(): NasScanCursor = NasScanCursor(
    account = account,
    share = share,
    epoch = epoch,
    resumeAfter = resumeAfter,
    lastFullScanAt = lastFullScanAt,
    freeze = freeze?.let { Freeze(it.reason, it.sinceNasMillis, ScanDiff(it.knownPresent, it.vanished, it.changed, it.notMedia)) },
    window = window?.let { ScanWindow(it.startNasMillis, it.knownPresent, it.vanished, it.changed, it.notMedia) },
  )

  private fun NasScanCursor.toState(): NasScanState = NasScanState(
    account = account,
    share = share,
    epoch = epoch,
    resumeAfter = resumeAfter,
    lastFullScanAt = lastFullScanAt,
    freeze = freeze?.let { NasFreeze(it.reason, it.sinceNasMillis, it.diff.knownPresent, it.diff.vanished, it.diff.changed, it.diff.notMedia) },
    window = window?.let { NasScanWindow(it.startNasMillis, it.knownPresent, it.vanished, it.changed, it.notMedia) },
  )
}

/**
 * [ReplicaCatalog] sul catalogo vero (D6, D7, D8; §2.2 "Applicazione"):
 *
 * - **timbri dell'elenco**: le posizioni viste da una scansione (non assenti); quelle nate solo dall'indice non contano;
 * - **file solo nell'indice** (nessuna scansione li ha ancora visti): nascono come osservazioni `KEEP_SEPARATE` con "visto
 *   a" zero (cosi' una scansione completa che non li trova li toglie, E-N3) e i metadati del record; poi l'adozione
 *   d'identita' del catalogo (`IdentityRules` di core-model: rifiuto se l'id remoto e' gia' legato qui ad altri byte, mai
 *   un'unione senza hash completo, media con copie sul telefono che tengono il loro id, D8);
 * - **nessun record valido**: si toglie cio' che esisteva solo per l'indice; cio' che ha visto la scansione resta;
 * - **share tolta** (E-I8): via i media che esistevano solo per l'indice e, R10 della revisione 4A, anche le posizioni viste
 *   dalla scansione (diventano assenti: escono dalla Libreria e dopo 30 giorni dal catalogo, con date, luoghi e nomi) e le
 *   miniature provvisorie delle chiavi che nessun'altra posizione usa piu' ([forgetProvisional]).
 *
 * Le impronte dei file nati solo dall'indice sono **adottate** (R6): niente legami per contenuto, segnate nel catalogo finche'
 * una scansione non legge il file qui.
 *
 * Le share arrivano in forma di chiave (minuscolo): [shareName] da' il nome com'e' configurato (quello che si apre via SMB).
 */
class CatalogReplicaPort(
  private val catalog: MediaCatalog,
  private val account: suspend () -> AccountId?,
  private val shareName: (String) -> String,
  /** R10: toglie le miniature provvisorie di queste chiavi ridotte (`ProvisionalThumbnails.forget`). */
  private val forgetProvisional: suspend (Set<Long>) -> Unit = {},
) : ReplicaCatalog {

  override suspend fun listingStamps(share: String, pathKeys: Collection<String>): Map<String, NasStamp> {
    val owner = account() ?: return emptyMap()
    val name = shareName(share)
    val out = HashMap<String, NasStamp>()
    for (key in pathKeys) {
      val path = runCatching { NasPath.of(key) }.getOrNull() ?: continue
      val info = catalog.nasLocationAt(Location.Nas(owner, name, path)) ?: continue
      if (info.indexOnly || info.state == LocationState.ABSENT) continue
      out[key] = info.stamp
    }
    return out
  }

  override suspend fun applyMerged(share: String, files: List<MergedFile>): List<IdentityOutcome> {
    val owner = account() ?: return files.map { IdentityOutcome.DEFERRED }
    val name = shareName(share)
    val outcomes = MutableList(files.size) { IdentityOutcome.REMOVED }
    val retire = ArrayList<LocationKey>()
    val created = ArrayList<Pair<Int, Observation>>()
    val createdMeta = HashMap<Int, MediaMetadata>()
    val claims = ArrayList<Pair<Int, IdentityClaim>>()
    for ((i, file) in files.withIndex()) {
      val winner = file.winner
      val identity = file.identity
      val path = (file.path ?: winner?.path)?.let { runCatching { NasPath.of(it.value) }.getOrNull() }
      if (path == null) continue
      val location = Location.Nas(owner, name, path, winner?.file?.stamp?.fileId)
      val existing = catalog.nasLocationAt(location)
      if (winner == null || identity == null) {
        // Nessun record valido: se il file esisteva solo per l'indice, esce dalla Libreria.
        if (existing != null && existing.indexOnly && existing.state != LocationState.ABSENT) retire += location.key
        continue
      }
      val remote = RemoteIdentity(identity.mediaId, identity.versionId, Fingerprint(winner.file.stamp.size, winner.file.quickHash))
      when {
        existing == null -> {
          created += i to observationOf(location, winner)
          createdMeta[i] = metadataOf(winner.file)
          claims += i to IdentityClaim(location.key, remote)
        }
        // La scansione l'ha visto sparire: un record dell'indice non lo fa tornare.
        existing.state == LocationState.ABSENT && !existing.indexOnly -> outcomes[i] = IdentityOutcome.DEFERRED
        else -> claims += i to IdentityClaim(location.key, remote)
      }
    }
    if (retire.isNotEmpty()) catalog.markNasVanished(retire)
    if (created.isNotEmpty()) {
      // Mai unire sulla parola dell'indice (D8): nel dubbio un media separato; "visto a" zero = nato dall'indice.
      val recorded = catalog.recordAll(created.map { it.second }, MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = 0L, fingerprintAdopted = true)
      val updates = created.mapIndexedNotNull { j, (i, _) ->
        recorded[j].locationId?.let { NasMetadataUpdate(it, NasMetadataResult.Read(createdMeta.getValue(i), files[i].winner!!.file.kind)) }
      }
      if (updates.isNotEmpty()) catalog.applyNasMetadata(updates)
    }
    if (claims.isNotEmpty()) {
      val createdIndexes = created.mapTo(HashSet()) { it.first }
      val results = catalog.adoptIdentities(claims.map { it.second })
      claims.forEachIndexed { j, (i, _) -> outcomes[i] = outcomeOf(results[j], fresh = i in createdIndexes) }
    }
    return outcomes
  }

  override suspend fun dropShare(share: String) {
    val owner = account() ?: return
    // R10 (revisione 4A): l'account non vede piu' la share (E-I8, due giorni di rifiuti). Non solo i file nati dall'indice:
    // tutte le posizioni della share escono dalla Libreria (assenti; dopo 30 giorni il catalogo le toglie del tutto), a lotti.
    val live = catalog.nasLocationsInShare(owner, shareName(share)).filter { it.state != LocationState.ABSENT }
    if (live.isEmpty()) return
    val dropped = HashSet<Long>()
    for (chunk in live.chunked(MediaCatalog.DEFAULT_NAS_CAP)) {
      for (info in chunk) catalog.nasContentKeyOf(info.locationId)?.let { dropped += it }
      catalog.markNasVanished(chunk.map { it.location.key })
    }
    // Le miniature provvisorie (EXIF, decodifica dell'originale) delle chiavi che nessun'altra posizione usa piu'.
    val stillUsed = catalog.liveNasShortKeys()
    val orphans = dropped.filterTo(HashSet()) { it !in stillUsed }
    if (orphans.isNotEmpty()) forgetProvisional(orphans)
  }

  private fun outcomeOf(result: AdoptOutcome, fresh: Boolean): IdentityOutcome = when (result) {
    AdoptOutcome.NotFound -> IdentityOutcome.REMOVED
    is AdoptOutcome.AlreadyShared -> if (fresh) IdentityOutcome.ADOPTED else IdentityOutcome.ALREADY
    is AdoptOutcome.Adopted -> if (fresh) IdentityOutcome.ADOPTED else IdentityOutcome.CONVERGED
    is AdoptOutcome.KeptLocal -> if (fresh) IdentityOutcome.ADOPTED else IdentityOutcome.KEPT_LOCAL
    is AdoptOutcome.Merged -> IdentityOutcome.CONVERGED
    is AdoptOutcome.Refused -> if (result.reason.isConflict) IdentityOutcome.CONFLICT else IdentityOutcome.DEFERRED
  }

  private fun observationOf(location: Location.Nas, record: MetaRecord.Present): Observation {
    val file = record.file
    return Observation(
      location = location,
      stamp = file.stamp.toFileStamp(),
      // Solo l'impronta rapida: l'hash completo dell'indice non basta mai a unire (D8).
      fingerprint = Fingerprint(file.stamp.size, file.quickHash),
      kind = file.kind,
      source = file.source,
      captureTimeHintMillis = file.capture?.takeIf { !it.isEstimated }?.utcMillis,
    )
  }
}

/** I metadati di un record dell'indice (gia' orientati: rotazione zero), come li adotta la scansione (P5). */
internal fun metadataOf(file: MetaFile): MediaMetadata = MediaMetadata(
  mimeType = file.mime,
  width = file.width,
  height = file.height,
  orientationDegrees = 0,
  durationMillis = file.durationMs,
  captureTime = file.capture,
  location = file.geo?.let { GeoPoint(it.latE7 / 1e7, it.lonE7 / 1e7) },
  mirrored = false,
  traits = MediaTrait.fromBits(file.traits),
  thumbHash = file.thumbHash,
)

/**
 * [ShareCatalog] sul catalogo (regola M3 dell'indice, C19): per ogni percorso della share il timbro e l'impronta rapida che
 * il catalogo conosce per l'account di questo dispositivo. Un percorso che il catalogo non conosce manca dalla mappa e il
 * pubblicatore rifiuta il cambiamento.
 */
class CatalogShareFiles(
  private val catalog: suspend () -> MediaCatalog?,
  private val account: suspend () -> AccountId?,
) : ShareCatalog {
  override suspend fun files(share: String, paths: Collection<SharePath>): Map<SharePath, CatalogFile> {
    val owner = account() ?: return emptyMap()
    val c = catalog() ?: return emptyMap()
    val byNas = paths.mapNotNull { p -> runCatching { NasPath.of(p.value) }.getOrNull()?.let { it to p } }.toMap()
    return c.nasFiles(owner, share, byNas.keys).mapNotNull { (nas, facts) ->
      byNas[nas]?.let { it to CatalogFile(facts.first, facts.second) }
    }.toMap()
  }
}
