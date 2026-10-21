package dev.pampa.mycloudgallery.core.search.index

import dev.pampa.mycloudgallery.core.ai.contract.AiResults
import dev.pampa.mycloudgallery.core.database.AiTextRecord
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * L'INDICE LOCALE DI RICERCA (docs/fase6-progetto.md §2.4, D23, D24): vettori e testo per **chiave di contenuto**, da qualunque
 * origine (telefono, area privata, area comune), una volta sola anche se lo stesso contenuto sta in tre posti.
 *
 * - **Scritture**: il calcolo di questo dispositivo ([AiResults], P5) e la replica delle teste `ai` ([applyReplica], [withdraw],
 *   [forgetDevice], [forgetShare]: :app li lega ad `AiReplicaSink` di P2). Ogni dato ha la sua **origine** ([OriginLedger]): la
 *   replica toglie solo le sue, mai cio' che e' stato calcolato qui. Per chiave vale l'origine con la ricetta piu' alta (D7), poi
 *   questo dispositivo, poi share e dispositivo minori.
 * - **Ordine delle scritture** (morte del processo a ogni passo): file dei vettori (`force`), testo nel database, registro (`force`,
 *   il punto di conferma). Una ripetizione (la replica ripete dopo una morte) e' idempotente.
 * - **Memoria**: uno "slot" per vettore scelto con i codici int8 della prima passata e la posizione nel file per la seconda. Le
 *   ricerche leggono sotto [lock] in lettura, senza sospendersi.
 * - **Rovinato si ricostruisce**: i codici della prima passata si rifanno dal file dei vettori; un record con il CRC sbagliato si
 *   toglie e si chiede di riaverlo ([IndexRepair]); un registro rotto in mezzo chiede di riavere tutto.
 */
class LocalAiIndex(
  private val dir: File,
  private val texts: TextRowStore,
  private val repair: IndexRepair = IndexRepair.NONE,
  val space: SpaceCode = SpaceCode.CURRENT,
  val counters: SearchIndexCounters = SearchIndexCounters(),
) : AiResults, Closeable {

  internal val dimension: Int = space.space.dimension
  internal val lock = ReentrantReadWriteLock()
  private val writes = Mutex()
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

  /** Apre l'indice: rilegge il registro, la prima passata dal suo file (o la ricostruisce) e ripete le rimozioni lasciate a meta'. */
  suspend fun open(): Unit = writes.withLock { openLocked() }

  // ---- Letture per il motore (sotto lock.read) ----

  internal fun vectorSlots(key: ContentKey): IntArray? = chosenVectors[key]?.slots

  internal fun vectorRecipe(key: ContentKey): Int = chosenVectors[key]?.recipe ?: 0

  internal fun hasText(key: ContentKey): Boolean = chosenTexts.containsKey(key)

  internal fun textRecipe(key: ContentKey): Int = chosenTexts[key]?.recipe ?: 0

  /** Il testo normalizzato scelto per [key], o null. */
  internal fun text(key: ContentKey): String? {
    val ref = lock.read { chosenTexts[key]?.holding?.text } ?: return null
    return ledger.readText(ref)
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

  override suspend fun saveVectors(key: ContentKey, body: VecBody) {
    val records = recordsOf(key, body) ?: return
    writes.withLock { changeLocked(emptyList(), listOf(Triple(AiOrigin.Local, key, records)), emptyList()) }
  }

  override suspend fun saveText(key: ContentKey, body: OcrBody) {
    writes.withLock { changeLocked(emptyList(), emptyList(), listOf(textOp(AiOrigin.Local, key, body))) }
  }

  override suspend fun saved(key: ContentKey, recipe: Int): Set<AiClass> = writes.withLock {
    buildSet {
      if ((ledger.vectors[key]?.get(AiOrigin.Local)?.recipe ?: 0) >= recipe) add(AiClass.VEC)
      if ((ledger.texts[key]?.get(AiOrigin.Local)?.recipe ?: 0) >= recipe) add(AiClass.OCR)
    }
  }

  // ---- La replica delle teste `ai` (P2, legata da :app ad AiReplicaSink) ----

  /** Una voce vincitrice di una share (D7): sostituisce quella di prima della stessa (share, chiave, classe), anche di un altro autore. */
  sealed interface ReplicaItem {
    val key: ContentKey
    val device: String

    class Vectors(override val key: ContentKey, override val device: String, val body: VecBody) : ReplicaItem

    class Text(override val key: ContentKey, override val device: String, val body: OcrBody) : ReplicaItem
  }

  suspend fun applyReplica(share: String, items: List<ReplicaItem>): Unit = writes.withLock {
    val vecs = ArrayList<Triple<AiOrigin, ContentKey, List<VectorRecord>>>()
    val txts = ArrayList<LedgerOp.PutText>()
    val drops = ArrayList<LedgerOp>()
    for (item in items) {
      val origin = AiOrigin.Replica(share, item.device)
      val cls = if (item is ReplicaItem.Vectors) AiClass.VEC else AiClass.OCR
      // Un altro autore della stessa share perde la voce (il vincitore e' uno per share, chiave e classe).
      holders(cls)[item.key]?.keys?.filter { it is AiOrigin.Replica && it.share == share && it != origin }?.forEach {
        drops += LedgerOp.Drop(it, item.key, cls)
      }
      when (item) {
        is ReplicaItem.Vectors -> recordsOf(item.key, item.body)?.let { vecs += Triple(origin, item.key, it) }
        is ReplicaItem.Text -> txts += textOp(origin, item.key, item.body)
      }
    }
    changeLocked(drops, vecs, txts)
  }

  /** Le voci di [share] che non hanno piu' un vincitore valido escono (D7, E-I21). Solo l'origine di quella share. */
  suspend fun withdraw(share: String, keys: List<Pair<ContentKey, AiClass>>): Unit = writes.withLock {
    val drops = keys.flatMap { (key, cls) ->
      holders(cls)[key]?.keys?.filter { it is AiOrigin.Replica && it.share == share }.orEmpty().map { LedgerOp.Drop(it, key, cls) }
    }
    changeLocked(drops, emptyList(), emptyList())
  }

  /** Un dispositivo revocato, perso o sconosciuto: escono le voci di cui era l'autore, in ogni share. Mai quelle di questo dispositivo. */
  suspend fun forgetDevice(device: String): Unit = writes.withLock {
    changeLocked(dropsWhere { it is AiOrigin.Replica && it.device == device }, emptyList(), emptyList())
  }

  /** Un'area persa (E-I8): escono le voci di quella share. */
  suspend fun forgetShare(share: String): Unit = writes.withLock {
    changeLocked(dropsWhere { it is AiOrigin.Replica && it.share == share }, emptyList(), emptyList())
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
   * si riscrive in una generazione nuova con i soli record vivi, poi il registro. Salva comunque i codici della prima passata.
   */
  suspend fun maintain(force: Boolean = false): Boolean = writes.withLock {
    val total = file.length / file.recordSize
    val live = LinkedHashMap<Long, ContentKey>()
    ledger.vectors.forEach { (key, holders) -> holders.values.forEach { h -> h.vectors!!.forEach { live[it] = key } } }
    if (!force && (total == 0L || (total - live.size) * 5 <= total)) {
      if (coarseDirty) writeCoarse()
      return@withLock false
    }
    val generation = ledger.generation + 1
    val remap = HashMap<Long, Long>(live.size * 2)
    VectorFile(vectorFileFor(generation), dimension).use { target ->
      for (chunk in live.keys.chunked(1_024)) {
        val read = chunk.mapNotNull { off -> file.read(off)?.takeIf { it.key == live[off] }?.let { off to it } }
        val positions = target.append(read.map { it.second })
        read.forEachIndexed { i, (off, _) -> remap[off] = positions[i] }
      }
    }
    val lost = live.filterKeys { it !in remap }.values.toSet()
    lock.read {
      val slots = (0 until slotCount).filter { slotKeys[it] != null && offsets[it] in remap }
      CoarseFile.write(coarseFile(), generation, entries(slots) { remap.getValue(offsets[it]) })
    }
    ledger.compact(generation) { remap[it] }
    counters.compactions.incrementAndGet()
    openLocked()
    if (lost.isNotEmpty()) {
      counters.corruptRecords.addAndGet(lost.size)
      repair.redeliver(lost)
    }
    true
  }

  override fun close() {
    if (vectorFile != null && coarseDirty) runCatching { writeCoarse() }
    vectorFile?.close()
    vectorFile = null
    ledger.close()
  }

  // ---- Interni ----

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
        for ((origin, h) in holders.entries.sortedWith(PREFERENCE)) {
          val slots = allocate(key, h.vectors!!, null, coarse, byOffset)
          if (slots != null) {
            chosenVectors[key] = Chosen(origin, h.recipe, slots, h)
            break
          }
          bad += LedgerOp.Drop(origin, key, AiClass.VEC)
          lostKeys += key
        }
      }
      for ((key, holders) in ledger.texts) best(holders)?.let { (o, h) -> chosenTexts[key] = Chosen(o, h.recipe, IntArray(0), h) }
      changes.value++
    }
    deleteOtherGenerations()
    if (bad.isNotEmpty()) ledger.commit(bad)
    // Le rimozioni dal database lasciate a meta' da una morte del processo (idempotenti).
    val gone = ledger.dropped.filter { !ledger.texts.containsKey(it) }
    if (gone.isNotEmpty()) texts.remove(gone)
    val rebuilt = coarseDirty && (coarse == null || coarse.size < slotCount / 2)
    if (coarseDirty) writeCoarse()
    if (report.lost) repair.redeliverAll()
    if (lostKeys.isNotEmpty()) {
      counters.corruptRecords.addAndGet(lostKeys.size)
      repair.redeliver(lostKeys)
    }
    if (report.lost || lostKeys.isNotEmpty() || rebuilt) counters.rebuilt.incrementAndGet()
  }

  private suspend fun changeLocked(
    drops: List<LedgerOp>,
    vecs: List<Triple<AiOrigin, ContentKey, List<VectorRecord>>>,
    txts: List<LedgerOp.PutText>,
  ) {
    // Idempotenza: lo stesso dato della stessa origine (una replica che ripete dopo una morte) non si riscrive.
    val newVecs = vecs.filter { (o, k, r) -> ledger.vectors[k]?.get(o)?.let { !sameVectors(it, r) } ?: true }
    val newTexts = txts.filter { op -> ledger.texts[op.key]?.get(op.origin)?.let { it.recipe != op.recipe || ledger.readText(it.text!!) != op.text } ?: true }
    if (drops.isEmpty() && newVecs.isEmpty() && newTexts.isEmpty()) return
    val fresh = HashMap<Long, ShortArray>()
    val vecOps = ArrayList<LedgerOp>()
    if (newVecs.isNotEmpty()) {
      val positions = file.append(newVecs.flatMap { it.third })
      var i = 0
      for ((origin, key, records) in newVecs) {
        val offs = LongArray(records.size) { positions[i + it] }
        records.forEachIndexed { j, r -> fresh[offs[j]] = r.halves }
        i += records.size
        vecOps += LedgerOp.PutVectors(origin, key, records.first().recipe, offs)
      }
    }
    val ops = drops + vecOps + newTexts
    syncTexts(ops)
    ledger.commit(ops)
    textListener?.let { listen -> newTexts.forEach { listen(it.text) } }
    lock.write {
      ops.filter { it is LedgerOp.PutVectors || (it is LedgerOp.Drop && it.cls == AiClass.VEC) }.map { keyOf(it) }.toSet()
        .forEach { setVectorChoice(it, fresh) }
      ops.filter { it is LedgerOp.PutText || (it is LedgerOp.Drop && it.cls == AiClass.OCR) }.map { keyOf(it) }.toSet().forEach { key ->
        val b = ledger.texts[key]?.let { best(it) }
        if (b == null) chosenTexts.remove(key) else chosenTexts[key] = Chosen(b.first, b.second.recipe, IntArray(0), b.second)
      }
      changes.value++
    }
  }

  /** Il testo nel database **prima** del registro: per ogni chiave toccata, il testo dell'origine che vincera' (o niente). */
  private suspend fun syncTexts(ops: List<LedgerOp>) {
    val touched = ops.filter { it is LedgerOp.PutText || (it is LedgerOp.Drop && it.cls == AiClass.OCR) }.groupBy { keyOf(it) }
    for ((key, keyOps) in touched) {
      val before = ledger.texts[key]?.let { best(it) }
      val after = HashMap<AiOrigin, Any>(ledger.texts[key].orEmpty())
      for (op in keyOps) when (op) {
        is LedgerOp.Drop -> after.remove(op.origin)
        is LedgerOp.PutText -> after[op.origin] = op
        else -> Unit
      }
      val winner = after.entries.maxWithOrNull(compareBy<Map.Entry<AiOrigin, Any>> { recipeOf(it.value) }.thenComparator { a, b -> -originOrder(a.key, b.key) })
      if (winner != null && before != null && winner.value === before.second) continue
      texts.remove(listOf(key))
      val record = when (val v = winner?.value) {
        null -> null
        is LedgerOp.PutText -> textRecord(key, v.recipe, v.engine, v.source, v.lines, v.chars, v.text)
        is Holding -> v.text?.let { t -> ledger.readText(t)?.let { textRecord(key, v.recipe, t.engine, t.source, t.lines, t.chars, it) } }
        else -> null
      }
      if (record != null) texts.put(record)
    }
  }

  private fun setVectorChoice(key: ContentKey, fresh: Map<Long, ShortArray>?) {
    val old = chosenVectors[key]
    val holders = ledger.vectors[key]
    if (old != null && holders != null && holders[old.origin] === old.holding && best(holders)?.second === old.holding) return
    old?.let { release(it.slots) }
    chosenVectors.remove(key)
    for ((origin, h) in holders?.entries?.sortedWith(PREFERENCE).orEmpty()) {
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

  private fun sameVectors(h: Holding, records: List<VectorRecord>): Boolean {
    val offs = h.vectors ?: return false
    if (h.recipe != records.first().recipe || offs.size != records.size) return false
    return offs.indices.all { i ->
      val r = file.read(offs[i]) ?: return false
      r.frame == records[i].frame && r.instantMs == records[i].instantMs && r.halves.contentEquals(records[i].halves)
    }
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
  )

  private fun textRecord(key: ContentKey, recipe: Int, engine: Int, source: Int, lines: Int, chars: Int, text: String): AiTextRecord? {
    val e = OcrEngineCode.fromWire(engine) ?: return null
    val s = OcrSource.fromWire(source) ?: return null
    return AiTextRecord(key, recipe, e, s, text, lines, chars, layout = null, createdAt = 0L)
  }

  private fun holders(cls: AiClass): Map<ContentKey, Map<AiOrigin, Holding>> = if (cls == AiClass.VEC) ledger.vectors else ledger.texts

  private fun dropsWhere(match: (AiOrigin) -> Boolean): List<LedgerOp> =
    ledger.vectors.flatMap { (k, h) -> h.keys.filter(match).map { LedgerOp.Drop(it, k, AiClass.VEC) } } +
      ledger.texts.flatMap { (k, h) -> h.keys.filter(match).map { LedgerOp.Drop(it, k, AiClass.OCR) } }

  private fun keyOf(op: LedgerOp): ContentKey = when (op) {
    is LedgerOp.PutVectors -> op.key
    is LedgerOp.PutText -> op.key
    is LedgerOp.Drop -> op.key
    is LedgerOp.Generation -> throw IllegalArgumentException()
  }

  private fun recipeOf(v: Any): Int = when (v) {
    is Holding -> v.recipe
    is LedgerOp.PutText -> v.recipe
    else -> 0
  }

  private fun best(holders: Map<AiOrigin, Holding>): Pair<AiOrigin, Holding>? =
    holders.entries.sortedWith(PREFERENCE).firstOrNull()?.let { it.key to it.value }

  private fun vectorFileFor(generation: Long) = File(dir, "vectors-${space.code}-$generation.bin")

  private fun coarseFile() = File(dir, "coarse-${space.code}.bin")

  private fun deleteOtherGenerations() {
    val current = vectorFileFor(ledger.generation).name
    dir.listFiles()?.filter { it.name.startsWith("vectors-${space.code}-") && it.name != current }?.forEach { it.delete() }
  }

  private companion object {
    const val LEDGER = "ledger.log"

    /** Local prima delle repliche, poi share e dispositivo in ordine (deterministico, come D7). */
    fun originOrder(a: AiOrigin, b: AiOrigin): Int = when {
      a == b -> 0
      a is AiOrigin.Local -> -1
      b is AiOrigin.Local -> 1
      else -> compareValuesBy(a as AiOrigin.Replica, b as AiOrigin.Replica, { it.share }, { it.device })
    }

    /** La preferenza fra le origini di una chiave: ricetta piu' alta, poi [originOrder]. */
    val PREFERENCE: Comparator<Map.Entry<AiOrigin, Holding>> =
      compareByDescending<Map.Entry<AiOrigin, Holding>> { it.value.recipe }.thenComparator { a, b -> originOrder(a.key, b.key) }
  }
}
