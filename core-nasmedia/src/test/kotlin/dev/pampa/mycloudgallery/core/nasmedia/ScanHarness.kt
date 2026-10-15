package dev.pampa.mycloudgallery.core.nasmedia

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.index.contract.ProvisionalThumbnails
import dev.pampa.mycloudgallery.core.index.contract.ReplicaRecords
import dev.pampa.mycloudgallery.core.index.testing.InMemoryReplica
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nasmedia.contract.OwnRemovals
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanReport
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanSettings
import dev.pampa.mycloudgallery.core.nasmedia.detect.NasDetector
import dev.pampa.mycloudgallery.core.nasmedia.scan.ArrivalWatch
import dev.pampa.mycloudgallery.core.nasmedia.scan.DefaultNasScanner
import dev.pampa.mycloudgallery.core.nasmedia.scan.ScanConfig
import dev.pampa.mycloudgallery.core.nasmedia.testing.CatalogNasScanStore
import dev.pampa.mycloudgallery.core.nasmedia.testing.HeadSniffingExtractor
import dev.pampa.mycloudgallery.core.nasmedia.testing.SyntheticNas
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.ZoneId
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

internal val ROME: ZoneId = ZoneId.of("Europe/Rome")

internal const val DAY: Long = 86_400_000L

/** Le operazioni che il lettore finto conta e a cui il test puo' dare una latenza a tempo virtuale. */
internal enum class Op { LIST, OPEN, READ }

/**
 * Un [NasMediaReader] che conta cio' che la scansione chiede (elenchi, aperture, letture, pagine, scopi) e fa avanzare un
 * tempo **virtuale** per ogni operazione: i test sul tempo (budget, riprese) non dipendono dal PC.
 */
internal class CountingReader(private val inner: NasMediaReader, private val onOp: (Op) -> Unit) : NasMediaReader {
  val listed = ArrayList<String>()
  val pageSizes = ArrayList<Int>()
  val purposes = HashSet<Purpose>()
  var lists = 0
  var opens = 0
  var reads = 0

  override suspend fun list(share: String, dir: SharePath?, purpose: Purpose, pageSize: Int, onPage: suspend (List<NasEntry>) -> Unit): NasResult<ListOutcome> {
    lists++
    purposes += purpose
    listed += dir?.value ?: ""
    onOp(Op.LIST)
    return inner.list(share, dir, purpose, pageSize) { page ->
      pageSizes += page.size
      onPage(page)
    }
  }

  override suspend fun stat(share: String, path: SharePath, purpose: Purpose) = inner.stat(share, path, purpose)

  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> {
    opens++
    purposes += purpose
    onOp(Op.OPEN)
    return when (val r = inner.open(share, path, purpose)) {
      is NasResult.Ok -> NasResult.Ok(TimedFile(r.value) { reads++; onOp(Op.READ) })
      is NasResult.Err -> r
    }
  }

  override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?) =
    inner.readAll(file, sink, progress)

  private class TimedFile(private val inner: NasRandomFile, private val onRead: () -> Unit) : NasRandomFile {
    override val size: Long get() = inner.size
    override val stamp get() = inner.stamp

    override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
      onRead()
      return inner.read(offset, buf, off, len)
    }

    override suspend fun readRange(range: LongRange): ByteArray {
      onRead()
      return inner.readRange(range)
    }

    override fun close() = inner.close()
  }
}

/**
 * Tutto cio' che serve a una scansione di prova: un NAS finto con ora propria (di default **+58 s** rispetto al telefono),
 * un catalogo vero in memoria, la porta [CatalogNasScanStore], il lettore che conta e lo scanner. Robolectric.
 */
internal class ScanHarness(
  shuffle: Random? = null,
  var settings: ScanSettings = ScanSettings(),
  val replica: InMemoryReplica = InMemoryReplica(),
  private val extractor: MetadataExtractor = HeadSniffingExtractor(),
  private val config: ScanConfig = ScanConfig(),
  val share: String = "test",
  /** Tempo virtuale per operazione (ms): zero = il budget non scade mai da solo. */
  private val latency: (Op) -> Long = { 0L },
  private val replicaRecords: ReplicaRecords = replica,
  private val provisional: ProvisionalThumbnails = replica,
  /** Un NAS condiviso con un altro "dispositivo" (due harness, due cataloghi, lo stesso NAS e la stessa ora). */
  sharedNas: InMemoryNasMedia? = null,
  private val clockSource: ScanHarness? = null,
  readerWrap: (NasMediaReader) -> NasMediaReader = { it },
  writeMetadata: Boolean = true,
) {
  val me = AccountId("test")

  /** L'ora del telefono, e quella del NAS: sempre avanti di [nasOffset] (come il WD: 53-58 s). */
  var phoneNow = 1_800_000_000_000L
  var nasOffset = 58_000L
  val nasNow: Long get() = clockSource?.nasNow ?: (phoneNow + nasOffset)

  /** Il tempo monotono virtuale (ms) che lo scanner usa per il budget. */
  var mono = 0L

  val nas = sharedNas ?: InMemoryNasMedia(nasNow = { nasNow }, shuffle = shuffle)
  val synthetic = SyntheticNas(nas, seed = 7)
  val db: GalleryDatabase = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null)
  val catalog = MediaCatalog(db, me, zone = { ROME }) { nasNow }
  val store = CatalogNasScanStore(catalog, { nasNow }, writeMetadata)
  val reader = CountingReader(readerWrap(nas.reader)) { op -> mono += latency(op) }
  var arrivals = ArrivalWatch()
    private set

  /** R8: cio' che "l'app" ha tolto dalla share (dal suo diario locale). */
  var ownRemovals: OwnRemovals = OwnRemovals.NONE

  private fun newScanner() = DefaultNasScanner(
    account = me,
    share = share,
    reader = reader,
    store = store,
    detector = NasDetector(extractor) { ROME },
    replica = replicaRecords,
    provisional = provisional,
    clock = NasClock { nasNow },
    settings = { settings },
    config = config,
    arrivals = arrivals,
    monotonicMs = { mono },
    ownRemovals = OwnRemovals { share -> ownRemovals.fileIds(share) },
  )

  var scanner: DefaultNasScanner = newScanner()
    private set

  /** Il processo muore e ne parte un altro: stesso catalogo e stesso NAS, ma lo scanner non ricorda niente del giro. */
  fun restartScanner() {
    arrivals = ArrivalWatch()
    scanner = newScanner()
  }

  init {
    nas.addShare(share)
  }

  suspend fun scan(mode: ScanMode = ScanMode.Verify, budgetMs: Long = 3_600_000): ScanReport = scanner.scan(mode, budgetMs.milliseconds)

  /** Avanza l'ora (telefono e NAS insieme). */
  fun advance(ms: Long) {
    phoneNow += ms
  }

  fun keyOf(path: String): LocationKey = Location.Nas(me, share, NasPath.of(path)).key

  /** Una foto virtuale (JPEG) di [size] byte, vecchia di [ageMs] (stabile: piu' di due minuti). Restituisce il `fileId`. */
  fun photo(path: String, ageMs: Long = 30L * DAY, size: Long = 1_500_000, seed: Long = path.hashCode().toLong()): Long =
    nas.putFile(share, path, SyntheticNas.content(size, seed, SyntheticNas.JPEG_HEAD), nasNow - ageMs)

  fun video(path: String, ageMs: Long = 30L * DAY, size: Long = 120_000_000, seed: Long = path.hashCode().toLong()): Long =
    nas.putFile(share, path, SyntheticNas.content(size, seed, SyntheticNas.MP4_HEAD), nasNow - ageMs)

  fun close() = db.close()
}

/**
 * Un lettore che cambia gli elenchi prima di consegnarli: aggiunge voci false o ne cambia gli attributi. Serve a provare
 * cio' che un NAS finto onesto non produce (nomi doppi a meno delle maiuscole, collegamenti, cartelle senza fine, E-N7, E-N10).
 */
internal class TamperingReader(
  private val inner: NasMediaReader,
  /** Gli elenchi di una cartella ([dir] = "" per la radice): restituisce le voci da consegnare. */
  private val tamper: (dir: String, entries: List<NasEntry>) -> List<NasEntry>,
  /** Una cartella "alias": l'elenco di [aliasOf].key viene letto dalla cartella [aliasOf].value (un collegamento simbolico). */
  private val aliases: Map<String, String> = emptyMap(),
) : NasMediaReader by inner {

  override suspend fun list(share: String, dir: SharePath?, purpose: Purpose, pageSize: Int, onPage: suspend (List<NasEntry>) -> Unit): NasResult<ListOutcome> {
    val name = dir?.value ?: ""
    val target = aliases[name]?.let { SharePath.parse(it) } ?: dir
    val collected = ArrayList<NasEntry>()
    val outcome = inner.list(share, target, purpose, pageSize) { collected += it }
    if (outcome is NasResult.Err) return outcome
    val entries = tamper(name, collected)
    entries.chunked(pageSize).forEach { onPage(it) }
    return NasResult.Ok(ListOutcome(entries.size, null))
  }

  /** Un file sotto un alias si apre dove sta davvero: il collegamento simbolico funziona anche per i file. */
  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> {
    val parent = path.parent()?.value
    val target = parent?.let { aliases[it] }
    return if (target != null) inner.open(share, SharePath.parse("$target/${path.name}"), purpose) else inner.open(share, path, purpose)
  }
}
