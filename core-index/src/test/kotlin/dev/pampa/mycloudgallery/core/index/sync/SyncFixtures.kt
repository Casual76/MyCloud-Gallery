package dev.pampa.mycloudgallery.core.index.sync

import dev.pampa.mycloudgallery.core.index.contract.BlobLocation
import dev.pampa.mycloudgallery.core.index.contract.SyncReport
import dev.pampa.mycloudgallery.core.index.contract.SyncScope
import dev.pampa.mycloudgallery.core.index.quality.ReplicaQualityController
import dev.pampa.mycloudgallery.core.index.replica.Fusion
import dev.pampa.mycloudgallery.core.index.replica.LocalReplica
import dev.pampa.mycloudgallery.core.index.replica.PathKeys
import dev.pampa.mycloudgallery.core.index.replica.pack.PackDirectory
import dev.pampa.mycloudgallery.core.index.replica.store.MemoryReplicaCatalog
import dev.pampa.mycloudgallery.core.index.TestStores
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaStore
import dev.pampa.mycloudgallery.core.index.testing.InMemoryRemoteIndex
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.data.BlobEntry
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Duration

/** Dati scritti a mano per i test di P4: id deterministici, contenuti piccoli, record e segmenti. */
object Fx {
  const val NOW: Long = 1_800_000_000_000L

  fun mediaId(n: Long): MediaId = MediaId("00000000-0000-4000-8000-%012d".format(n))

  fun versionId(n: Long): VersionId = VersionId("00000000-0000-4000-9000-%012d".format(n))

  fun content(n: Int): ByteArray = "contenuto del file numero $n".toByteArray()

  /** Il file numero [n]: stessi byte → stessa chiave di contenuto su ogni dispositivo. */
  fun file(
    n: Int,
    media: Long = n.toLong(),
    prepared: Set<SegmentClass> = emptySet(),
    mtime: Long = NOW,
    fileId: Long? = null,
  ): MetaFile {
    val bytes = content(n)
    return MetaFile(
      stamp = NasStamp(bytes.size.toLong(), mtime, fileId),
      quickHash = QuickHashAlgorithm.ofContent(bytes),
      mediaId = mediaId(media),
      versionId = versionId(media),
      kind = MediaKind.PHOTO,
      source = MediaSource.CAMERA,
      mime = "image/jpeg",
      prepared = SegmentClass.maskOf(prepared),
    )
  }

  fun key(n: Int): ContentKey = file(n).contentKey

  fun path(raw: String): SharePath = SharePath.parseMediaPath(raw)

  fun put(raw: String, seq: Long, file: MetaFile): MetaRecord.Present = MetaRecord.Present(path(raw), seq, file)

  fun gone(raw: String, seq: Long): MetaRecord.Gone = MetaRecord.Gone(path(raw), seq)

  fun meta(vararg records: MetaRecord): SegmentContent.Meta = SegmentContent.Meta(records.toList())

  /** Un segmento d'immagini come lo scrive il pubblicatore: voci in ordine di chiave, corpi uno dopo l'altro. */
  fun images(cls: SegmentClass, vararg items: Pair<ContentKey, ByteArray>): SegmentContent.Blobs {
    val body = ByteArrayOutputStream()
    val entries = items.sortedBy { it.first.hex }.map { (key, bytes) ->
      BlobEntry(key, body.size(), bytes.size).also { body.write(bytes) }
    }
    return SegmentContent.Blobs(cls, entries, body.toByteArray())
  }

  /** I byte finti di un'immagine (il fake non controlla il formato: lo fa il codec di P3). */
  fun pixels(cls: SegmentClass, n: Int, device: String = "x", size: Int = 64): ByteArray =
    ByteArray(size) { i -> (n * 31 + i + cls.code * 7 + device.hashCode()).toByte() }
}

/** Un "dispositivo" che legge: replica in memoria, pacchi su disco in [dir], motore di sincronizzazione. */
class Harness(
  val own: String,
  dir: File,
  val remote: InMemoryRemoteIndex,
  var nasNow: Long = Fx.NOW,
  maxReleasePerRound: Long = ReplicaQualityController.MAX_RELEASE_BYTES_PER_ROUND,
) {
  val store: ReplicaStore = TestStores.replica()
  val catalog = MemoryReplicaCatalog()
  val packs = PackDirectory(File(dir, "packs-$own"))
  val replica = LocalReplica(store, packs, catalog, own)
  val quality = ReplicaQualityController(store, replica, maxReleasePerRound)
  val standings = HashMap<String, DeviceStanding>()
  var block: SyncBlock? = null

  /** L'orologio monotono finto: avanza di [step] a ogni lettura, oppure segue [clock] se impostato. */
  var ticks = 0L
  var step = 0L
  var clock: (() -> Long)? = null

  val engine = IndexSyncEngine(
    remote = remote,
    replica = replica,
    store = store,
    gate = { block },
    standings = { standings[it] ?: DeviceStanding.APPROVED },
    nasClock = NasClock { nasNow },
    quality = quality,
    ticker = { clock?.invoke() ?: run { ticks += step; ticks } },
  )

  suspend fun sync(
    vararg shares: String,
    classes: Set<SegmentClass> = quality.syncClasses(),
    budget: Duration = Duration.INFINITE,
    visibleOnly: Boolean = false,
  ): SyncReport = engine.sync(SyncScope(shares.toSet(), classes, visibleOnly), budget)

  /** Le richieste fatte al NAS finora (elenchi, teste, segmenti). */
  fun requests(): Int = remote.deviceListings.get() + remote.headReads.get() + remote.segmentReads.get()

  /** La vista fusa di un percorso (D6) come la vede il catalogo. */
  suspend fun winner(share: String, raw: String): Pair<String, MetaRecord.Present>? {
    val key = PathKeys.of(Fx.path(raw))
    val rows = store.records(PathKeys.share(share), listOf(key))
    val listing = catalog.listingStamps(PathKeys.share(share), listOf(key))[key]
    return Fusion.winner(Fusion.latestByDevice(rows), listing)
  }

  fun blob(key: ContentKey, cls: SegmentClass): ByteArray? = replica.locate(key.short, cls)?.let { bytes(it) }

  fun bytes(location: BlobLocation): ByteArray {
    val buffer = replica.read(location)
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
  }

  /** Lo stato logico della replica (E-I7): vedi [replicaDigest]. */
  suspend fun digest(vararg shares: String): Map<String, Any> = replicaDigest(store, replica, *shares)
}

/**
 * Lo stato logico di una replica (E-I7): per ogni share e percorso il record corrente di ogni dispositivo (D5), e per ogni
 * chiave e classe l'impronta dei byte del blob scelto. Due repliche con le stesse teste devono dare la stessa mappa.
 */
suspend fun replicaDigest(store: ReplicaStore, replica: LocalReplica, vararg shares: String): Map<String, Any> {
  val out = sortedMapOf<String, Any>()
  for (share in shares.map(PathKeys::share)) {
    val keys = store.pathKeys(share)
    val rows = store.records(share, keys).groupBy { it.pathKey }
    for ((pathKey, list) in rows) {
      for (current in Fusion.latestByDevice(list)) out["rec|$share|$pathKey|${current.deviceId}"] = current.record
    }
  }
  for (row in replica.chosenBlobs()) {
    val buffer = replica.read(BlobLocation(row.packId, row.offset, row.length, row.cls))
    out["blob|${row.contentKey.hex}|${row.cls.wire}"] = Sha256.of(ByteArray(buffer.remaining()).also { buffer.get(it) }).hex
  }
  return out
}
