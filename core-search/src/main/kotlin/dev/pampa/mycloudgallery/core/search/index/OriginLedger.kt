package dev.pampa.mycloudgallery.core.search.index

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.CRC32

// IL REGISTRO DELLE ORIGINI dell'indice locale (richieste di P1 e P2 a P6): per ogni (chiave di contenuto, classe) **chi** ha dato il
// dato: questo dispositivo ([AiOrigin.Local], il calcolo di P5), il vincitore di una share ([AiOrigin.Replica], P2) o un calcolo fatto qui
// sull'anteprima di un altro dispositivo ([AiOrigin.Derived], revisione A, R5). `withdraw` e `forgetDevice`/`forgetShare` di una replica
// tolgono solo le **loro** origini: un vettore o un testo calcolato qui dai propri byte non lo tocca mai nessun altro.
//
// Un giornale in sola aggiunta (`search/ledger.log`): ogni scrittura e' una o piu' cornici con lunghezza e CRC32, salvate con `force` prima
// di dire "fatto" (e' il punto di conferma: file dei vettori prima, poi il registro, infine la proiezione SQL del testo). Una cornice a meta' in
// coda (morte del processo) si scarta; una cornice rovinata in mezzo ferma la rilettura e si chiede di riavere tutto.
//
// Revisione A (R2, R4, R5): una scrittura grande si divide in cornici da al piu' [FRAME_TARGET] byte (mai oltre il tetto di chi rilegge);
// le voci nuove portano la sorgente dei vettori e il corpo `ocr` com'e' (le righe del testo dal vivo seguono l'origine che vince, e cio'
// che e' stato calcolato qui si ripubblica senza ricalcolo). Le voci vecchie (senza) si rileggono ancora.

/** Chi ha dato un dato all'indice locale. [rank] e' l'ordine di preferenza a parita' di ricetta: prima questo dispositivo. */
internal sealed interface AiOrigin {
  val rank: Int

  data object Local : AiOrigin {
    override val rank: Int get() = 0
  }

  /** Calcolato qui sull'anteprima di [device] attestata in [share] (R5); [device] vuoto = dispositivo non noto. */
  data class Derived(val share: String, val device: String) : AiOrigin {
    override val rank: Int get() = 1

    override fun toString(): String = "Derived"
  }

  /** Il vincitore della fusione D7 di [share] (sempre in minuscolo, I13: Samba non distingue le maiuscole). */
  data class Replica(val share: String, val device: String) : AiOrigin {
    override val rank: Int get() = 2

    override fun toString(): String = "Replica"
  }

  companion object {
    fun replica(share: String, device: String): Replica = Replica(share.lowercase(Locale.ROOT), device)

    fun derived(share: String, device: String?): Derived = Derived(share.lowercase(Locale.ROOT), device.orEmpty())
  }
}

/** Il testo di un'origine: dove sta nel giornale (UTF-8), le sue misure (D34) e, se c'e', il corpo `ocr` com'e' ([bodyPosition]). */
internal class TextRef(
  val position: Long,
  val length: Int,
  val engine: Int,
  val source: Int,
  val lines: Int,
  val chars: Int,
  val bodyPosition: Long = -1,
  val bodyLength: Int = 0,
) {
  val hasBody: Boolean get() = bodyPosition >= 0 && bodyLength > 0
}

/** Cio' che un'origine tiene per una (chiave, classe): la ricetta e il dato; [source] e' la sorgente dei vettori (0 = non nota). */
internal class Holding(val recipe: Int, val vectors: LongArray?, val text: TextRef?, val source: Int = 0)

internal sealed interface LedgerOp {
  class PutVectors(val origin: AiOrigin, val key: ContentKey, val recipe: Int, val offsets: LongArray, val source: Int = 0) : LedgerOp

  class PutText(
    val origin: AiOrigin,
    val key: ContentKey,
    val recipe: Int,
    val engine: Int,
    val source: Int,
    val lines: Int,
    val chars: Int,
    val text: String,
    /** Il corpo `ocr` codificato (`AiSegments`), o null per le voci vecchie. */
    val body: ByteArray? = null,
  ) : LedgerOp

  class Drop(val origin: AiOrigin, val key: ContentKey, val cls: AiClass) : LedgerOp

  /** La generazione del file dei vettori a cui puntano le posizioni (la prima voce dopo una compattazione). */
  class Generation(val generation: Long) : LedgerOp
}

/** Com'e' andata la rilettura del giornale. [lost] = una cornice rovinata in mezzo: cio' che seguiva non si sa piu'. */
internal class ReplayReport(val frames: Int, val tornTail: Boolean, val lost: Boolean)

/** Quanto tiene un'origine (R1, R3): record dei vettori e byte del testo (testo normalizzato piu' corpo). */
internal class OriginUsage {
  var records: Long = 0
  var textBytes: Long = 0
}

internal class OriginLedger(private val file: File, private val frameTarget: Int = FRAME_TARGET) : Closeable {

  val vectors: HashMap<ContentKey, HashMap<AiOrigin, Holding>> = HashMap()
  val texts: HashMap<ContentKey, HashMap<AiOrigin, Holding>> = HashMap()

  /** Le chiavi con un `Drop` nel giornale: all'apertura si ripete la loro rimozione dal database (idempotente). */
  val dropped: MutableSet<ContentKey> = HashSet()

  /** Quanto tiene ogni origine (per i tetti, R1 e R3). */
  val usage: HashMap<AiOrigin, OriginUsage> = HashMap()

  /** Una stima dei byte vivi del giornale (le voci che una compattazione riscriverebbe): il resto e' morto (R3). */
  var liveBytes: Long = 0
    private set

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
    usage.clear()
    liveBytes = 0
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
      ops.forEach { apply(it.op, it.textAt, it.bodyAt) }
      frames++
      at += 8 + len
    }
    if (at < size) r.setLength(at)
    return ReplayReport(frames, torn, lost)
  }

  /**
   * Scrive [ops] in una o piu' cornici (R2: ognuna al piu' [FRAME_TARGET] byte, salvo una voce sola piu' grande, sempre sotto il tetto
   * di chi rilegge), con un solo `force`, poi le applica allo stato. Una morte a meta' lascia le prime cornici: chi scrive ripete (la
   * replica riconsegna, il calcolo rifa' cio' che `saved` non dice).
   */
  fun commit(ops: List<LedgerOp>) {
    if (ops.isEmpty()) return
    val r = raf ?: throw IllegalStateException("Registro chiuso.")
    val encoded = ops.map { encodeOne(it) }
    val start = r.length()
    val out = ByteArrayOutputStream()
    val textAt = LongArray(ops.size) { -1 }
    val bodyAt = LongArray(ops.size) { -1 }
    var i = 0
    while (i < encoded.size) {
      var end = i
      var size = 0
      while (end < encoded.size && (end == i || size + encoded[end].bytes.size <= frameTarget)) {
        size += encoded[end].bytes.size
        end++
      }
      require(size <= MAX_FRAME) { "Cornice troppo grande." }
      val payloadStart = start + out.size() + 8
      val payload = ByteArrayOutputStream(size)
      for (k in i until end) {
        val e = encoded[k]
        val base = payloadStart + payload.size()
        if (e.textOffset >= 0) textAt[k] = base + e.textOffset
        if (e.bodyOffset >= 0) bodyAt[k] = base + e.bodyOffset
        payload.write(e.bytes)
      }
      val bytes = payload.toByteArray()
      out.write(ByteBuffer.allocate(8).putInt(bytes.size).putInt(crc32(bytes)).array())
      out.write(bytes)
      i = end
    }
    r.seek(start)
    r.write(out.toByteArray())
    r.channel.force(false)
    ops.forEachIndexed { k, op -> apply(op, textAt[k], bodyAt[k]) }
  }

  /** Il testo UTF-8 di [ref], o null se il giornale non lo ha piu'. */
  fun readText(ref: TextRef): String? = readAt(ref.position, ref.length)?.toString(Charsets.UTF_8)

  /** Il corpo `ocr` di [ref] cosi' come e' stato scritto, o null (voce vecchia, giornale cambiato). */
  fun readBody(ref: TextRef): ByteArray? = if (ref.hasBody) readAt(ref.bodyPosition, ref.bodyLength) else null

  private fun readAt(position: Long, length: Int): ByteArray? {
    // Lettura per posizione sul canale: sicura insieme alle scritture (che usano `seek`) e alle altre letture.
    val channel = raf?.channel ?: return null
    if (position < 0 || length < 0 || position + length > channel.size()) return null
    val buffer = ByteBuffer.allocate(length)
    while (buffer.hasRemaining()) {
      if (channel.read(buffer, position + buffer.position()) < 0) return null
    }
    return buffer.array()
  }

  /**
   * Riscrive il giornale con le sole origini vive (temporaneo e rinomina), con la generazione [newGeneration] del file dei vettori e
   * le posizioni nuove [remap] (null = vettore perso: l'origine si toglie). Con la stessa generazione e [remap] identita' compatta solo il
   * giornale (R3: il testo morto di chi ripubblica a ogni giro).
   */
  fun compact(newGeneration: Long, remap: (Long) -> Long?) {
    val ops = ArrayList<LedgerOp>()
    ops += LedgerOp.Generation(newGeneration)
    for ((key, holders) in vectors) for ((origin, h) in holders) {
      val moved = h.vectors!!.map { remap(it) }
      if (moved.all { it != null }) ops += LedgerOp.PutVectors(origin, key, h.recipe, LongArray(moved.size) { moved[it]!! }, h.source)
    }
    for ((key, holders) in texts) for ((origin, h) in holders) {
      val t = h.text!!
      val text = readText(t) ?: continue
      ops += LedgerOp.PutText(origin, key, h.recipe, t.engine, t.source, t.lines, t.chars, text, readBody(t))
    }
    val tmp = File(file.parentFile, file.name + ".tmp")
    // Un temporaneo di una compattazione morta a meta': si scrive da capo (`rw` non tronca, e un resto in coda sarebbe una cornice storta).
    check(StateFiles.delete(tmp)) { "Compattazione del registro non riuscita." }
    RandomAccessFile(tmp, "rw").use { out ->
      var chunk = ArrayList<ByteArray>()
      var size = 0
      fun flush() {
        if (chunk.isEmpty()) return
        val payload = ByteArrayOutputStream(size).also { b -> chunk.forEach { b.write(it) } }.toByteArray()
        out.write(ByteBuffer.allocate(8 + payload.size).putInt(payload.size).putInt(crc32(payload)).put(payload).array())
        chunk = ArrayList()
        size = 0
      }
      for (op in ops) {
        val bytes = encodeOne(op).bytes
        if (chunk.isNotEmpty() && size + bytes.size > frameTarget) flush()
        chunk += bytes
        size += bytes.size
      }
      flush()
      out.channel.force(true)
    }
    close()
    try {
      StateFiles.replace(tmp, file)
    } catch (e: IOException) {
      throw IllegalStateException("Compattazione del registro non riuscita.", e)
    }
    open()
  }

  override fun close() {
    runCatching { raf?.close() }
    raf = null
  }

  private fun apply(op: LedgerOp, textPosition: Long, bodyPosition: Long) {
    when (op) {
      is LedgerOp.PutVectors -> {
        val holders = vectors.getOrPut(op.key) { HashMap(2) }
        holders[op.origin]?.let { forgetUsage(op.origin, it, AiClass.VEC) }
        val h = Holding(op.recipe, op.offsets, null, op.source)
        holders[op.origin] = h
        addUsage(op.origin, h, AiClass.VEC)
      }
      is LedgerOp.PutText -> {
        val holders = texts.getOrPut(op.key) { HashMap(2) }
        holders[op.origin]?.let { forgetUsage(op.origin, it, AiClass.OCR) }
        val body = op.body
        val ref = TextRef(
          textPosition, op.text.toByteArray(Charsets.UTF_8).size, op.engine, op.source, op.lines, op.chars,
          if (body != null) bodyPosition else -1, body?.size ?: 0,
        )
        val h = Holding(op.recipe, null, ref)
        holders[op.origin] = h
        addUsage(op.origin, h, AiClass.OCR)
      }
      is LedgerOp.Drop -> {
        val map = if (op.cls == AiClass.VEC) vectors else texts
        map[op.key]?.let { holders ->
          holders.remove(op.origin)?.let { forgetUsage(op.origin, it, op.cls) }
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

  private fun weight(origin: AiOrigin, h: Holding, cls: AiClass): Long = when (cls) {
    AiClass.VEC -> VEC_OP_BYTES + originBytes(origin) + 8L * (h.vectors?.size ?: 0)
    AiClass.OCR -> TEXT_OP_BYTES + originBytes(origin) + (h.text?.length ?: 0) + (h.text?.bodyLength ?: 0)
  }

  private fun addUsage(origin: AiOrigin, h: Holding, cls: AiClass) {
    val u = usage.getOrPut(origin) { OriginUsage() }
    if (cls == AiClass.VEC) u.records += h.vectors?.size ?: 0 else u.textBytes += (h.text?.length ?: 0) + (h.text?.bodyLength ?: 0)
    liveBytes += weight(origin, h, cls)
  }

  private fun forgetUsage(origin: AiOrigin, h: Holding, cls: AiClass) {
    usage[origin]?.let { u ->
      if (cls == AiClass.VEC) u.records -= h.vectors?.size ?: 0 else u.textBytes -= (h.text?.length ?: 0) + (h.text?.bodyLength ?: 0)
      if (u.records <= 0 && u.textBytes <= 0) usage.remove(origin)
    }
    liveBytes -= weight(origin, h, cls)
  }

  /** Una voce codificata: i byte e, per il testo, dove cominciano il testo e il corpo dentro i byte (-1 = niente). */
  private class Encoded(val bytes: ByteArray, val textOffset: Int, val bodyOffset: Int)

  private fun encodeOne(op: LedgerOp): Encoded {
    val bytes = ByteArrayOutputStream()
    val out = DataOutputStream(bytes)
    var textOffset = -1
    var bodyOffset = -1
    when (op) {
      is LedgerOp.PutVectors -> {
        out.writeByte(OP_VEC2); origin(out, op.origin); out.write(op.key.bytes()); out.writeByte(op.recipe); out.writeByte(op.source)
        out.writeByte(op.offsets.size); op.offsets.forEach { out.writeLong(it) }
      }
      is LedgerOp.PutText -> {
        out.writeByte(OP_TEXT2); origin(out, op.origin); out.write(op.key.bytes()); out.writeByte(op.recipe)
        out.writeByte(op.engine); out.writeByte(op.source); out.writeInt(op.lines); out.writeInt(op.chars)
        val utf = op.text.toByteArray(Charsets.UTF_8)
        out.writeInt(utf.size)
        out.flush()
        textOffset = bytes.size()
        out.write(utf)
        val body = op.body
        out.writeInt(body?.size ?: -1)
        out.flush()
        if (body != null) {
          bodyOffset = bytes.size()
          out.write(body)
        }
      }
      is LedgerOp.Drop -> {
        out.writeByte(OP_DROP); origin(out, op.origin); out.write(op.key.bytes()); out.writeByte(op.cls.code)
      }
      is LedgerOp.Generation -> {
        out.writeByte(OP_GEN); out.writeLong(op.generation)
      }
    }
    out.flush()
    return Encoded(bytes.toByteArray(), textOffset, bodyOffset)
  }

  private fun origin(out: DataOutputStream, origin: AiOrigin) {
    when (origin) {
      AiOrigin.Local -> out.writeByte(0)
      is AiOrigin.Replica -> {
        out.writeByte(1)
        string(out, origin.share)
        string(out, origin.device)
      }
      is AiOrigin.Derived -> {
        out.writeByte(2)
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

  private class Decoded(val op: LedgerOp, val textAt: Long, val bodyAt: Long)

  private fun decode(payload: ByteArray, payloadStart: Long): List<Decoded> {
    val b = ByteBuffer.wrap(payload)
    val out = ArrayList<Decoded>()
    fun str(): String = ByteArray(b.short.toInt() and 0xffff).also { b.get(it) }.toString(Charsets.UTF_8)
    fun origin(): AiOrigin = when (b.get().toInt()) {
      0 -> AiOrigin.Local
      // I13: la share sempre in minuscolo, anche per le voci scritte prima.
      1 -> AiOrigin.replica(str(), str())
      2 -> AiOrigin.derived(str(), str())
      else -> error("Origine sconosciuta.")
    }
    fun key(): ContentKey = ContentKey(VectorFile.hex(ByteArray(32).also { b.get(it) }))
    while (b.hasRemaining()) {
      when (val code = b.get().toInt()) {
        OP_VEC, OP_VEC2 -> {
          val o = origin(); val k = key(); val recipe = b.get().toInt() and 0xff
          val source = if (code == OP_VEC2) b.get().toInt() and 0xff else 0
          val n = b.get().toInt() and 0xff
          out += Decoded(LedgerOp.PutVectors(o, k, recipe, LongArray(n) { b.long }, source), -1, -1)
        }
        OP_TEXT, OP_TEXT2 -> {
          val o = origin(); val k = key(); val recipe = b.get().toInt() and 0xff
          val engine = b.get().toInt() and 0xff; val source = b.get().toInt() and 0xff
          val lines = b.int; val chars = b.int; val len = b.int
          require(len in 0..b.remaining()) { "Testo troppo lungo." }
          val position = payloadStart + b.position()
          val text = ByteArray(len).also { b.get(it) }.toString(Charsets.UTF_8)
          var body: ByteArray? = null
          var bodyAt = -1L
          if (code == OP_TEXT2) {
            val bodyLen = b.int
            require(bodyLen in -1..b.remaining()) { "Corpo troppo lungo." }
            if (bodyLen >= 0) {
              bodyAt = payloadStart + b.position()
              body = ByteArray(bodyLen).also { b.get(it) }
            }
          }
          out += Decoded(LedgerOp.PutText(o, k, recipe, engine, source, lines, chars, text, body), position, bodyAt)
        }
        OP_DROP -> {
          val o = origin(); val k = key()
          out += Decoded(LedgerOp.Drop(o, k, AiClass.fromCode(b.get().toInt()) ?: error("Classe sconosciuta.")), -1, -1)
        }
        OP_GEN -> out += Decoded(LedgerOp.Generation(b.long), -1, -1)
        else -> error("Voce sconosciuta.")
      }
    }
    return out
  }

  private fun crc32(bytes: ByteArray): Int = CRC32().also { it.update(bytes) }.value.toInt()

  companion object {
    private const val OP_VEC = 1
    private const val OP_TEXT = 2
    private const val OP_DROP = 3
    private const val OP_GEN = 4

    /** Revisione A: i vettori con la loro sorgente (`VecSource`) e il testo con il corpo `ocr` (R4, R5). */
    private const val OP_VEC2 = 5
    private const val OP_TEXT2 = 6

    /** Il tetto di una cornice per chi rilegge: oltre, il giornale e' rovinato. */
    const val MAX_FRAME: Int = 64 * 1024 * 1024

    /** R2: le cornici si scrivono da al piu' 4 MiB (una voce sola piu' grande ha la sua cornice). */
    const val FRAME_TARGET: Int = 4 * 1024 * 1024

    private const val VEC_OP_BYTES = 40L
    private const val TEXT_OP_BYTES = 60L

    private fun originBytes(origin: AiOrigin): Long = when (origin) {
      AiOrigin.Local -> 1
      is AiOrigin.Replica -> 5L + origin.share.length + origin.device.length
      is AiOrigin.Derived -> 5L + origin.share.length + origin.device.length
    }
  }
}
