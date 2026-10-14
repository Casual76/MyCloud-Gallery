package dev.pampa.mycloudgallery.core.index.replica

import dev.pampa.mycloudgallery.core.index.contract.BlobLocation
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.BlobScope
import dev.pampa.mycloudgallery.core.index.contract.OwnIndexSink
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.contract.ReplicaRecords
import dev.pampa.mycloudgallery.core.index.contract.SpaceEstimate
import dev.pampa.mycloudgallery.core.index.replica.pack.PackDirectory
import dev.pampa.mycloudgallery.core.index.replica.store.BlobRow
import dev.pampa.mycloudgallery.core.index.replica.store.HeadApplication
import dev.pampa.mycloudgallery.core.index.replica.store.HeadRow
import dev.pampa.mycloudgallery.core.index.replica.store.IdentityOutcome
import dev.pampa.mycloudgallery.core.index.replica.store.PackKind
import dev.pampa.mycloudgallery.core.index.replica.store.PackRow
import dev.pampa.mycloudgallery.core.index.replica.store.RecordRow
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaCatalog
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaStore
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentRow
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentState
import dev.pampa.mycloudgallery.core.index.replica.store.StoreChange
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cio' che la replica sa di un dispositivo in una share: testa accettata e segmenti. */
data class DeviceState(val head: HeadRow?, val segments: List<SegmentRow>)

/** L'esito della rifusione dei percorsi toccati (D6, D7). */
data class RefreshReport(val files: Int, val outcomes: Map<IdentityOutcome, Int>) {
  val identityConflicts: Int get() = outcomes[IdentityOutcome.CONFLICT] ?: 0

  operator fun plus(other: RefreshReport): RefreshReport =
    RefreshReport(files + other.files, (outcomes.keys + other.outcomes.keys).associateWith { (outcomes[it] ?: 0) + (other.outcomes[it] ?: 0) })

  companion object {
    val EMPTY = RefreshReport(0, emptyMap())
  }
}

/**
 * La REPLICA LOCALE dell'indice condiviso (docs/fase4-progetto.md §2.2; D5, D6, D7, D15, D16, D17): record nel database
 * ([ReplicaStore]), immagini in pacchi mappati ([PackDirectory]), indice dei blob in memoria ([BlobIndex]), viste fuse
 * consegnate al catalogo ([ReplicaCatalog]).
 *
 * E' insieme:
 * - [ReplicaBlobs] per la Libreria e il viewer (P7, P8): `locate` e `read` bloccanti, senza copie;
 * - [ReplicaRecords] per la scansione (P5): il record vincente D6 con lo stesso timbro, con le classi **davvero**
 *   disponibili (E-I17: i media non coperti tornano "da preparare");
 * - [OwnIndexSink] per il pubblicatore (P3): cio' che questo dispositivo prepara e scopre e' visibile subito, anche quando
 *   non si pubblica (D31).
 *
 * Le scritture sono serializzate ([writeMutex]); le chiamate `suspend` aprono la replica se serve ([open]). Le share
 * arrivano in qualunque forma e si confrontano in minuscolo ([PathKeys.share]).
 */
class LocalReplica(
  private val store: ReplicaStore,
  val packs: PackDirectory,
  private val catalog: ReplicaCatalog,
  val ownDeviceId: String,
) : ReplicaBlobs, ReplicaRecords, OwnIndexSink {

  private val index = BlobIndex()
  private val packRows = ConcurrentHashMap<Long, PackRow>()
  private val openMutex = Mutex()
  private val writeMutex = Mutex()

  @Volatile private var opened = false

  /** Le classi d'immagine tenute (D16): le aggiorna la qualita'; le proprie immagini fuori livello non entrano. */
  @Volatile var keptClasses: Set<SegmentClass> = QualityLevel.DEFAULT.classes

  /** Carica l'indice dei blob e ripulisce i pacchi orfani (crash fra file e database). Idempotente. */
  suspend fun open() {
    if (opened) return
    openMutex.withLock {
      if (opened) return
      val rows = store.packs()
      packRows.clear()
      rows.forEach { packRows[it.id] = it }
      index.clear()
      val blobs = store.blobs()
      index.add(blobs, ::shareOf, attestor(blobs))
      packs.sweep(rows.mapTo(HashSet()) { it.name })
      opened = true
    }
  }

  // --- ReplicaBlobs -------------------------------------------------------------------------------------------------

  override fun locate(contentKey: Long, cls: SegmentClass): BlobLocation? = index.locate(contentKey, cls)

  override fun locate(contentKey: Long, cls: SegmentClass, scope: BlobScope): BlobLocation? = index.locate(contentKey, cls, scope)

  /**
   * Le chiavi intere che il segmento d'immagini [segment] di classe [cls] contiene davvero, attestate (R5), se la replica
   * l'ha applicato; null se non lo conosce.
   */
  fun blobKeysOfSegment(segment: String, cls: SegmentClass): Set<ContentKey>? = index.keysOfSegment(segment, cls)

  override fun read(location: BlobLocation): ByteBuffer {
    val pack = packRows[location.packId] ?: throw IOException("pacco assente")
    return packs.slice(pack.name, location.offset, location.length)
  }

  /**
   * La stima di un livello (E-I9, E-L6): i byte dei segmenti delle sue classi (e dei `meta`) **elencati nelle teste**,
   * conosciuti prima di scaricare, piu' le immagini proprie gia' tenute; gli elementi sono i contenuti distinti che hanno
   * almeno una di quelle classi preparata.
   */
  override suspend fun estimate(quality: QualityLevel): SpaceEstimate {
    open()
    val classes = quality.classes
    val segments = store.segmentsWhere(SegmentState.entries.toSet(), classes + SegmentClass.META)
      .filter { it.listed }
      .distinctBy { it.hash }
    val own = packRows.values.filter { it.kind == PackKind.OWN && it.cls in classes }.sumOf { it.bytes }
    return SpaceEstimate(segments.sumOf { it.size } + own, store.preparedContentCount(SegmentClass.maskOf(classes)))
  }

  /** Lo spazio occupato adesso: pacchi + segmenti `meta` applicati (il database dell'indice, a grandi linee). */
  suspend fun currentBytes(): Long {
    open()
    val meta = store.segmentsWhere(setOf(SegmentState.APPLIED), setOf(SegmentClass.META)).distinctBy { it.hash }.sumOf { it.size }
    return packRows.values.sumOf { it.bytes } + meta
  }

  /** I pacchi delle classi fuori da [keep] ancora presenti (la convergenza verso un livello piu' basso, E-L6). */
  fun bytesOutside(keep: Set<SegmentClass>): Long = packRows.values.filter { it.cls !in keep }.sumOf { it.bytes }

  // --- ReplicaRecords -----------------------------------------------------------------------------------------------

  override suspend fun matching(share: String, stamps: Map<SharePath, NasStamp>): Map<SharePath, MetaRecord.Present> {
    if (stamps.isEmpty()) return emptyMap()
    open()
    val shareKey = PathKeys.share(share)
    val byKey = stamps.keys.associateBy { PathKeys.of(it) }
    val rows = store.records(shareKey, byKey.keys).groupBy { it.pathKey }
    val unavailable = unavailableMasks(shareKey)
    val out = HashMap<SharePath, MetaRecord.Present>()
    for ((pathKey, path) in byKey) {
      val (device, record) = Fusion.winner(Fusion.latestByDevice(rows[pathKey].orEmpty()), stamps.getValue(path)) ?: continue
      out[path] = withEffectivePrepared(record, unavailable[device] ?: 0)
    }
    return out
  }

  /**
   * E-I17: le classi che il dispositivo dichiara preparate ma i cui segmenti, qui, risultano assenti o rifiutati, e per
   * cui nessun altro dispositivo ha dato il blob, non contano: il media torna "da preparare".
   */
  private fun withEffectivePrepared(record: MetaRecord.Present, unavailableMask: Int): MetaRecord.Present {
    if (unavailableMask == 0) return record
    val availableMask = SegmentClass.maskOf(index.classesOf(record.file.contentKey.short))
    val effective = record.file.prepared and (unavailableMask and availableMask.inv()).inv()
    return if (effective == record.file.prepared) record else record.copy(file = record.file.copy(prepared = effective))
  }

  private suspend fun unavailableMasks(shareKey: String): Map<String, Int> {
    val out = HashMap<String, Int>()
    for (head in store.heads(shareKey)) {
      val mask = store.segments(shareKey, head.deviceId).filter { it.listed && it.state.unavailable }.fold(0) { acc, s -> acc or s.cls.mask }
      if (mask != 0) out[head.deviceId] = mask
    }
    return out
  }

  // --- OwnIndexSink -------------------------------------------------------------------------------------------------

  override suspend fun applyOwn(share: String, records: List<MetaRecord>, blobs: List<BlobPut>) {
    open()
    val shareKey = PathKeys.share(share)
    writeMutex.withLock {
      if (records.isNotEmpty()) {
        val rows = records.map { RecordRow(ownDeviceId, RecordRow.LOCAL_SEGMENT, PathKeys.of(it.path), it) }
        store.putLocalRecords(shareKey, rows)
        flushLocked(shareKey)
      }
      val kept = keptClasses
      blobs.asSequence()
        .filterIsInstance<BlobPut.Image>()
        .filter { it.cls in kept && it.bytes.size <= TrustLimits.MAX_BLOB_BYTES }
        .groupBy { it.cls }
        .toSortedMap(compareBy { it.code })
        .forEach { (cls, images) -> appendOwnLocked(shareKey, cls, images) }
    }
  }

  private suspend fun appendOwnLocked(shareKey: String, cls: SegmentClass, images: List<BlobPut.Image>) {
    var pack = packRows.values
      .filter { it.kind == PackKind.OWN && it.cls == cls && it.share == shareKey }
      .maxByOrNull { it.id }
    if (pack == null || pack.bytes >= TrustLimits.SEGMENT_TARGET_BYTES) {
      val draft = PackRow(0, nextOwnName(cls), PackKind.OWN, cls, 0, shareKey, ownDeviceId, RecordRow.LOCAL_SEGMENT)
      val id = store.openOwnPack(draft)
      pack = draft.copy(id = id)
      packRows[id] = pack
    }
    val body = java.io.ByteArrayOutputStream(images.sumOf { it.bytes.size })
    val relative = images.map { image -> body.size().also { body.write(image.bytes) } }
    val offset = packs.append(pack.name, pack.bytes, body.toByteArray())
    val newLength = offset + body.size()
    val rows = images.mapIndexed { i, image ->
      BlobRow(image.contentKey, cls, pack.id, offset + relative[i], image.bytes.size, ownDeviceId, RecordRow.LOCAL_SEGMENT)
    }
    store.appendOwnBlobs(pack.id, newLength, rows)
    packRows[pack.id] = pack.copy(bytes = newLength)
    // Le proprie immagini: M3 (C19) le ha gia' legate a un file di questa share alla pubblicazione.
    index.add(rows, { shareKey }, { true })
  }

  private fun nextOwnName(cls: SegmentClass): String {
    val prefix = "own-${cls.code}-"
    val used = packRows.values.map { it.name } + (packs.root.list()?.toList() ?: emptyList())
    val max = used.filter { it.startsWith(prefix) }.mapNotNull { it.removePrefix(prefix).toLongOrNull() }.maxOrNull() ?: 0
    return "$prefix${max + 1}"
  }

  // --- Scritture della sincronizzazione ----------------------------------------------------------------------------

  /** Le teste e i segmenti di ogni dispositivo noto in [shareKey]. */
  suspend fun devices(shareKey: String): Map<String, DeviceState> {
    open()
    return store.heads(shareKey).associate { it.deviceId to DeviceState(it, store.segments(shareKey, it.deviceId)) }
  }

  /**
   * Applica una testa (D5). I percorsi toccati restano "da rifondere" nel database: li consegna al catalogo [flush], di
   * solito una volta per share dopo tutte le teste del giro (cosi' la fusione vede tutti i dispositivi insieme).
   */
  suspend fun applyHead(application: HeadApplication): Int {
    open()
    return writeMutex.withLock {
      val change = store.applyHead(application)
      afterChange(change)
      reattest(application.head.share, application.head.deviceId)
      change.touchedPathKeys.size
    }
  }

  /** Rifonde (D6) e consegna al catalogo (D7) i percorsi "da rifondere" di [shareKey]. Idempotente, riprendibile. */
  suspend fun flush(shareKey: String): RefreshReport {
    open()
    return writeMutex.withLock { flushLocked(shareKey) }
  }

  /** Come [flush] per ogni share con lavoro lasciato a meta' (crash fra la replica e il catalogo). */
  suspend fun flushAll(): RefreshReport {
    open()
    return writeMutex.withLock {
      store.dirtyShares().sorted().fold(RefreshReport.EMPTY) { acc, share -> acc + flushLocked(share) }
    }
  }

  /**
   * Un segmento d'immagini scaricato e verificato: pacco immutabile su disco (nome = impronta), poi voci e stato nel
   * database. Falso se nel frattempo il segmento non e' piu' elencato.
   */
  suspend fun applyBlobSegment(segment: SegmentRow, content: SegmentContent.Blobs): Boolean {
    open()
    require(content.cls == segment.cls) { "Classe diversa da quella della testa." }
    val limit = if (segment.cls == SegmentClass.FRAMES) TrustLimits.MAX_FRAMES_BODY_BYTES else TrustLimits.MAX_BLOB_BYTES
    val entries = content.entries.filter { it.length <= limit }
    packs.writeImmutable(segment.hash, content.bytes)
    val draft = PackRow(0, segment.hash, PackKind.SEGMENT, segment.cls, content.bytes.size.toLong(), segment.share, segment.deviceId, segment.hash)
    val rows = entries.map { BlobRow(it.contentKey, segment.cls, 0, it.offset.toLong(), it.length, segment.deviceId, segment.hash) }
    return writeMutex.withLock {
      val id = store.applyBlobSegment(draft, rows)
      if (id == null) {
        packs.delete(segment.hash)
        false
      } else {
        packRows[id] = draft.copy(id = id)
        // R1: una voce vale solo se il dispositivo ha in questa share un record presente con la stessa chiave intera.
        val present = store.presentContentKeys(segment.share, segment.deviceId)
        index.add(rows.map { it.copy(packId = id) }, { segment.share }, { it.contentKey in present })
        true
      }
    }
  }

  /**
   * Quando tutti i segmenti elencati di [cls] di un dispositivo sono applicati, i segmenti vecchi della stessa classe
   * trattenuti nel frattempo (E-I17, "prima costruire, poi togliere") se ne vanno.
   */
  suspend fun dropRetained(shareKey: String, deviceId: String, cls: SegmentClass): RefreshReport {
    open()
    return writeMutex.withLock {
      val head = store.heads(shareKey).firstOrNull { it.deviceId == deviceId } ?: return@withLock RefreshReport.EMPTY
      val segments = store.segments(shareKey, deviceId)
      if (segments.any { it.listed && it.cls == cls && it.state != SegmentState.APPLIED }) return@withLock RefreshReport.EMPTY
      val retained = segments.filter { !it.listed && it.cls == cls }
      if (retained.isEmpty()) return@withLock RefreshReport.EMPTY
      val gone = retained.mapTo(HashSet()) { it.hash }
      val change = store.applyHead(HeadApplication(head, segments.filter { it.hash !in gone }, emptyMap(), gone))
      afterChange(change)
      reattest(shareKey, deviceId)
      flushLocked(shareKey)
    }
  }

  suspend fun setSegmentState(segment: SegmentRow, state: SegmentState) {
    writeMutex.withLock { store.setSegmentState(segment.share, segment.deviceId, segment.hash, state) }
  }

  /**
   * E-I15: i dati di un dispositivo revocato, perso o sconosciuto spariscono; i suoi media tornano "da preparare". I
   * percorsi toccati vanno al catalogo con il prossimo [flush].
   */
  suspend fun forgetDevice(shareKey: String, deviceId: String) {
    open()
    writeMutex.withLock { afterChange(store.forgetDevice(shareKey, deviceId)) }
  }

  /** E-I8: tutti i dati della share via (solo locali), e dal catalogo cio' che c'era solo per l'indice. */
  suspend fun forgetShare(shareKey: String) {
    open()
    writeMutex.withLock {
      afterChange(store.forgetShare(shareKey))
      catalog.dropShare(shareKey)
    }
  }

  /** Convergenza verso un livello piu' basso (E-L6): toglie al massimo [maxBytes] di pacchi fuori da [keep]. */
  suspend fun releaseOutside(keep: Set<SegmentClass>, maxBytes: Long): Long {
    open()
    return writeMutex.withLock {
      val change = store.releasePacksOutside(keep, maxBytes)
      afterChange(change)
      change.releasedPacks.sumOf { it.bytes }
    }
  }

  /** Le voci scelte dall'indice in memoria (una per chiave e classe): per confrontare due repliche (E-I7). */
  fun chosenBlobs(): List<BlobRow> = index.chosen()

  /** La share (chiave) del pacco di un blob. */
  private fun shareOf(row: BlobRow): String? = packRows[row.packId]?.share

  /**
   * R1: per le voci di [rows] (all'apertura), se il loro dispositivo ha nella share del pacco un record presente con la stessa
   * chiave intera. Le immagini preparate qui e non ancora rilette dalla propria testa valgono (M3 alla pubblicazione).
   */
  private suspend fun attestor(rows: Collection<BlobRow>): (BlobRow) -> Boolean {
    val keys = HashMap<Pair<String, String>, Set<ContentKey>>()
    for (row in rows) {
      if (row.segment == RecordRow.LOCAL_SEGMENT) continue
      val share = shareOf(row) ?: continue
      keys.getOrPut(share to row.deviceId) { store.presentContentKeys(share, row.deviceId) }
    }
    return { row ->
      row.segment == RecordRow.LOCAL_SEGMENT || shareOf(row)?.let { share -> row.contentKey in keys[share to row.deviceId].orEmpty() } == true
    }
  }

  /** R1: i record di [deviceId] in [shareKey] sono cambiati; le sue voci valgono solo con un record presente uguale. */
  private suspend fun reattest(shareKey: String, deviceId: String) {
    if (!index.hasEntries(shareKey, deviceId)) return
    index.reattest(shareKey, deviceId, store.presentContentKeys(shareKey, deviceId))
  }

  private fun afterChange(change: StoreChange) {
    index.remove(change.removedBlobs)
    for (pack in change.releasedPacks) {
      packRows.remove(pack.id)
      packs.delete(pack.name)
    }
  }

  private suspend fun flushLocked(shareKey: String): RefreshReport {
    var report = RefreshReport.EMPTY
    while (true) {
      val chunk = store.dirty(shareKey, REFRESH_BATCH)
      if (chunk.isEmpty()) return report
      val rows = store.records(shareKey, chunk).groupBy { it.pathKey }
      val listing = catalog.listingStamps(shareKey, chunk)
      val merged = chunk.map { key -> Fusion.merge(key, rows[key].orEmpty(), listing[key]) }
      val outcomes = catalog.applyMerged(shareKey, merged)
      store.clearDirty(shareKey, chunk)
      report += RefreshReport(merged.size, outcomes.groupingBy { it }.eachCount())
    }
  }

  private companion object {
    /** Percorsi per transazione del catalogo (come i lotti da 500–2.000 della scansione). */
    const val REFRESH_BATCH = 2_000
  }
}
