package dev.pampa.mycloudgallery.core.search.index

import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.AiResults
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.database.AiTextRecord
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import dev.pampa.mycloudgallery.core.trust.data.VecItem
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * I tetti dell'indice locale (revisione A, R1 e R3): un dispositivo compromesso non riempie il telefono ne' rompe la ricerca degli altri.
 * Valgono per ogni origine **d'altri** (una share e un dispositivo: la replica, o un calcolo fatto qui sull'anteprima di un altro); il
 * calcolo di questo dispositivo dai propri byte non ha tetti (e' limitato dai media di chi lo usa).
 */
data class IndexLimits(
  /** Record dei vettori per origine: un vettore per ognuno dei 262.144 record che un dispositivo puo' avere in una share (M9). */
  val maxRecordsPerOrigin: Long = 262_144,
  /** Byte di testo letto (normalizzato e corpo `ocr`) per origine: 128 MiB, ben oltre un archivio di famiglia pieno di schermate. */
  val maxTextBytesPerOrigin: Long = 128L * 1024 * 1024,
  /** Compattazione subito (non solo di notte) quando i record morti superano quelli vivi e almeno questi (circa 100 MB). */
  val compactDeadRecords: Long = 65_536,
  /** Compattazione del solo registro quando i byte morti superano quelli vivi e almeno questi. */
  val compactDeadLedgerBytes: Long = 32L * 1024 * 1024,
  /** Sotto questo spazio libero la replica non si applica (riprova al giro dopo). */
  val minFreeBytes: Long = 256L * 1024 * 1024,
) {
  init {
    require(maxRecordsPerOrigin > 0 && maxTextBytesPerOrigin > 0 && compactDeadRecords > 0 && compactDeadLedgerBytes > 0) { "Tetti non validi." }
  }
}

/**
 * L'INDICE LOCALE DI RICERCA (docs/fase6-progetto.md §2.4, D23, D24): vettori e testo per **chiave di contenuto**, da qualunque
 * origine (telefono, area privata, area comune), una volta sola anche se lo stesso contenuto sta in tre posti.
 *
 * - **Scritture**: il calcolo di questo dispositivo ([AiResults], P5) e la replica delle teste `ai` ([applyReplica], [withdraw],
 *   [forgetDevice], [forgetShare]: :app li lega ad `AiReplicaSink` di P2). Ogni dato ha la sua **origine** ([OriginLedger]): la
 *   replica toglie solo le sue, mai cio' che e' stato calcolato qui dai propri byte.
 * - **Preferenza** (D7, revisione A R4 e R16): la ricetta piu' alta **fra quelle che quest'app conosce** (una ricetta oltre [knownRecipe]
 *   vale come quella: un dispositivo compromesso non vince con 255), poi questo dispositivo, poi i calcoli fatti qui su anteprime altrui,
 *   poi la replica; a parita', share e dispositivo in ordine. Le righe del testo dal vivo ([textBody]) seguono la stessa scelta.
 * - **Provenienza** (R5): un calcolo fatto qui sull'anteprima di un altro dispositivo ([Derivation.Preview]) non e' "proprio": vale solo per
 *   la share in cui l'anteprima era attestata ([savedFor], [localVectors], [localText]) e cade con la revoca di chi l'ha preparata.
 * - **Tetti** ([limits], R1 e R3): record e testo per origine d'altri; oltre, le voci si scartano e si contano. Il file dei vettori si
 *   compatta anche subito quando i record morti superano i vivi; un'apertura che fallisce ricostruisce l'indice e chiede di riavere tutto.
 * - **Ordine delle scritture** (morte del processo a ogni passo): file dei vettori (`force`), testo nel database, registro (`force`,
 *   il punto di conferma). Una ripetizione (la replica ripete dopo una morte) e' idempotente.
 * - **Memoria**: uno "slot" per vettore scelto con i codici int8 della prima passata e la posizione nel file per la seconda. Le
 *   ricerche leggono sotto [lock] in lettura, senza sospendersi.
 */
class LocalAiIndex(
  private val dir: File,
  private val texts: TextRowStore,
  private val repair: IndexRepair = IndexRepair.NONE,
  val space: SpaceCode = SpaceCode.CURRENT,
  val counters: SearchIndexCounters = SearchIndexCounters(),
  val limits: IndexLimits = IndexLimits(),
  /** R16: la ricetta piu' alta che quest'app conosce per ogni classe (quella corrente). */
  private val knownRecipe: (AiClass) -> Int = { if (it == AiClass.VEC) AiRecipe.CURRENT.version else AiRecipe.CURRENT.ocrVersion },
  /** Lo spazio libero del volume dell'indice (R3): di serie quello della prima cartella che c'e'. */
  private val freeSpace: () -> Long = { generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: Long.MAX_VALUE },
) : AiResults, Closeable {

  /** Da dove viene un risultato calcolato qui (R5). */
  sealed interface Derivation {
    /** Dai byte che questo dispositivo ha letto e preparato da se' (il telefono, un originale del NAS): vale per ogni share. */
    data object Own : Derivation

    /** Dall'anteprima di [device] attestata in [share] ([device] null = non noto): vale solo per [share]. */
    data class Preview(val share: String, val device: String?) : Derivation {
      override fun toString(): String = "Preview"
    }
  }

  internal val dimension: Int = space.space.dimension
  internal val lock = ReentrantReadWriteLock()
  private val writes = Mutex()

  /** Chiavi confermate nel registro ma non ancora riconciliate in SQL (SOL-1). */
  private val pendingTexts = HashSet<ContentKey>()
  private val ledger = OriginLedger(File(dir, LEDGER))
  private var vectorFile: VectorFile? = null

  internal val file: VectorFile get() = vectorFile ?: throw IllegalStateException("Indice non aperto.")

  // Gli slot (uno per vettore scelto). Si scrivono sotto lock.write; le ricerche li leggono sotto lock.read.
  internal var codes: ByteArray = ByteArray(0)
    private set
  internal var scales: FloatArray = FloatArray(0)
    private set
  internal var offsets: LongArray = LongArray(0)
    private set
  private var slotKeys: Array<ContentKey?> = arrayOfNulls(0)

  /** 1 = record gia' verificato (CRC) in questo processo. Scritture benigne da piu' thread (sempre lo stesso valore). */
  internal var verified: ByteArray = ByteArray(0)
    private set
  private var slotCount = 0
  private val freeSlots = ArrayDeque<Int>()
  private val chosenVectors = HashMap<ContentKey, Chosen>()
  private val chosenTexts = HashMap<ContentKey, Chosen>()
  private val corrupt: MutableSet<ContentKey> = ConcurrentHashMap.newKeySet()
  private var coarseDirty = false

  private val changes = MutableStateFlow(0L)

  /**
   * Cresce a ogni cambiamento dell'indice, **dentro** il blocco di scrittura: chi legge sotto [lock] in lettura e trova lo stesso
   * valore sa che gli slot non sono cambiati (mappa delle celle e copertura si ricalcolano solo allora, non a ogni query).
   */
  val version: StateFlow<Long> get() = changes

  /** Chi impara le parole dei testi nuovi (il vocabolario dei refusi del motore). */
  @Volatile internal var textListener: ((String) -> Unit)? = null

  internal class Chosen(val origin: AiOrigin, val recipe: Int, val slots: IntArray, val holding: Holding)

  /** Un vettore da scrivere: l'origine, la chiave, i record e la sorgente (`VecSource`, per ripubblicare senza ricalcolo). */
  private class VecPut(val origin: AiOrigin, val key: ContentKey, val records: List<VectorRecord>, val source: Int)

  /**
   * Apre l'indice: rilegge il registro, la prima passata dal suo file (o la ricostruisce) e riconcilia le righe SQL, anche mancanti. Se
   * l'apertura fallisce (R1: file illeggibile, memoria), l'indice si **ricostruisce vuoto** e chiede di riavere tutto (la replica
   * riconsegna, il calcolo rifa'): mai una ricerca ferma per tutto il processo.
   */
  suspend fun open(): Unit = writes.withLock {
    try {
      openLocked()
    } catch (e: CancellationException) {
      throw e
    } catch (e: TextRowsUnavailable) {
      // Un guasto SQL non e' un registro rovinato: conservarlo per il prossimo tentativo.
      throw e
    } catch (e: Exception) {
      resetLocked()
    } catch (e: OutOfMemoryError) {
      resetLocked()
    }
  }

  // ---- Letture per il motore (sotto lock.read) ----

  internal fun vectorSlots(key: ContentKey): IntArray? = chosenVectors[key]?.slots

  /** Vero se per [key] c'e' un vettore scelto (dalla memoria, senza attese: lo chiede il menu del viewer a ogni composizione). */
  fun hasVectors(key: ContentKey): Boolean = lock.read { chosenVectors.containsKey(key) }

  internal fun vectorRecipe(key: ContentKey): Int = chosenVectors[key]?.recipe ?: 0

  internal fun hasText(key: ContentKey): Boolean = chosenTexts.containsKey(key)

  internal fun textRecipe(key: ContentKey): Int = chosenTexts[key]?.recipe ?: 0

  /** Il testo normalizzato scelto per [key], o null. */
  internal fun text(key: ContentKey): String? = lock.read {
    chosenTexts[key]?.holding?.text?.let { ledger.readText(it) }
  }

  /** Righe e caratteri del testo scelto per [key] (la regola dei documenti, D34), o null senza testo. */
  fun textStats(key: ContentKey): Pair<Int, Int>? = lock.read { chosenTexts[key]?.holding?.text?.let { it.lines to it.chars } }

  /**
   * Il corpo `ocr` **che vince** per [key] (R4, VT-2): le righe del testo dal vivo seguono la scelta dell'indice (prima la lettura di
   * questo dispositivo), non l'ultimo arrivato; un testo ritirato o di un dispositivo revocato non c'e' piu'. Null = nessun testo con le
   * righe (mai letto, o una voce vecchia senza corpo).
   */
  fun textBody(key: ContentKey): OcrBody? {
    val bytes = lock.read { chosenTexts[key]?.holding?.text?.let { ledger.readBody(it) } } ?: return null
    return AiSegments.decodeBody(AiClass.OCR, bytes).acceptedOrNull() as? OcrBody
  }

  /** Le posizioni dei vettori scelti per [key] (per "Trova simili" e per l'esecutore di riferimento). */
  internal fun vectors(key: ContentKey): List<FloatArray> = lock.read {
    val slots = chosenVectors[key]?.slots ?: return emptyList()
    val scratch = ShortArray(dimension)
    slots.toList().mapNotNull { slot ->
      if (!verify(slot)) return@mapNotNull null
      file.halves(offsets[slot], scratch)
      FloatArray(dimension) { HalfTable.of(scratch[it]) }
    }
  }

  /** Vero se il record dello slot e' sano (verificato una volta per processo); un record rovinato va fra quelli da riparare. */
  internal fun verify(slot: Int): Boolean {
    if (verified[slot].toInt() == 1) return true
    if (file.verify(offsets[slot])) {
      verified[slot] = 1
      return true
    }
    slotKeys[slot]?.let { corrupt += it }
    return false
  }

  /** Il coseno esatto (float32 sui float16) fra [query] e lo slot, o null se il record e' rovinato. */
  internal fun exact(slot: Int, query: FloatArray, scratch: ShortArray): Float? {
    if (!verify(slot)) return null
    return file.dot(offsets[slot], query, scratch)
  }

  /** Quante chiavi hanno vettori e testo scelti (per i test e per la pagina Ricerca e modelli). */
  val sizes: Pair<Int, Int> get() = lock.read { chosenVectors.size to chosenTexts.size }

  /** Vero se ci sono record rovinati da riparare ([repairPending]). */
  val needsRepair: Boolean get() = corrupt.isNotEmpty()

  // ---- AiResults (il calcolo di questo dispositivo, P5) ----

  override suspend fun saveVectors(key: ContentKey, body: VecBody) = saveVectors(key, body, Derivation.Own)

  override suspend fun saveText(key: ContentKey, body: OcrBody) = saveText(key, body, Derivation.Own)

  /** [saved] di P5: le classi calcolate qui **dai propri byte**, con la ricetta [recipe] (o una piu' alta) e il dato intero. */
  override suspend fun saved(key: ContentKey, recipe: Int): Set<AiClass> = savedFor(key, recipe, null)

  /** Un vettore calcolato qui, con la sua provenienza (R5). */
  suspend fun saveVectors(key: ContentKey, body: VecBody, from: Derivation) {
    val records = recordsOf(key, body) ?: return
    writes.withLock {
      val origin = originOf(from)
      val put = VecPut(origin, key, records, body.source.wire)
      if (!fits(origin, AiClass.VEC, key, records.size.toLong(), HashMap())) {
        counters.overCap.incrementAndGet()
        return@withLock
      }
      changeLocked(supersededBy(origin, key, AiClass.VEC), listOf(put), emptyList())
    }
  }

  /** Un testo letto qui, con la sua provenienza (R5). */
  suspend fun saveText(key: ContentKey, body: OcrBody, from: Derivation) {
    writes.withLock {
      val origin = originOf(from)
      val op = textOp(origin, key, body)
      if (!fits(origin, AiClass.OCR, key, textBytes(op), HashMap())) {
        counters.overCap.incrementAndGet()
        return@withLock
      }
      changeLocked(supersededBy(origin, key, AiClass.OCR), emptyList(), listOf(op))
    }
  }

  /**
   * Le classi calcolate qui per [key] con la ricetta [recipe] (o piu' alta) che valgono per [share] (R5): quelle dai propri byte, e per
   * una share ([share] non null) anche quelle calcolate su un'anteprima attestata **in quella share**. Solo voci intere (sorgente dei
   * vettori nota, corpo `ocr` presente): una voce di prima della revisione A si ricalcola una volta.
   */
  suspend fun savedFor(key: ContentKey, recipe: Int, share: String?): Set<AiClass> = writes.withLock {
    syncTexts()
    buildSet {
      if (usable(ledger.vectors[key], share).any { it.recipe >= recipe && it.source != 0 }) add(AiClass.VEC)
      if (usable(ledger.texts[key], share).any { it.recipe >= recipe && it.text?.hasBody == true }) add(AiClass.OCR)
    }
  }

  /** Il corpo `vec` calcolato qui che vale per [share] (prima quello dai propri byte), ricostruito dai record: per ripubblicarlo. */
  suspend fun localVectors(key: ContentKey, share: String?): VecBody? = writes.withLock {
    val holding = usable(ledger.vectors[key], share).firstOrNull { it.source != 0 } ?: return@withLock null
    val source = VecSource.fromWire(holding.source) ?: return@withLock null
    val records = holding.vectors!!.map { off -> file.read(off)?.takeIf { it.key == key } ?: return@withLock null }
    runCatching {
      VecBody(space, holding.recipe, source, records.map { VecItem(if (source == VecSource.FRAMES) it.instantMs ?: 0 else null, it.halves) })
    }.getOrNull()
  }

  /** Il corpo `ocr` letto qui che vale per [share] (prima quello dai propri byte): per ripubblicarlo. */
  suspend fun localText(key: ContentKey, share: String?): OcrBody? = writes.withLock {
    val ref = usable(ledger.texts[key], share).firstNotNullOfOrNull { h -> h.text?.takeIf { it.hasBody } } ?: return@withLock null
    val bytes = ledger.readBody(ref) ?: return@withLock null
    AiSegments.decodeBody(AiClass.OCR, bytes).acceptedOrNull() as? OcrBody
  }

  /** I dati calcolati qui che valgono per [share]: prima i propri, poi quelli su anteprime di quella share. */
  private fun usable(holders: Map<AiOrigin, Holding>?, share: String?): List<Holding> {
    if (holders == null) return emptyList()
    val wanted = share?.lowercase(Locale.ROOT)
    return holders.entries.filter { (o, _) -> o is AiOrigin.Local || (wanted != null && o is AiOrigin.Derived && o.share == wanted) }
      .sortedBy { it.key.rank }.map { it.value }
  }

  // ---- La replica delle teste `ai` (P2, legata da :app ad AiReplicaSink) ----

  /** Una voce vincitrice di una share (D7): sostituisce quella di prima della stessa (share, chiave, classe), anche di un altro autore. */
  sealed interface ReplicaItem {
    val key: ContentKey
    val device: String

    class Vectors(override val key: ContentKey, override val device: String, val body: VecBody) : ReplicaItem

    class Text(override val key: ContentKey, override val device: String, val body: OcrBody) : ReplicaItem
  }

  /**
   * I vincitori di [share]. Una voce che porterebbe la sua origine oltre i [limits] si scarta e si conta (R1, R3): resta quella di
   * prima. Con meno di [IndexLimits.minFreeBytes] liberi niente si applica ([IOException]: la replica riprova al giro dopo).
   */
  suspend fun applyReplica(share: String, items: List<ReplicaItem>): Unit = writes.withLock {
    if (items.isEmpty()) return@withLock
    if (freeSpace() < limits.minFreeBytes) throw IOException("Spazio libero insufficiente per l'indice di ricerca.")
    val vecs = ArrayList<VecPut>()
    val txts = ArrayList<LedgerOp.PutText>()
    val drops = ArrayList<LedgerOp>()
    val delta = HashMap<AiOrigin, LongArray>()
    for (item in items) {
      val origin = AiOrigin.replica(share, item.device)
      val cls = if (item is ReplicaItem.Vectors) AiClass.VEC else AiClass.OCR
      when (item) {
        is ReplicaItem.Vectors -> {
          val records = recordsOf(item.key, item.body) ?: continue
          if (!fits(origin, cls, item.key, records.size.toLong(), delta)) {
            counters.overCap.incrementAndGet()
            continue
          }
          vecs += VecPut(origin, item.key, records, item.body.source.wire)
        }
        is ReplicaItem.Text -> {
          val op = textOp(origin, item.key, item.body)
          if (!fits(origin, cls, item.key, textBytes(op), delta)) {
            counters.overCap.incrementAndGet()
            continue
          }
          txts += op
        }
      }
      // Un altro autore della stessa share perde la voce (il vincitore e' uno per share, chiave e classe).
      holders(cls)[item.key]?.keys?.filter { it is AiOrigin.Replica && it.share == origin.share && it != origin }?.forEach {
        drops += LedgerOp.Drop(it, item.key, cls)
      }
    }
    changeLocked(drops, vecs, txts)
  }

  /** Le voci di [share] che non hanno piu' un vincitore valido escono (D7, E-I21). Solo l'origine di quella share. */
  suspend fun withdraw(share: String, keys: List<Pair<ContentKey, AiClass>>): Unit = writes.withLock {
    val wanted = share.lowercase(Locale.ROOT)
    val drops = keys.flatMap { (key, cls) ->
      holders(cls)[key]?.keys?.filter { it is AiOrigin.Replica && it.share == wanted }.orEmpty().map { LedgerOp.Drop(it, key, cls) }
    }
    changeLocked(drops, emptyList(), emptyList())
  }

  /**
   * Un dispositivo revocato, perso o sconosciuto: escono le voci di cui era l'autore, in ogni share, e (R5) cio' che questo dispositivo
   * ha calcolato sulle sue anteprime (e su quelle di un dispositivo non noto: nel dubbio si ricalcola). Mai cio' che e' calcolato qui dai
   * propri byte. Le chiavi del calcolo tolte tornano "da fare" ([savedFor]).
   */
  suspend fun forgetDevice(device: String): Unit = writes.withLock {
    changeLocked(
      dropsWhere {
        (it is AiOrigin.Replica && it.device == device) || (it is AiOrigin.Derived && (it.device == device || it.device.isEmpty()))
      },
      emptyList(), emptyList(),
    )
  }

  /** Un'area persa (E-I8): escono le voci di quella share e cio' che e' stato calcolato qui sulle sue anteprime. */
  suspend fun forgetShare(share: String): Unit = writes.withLock {
    val wanted = share.lowercase(Locale.ROOT)
    changeLocked(dropsWhere { (it is AiOrigin.Replica && it.share == wanted) || (it is AiOrigin.Derived && it.share == wanted) }, emptyList(), emptyList())
  }

  // ---- Manutenzione ----

  /** Toglie i record rovinati trovati dalle ricerche e chiede di riaverli (replica o ricalcolo). Quante chiavi. */
  suspend fun repairPending(): Int = writes.withLock {
    if (corrupt.isEmpty()) return@withLock 0
    val keys = corrupt.toList()
    corrupt.removeAll(keys.toSet())
    val drops = keys.flatMap { key ->
      ledger.vectors[key].orEmpty().filter { (_, h) -> h.vectors!!.any { !file.verify(it) } }.map { (o, _) -> LedgerOp.Drop(o, key, AiClass.VEC) }
    }
    changeLocked(drops, emptyList(), emptyList())
    // Una chiave segnata ma senza piu' record rovinati (gia' riparata) si ricarica comunque.
    lock.write {
      keys.forEach { setVectorChoice(it, null) }
      changes.value++
    }
    counters.corruptRecords.addAndGet(keys.size)
    counters.rebuilt.incrementAndGet()
    repair.redeliver(keys)
    keys.size
  }

  /**
   * La compattazione (in `LibraryMaintenanceWorker`, §2.4): quando i record morti superano il 20% (o con [force]) il file dei vettori
   * si riscrive in una generazione nuova con i soli record vivi, poi il registro; altrimenti, se il registro ha molto testo morto (R3),
   * si riscrive solo lui. Salva comunque i codici della prima passata.
   */
  suspend fun maintain(force: Boolean = false): Boolean = writes.withLock {
    syncTexts()
    val total = file.length / file.recordSize
    val live = liveRecords()
    if (force || (total > 0L && (total - live) * 5 > total)) {
      compactLocked()
      return@withLock true
    }
    if (ledgerTooDead()) {
      compactLedgerLocked()
      return@withLock true
    }
    if (coarseDirty) writeCoarse()
    false
  }

  override fun close() {
    if (vectorFile != null && coarseDirty) runCatching { writeCoarse() }
    vectorFile?.close()
    vectorFile = null
    ledger.close()
  }

  // ---- Interni: compattazione e ricostruzione ----

  private fun liveRecords(): Long = ledger.usage.values.sumOf { it.records }

  private fun ledgerTooDead(): Boolean {
    val dead = ledger.length - ledger.liveBytes
    return dead > maxOf(ledger.liveBytes, limits.compactDeadLedgerBytes)
  }

  /** R1: compattazione subito, nel blocco di scrittura, quando i record morti superano i vivi (chi ricambia i vettori a ogni giro). */
  private suspend fun compactIfNeededLocked() {
    val total = file.length / file.recordSize
    val live = liveRecords()
    if (total - live > maxOf(live, limits.compactDeadRecords)) {
      compactLocked()
      counters.earlyCompactions.incrementAndGet()
    } else if (ledgerTooDead()) {
      compactLedgerLocked()
    }
  }

  private suspend fun compactLocked() {
    val generation = ledger.generation + 1
    val live = LinkedHashMap<Long, ContentKey>()
    ledger.vectors.forEach { (key, holders) -> holders.values.forEach { h -> h.vectors!!.forEach { live[it] = key } } }
    val remap = HashMap<Long, Long>(live.size * 2)
    VectorFile(vectorFileFor(generation), dimension).use { target ->
      for (chunk in live.keys.chunked(1_024)) {
        val read = chunk.mapNotNull { off -> file.read(off)?.takeIf { it.key == live[off] }?.let { off to it } }
        val positions = target.append(read.map { it.second })
        read.forEachIndexed { i, (off, _) -> remap[off] = positions[i] }
      }
    }
    val lost = live.filterKeys { it !in remap }.values.toSet()
    lock.write {
      val slots = (0 until slotCount).filter { slotKeys[it] != null && offsets[it] in remap }
      CoarseFile.write(coarseFile(), generation, entries(slots) { remap.getValue(offsets[it]) })
      // Il registro si riscrive con il lucchetto in scrittura: nessuna lettura del testo trova posizioni del giornale vecchio.
      ledger.compact(generation) { remap[it] }
    }
    counters.compactions.incrementAndGet()
    openLocked()
    if (lost.isNotEmpty()) {
      counters.corruptRecords.addAndGet(lost.size)
      repair.redeliver(lost)
    }
  }

  /** R3: il solo registro, con la stessa generazione (il testo morto di chi ripubblica testi diversi a ogni giro). */
  private suspend fun compactLedgerLocked() {
    if (coarseDirty) writeCoarse()
    lock.write { ledger.compact(ledger.generation) { it } }
    counters.compactions.incrementAndGet()
    openLocked()
  }

  /** R1: l'indice non si apre (file rovinato oltre ogni ripresa, memoria): si riparte vuoti e si chiede di riavere tutto. */
  private suspend fun resetLocked() {
    runCatching { vectorFile?.close() }
    vectorFile = null
    ledger.close()
    dir.listFiles()?.forEach { f ->
      if (f.name == LEDGER || f.name == "$LEDGER.tmp" || f.name.startsWith("vectors-") || f.name.startsWith("coarse-")) {
        // Un file dell'indice che non si toglie riaprirebbe lo stesso indice rovinato: lo dice il log di `StateFiles`.
        if (f.isDirectory) f.deleteRecursively() else StateFiles.delete(f)
      }
    }
    runCatching { texts.clear() }
    lock.write { resetSlots() }
    openLocked()
    counters.rebuilt.incrementAndGet()
    counters.resets.incrementAndGet()
    repair.redeliverAll()
  }

  // ---- Interni: apertura e scritture ----

  private suspend fun openLocked() {
    dir.mkdirs()
    val report = ledger.open()
    val coarse = CoarseFile.read(coarseFile(), ledger.generation)
    val byOffset = HashMap<Long, Int>((coarse?.size ?: 0) * 2)
    coarse?.offsets?.forEachIndexed { i, off -> byOffset[off] = i }
    val bad = ArrayList<LedgerOp>()
    val lostKeys = HashSet<ContentKey>()
    lock.write {
      // File e slot cambiano insieme sotto il lucchetto in scrittura: nessuna ricerca legge posizioni vecchie nel file nuovo
      // (compattazione con Cerca aperta).
      vectorFile?.close()
      vectorFile = VectorFile(vectorFileFor(ledger.generation), dimension)
      resetSlots()
      for ((key, holders) in ledger.vectors) {
        for ((origin, h) in holders.entries.sortedWith(preference(AiClass.VEC))) {
          val slots = allocate(key, h.vectors!!, null, coarse, byOffset)
          if (slots != null) {
            chosenVectors[key] = Chosen(origin, h.recipe, slots, h)
            break
          }
          bad += LedgerOp.Drop(origin, key, AiClass.VEC)
          lostKeys += key
        }
      }
      for ((key, holders) in ledger.texts) best(holders, AiClass.OCR)?.let { (o, h) -> chosenTexts[key] = Chosen(o, h.recipe, IntArray(0), h) }
      changes.value++
    }
    deleteOtherGenerations()
    if (bad.isNotEmpty()) ledger.commit(bad)
    // Il registro e' la fonte durevole: ripara righe mancanti, obsolete e rimozioni interrotte.
    // Vale anche per l'incidente delle versioni precedenti (DELETE riuscito, INSERT fallito).
    pendingTexts.clear()
    pendingTexts.addAll(ledger.texts.keys)
    pendingTexts.addAll(ledger.dropped)
    syncTexts()
    val rebuilt = coarseDirty && (coarse == null || coarse.size < slotCount / 2)
    if (coarseDirty) writeCoarse()
    if (report.lost) repair.redeliverAll()
    if (lostKeys.isNotEmpty()) {
      counters.corruptRecords.addAndGet(lostKeys.size)
      repair.redeliver(lostKeys)
    }
    if (report.lost || lostKeys.isNotEmpty() || rebuilt) counters.rebuilt.incrementAndGet()
  }

  private suspend fun changeLocked(drops: List<LedgerOp>, vecs: List<VecPut>, txts: List<LedgerOp.PutText>) {
    syncTexts()
    // Idempotenza: lo stesso dato della stessa origine (una replica che ripete dopo una morte) non si riscrive.
    val newVecs = vecs.filter { p -> ledger.vectors[p.key]?.get(p.origin)?.let { !sameVectors(it, p) } ?: true }
    val newTexts = txts.filter { op -> ledger.texts[op.key]?.get(op.origin)?.let { !sameText(it, op) } ?: true }
    if (drops.isEmpty() && newVecs.isEmpty() && newTexts.isEmpty()) return
    val fresh = HashMap<Long, ShortArray>()
    val vecOps = ArrayList<LedgerOp>()
    if (newVecs.isNotEmpty()) {
      val positions = file.append(newVecs.flatMap { it.records })
      var i = 0
      for (p in newVecs) {
        val offs = LongArray(p.records.size) { positions[i + it] }
        p.records.forEachIndexed { j, r -> fresh[offs[j]] = r.halves }
        i += p.records.size
        vecOps += LedgerOp.PutVectors(p.origin, p.key, p.records.first().recipe, offs, p.source)
      }
    }
    val ops = drops + vecOps + newTexts
    // Prima il commit durevole, poi la proiezione SQL: ogni interruzione si ripara dal registro.
    ledger.commit(ops)
    pendingTexts.addAll(ops.filter { it is LedgerOp.PutText || (it is LedgerOp.Drop && it.cls == AiClass.OCR) }.map { keyOf(it) })
    lock.write {
      ops.filter { it is LedgerOp.PutVectors || (it is LedgerOp.Drop && it.cls == AiClass.VEC) }.map { keyOf(it) }.toSet()
        .forEach { setVectorChoice(it, fresh) }
      ops.filter { it is LedgerOp.PutText || (it is LedgerOp.Drop && it.cls == AiClass.OCR) }.map { keyOf(it) }.toSet().forEach { key ->
        val b = ledger.texts[key]?.let { best(it, AiClass.OCR) }
        if (b == null) chosenTexts.remove(key) else chosenTexts[key] = Chosen(b.first, b.second.recipe, IntArray(0), b.second)
      }
      changes.value++
    }
    syncTexts()
    compactIfNeededLocked()
  }

  /**
   * Proiezione idempotente del registro in SQL. Le chiavi restano pendenti fino alla conferma di tutte le scritture del lotto;
   * un retry nello stesso processo (anche `savedFor`) riprende il lavoro. Alla riapertura il registro ricostruisce l'intero elenco.
   */
  private suspend fun syncTexts() {
    if (pendingTexts.isEmpty()) return
    try {
      for (chunk in pendingTexts.toList().chunked(TEXT_BATCH)) {
        val records = chunk.mapNotNull { key ->
          val holding = ledger.texts[key]?.let { best(it, AiClass.OCR) }?.second ?: return@mapNotNull null
          val ref = holding.text ?: return@mapNotNull null
          val text = ledger.readText(ref) ?: throw IOException("Corpo del testo non leggibile.")
          textRecord(key, holding.recipe, ref.engine, ref.source, ref.lines, ref.chars, text)
        }
        if (records.isNotEmpty()) texts.replace(records)
        val present = records.mapTo(HashSet()) { it.contentKey }
        val gone = chunk.filter { it !in present }
        if (gone.isNotEmpty()) texts.remove(gone)
        textListener?.let { listen -> records.forEach { listen(it.text) } }
        pendingTexts.removeAll(chunk.toSet())
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      throw TextRowsUnavailable(e)
    }
  }

  /** Distinta dal registro illeggibile: `open` non deve cancellare i corpi durevoli per un errore SQL. */
  private class TextRowsUnavailable(cause: Exception) : IOException("Riconciliazione del testo non riuscita.", cause)

  private fun setVectorChoice(key: ContentKey, fresh: Map<Long, ShortArray>?) {
    val old = chosenVectors[key]
    val holders = ledger.vectors[key]
    if (old != null && holders != null && holders[old.origin] === old.holding && best(holders, AiClass.VEC)?.second === old.holding) return
    old?.let { release(it.slots) }
    chosenVectors.remove(key)
    for ((origin, h) in holders?.entries?.sortedWith(preference(AiClass.VEC)).orEmpty()) {
      val slots = allocate(key, h.vectors!!, fresh, null, null)
      if (slots != null) {
        chosenVectors[key] = Chosen(origin, h.recipe, slots, h)
        return
      }
      corrupt += key
    }
  }

  private fun allocate(key: ContentKey, offs: LongArray, fresh: Map<Long, ShortArray>?, coarse: CoarseEntries?, byOffset: Map<Long, Int>?): IntArray? {
    val slots = IntArray(offs.size) { -1 }
    for (j in offs.indices) {
      val slot = newSlot()
      slots[j] = slot
      offsets[slot] = offs[j]
      slotKeys[slot] = key
      val at = byOffset?.get(offs[j])
      if (coarse != null && at != null) {
        System.arraycopy(coarse.codes, at * CoarseCodes.DIMS, codes, slot * CoarseCodes.DIMS, CoarseCodes.DIMS)
        scales[slot] = coarse.scales[at]
        verified[slot] = 0
        continue
      }
      val halves = fresh?.get(offs[j]) ?: file.read(offs[j])?.takeIf { it.key == key }?.halves
      if (halves == null) {
        release(slots.filter { it >= 0 }.toIntArray())
        return null
      }
      scales[slot] = CoarseCodes.encodeHalves(halves, codes, slot * CoarseCodes.DIMS)
      verified[slot] = 1
      coarseDirty = true
    }
    return slots
  }

  private fun newSlot(): Int {
    freeSlots.removeLastOrNull()?.let { return it }
    if (slotCount == offsets.size) {
      val capacity = maxOf(1_024, offsets.size * 3 / 2)
      codes = codes.copyOf(capacity * CoarseCodes.DIMS)
      scales = scales.copyOf(capacity)
      offsets = offsets.copyOf(capacity)
      slotKeys = slotKeys.copyOf(capacity)
      verified = verified.copyOf(capacity)
    }
    return slotCount++
  }

  private fun release(slots: IntArray) {
    for (s in slots) {
      slotKeys[s] = null
      scales[s] = 0f
      freeSlots.addLast(s)
    }
    coarseDirty = true
  }

  private fun resetSlots() {
    chosenVectors.clear()
    chosenTexts.clear()
    freeSlots.clear()
    slotCount = 0
    codes = ByteArray(0)
    scales = FloatArray(0)
    offsets = LongArray(0)
    slotKeys = arrayOfNulls(0)
    verified = ByteArray(0)
    coarseDirty = false
  }

  private fun writeCoarse() {
    lock.read {
      val slots = (0 until slotCount).filter { slotKeys[it] != null }
      CoarseFile.write(coarseFile(), ledger.generation, entries(slots) { offsets[it] })
    }
    coarseDirty = false
  }

  private fun entries(slots: List<Int>, offsetOf: (Int) -> Long): CoarseEntries {
    val out = ByteArray(slots.size * CoarseCodes.DIMS)
    slots.forEachIndexed { i, s -> System.arraycopy(codes, s * CoarseCodes.DIMS, out, i * CoarseCodes.DIMS, CoarseCodes.DIMS) }
    return CoarseEntries(LongArray(slots.size) { offsetOf(slots[it]) }, FloatArray(slots.size) { scales[slots[it]] }, out)
  }

  private fun sameVectors(h: Holding, p: VecPut): Boolean {
    val offs = h.vectors ?: return false
    if (h.recipe != p.records.first().recipe || h.source != p.source || offs.size != p.records.size) return false
    return offs.indices.all { i ->
      val r = file.read(offs[i]) ?: return false
      r.frame == p.records[i].frame && r.instantMs == p.records[i].instantMs && r.halves.contentEquals(p.records[i].halves)
    }
  }

  private fun sameText(h: Holding, op: LedgerOp.PutText): Boolean {
    val t = h.text ?: return false
    if (h.recipe != op.recipe || ledger.readText(t) != op.text) return false
    val body = op.body ?: return !t.hasBody
    return ledger.readBody(t)?.contentEquals(body) == true
  }

  private fun recordsOf(key: ContentKey, body: VecBody): List<VectorRecord>? {
    if (body.space != space) return null
    val records = body.vectors.mapIndexed { i, item -> VectorRecord(key, body.recipe, i, item.frameMs, item.f16) }
    // Un vettore non finito non entra mai (E-A12, E-I20): il codec e il calcolo lo rifiutano gia', qui si ricontrolla.
    if (records.any { r -> r.halves.any { (it.toInt() and 0x7c00) == 0x7c00 } }) return null
    return records
  }

  private fun textOp(origin: AiOrigin, key: ContentKey, body: OcrBody): LedgerOp.PutText = LedgerOp.PutText(
    origin, key, body.recipe, body.engine.wire, body.source.wire, body.lines.size, body.lines.sumOf { it.text.length },
    SearchText.normalize(body.lines.joinToString(" ") { it.text }),
    AiSegments.bodyOrNull(body),
  )

  private fun textBytes(op: LedgerOp.PutText): Long = op.text.toByteArray(Charsets.UTF_8).size.toLong() + (op.body?.size ?: 0)

  private fun textRecord(key: ContentKey, recipe: Int, engine: Int, source: Int, lines: Int, chars: Int, text: String): AiTextRecord? {
    val e = OcrEngineCode.fromWire(engine) ?: return null
    val s = OcrSource.fromWire(source) ?: return null
    return AiTextRecord(key, recipe, e, s, text, lines, chars, layout = null, createdAt = 0L)
  }

  private fun holders(cls: AiClass): Map<ContentKey, Map<AiOrigin, Holding>> = if (cls == AiClass.VEC) ledger.vectors else ledger.texts

  private fun dropsWhere(match: (AiOrigin) -> Boolean): List<LedgerOp> =
    ledger.vectors.flatMap { (k, h) -> h.keys.filter(match).map { LedgerOp.Drop(it, k, AiClass.VEC) } } +
      ledger.texts.flatMap { (k, h) -> h.keys.filter(match).map { LedgerOp.Drop(it, k, AiClass.OCR) } }

  /**
   * R5: un dato calcolato qui dai propri byte rende superflui quelli calcolati su anteprime altrui (vale per ogni share); un calcolo su
   * un'anteprima di una share sostituisce quello di prima della stessa share.
   */
  private fun supersededBy(origin: AiOrigin, key: ContentKey, cls: AiClass): List<LedgerOp> = holders(cls)[key]?.keys.orEmpty().filter {
    it != origin && it is AiOrigin.Derived && (origin is AiOrigin.Local || (origin is AiOrigin.Derived && it.share == origin.share))
  }.map { LedgerOp.Drop(it, key, cls) }

  /**
   * R1, R3: un'origine d'altri (una replica, o un calcolo fatto qui sull'anteprima di un altro) non supera i [limits]. [delta] tiene cio'
   * che la stessa scrittura ha gia' aggiunto. Il calcolo dai propri byte non ha tetti.
   */
  private fun fits(origin: AiOrigin, cls: AiClass, key: ContentKey, amount: Long, delta: HashMap<AiOrigin, LongArray>): Boolean {
    if (origin is AiOrigin.Local) return true
    val i = if (cls == AiClass.VEC) 0 else 1
    val old = holders(cls)[key]?.get(origin)?.let { h -> if (i == 0) h.vectors?.size?.toLong() ?: 0 else (h.text?.length ?: 0).toLong() + (h.text?.bodyLength ?: 0) } ?: 0
    val used = ledger.usage[origin]?.let { if (i == 0) it.records else it.textBytes } ?: 0
    val d = delta.getOrPut(origin) { LongArray(2) }
    val cap = if (i == 0) limits.maxRecordsPerOrigin else limits.maxTextBytesPerOrigin
    if (used + d[i] + amount - old > cap) return false
    d[i] += amount - old
    return true
  }

  private fun originOf(from: Derivation): AiOrigin = when (from) {
    Derivation.Own -> AiOrigin.Local
    is Derivation.Preview -> AiOrigin.derived(from.share, from.device)
  }

  private fun keyOf(op: LedgerOp): ContentKey = when (op) {
    is LedgerOp.PutVectors -> op.key
    is LedgerOp.PutText -> op.key
    is LedgerOp.Drop -> op.key
    is LedgerOp.Generation -> throw IllegalArgumentException()
  }

  /** R16: la preferenza fra le origini di una chiave: ricetta piu' alta **fino a quella nota**, poi [originOrder]. */
  private fun <T> preferenceOf(cls: AiClass, recipe: (T) -> Int): Comparator<Map.Entry<AiOrigin, T>> {
    val known = knownRecipe(cls)
    return compareByDescending<Map.Entry<AiOrigin, T>> { minOf(recipe(it.value), known) }.thenComparator { a, b -> originOrder(a.key, b.key) }
  }

  private fun preference(cls: AiClass): Comparator<Map.Entry<AiOrigin, Holding>> = preferenceOf(cls) { it.recipe }

  private fun best(holders: Map<AiOrigin, Holding>, cls: AiClass): Pair<AiOrigin, Holding>? =
    holders.entries.minWithOrNull(preference(cls))?.let { it.key to it.value }

  private fun vectorFileFor(generation: Long) = File(dir, "vectors-${space.code}-$generation.bin")

  private fun coarseFile() = File(dir, "coarse-${space.code}.bin")

  private fun deleteOtherGenerations() {
    val current = vectorFileFor(ledger.generation).name
    dir.listFiles()?.filter { it.name.startsWith("vectors-${space.code}-") && it.name != current }?.forEach { StateFiles.delete(it) }
  }

  private companion object {
    private const val TEXT_BATCH = 500

    const val LEDGER = "ledger.log"

    /** Prima questo dispositivo, poi i calcoli su anteprime altrui, poi la replica; poi share e dispositivo in ordine (deterministico). */
    fun originOrder(a: AiOrigin, b: AiOrigin): Int = when {
      a == b -> 0
      a.rank != b.rank -> a.rank - b.rank
      a is AiOrigin.Replica && b is AiOrigin.Replica -> compareValuesBy(a, b, { it.share }, { it.device })
      a is AiOrigin.Derived && b is AiOrigin.Derived -> compareValuesBy(a, b, { it.share }, { it.device })
      else -> 0
    }
  }
}
