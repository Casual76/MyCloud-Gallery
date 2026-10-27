package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.index.contract.ReplicaRecords
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnitKey
import dev.pampa.mycloudgallery.core.index.work.WorkSource
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.detect.NasMediaBytes
import dev.pampa.mycloudgallery.core.prepare.contract.PrepAttempts
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepInputs
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepRecordSource
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.trust.data.GeoE7
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Le PORTE della preparazione (P6) e delle unita' di lavoro (P3, `WorkSource`) sopra il catalogo, la replica e il NAS
// (P11; docs/fase4-progetto.md §2.4, D12, D13, D14). La preparazione non vede il NAS: le arriva un `MediaBytes`.

/** Le classi che la preparazione produce per un media (D12): foto S, M, analisi, HQ; video in piu' i fotogrammi. */
object PrepClasses {
  val PHOTO: Set<SegmentClass> = setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)
  val VIDEO: Set<SegmentClass> = PHOTO + SegmentClass.FRAMES

  fun required(kind: MediaKind): Set<SegmentClass> = if (kind == MediaKind.VIDEO) VIDEO else PHOTO

  /** Il mese di scatto di un'unita' (`aaaa-mm`) dal giorno `aaaammgg`; `0000-00` senza data (o con una data assurda). */
  fun monthOf(localDay: Int?): String {
    if (localDay == null || localDay <= 0) return WorkUnitKey.NO_DATE
    val year = localDay / 10_000
    val month = (localDay / 100) % 100
    return if (year in 1..9999 && month in 1..12) String.format(Locale.ROOT, "%04d-%02d", year, month) else WorkUnitKey.NO_DATE
  }
}

/**
 * I media di una share per le unita' di lavoro (porta `WorkSource` di core-index, D13). Tutti i media del mese, con
 * [WorkItem.missing] = le classi che mancano: le richieste ([PrepClasses]) meno quelle che il record vincente della replica
 * (D6, propri compresi) dice gia' preparate. Un fallimento pubblicato ferma il lavoro su quel media, tranne
 * `DECODER_MISSING` che passa di mano (D14); un media a cui questo dispositivo ha rinunciato ([gaveUp]) non si ripropone.
 *
 * Il quadro di una share si calcola una volta e vale [ttlMs] (orologio monotono) o fino a [invalidate]: una tappa di
 * preparazione non rilegge 20 mila righe a ogni unita'. Quello che si prepara nel frattempo lo tiene fuori il giro stesso
 * (un media si prova una volta per giro).
 */
class CatalogWorkSource(
  private val catalog: suspend () -> MediaCatalog?,
  private val account: suspend () -> AccountId?,
  private val replica: ReplicaRecords,
  private val gaveUp: suspend (ContentKey) -> Boolean,
  private val monotonicMs: () -> Long,
  private val ttlMs: Long = DEFAULT_TTL_MS,
) : WorkSource {

  private class Snapshot(val at: Long, val byMonth: Map<String, List<WorkItem>>, val monthByShort: Map<Long, String>)

  private val lock = Mutex()
  private val cache = HashMap<String, Snapshot>()

  /** Il catalogo o la replica sono cambiati molto (fine di una scansione o di una sincronizzazione): si ricalcola. */
  fun invalidate() {
    synchronized(cache) { cache.clear() }
  }

  override suspend fun months(share: String, pass: WorkPass): List<String> =
    snapshot(share).byMonth.filterValues { items -> items.any { it.missing.isNotEmpty() } }.keys.sortedDescending()

  override suspend fun monthsOf(share: String, pass: WorkPass, visible: Set<Long>): List<String> {
    if (visible.isEmpty()) return emptyList()
    val months = snapshot(share).monthByShort
    return visible.mapNotNull { months[it] }.distinct().sortedDescending()
  }

  override suspend fun monthItems(share: String, pass: WorkPass, month: String): List<WorkItem> = snapshot(share).byMonth[month].orEmpty()

  private suspend fun snapshot(share: String): Snapshot = lock.withLock {
    val key = share.lowercase(Locale.ROOT)
    val now = monotonicMs()
    synchronized(cache) { cache[key] }?.takeIf { now - it.at < ttlMs }?.let { return@withLock it }
    build(share, now).also { synchronized(cache) { cache[key] = it } }
  }

  private suspend fun build(share: String, now: Long): Snapshot {
    val owner = account() ?: return Snapshot(now, emptyMap(), emptyMap())
    val c = catalog() ?: return Snapshot(now, emptyMap(), emptyMap())
    val rows = c.nasWorkRows(owner, share)
    val paths = HashMap<NasPath, SharePath>(rows.size * 2)
    for (row in rows) runCatching { SharePath.parseMediaPath(row.path.value) }.getOrNull()?.let { paths[row.path] = it }
    val winners = HashMap<SharePath, dev.pampa.mycloudgallery.core.trust.data.MetaRecord.Present>()
    rows.filter { it.path in paths }.chunked(CHUNK).forEach { chunk ->
      winners += replica.matching(share, chunk.associate { paths.getValue(it.path) to it.stamp })
    }
    val byMonth = HashMap<String, MutableList<WorkItem>>()
    val monthByShort = HashMap<Long, String>()
    for (row in rows) {
      val path = paths[row.path] ?: continue
      val contentKey = ContentKey.of(row.quickHash, row.stamp.size)
      val month = PrepClasses.monthOf(row.localDay)
      val winner = winners[path]?.file
      val failure = winner?.failure
      val handOver = failure?.code == PrepFailCode.DECODER_MISSING.name
      val missing = when {
        failure != null && !handOver -> emptySet()
        gaveUp(contentKey) -> emptySet()
        else -> PrepClasses.required(row.kind) - (winner?.preparedClasses.orEmpty())
      }
      byMonth.getOrPut(month) { ArrayList() } += WorkItem(
        contentKey = contentKey,
        path = path,
        stamp = row.stamp,
        kind = row.kind,
        mime = row.mime,
        mediaId = row.mediaId,
        versionId = row.versionId,
        missing = missing,
        failedDecoders = if (handOver) setOfNotNull(failure?.decoder) else emptySet(),
      )
      monthByShort[contentKey.short] = month
    }
    return Snapshot(now, byMonth, monthByShort)
  }

  companion object {
    const val DEFAULT_TTL_MS: Long = 10 * 60 * 1000L
    private const val CHUNK = 500
  }
}

/**
 * Il record di partenza di un media da preparare (porta `PrepRecordSource`): cio' che il **catalogo locale** sa del file
 * (D8: mai da un record remoto): timbro, impronte, identita', tipo, data, dimensioni gia' orientate, tratti, luogo,
 * segnaposto. Null se il file non e' piu' quello dell'elenco (spostato, cambiato, sparito): la scansione sistemera'.
 */
class CatalogPrepRecords(
  private val catalog: suspend () -> MediaCatalog?,
  private val account: suspend () -> AccountId?,
) : PrepRecordSource {

  override suspend fun baseFor(share: String, item: WorkItem): MetaFile? {
    val c = catalog() ?: return null
    val owner = account() ?: return null
    val mediaId = item.mediaId ?: return null
    val versionId = item.versionId ?: return null
    val nasPath = runCatching { NasPath.of(item.path.value) }.getOrNull() ?: return null
    val place = c.nasLocationAt(Location.Nas(owner, share, nasPath)) ?: return null
    if (place.state == LocationState.ABSENT || !place.stamp.sameAs(item.stamp)) return null
    val media = c.media(mediaId) ?: return null
    val version = media.versions.firstOrNull { it.id == versionId } ?: return null
    if (version.fingerprint.size != item.stamp.size) return null
    val meta = c.versionMetadata(versionId)
    val swap = meta != null && (meta.orientationDegrees == 90 || meta.orientationDegrees == 270)
    val width = (if (swap) meta?.height else meta?.width)?.takeIf { it > 0 }
    val height = (if (swap) meta?.width else meta?.height)?.takeIf { it > 0 }
    fun build(mime: String?) = MetaFile(
      stamp = item.stamp,
      quickHash = version.fingerprint.quickHash,
      fullHash = version.fingerprint.fullHash,
      mediaId = media.id,
      versionId = version.id,
      parentVersionId = version.parentId,
      versionKind = version.kind,
      kind = media.kind,
      source = media.source,
      mime = mime,
      capture = meta?.captureTime,
      width = width,
      height = height,
      durationMs = meta?.durationMillis?.takeIf { it >= 0 },
      traits = MediaTrait.toBits(meta?.traits.orEmpty()),
      geo = meta?.location?.let { GeoE7((it.latitude * 1e7).roundToInt(), (it.longitude * 1e7).roundToInt()) },
      thumbHash = meta?.thumbHash,
    )
    // Un MIME che il formato non ammette (stringa strana da un'estrazione) non deve fermare la preparazione.
    return runCatching { build(meta?.mimeType ?: item.mime) }.getOrElse { runCatching { build(null) }.getOrNull() }
  }
}

/**
 * Come si apre l'originale di un media da preparare (porta `PrepInputs`): `NasMediaReader` in background (60 s a blocco,
 * D3) e un [NasMediaBytes] a intervalli (i video non si scaricano mai interi, D12). Se il file non ha piu' il timbro
 * dell'elenco (modificato dal PC) non si prepara: `SOURCE_CHANGED`, lo rilevera' la scansione.
 */
class NasPrepInputs(private val reader: () -> NasMediaReader) : PrepInputs {

  override suspend fun open(share: String, item: WorkItem): PrepOpen =
    when (val opened = reader().open(share, item.path, Purpose.BACKGROUND)) {
      is NasResult.Err -> PrepOpen.Unavailable(codeOf(opened.error))
      is NasResult.Ok -> {
        val file = opened.value
        if (file.size != item.stamp.size || file.stamp.modifiedAtMillis != item.stamp.modifiedAtMillis) {
          runCatching { file.close() }
          PrepOpen.Unavailable(PrepFailCode.SOURCE_CHANGED)
        } else {
          PrepOpen.Ready(PrepareSource.Bytes(NasMediaBytes(file), item.kind, item.mime)) { runCatching { file.close() } }
        }
      }
    }

  private fun codeOf(error: NasError): PrepFailCode = when (error) {
    NasError.NotFound, NasError.FileBusy -> PrepFailCode.SOURCE_CHANGED
    else -> PrepFailCode.NAS_UNREACHABLE
  }
}

/**
 * I tentativi falliti della preparazione per chiave di contenuto (porta `PrepAttempts`, E-M10): un piccolo file in
 * `noBackupFilesDir` (solo chiavi di contenuto e conteggi, mai percorsi), scritto con una rinomina atomica. Alla quinta
 * volta il giro pubblica "non leggibile" e [gaveUp] tiene il media fuori dalle unita' di questo dispositivo.
 */
class FilePrepAttempts(private val file: File) : PrepAttempts {

  private val lock = Mutex()
  private var loaded: MutableMap<String, Int>? = null

  override suspend fun failed(share: String, item: WorkItem, code: PrepFailCode): Int = lock.withLock {
    val counts = load()
    val count = (counts[item.contentKey.hex] ?: 0) + 1
    counts[item.contentKey.hex] = count
    save(counts)
    count
  }

  suspend fun gaveUp(contentKey: ContentKey): Boolean = lock.withLock { (load()[contentKey.hex] ?: 0) >= PrepAttempts.MAX_ATTEMPTS }

  private suspend fun load(): MutableMap<String, Int> {
    loaded?.let { return it }
    val read = withContext(Dispatchers.IO) {
      try {
        val lines = StateFiles.readLines(file) ?: return@withContext HashMap()
        lines.mapNotNull { line ->
          val parts = line.split(' ')
          val key = parts.getOrNull(0)?.takeIf { HEX.matches(it) } ?: return@mapNotNull null
          val count = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
          key to count
        }.toMap(HashMap())
      } catch (e: IOException) {
        GalleryLog.w(TAG) { "tentativi della preparazione non letti" }
        HashMap()
      }
    }
    loaded = read
    return read
  }

  private suspend fun save(counts: Map<String, Int>) = withContext(Dispatchers.IO) {
    try {
      StateFiles.writeText(file, counts.entries.joinToString("\n") { "${it.key} ${it.value}" })
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "tentativi della preparazione non salvati" }
    }
  }

  private companion object {
    const val TAG = "PrepAttempts"
    val HEX = Regex("^[0-9a-f]{64}$")
  }
}
