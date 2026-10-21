package dev.pampa.mycloudgallery.core.search.index

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32

// IL REGISTRO DELLE ORIGINI dell'indice locale (richieste di P1 e P2 a P6): per ogni (chiave di contenuto, classe) **chi** ha dato il
// dato: questo dispositivo ([AiOrigin.Local], il calcolo di P5) o il vincitore di una share ([AiOrigin.Replica], P2). `withdraw` e
// `forgetDevice`/`forgetShare` di una replica tolgono solo le **loro** origini: un vettore o un testo calcolato qui non lo tocca mai
// nessun altro. `ai_vector` di P1 non sa dire l'origine: le righe dei vettori (posizione nel file e ricetta) stanno qui.
//
// Un giornale in sola aggiunta (`search/ledger.log`): ogni scrittura e' una cornice con lunghezza e CRC32, salvata con `force` prima
// di dire "fatto" (e' il punto di conferma: file dei vettori prima, poi il testo nel database, poi il registro). Una cornice a meta'
// in coda (morte del processo) si scarta; una cornice rovinata in mezzo ferma la rilettura e si chiede di riavere tutto.

/** Chi ha dato un dato all'indice locale. */
internal sealed interface AiOrigin {
  data object Local : AiOrigin

  data class Replica(val share: String, val device: String) : AiOrigin {
    override fun toString(): String = "Replica"
  }
}

/** Il testo di un'origine: dove sta nel giornale (UTF-8) e le sue misure (D34). */
internal class TextRef(val position: Long, val length: Int, val engine: Int, val source: Int, val lines: Int, val chars: Int)

/** Cio' che un'origine tiene per una (chiave, classe): la ricetta e il dato (posizioni dei vettori o testo). */
internal class Holding(val recipe: Int, val vectors: LongArray?, val text: TextRef?)

internal sealed interface LedgerOp {
  class PutVectors(val origin: AiOrigin, val key: ContentKey, val recipe: Int, val offsets: LongArray) : LedgerOp

  class PutText(
    val origin: AiOrigin,
    val key: ContentKey,
    val recipe: Int,
    val engine: Int,
    val source: Int,
    val lines: Int,
    val chars: Int,
    val text: String,
  ) : LedgerOp

  class Drop(val origin: AiOrigin, val key: ContentKey, val cls: AiClass) : LedgerOp

  /** La generazione del file dei vettori a cui puntano le posizioni (la prima voce dopo una compattazione). */
  class Generation(val generation: Long) : LedgerOp
}

/** Com'e' andata la rilettura del giornale. [lost] = una cornice rovinata in mezzo: cio' che seguiva non si sa piu'. */
internal class ReplayReport(val frames: Int, val tornTail: Boolean, val lost: Boolean)

internal class OriginLedger(private val file: File) : Closeable {

  val vectors: HashMap<ContentKey, HashMap<AiOrigin, Holding>> = HashMap()
  val texts: HashMap<ContentKey, HashMap<AiOrigin, Holding>> = HashMap()

  /** Le chiavi con un `Drop` nel giornale: all'apertura si ripete la loro rimozione dal database (idempotente). */
  val dropped: MutableSet<ContentKey> = HashSet()

  var generation: Long = 0
    private set

  private var raf: RandomAccessFile? = null

  val length: Long get() = raf?.length() ?: 0

  /** Rilegge il giornale e prepara le scritture in coda all'ultima cornice valida. */
  fun open(): ReplayReport {
    close()
    vectors.clear()
    texts.clear()
    dropped.clear()
    generation = 0
    val r = RandomAccessFile(file, "rw")
    raf = r
    val size = r.length()
    var at = 0L
    var frames = 0
    var torn = false
    var lost = false
    val header = ByteArray(8)
    while (at < size) {
      if (size - at < 8) {
        torn = true
        break
      }
      r.seek(at)
      r.readFully(header)
      val len = ByteBuffer.wrap(header).getInt(0)
      val crc = ByteBuffer.wrap(header).getInt(4)
      if (len <= 0 || len > MAX_FRAME) {
        lost = true
        break
      }
      if (size - at - 8 < len) {
        torn = true
        break
      }
      val payload = ByteArray(len)
      r.readFully(payload)
      if (crc32(payload) != crc) {
        lost = at + 8 + len < size
        torn = !lost
        break
      }
      val ops = runCatching { decode(payload, at + 8) }.getOrNull()
      if (ops == null) {
        lost = true
        break
      }
      ops.forEach { apply(it.first, it.second) }
      frames++
      at += 8 + len
    }
    if (at < size) r.setLength(at)
    return ReplayReport(frames, torn, lost)
  }

  /** Scrive [ops] in una cornice (tutte o nessuna), con `force`, poi le applica allo stato. */
  fun commit(ops: List<LedgerOp>) {
    if (ops.isEmpty()) return
    val r = raf ?: throw IllegalStateException("Registro chiuso.")
    val start = r.length()
    val (payload, textPositions) = encode(ops, start + 8)
    require(payload.size <= MAX_FRAME) { "Cornice troppo grande." }
    val frame = ByteBuffer.allocate(8 + payload.size).putInt(payload.size).putInt(crc32(payload)).put(payload)
    r.seek(start)
    r.write(frame.array())
    r.channel.force(false)
    ops.forEachIndexed { i, op -> apply(op, textPositions[i]) }
  }

  /** Il testo UTF-8 di [ref], o null se il giornale non lo ha piu'. */
  fun readText(ref: TextRef): String? {
    // Lettura per posizione sul canale: sicura insieme alle scritture (che usano `seek`) e alle altre letture.
    val channel = raf?.channel ?: return null
    if (ref.position < 0 || ref.position + ref.length > channel.size()) return null
    val buffer = ByteBuffer.allocate(ref.length)
    while (buffer.hasRemaining()) {
      if (channel.read(buffer, ref.position + buffer.position()) < 0) return null
    }
    return String(buffer.array(), Charsets.UTF_8)
  }

  /**
   * Riscrive il giornale con le sole origini vive (temporaneo e rinomina), con la generazione [newGeneration] del file dei vettori e
   * le posizioni nuove [remap] (null = vettore perso: l'origine si toglie).
   */
  fun compact(newGeneration: Long, remap: (Long) -> Long?) {
    val ops = ArrayList<LedgerOp>()
    ops += LedgerOp.Generation(newGeneration)
    for ((key, holders) in vectors) for ((origin, h) in holders) {
      val moved = h.vectors!!.map { remap(it) }
      if (moved.all { it != null }) ops += LedgerOp.PutVectors(origin, key, h.recipe, LongArray(moved.size) { moved[it]!! })
    }
    for ((key, holders) in texts) for ((origin, h) in holders) {
      val t = h.text!!
      val text = readText(t) ?: continue
      ops += LedgerOp.PutText(origin, key, h.recipe, t.engine, t.source, t.lines, t.chars, text)
    }
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.delete()
    RandomAccessFile(tmp, "rw").use { out ->
      for (chunk in ops.chunked(COMPACT_CHUNK)) {
        val (payload, _) = encode(chunk, out.length() + 8)
        out.write(ByteBuffer.allocate(8 + payload.size).putInt(payload.size).putInt(crc32(payload)).put(payload).array())
      }
      out.channel.force(true)
    }
    close()
    if (!tmp.renameTo(file)) {
      file.delete()
      check(tmp.renameTo(file)) { "Compattazione del registro non riuscita." }
    }
    open()
  }

  override fun close() {
    runCatching { raf?.close() }
    raf = null
  }

  private fun apply(op: LedgerOp, textPosition: Long) {
    when (op) {
      is LedgerOp.PutVectors -> vectors.getOrPut(op.key) { HashMap(2) }[op.origin] = Holding(op.recipe, op.offsets, null)
      is LedgerOp.PutText -> texts.getOrPut(op.key) { HashMap(2) }[op.origin] =
        Holding(op.recipe, null, TextRef(textPosition, op.text.toByteArray(Charsets.UTF_8).size, op.engine, op.source, op.lines, op.chars))
      is LedgerOp.Drop -> {
        val map = if (op.cls == AiClass.VEC) vectors else texts
        map[op.key]?.let { holders ->
          holders.remove(op.origin)
          if (holders.isEmpty()) map.remove(op.key)
        }
        dropped += op.key
      }
      is LedgerOp.Generation -> {
        generation = op.generation
        dropped.clear()
      }
    }
  }

  /** La cornice di [ops] e, per ogni op di testo, la posizione assoluta dei byte del testo (altrimenti -1). */
  private fun encode(ops: List<LedgerOp>, payloadStart: Long): Pair<ByteArray, LongArray> {
    val bytes = ByteArrayOutputStream()
    val out = DataOutputStream(bytes)
    val positions = LongArray(ops.size) { -1 }
    ops.forEachIndexed { i, op ->
      when (op) {
        is LedgerOp.PutVectors -> {
          out.writeByte(OP_VEC); origin(out, op.origin); out.write(op.key.bytes()); out.writeByte(op.recipe)
          out.writeByte(op.offsets.size); op.offsets.forEach { out.writeLong(it) }
        }
        is LedgerOp.PutText -> {
          out.writeByte(OP_TEXT); origin(out, op.origin); out.write(op.key.bytes()); out.writeByte(op.recipe)
          out.writeByte(op.engine); out.writeByte(op.source); out.writeInt(op.lines); out.writeInt(op.chars)
          val utf = op.text.toByteArray(Charsets.UTF_8)
          out.writeInt(utf.size)
          out.flush()
          positions[i] = payloadStart + bytes.size()
          out.write(utf)
        }
        is LedgerOp.Drop -> {
          out.writeByte(OP_DROP); origin(out, op.origin); out.write(op.key.bytes()); out.writeByte(op.cls.code)
        }
        is LedgerOp.Generation -> {
          out.writeByte(OP_GEN); out.writeLong(op.generation)
        }
      }
    }
    out.flush()
    return bytes.toByteArray() to positions
  }

  private fun origin(out: DataOutputStream, origin: AiOrigin) {
    when (origin) {
      AiOrigin.Local -> out.writeByte(0)
      is AiOrigin.Replica -> {
        out.writeByte(1)
        string(out, origin.share)
        string(out, origin.device)
      }
    }
  }

  private fun string(out: DataOutputStream, s: String) {
    val b = s.toByteArray(Charsets.UTF_8)
    require(b.size <= 0xffff) { "Nome troppo lungo." }
    out.writeShort(b.size)
    out.write(b)
  }

  private fun decode(payload: ByteArray, payloadStart: Long): List<Pair<LedgerOp, Long>> {
    val b = ByteBuffer.wrap(payload)
    val out = ArrayList<Pair<LedgerOp, Long>>()
    fun str(): String = ByteArray(b.short.toInt() and 0xffff).also { b.get(it) }.toString(Charsets.UTF_8)
    fun origin(): AiOrigin = when (b.get().toInt()) {
      0 -> AiOrigin.Local
      1 -> AiOrigin.Replica(str(), str())
      else -> error("Origine sconosciuta.")
    }
    fun key(): ContentKey = ContentKey(VectorFile.hex(ByteArray(32).also { b.get(it) }))
    while (b.hasRemaining()) {
      when (b.get().toInt()) {
        OP_VEC -> {
          val o = origin(); val k = key(); val recipe = b.get().toInt() and 0xff
          val n = b.get().toInt() and 0xff
          out += LedgerOp.PutVectors(o, k, recipe, LongArray(n) { b.long }) to -1L
        }
        OP_TEXT -> {
          val o = origin(); val k = key(); val recipe = b.get().toInt() and 0xff
          val engine = b.get().toInt() and 0xff; val source = b.get().toInt() and 0xff
          val lines = b.int; val chars = b.int; val len = b.int
          require(len in 0..b.remaining()) { "Testo troppo lungo." }
          val position = payloadStart + b.position()
          val text = ByteArray(len).also { b.get(it) }.toString(Charsets.UTF_8)
          out += LedgerOp.PutText(o, k, recipe, engine, source, lines, chars, text) to position
        }
        OP_DROP -> {
          val o = origin(); val k = key()
          out += LedgerOp.Drop(o, k, AiClass.fromCode(b.get().toInt()) ?: error("Classe sconosciuta.")) to -1L
        }
        OP_GEN -> out += LedgerOp.Generation(b.long) to -1L
        else -> error("Voce sconosciuta.")
      }
    }
    return out
  }

  private fun crc32(bytes: ByteArray): Int = CRC32().also { it.update(bytes) }.value.toInt()

  private companion object {
    const val OP_VEC = 1
    const val OP_TEXT = 2
    const val OP_DROP = 3
    const val OP_GEN = 4
    const val MAX_FRAME = 64 * 1024 * 1024
    const val COMPACT_CHUNK = 256
  }
}
