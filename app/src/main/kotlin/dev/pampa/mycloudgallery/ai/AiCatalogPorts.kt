package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.compute.AiInputOrigin
import dev.pampa.mycloudgallery.core.ai.compute.AiUnsupportedFrom
import dev.pampa.mycloudgallery.core.ai.compute.AreaView
import dev.pampa.mycloudgallery.core.ai.compute.DeviceRoleView
import dev.pampa.mycloudgallery.core.ai.compute.IndexerAreas
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidate
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidates
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.NasWorkItem
import dev.pampa.mycloudgallery.core.index.ai.AiKey
import dev.pampa.mycloudgallery.core.index.ai.AiPublishStore
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaStore
import dev.pampa.mycloudgallery.core.index.ai.AiRowState
import dev.pampa.mycloudgallery.core.index.ai.AiShareContent
import dev.pampa.mycloudgallery.core.index.ai.AiWorkCatalog
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.search.contract.CoverageReason
import dev.pampa.mycloudgallery.core.search.coverage.CoverageNotices
import dev.pampa.mycloudgallery.core.search.coverage.CoverageReasons
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// Le PORTE dei dati AI dell'indice condiviso (P2) e del calcolo (P5) sopra il catalogo locale (Fase 6, P11). Solo dati locali: le
// chiavi di contenuto vengono dalle posizioni del NAS che il catalogo conosce (impronta rapida e dimensione, `ContentKey.of`), mai da un
// record remoto.

/** I file **presenti** di una share per l'account di questo dispositivo, con la loro chiave di contenuto (versione corrente). */
internal suspend fun shareRows(catalog: MediaCatalog, account: AccountId, share: String): List<Pair<ContentKey, NasWorkItem>> =
  catalog.nasWorkRows(account, share).mapNotNull { row ->
    runCatching { ContentKey.of(row.quickHash, row.stamp.size) }.getOrNull()?.let { it to row }
  }

/** Il mese di un media (`aaaa-mm`, `0000-00` senza data), come le unita' della preparazione. */
internal fun monthOf(localDay: Int?): String {
  if (localDay == null || localDay <= 0) return "0000-00"
  return "%04d-%02d".format(Locale.ROOT, localDay / 10_000, localDay / 100 % 100)
}

/**
 * La regola **M3** con le chiavi (D8): delle chiavi date, quelle che sono la chiave di contenuto di un file **presente** di [share]
 * nel catalogo locale, con la versione corrente (timbro e impronta rapida dell'ultimo elenco).
 *
 * IX-6: le chiavi della share si leggono una volta e valgono finche' il catalogo non cambia (`changeSeq`) e al piu' [freshNanos]
 * (orologio monotono): un lotto del calcolo non rilegge tutta la share per ogni media.
 */
class CatalogAiShareContent(
  private val catalog: suspend () -> MediaCatalog?,
  private val account: suspend () -> AccountId?,
  private val freshNanos: Long = SHARE_ROWS_FRESH_NANOS,
  private val nanos: () -> Long = System::nanoTime,
) : AiShareContent {
  private val rows = ShareRowsCache(freshNanos, nanos)

  override suspend fun owned(share: String, keys: Collection<ContentKey>): Set<ContentKey> {
    if (keys.isEmpty()) return emptySet()
    val c = catalog() ?: return emptySet()
    val owner = account() ?: return emptySet()
    val known = rows.keysOf(c, owner, share)
    return keys.filterTo(HashSet()) { it in known }
  }
}

/** Le righe dei file presenti di una share, ricordate finche' il catalogo non cambia e al piu' per poco (IX-6). */
internal class ShareRowsCache(private val freshNanos: Long, private val nanos: () -> Long) {
  private class Entry(val seq: Long, val at: Long, val rows: List<Pair<ContentKey, NasWorkItem>>, val keys: Set<ContentKey>)

  private val lock = Any()
  private val byShare = HashMap<String, Entry>()

  suspend fun rowsOf(catalog: MediaCatalog, owner: AccountId, share: String): List<Pair<ContentKey, NasWorkItem>> = entry(catalog, owner, share).rows

  suspend fun keysOf(catalog: MediaCatalog, owner: AccountId, share: String): Set<ContentKey> = entry(catalog, owner, share).keys

  private suspend fun entry(catalog: MediaCatalog, owner: AccountId, share: String): Entry {
    val name = share.lowercase(Locale.ROOT)
    val seq = catalog.changeSeq()
    val now = nanos()
    synchronized(lock) { byShare[name] }?.takeIf { it.seq == seq && now - it.at in 0..freshNanos }?.let { return it }
    val fresh = shareRows(catalog, owner, share)
    val entry = Entry(seq, now, fresh, fresh.mapTo(HashSet()) { it.first })
    synchronized(lock) { byShare[name] = entry }
    return entry
  }
}

/** IX-6: le righe di una share valgono al piu' 10 secondi (e finche' il catalogo non cambia). */
const val SHARE_ROWS_FRESH_NANOS: Long = 10_000_000_000L

/**
 * I media "non supportati" per classe (E-A12: ingresso rifiutato, vettore non valido due volte): segnati dal calcolo, letti dal
 * catalogo del lavoro (non si riprovano a ogni giro) e dalla copertura. Un file di righe in `noBackupFilesDir`; l'azzeramento lo toglie.
 *
 * Revisione A (R5, IX-7): il segno ricorda **da dove** veniva l'ingresso rifiutato ([mark] con [AiInputOrigin]) e vale solo li': un
 * ingresso proprio vale ovunque, l'anteprima di un altro dispositivo solo nella sua share ([classesOf] con la share), e la revoca di chi
 * l'ha preparata lo toglie ([forgetDevice]). Ogni segno **scade** dopo [EXPIRY_MILLIS]: il media si riprova. Le righe di prima (senza
 * provenienza ne' data) non valgono piu': quei media si riprovano una volta. Le share si scrivono come impronta, mai per nome.
 */
class FileAiUnsupported(
  private val file: File,
  /** L'ora del telefono: serve solo a far scadere i segni (un orologio sbagliato fa riprovare prima o dopo, niente di piu'). */
  private val now: () -> Long = System::currentTimeMillis,
) : AiUnsupportedFrom {
  private class Mark(val key: AiKey, val share: String?, val device: String?, val at: Long)

  private val lock = Any()
  private var loaded: MutableList<Mark>? = null

  override suspend fun mark(key: ContentKey, cls: AiClass) = mark(key, cls, AiInputOrigin.Own)

  override suspend fun mark(key: ContentKey, cls: AiClass, from: AiInputOrigin) {
    synchronized(lock) {
      val marks = loadLocked()
      val share = (from as? AiInputOrigin.Preview)?.let { shareTag(it.share) }
      val device = (from as? AiInputOrigin.Preview)?.device
      val at = now()
      marks.removeAll { it.key == AiKey(key, cls) && it.share == share }
      val mark = Mark(AiKey(key, cls), share, device, at)
      marks += mark
      try {
        file.parentFile?.mkdirs()
        file.appendText(line(mark))
      } catch (e: IOException) {
        // Resta in memoria per questo processo: al prossimo il media si riprova una volta.
      }
    }
  }

  /** Le classi "non supportate" di [keys] per i media del telefono: solo i segni nati dai propri byte. */
  fun classesOf(keys: Collection<ContentKey>): Map<ContentKey, Set<AiClass>> = classesOf(keys, null)

  /** Le classi "non supportate" di [keys] in [share] (null = il telefono): i segni propri e quelli di un'anteprima di quella share. */
  fun classesOf(keys: Collection<ContentKey>, share: String?): Map<ContentKey, Set<AiClass>> = synchronized(lock) {
    val tag = share?.let { shareTag(it) }
    val fresh = now()
    val valid = loadLocked().filter { m -> fresh - m.at in 0 until EXPIRY_MILLIS && (m.share == null || m.share == tag) }
      .mapTo(HashSet()) { it.key }
    keys.associateWith { k -> AiClass.entries.filterTo(LinkedHashSet()) { AiKey(k, it) in valid } }.filterValues { it.isNotEmpty() }
  }

  /** R5: un dispositivo revocato: i segni nati dalle sue anteprime (e da quelle di un dispositivo non noto) si tolgono. */
  fun forgetDevice(device: String) {
    synchronized(lock) {
      val marks = loadLocked()
      if (!marks.removeAll { it.share != null && (it.device == device || it.device == null) }) return
      rewriteLocked(marks)
    }
  }

  private fun line(m: Mark): String = "$V2 ${m.key.contentKey.hex} ${m.key.cls.wire} ${m.share ?: OWN} ${m.device ?: if (m.share == null) OWN else UNKNOWN} ${m.at}\n"

  private fun rewriteLocked(marks: List<Mark>) {
    try {
      val fresh = now()
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText(marks.filter { fresh - it.at in 0 until EXPIRY_MILLIS }.joinToString("") { line(it) })
      if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
      }
    } catch (e: IOException) {
      // Resta in memoria.
    }
  }

  private fun loadLocked(): MutableList<Mark> = loaded ?: ArrayList<Mark>().also { marks ->
    try {
      if (file.isFile) {
        file.readLines().forEach { raw ->
          val parts = raw.trim().split(' ')
          if (parts.size != 6 || parts[0] != V2) return@forEach
          val cls = AiClass.fromWire(parts[2]) ?: return@forEach
          val key = runCatching { ContentKey(parts[1]) }.getOrNull() ?: return@forEach
          val at = parts[5].toLongOrNull() ?: return@forEach
          val share = parts[3].takeUnless { it == OWN }
          val device = parts[4].takeUnless { it == OWN || it == UNKNOWN }
          marks += Mark(AiKey(key, cls), share, device, at)
        }
      }
    } catch (e: IOException) {
      // Nel dubbio niente e' "non supportato": il calcolo riprova.
    }
    loaded = marks
  }

  companion object {
    const val FILE = "ai-unsupported"

    /** Un segno "non supportato" vale 30 giorni, poi il media si riprova (R5, IX-7). */
    const val EXPIRY_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    private const val V2 = "v2"
    private const val OWN = "own"
    private const val UNKNOWN = "-"
  }
}

/**
 * Il catalogo del lavoro AI (D9, `AiWorkSource`): i media **presenti** della share per mese e, per P5 (`REPORT-p5`), le classi gia' a
 * posto **nella share**: i vincitori consegnati dalla replica `ai` (anche i propri, una volta pubblicati e riletti) con la ricetta
 * corrente, oppure "non supportato" per questa share. **Non** conta cio' che e' salvato solo qui: il calcolo lo rimette nell'outbox senza
 * ricalcolarlo, e un media salvato ma mai pubblicato (morte del processo) torna cosi' in un'unita'.
 *
 * Revisione A:
 * - IX-6: conta come a posto anche cio' che questo dispositivo ha **gia' in fila** per la share ([publishStore]: nell'outbox o in attesa di
 *   una share pubblicabile, F4-D31): sulle share vere le tappe non ripercorrono ogni notte tutto cio' che e' gia' calcolato. Le righe della
 *   share si leggono una volta per tappa ([ShareRowsCache]);
 * - R16: "a posto" vuol dire la ricetta corrente, non una piu' alta (una 255 non conta come fatta);
 * - R5: un segno "non supportato" vale solo nella share della sua anteprima (o ovunque se dai propri byte).
 */
class CatalogAiWorkCatalog(
  private val catalog: suspend () -> MediaCatalog?,
  private val account: suspend () -> AccountId?,
  private val replica: AiReplicaStore,
  private val unsupported: FileAiUnsupported,
  private val recipe: AiRecipe = AiRecipe.CURRENT,
  /** Cio' che si elenca si ricorda per chiave (i byte del telefono per i media del NAS che sono anche qui, la sorgente degli screenshot). */
  private val facts: ContentFacts? = null,
  /** IX-6: le voci di questo dispositivo in fila per la share (null = nessuna, come prima). */
  private val publishStore: AiPublishStore? = null,
  freshNanos: Long = SHARE_ROWS_FRESH_NANOS,
  nanos: () -> Long = System::nanoTime,
) : AiWorkCatalog {

  private val cache = ShareRowsCache(freshNanos, nanos)

  private suspend fun rows(share: String): List<Pair<ContentKey, NasWorkItem>> {
    val c = catalog() ?: return emptyList()
    val owner = account() ?: return emptyList()
    return cache.rowsOf(c, owner, share)
  }

  override suspend fun months(share: String): List<String> = rows(share).map { monthOf(it.second.localDay) }.distinct().sortedDescending()

  override suspend fun monthsOf(share: String, visible: Set<Long>): List<String> =
    rows(share).filter { it.first.short in visible }.map { monthOf(it.second.localDay) }.distinct().sortedDescending()

  override suspend fun monthMedia(share: String, month: String): List<WorkItem> = rows(share).mapNotNull { (key, row) ->
    if (monthOf(row.localDay) != month) return@mapNotNull null
    val path = runCatching { SharePath.parseMediaPath(row.path.value) }.getOrNull() ?: return@mapNotNull null
    facts?.put(key, ContentFact(row.mediaId, row.versionId, row.stamp.size, row.source, row.quickHash))
    WorkItem(key, path, row.stamp, row.kind, row.mime, row.mediaId, row.versionId, emptySet())
  }

  override suspend fun settled(share: String, keys: Collection<ContentKey>): Map<ContentKey, Set<AiClass>> {
    if (keys.isEmpty()) return emptyMap()
    val out = HashMap<ContentKey, MutableSet<AiClass>>()
    val wanted = keys.flatMap { k -> AiClass.entries.map { AiKey(k, it) } }
    for ((key, mark) in replica.applied(share.lowercase(Locale.ROOT), wanted)) {
      val current = if (key.cls == AiClass.VEC) recipe.version else recipe.ocrVersion
      if (mark.recipe == current) out.getOrPut(key.contentKey) { LinkedHashSet() } += key.cls
    }
    unsupported.classesOf(keys, share).forEach { (k, classes) -> out.getOrPut(k) { LinkedHashSet() } += classes }
    queued(share, keys.toHashSet()).forEach { (k, cls) -> out.getOrPut(k) { LinkedHashSet() } += cls }
    return out
  }

  /** IX-6: le (chiave, classe) di [keys] che questo dispositivo ha in fila per [share] (outbox o in attesa). */
  private suspend fun queued(share: String, keys: Set<ContentKey>): List<Pair<ContentKey, AiClass>> {
    val store = publishStore ?: return emptyList()
    val refs = store.refs(share, AiRowState.OUTBOX) + store.refs(share, AiRowState.HELD)
    return refs.mapNotNull { r -> r.key.takeIf { it.contentKey in keys }?.let { it.contentKey to it.cls } }
  }
}

/**
 * I candidati del calcolo dal catalogo locale (contratto `AiCandidates`): per una share (il calcolo con il solo buffer, E-A6) i media
 * presenti dal piu' recente con le classi che mancano nella share; i media del solo telefono da [phone] (P11b: dai byte del telefono, risultati
 * solo locali). Senza [phone] (i montaggi che non lo danno) nessuno.
 */
class CatalogAiCandidates(
  private val work: CatalogAiWorkCatalog,
  private val readingHere: () -> Boolean,
  private val phone: AppAiPhone? = null,
) : AiCandidates {
  override suspend fun forShare(share: String, recipe: Int, limit: Int): List<AiCandidate> {
    val months = work.months(share)
    val out = ArrayList<AiCandidate>()
    for (month in months) {
      val media = work.monthMedia(share, month)
      val settled = work.settled(share, media.map { it.contentKey })
      for (item in media) {
        val wanted = if (readingHere()) setOf(AiClass.VEC, AiClass.OCR) else setOf(AiClass.VEC)
        val missing = wanted - settled[item.contentKey].orEmpty()
        if (missing.isNotEmpty()) out += AiCandidate(item.contentKey, item.kind, MediaSource.OTHER, missing)
        if (out.size >= limit) return out
      }
    }
    return out
  }

  override suspend fun phoneOnly(recipe: Int, limit: Int): List<AiCandidate> = phone?.candidates(readingHere(), limit).orEmpty()
}

/**
 * PR-1 (D14, D31, D42, E-I12): la copertura detta all'utente, in Cerca e nella pagina Ricerca e modelli.
 *
 * - **Aree senza indicizzatori** ([uncovered]): l'area privata di questo account e l'area comune, contro i dispositivi approvati del
 *   registro con il loro stato firmato (ruolo e ultima volta visto, [IndexerAreas]); questo dispositivo vale col ruolo di adesso. Finche'
 *   gli stati non si sono letti, o manca l'ora del NAS, nessuna area si dice scoperta: un'informazione, mai un'ipotesi. La prima volta che
 *   un'area risulta scoperta lo si dice alla Diagnostica ([told], `AI_NO_INDEXER_AREA`).
 * - **Motivi** di una cella senza vettore ([reasons]), nell'ordine: formato non supportato (un segno "non supportato" del vettore, dai
 *   propri byte o da un'anteprima di una sua share); solo sul telefono di un dispositivo che non analizza; solo sul telefono di un
 *   indicizzatore senza il modello; tutte le sue share senza indicizzatori; altrimenti in preparazione.
 * - **Lettura del testo non attiva** ([textReadingOff]): lo dice [readingOff] (lo stato della lettura per l'account e il testo che c'e').
 *
 * Solo tipi di area e conteggi: mai nomi di share fuori da qui.
 */
class AppCoverage(
  private val areas: suspend () -> List<AreaView>,
  /** I dispositivi con il loro ruolo, o null se gli stati firmati non si sono ancora letti. */
  private val devices: suspend () -> List<DeviceRoleView>?,
  private val nasNow: () -> Long?,
  private val catalog: suspend () -> MediaCatalog?,
  private val indexerHere: () -> Boolean,
  private val modelReadyHere: suspend () -> Boolean,
  private val unsupported: () -> FileAiUnsupported?,
  private val readingOff: suspend () -> Boolean,
  private val told: (List<AreaView>) -> Unit = {},
) : CoverageReasons, CoverageNotices {
  private val reported = AtomicBoolean(false)

  /** Le aree (di questo account e comune) senza un indicizzatore vivo adesso. */
  suspend fun uncovered(): List<AreaView> {
    val now = nasNow() ?: return emptyList()
    val known = devices() ?: return emptyList()
    val out = IndexerAreas.uncovered(areas(), known, now)
    if (out.isEmpty()) reported.set(false) else if (reported.compareAndSet(false, true)) told(out)
    return out
  }

  override suspend fun areasWithoutIndexer(): List<Boolean> = uncovered().map { it.ownerAccount == null }

  override suspend fun textReadingOff(): Boolean = readingOff()

  override suspend fun reasons(missing: List<MediaId>): Map<CoverageReason, Int> {
    if (missing.isEmpty()) return emptyMap()
    val cat = catalog() ?: return mapOf(CoverageReason.PREPARATION to missing.size)
    val shares = cat.nasSharesOf(missing)
    val uncoveredKeys = uncovered().mapTo(HashSet()) { Location.Nas.shareKeyOf(it.share) }
    val notSupported = unsupportedAmong(cat, missing, shares)
    val indexer = indexerHere()
    val model = modelReadyHere()
    val out = LinkedHashMap<CoverageReason, Int>()
    for (id in missing) {
      val where = shares[id].orEmpty()
      val reason = when {
        id in notSupported -> CoverageReason.UNSUPPORTED
        where.isEmpty() && !indexer -> CoverageReason.PHONE_ONLY_NOT_INDEXER
        where.isEmpty() && !model -> CoverageReason.MODEL_MISSING
        where.isNotEmpty() && where.all { it in uncoveredKeys } -> CoverageReason.NO_INDEXER_FOR_AREA
        else -> CoverageReason.PREPARATION
      }
      out[reason] = (out[reason] ?: 0) + 1
    }
    return out
  }

  /** Le celle di [ids] con il vettore "non supportato": un segno dai propri byte, o da un'anteprima di una delle loro share (R5). */
  private suspend fun unsupportedAmong(cat: MediaCatalog, ids: List<MediaId>, shares: Map<MediaId, Set<String>>): Set<MediaId> {
    val marks = unsupported() ?: return emptySet()
    val cells = cat.cellContentKeysOf(ids)
    val out = HashSet<MediaId>()
    val own = marks.classesOf(cells.flatMap { it.own })
    for (cell in cells) {
      if (cell.own.any { AiClass.VEC in own[it].orEmpty() }) {
        out += cell.mediaId
        continue
      }
      for (share in shares[cell.mediaId].orEmpty()) {
        if (marks.classesOf(cell.own, share).values.any { AiClass.VEC in it }) {
          out += cell.mediaId
          break
        }
      }
    }
    return out
  }
}
