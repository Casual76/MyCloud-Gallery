package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.model.VectorPrecision
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.verdictOf
import dev.pampa.mycloudgallery.core.trust.violation
import java.nio.ByteBuffer
import java.text.Normalizer

// =====================================================================================================================
// Il codec dei DATI AI nell'indice condiviso (Fase 6, P2; docs/fase6-progetto.md §2.3, D6, D13, D43; docs/formato-nas.md
// §5.5-bis "Dati AI: i corpi vec e ocr", §7, §10). Input OSTILE per definizione, come i segmenti della Fase 4: chiunque scriva via
// SMB, o un dispositivo approvato ma compromesso, puo' mettere qualunque byte nella share. Regole:
// - "MCGB" | versione **2** | la stessa tabella di §5.5-bis (classe 7 o 8, N voci, chiavi di contenuto strettamente crescenti,
//   lunghezze) | i corpi binari, big endian;
// - prima dimensione e impronta della testa, poi versione, classe, tabella, corpi, ognuno con i suoi tetti;
// - nessuna allocazione da lunghezze dichiarate: N, righe, parole e testi si controllano contro i byte che ci sono davvero;
// - un errore qualunque (anche un'eccezione imprevista) scarta il segmento INTERO (E-I20), mai un crash;
// - chi scrive usa gli stessi controlli di chi legge: un segmento che questo codice produce si rilegge sempre.
// =====================================================================================================================

/**
 * Una voce di un segmento `vec` o `ocr` gia' verificato: la chiave di contenuto **intera**, il corpo letto e i suoi byte cosi' come
 * sono stati pubblicati ([bodyBytes], per conservarli e confrontarli senza ricodificare).
 */
class AiSegmentEntry(val contentKey: ContentKey, val body: AiBody, bytes: ByteArray) {
  private val raw: ByteArray = bytes.copyOf()

  /** I byte del corpo (copia). */
  val bodyBytes: ByteArray get() = raw.copyOf()

  /** L'impronta dei byte del corpo ([AiSegments.digest]): lo spareggio deterministico di D7 fra voci uguali per tutto il resto. */
  val digest: String = AiSegments.digest(raw)

  val size: Int get() = raw.size

  override fun toString(): String = "AiSegmentEntry(${body.cls.wire}, r${body.recipe}, ${raw.size} B)"
}

/** Il contenuto di un segmento `vec` o `ocr` verificato: le voci nell'ordine crescente delle chiavi. */
class AiSegmentContent(val cls: AiClass, val entries: List<AiSegmentEntry>) {
  override fun toString(): String = "AiSegmentContent(${cls.wire}, ${entries.size} voci)"
}

/**
 * Il codec (`AiSegments.encode/decode`, §1.2 del progetto della Fase 6):
 *
 * ```
 * "MCGB" | 2 | classe (7 vec, 8 ocr) | 0 0 0 | N (4, BE) | N × (chiave di contenuto 32 + lunghezza 4, BE) | corpi
 * ```
 */
object AiSegments {
  private val MAGIC = "MCGB".toByteArray(Charsets.US_ASCII)

  /** La versione del corpo `vec` e del corpo `ocr` (il primo byte di ognuno). */
  const val BODY_VERSION: Int = 1

  /** La precisione float16 nel byte 4 del corpo `vec` (§2.3: 2 = float16; altre rifiutate). */
  const val PRECISION_FLOAT16: Int = 2

  /** L'intestazione del corpo `vec` prima degli istanti e delle componenti. */
  const val VEC_HEADER_BYTES: Int = 8

  /** L'intestazione del corpo `ocr` prima delle righe. */
  const val OCR_HEADER_BYTES: Int = 6

  /** Il tetto del corpo di una voce di [cls]. */
  fun maxBodyBytes(cls: AiClass): Int = if (cls == AiClass.VEC) TrustLimits.MAX_VEC_BODY_BYTES else TrustLimits.MAX_OCR_BODY_BYTES

  /** SHA-256 dei byte di un corpo, in base32 (come i nomi dei segmenti): mai nei log, solo per confrontare. */
  fun digest(bodyBytes: ByteArray): String = Hashes.sha256Name(bodyBytes)

  // --- Corpi ---------------------------------------------------------------------------------------------------------

  /**
   * I byte del corpo di [body], **solo** se chi legge lo rileggerebbe uguale (componenti finite, norma, testo valido, tetti):
   * altrimenti null. Chi pubblica scarta il resto invece di produrre un segmento che gli altri rifiuterebbero intero.
   */
  fun bodyOrNull(body: AiBody): ByteArray? {
    val bytes = try {
      write(body)
    } catch (e: IllegalArgumentException) {
      return null
    }
    val back = (decodeBody(body.cls, bytes) as? Verdict.Accepted)?.value ?: return null
    return bytes.takeIf { back == body }
  }

  /** Vero se [body] si puo' pubblicare ([bodyOrNull] non null). */
  fun admits(body: AiBody): Boolean = bodyOrNull(body) != null

  /** I byte del corpo di [body]; [IllegalArgumentException] se chi legge lo rifiuterebbe (errore di chi chiama). */
  fun encodeBody(body: AiBody): ByteArray = requireNotNull(bodyOrNull(body)) { "Corpo non ammesso dal formato." }

  /** Legge un corpo isolato (per esempio rilegge cio' che sta nell'outbox o nella replica locale), con tutti i controlli. */
  fun decodeBody(cls: AiClass, bytes: ByteArray): Verdict<AiBody> = verdictOf { parseBody(cls, bytes, 0, bytes.size) }

  internal fun parseBody(cls: AiClass, b: ByteArray, off: Int, len: Int): AiBody {
    ensure(len >= 1 && off >= 0 && off.toLong() + len <= b.size, RejectReason.MALFORMED, "corpo ai: fuori dal segmento")
    ensure(len <= maxBodyBytes(cls), RejectReason.TOO_LARGE, "corpo ai: oltre il tetto")
    return if (cls == AiClass.VEC) parseVec(b, off, len) else parseOcr(b, off, len)
  }

  private fun write(body: AiBody): ByteArray = when (body) {
    is VecBody -> writeVec(body)
    is OcrBody -> writeOcr(body)
  }

  // --- Corpo vec -----------------------------------------------------------------------------------------------------

  private fun writeVec(body: VecBody): ByteArray {
    require(body.space.space.precision == VectorPrecision.FLOAT16) { "Solo spazi in float16." }
    val dimension = body.dimension
    val frames = body.source == VecSource.FRAMES
    val size = VEC_HEADER_BYTES + (if (frames) 4 * body.vectors.size else 0) + body.vectors.size * dimension * 2
    require(size <= TrustLimits.MAX_VEC_BODY_BYTES) { "Corpo vec oltre il tetto." }
    val out = ByteBuffer.allocate(size)
    out.put(BODY_VERSION.toByte())
    out.put(body.space.wire.toByte())
    out.putShort(dimension.toShort())
    out.put(PRECISION_FLOAT16.toByte())
    out.put(body.recipe.toByte())
    out.put(body.source.wire.toByte())
    out.put(body.vectors.size.toByte())
    if (frames) body.vectors.forEach { out.putInt(requireNotNull(it.frameMs)) }
    for (vector in body.vectors) for (i in 0 until dimension) out.putShort(vector.component(i))
    return out.array()
  }

  private fun parseVec(b: ByteArray, off: Int, len: Int): VecBody {
    ensure(len >= VEC_HEADER_BYTES, RejectReason.MALFORMED, "vec: intestazione troncata")
    ensure(u8(b, off) == BODY_VERSION, RejectReason.INVALID_FIELD, "vec: versione del corpo sconosciuta")
    val space = SpaceCode.fromWire(u8(b, off + 1)) ?: violation(RejectReason.INVALID_FIELD, "vec: spazio sconosciuto")
    ensure(space.space.precision == VectorPrecision.FLOAT16, RejectReason.INVALID_FIELD, "vec: spazio non in float16")
    val dimension = u16(b, off + 2)
    ensure(dimension == space.space.dimension, RejectReason.INVALID_FIELD, "vec: dimensione diversa da quella dello spazio")
    ensure(u8(b, off + 4) == PRECISION_FLOAT16, RejectReason.INVALID_FIELD, "vec: precisione sconosciuta")
    val recipe = u8(b, off + 5)
    ensure(recipe in 1..AiLimits.MAX_RECIPE, RejectReason.INVALID_FIELD, "vec: ricetta")
    val source = VecSource.fromWire(u8(b, off + 6)) ?: violation(RejectReason.INVALID_FIELD, "vec: sorgente sconosciuta")
    val count = u8(b, off + 7)
    ensure(count in 1..AiLimits.MAX_VECTORS, RejectReason.INVALID_FIELD, "vec: numero di vettori")
    ensure(!source.singleVector || count == 1, RejectReason.INVALID_FIELD, "vec: piu' vettori per un'immagine")
    val frames = source == VecSource.FRAMES
    // La lunghezza esatta si controlla PRIMA di allocare: count <= 9 e dimension e' quella dello spazio (768).
    val expected = VEC_HEADER_BYTES.toLong() + (if (frames) 4L * count else 0L) + count.toLong() * dimension * 2
    ensure(len.toLong() == expected, RejectReason.MALFORMED, "vec: lunghezza diversa da quella dichiarata")
    var p = off + VEC_HEADER_BYTES
    val instants = IntArray(count)
    if (frames) {
      for (i in 0 until count) {
        val at = ByteBuffer.wrap(b, p, 4).int
        ensure(at >= 0, RejectReason.INVALID_FIELD, "vec: istante negativo")
        instants[i] = at
        p += 4
      }
    }
    val items = ArrayList<VecItem>(count)
    for (i in 0 until count) {
      val bits = ShortArray(dimension)
      for (c in 0 until dimension) {
        val half = u16(b, p)
        // Esponente tutto a uno = infinito o NaN (E-I20): un vettore non finito non si usa ne' si confronta.
        ensure(half and 0x7c00 != 0x7c00, RejectReason.INVALID_FIELD, "vec: componente non finita")
        bits[c] = half.toShort()
        p += 2
      }
      val norm = Float16.norm(bits)
      ensure(norm >= AiLimits.MIN_NORM && norm <= AiLimits.MAX_NORM, RejectReason.INVALID_FIELD, "vec: norma fuori da 0,99..1,01")
      items += VecItem(if (frames) instants[i] else null, bits)
    }
    ensure(p == off + len, RejectReason.MALFORMED, "vec: byte in piu'")
    return fieldOf { VecBody(space, recipe, source, items) }
  }

  // --- Corpo ocr -----------------------------------------------------------------------------------------------------

  private fun writeOcr(body: OcrBody): ByteArray {
    val out = java.io.ByteArrayOutputStream(OCR_HEADER_BYTES + body.lines.size * 32)
    out.write(BODY_VERSION)
    out.write(body.engine.wire)
    out.write(body.recipe)
    out.write(body.source.wire)
    writeU16(out, body.lines.size)
    for (line in body.lines) {
      writeU16(out, line.box.x0)
      writeU16(out, line.box.y0)
      writeU16(out, line.box.x1)
      writeU16(out, line.box.y1)
      out.write(line.angle and 0xff)
      out.write(line.words.size)
      for (word in line.words) {
        writeU16(out, word.start)
        writeU16(out, word.end)
      }
      val text = line.text.toByteArray(Charsets.UTF_8)
      writeU16(out, text.size)
      out.write(text)
    }
    require(out.size() <= TrustLimits.MAX_OCR_BODY_BYTES) { "Corpo ocr oltre il tetto." }
    return out.toByteArray()
  }

  private fun parseOcr(b: ByteArray, off: Int, len: Int): OcrBody {
    ensure(len >= OCR_HEADER_BYTES, RejectReason.MALFORMED, "ocr: intestazione troncata")
    ensure(u8(b, off) == BODY_VERSION, RejectReason.INVALID_FIELD, "ocr: versione del corpo sconosciuta")
    val engine = OcrEngineCode.fromWire(u8(b, off + 1)) ?: violation(RejectReason.INVALID_FIELD, "ocr: motore sconosciuto")
    val recipe = u8(b, off + 2)
    ensure(recipe in 1..AiLimits.MAX_RECIPE, RejectReason.INVALID_FIELD, "ocr: ricetta")
    val source = OcrSource.fromWire(u8(b, off + 3)) ?: violation(RejectReason.INVALID_FIELD, "ocr: sorgente sconosciuta")
    val count = u16(b, off + 4)
    ensure(count <= AiLimits.MAX_OCR_LINES, RejectReason.TOO_LARGE, "ocr: troppe righe")
    val end = off + len
    var p = off + OCR_HEADER_BYTES
    val lines = ArrayList<OcrLine>(count)
    repeat(count) {
      // Riquadro (8), inclinazione (1), parole (1): poi le parole, poi la lunghezza del testo e il testo.
      ensure(p + 10 <= end, RejectReason.MALFORMED, "ocr: riga troncata")
      val x0 = u16(b, p)
      val y0 = u16(b, p + 2)
      val x1 = u16(b, p + 4)
      val y1 = u16(b, p + 6)
      ensure(x0 <= x1 && y0 <= y1, RejectReason.INVALID_FIELD, "ocr: riquadro rovesciato")
      val angle = b[p + 8].toInt()
      ensure(angle in -90..90, RejectReason.INVALID_FIELD, "ocr: inclinazione fuori da -90..90")
      val words = u8(b, p + 9)
      ensure(words in 1..AiLimits.MAX_WORDS_PER_LINE, RejectReason.INVALID_FIELD, "ocr: numero di parole")
      p += 10
      ensure(p.toLong() + 4L * words + 2 <= end, RejectReason.MALFORMED, "ocr: parole troncate")
      val spans = ArrayList<WordSpan>(words)
      var lastStart = -1
      var lastEnd = -1
      for (w in 0 until words) {
        val start = u16(b, p)
        val stop = u16(b, p + 2)
        ensure(start <= stop, RejectReason.INVALID_FIELD, "ocr: parola con estremi rovesciati")
        ensure(start >= lastStart && stop >= lastEnd, RejectReason.INVALID_FIELD, "ocr: estremi delle parole decrescenti")
        lastStart = start
        lastEnd = stop
        spans += WordSpan(start, stop)
        p += 4
      }
      val textBytes = u16(b, p)
      p += 2
      ensure(textBytes in 1..AiLimits.MAX_LINE_BYTES, RejectReason.INVALID_FIELD, "ocr: lunghezza del testo")
      ensure(p.toLong() + textBytes <= end, RejectReason.MALFORMED, "ocr: testo troncato")
      val text = OcrText.read(b, p, textBytes)
      p += textBytes
      val pieces = text.split(' ')
      ensure(pieces.size == words, RejectReason.INVALID_FIELD, "ocr: le parole non tornano con il testo")
      ensure(pieces.none { it.isEmpty() }, RejectReason.INVALID_FIELD, "ocr: parola vuota")
      lines += fieldOf { OcrLine(ShortBox(x0, y0, x1, y1), angle, spans, text) }
    }
    ensure(p == end, RejectReason.MALFORMED, "ocr: byte in piu'")
    return fieldOf { OcrBody(engine, recipe, source, lines) }
  }

  // --- Segmenti ------------------------------------------------------------------------------------------------------

  /**
   * Scrive un segmento di [cls] con le voci [entries] (in qualunque ordine: si ordinano per chiave). Ogni corpo passa dagli stessi
   * controlli di chi legge; un corpo non ammesso, una classe diversa da [cls], chiavi ripetute o tetti superati sono errori di chi
   * chiama ([IllegalArgumentException]).
   */
  fun encode(cls: AiClass, entries: List<Pair<ContentKey, AiBody>>): EncodedSegment {
    require(entries.all { it.second.cls == cls }) { "Voci di un'altra classe." }
    return encodeBodies(cls, entries.map { (key, body) -> key to encodeBody(body) })
  }

  /**
   * Come [encode], con i corpi gia' codificati (l'outbox li conserva cosi'): ognuno si rilegge con i controlli di chi legge prima di
   * entrare nel segmento.
   */
  fun encodeBodies(cls: AiClass, entries: List<Pair<ContentKey, ByteArray>>): EncodedSegment {
    require(entries.size in 1..TrustLimits.MAX_SEGMENT_ENTRIES) { "Da 1 a ${TrustLimits.MAX_SEGMENT_ENTRIES} voci." }
    val sorted = entries.map { SegmentEntry(IndexKeys.content(it.first), it.second) }.sortedWith { a, b -> IndexKeys.compare(a.key, b.key) }
    for (i in 1 until sorted.size) require(IndexKeys.compare(sorted[i - 1].key, sorted[i].key) < 0) { "Chiavi ripetute." }
    for (entry in sorted) {
      require(entry.body.size <= maxBodyBytes(cls)) { "Corpo oltre il tetto." }
      try {
        parseBody(cls, entry.body, 0, entry.body.size)
      } catch (v: TrustViolation) {
        throw IllegalArgumentException("Corpo non ammesso dal formato (${v.reason}).")
      }
    }
    val total = IndexSegments.sizeOf(sorted.size, sorted.sumOf { it.body.size.toLong() })
    require(total <= TrustLimits.MAX_SEGMENT_BYTES) { "Segmento oltre il tetto." }
    val out = ByteBuffer.allocate(total.toInt())
    out.put(MAGIC).put(TrustFormat.AI_VERSION.toByte())
    out.put(cls.code.toByte()).put(0).put(0).put(0).putInt(sorted.size)
    for (entry in sorted) out.put(entry.key).putInt(entry.body.size)
    for (entry in sorted) out.put(entry.body)
    val bytes = out.array()
    return EncodedSegment(cls.segmentClass, bytes, SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong()), sorted.size)
  }

  /**
   * Legge un segmento elencato da una testa `ai` ACCETTATA come [ref] con la classe [expected]: dimensione e impronta → contenitore
   * e versione (2; piu' nuova = `NEWER_FORMAT`, E-I3; la 1 qui e' un segmento dell'indice falso) → classe (`WRONG_TYPE`) → `N`
   * entro il tetto → tabella coerente (chiavi strettamente crescenti, somma delle lunghezze = resto del file) → ogni corpo con i
   * suoi tetti e i suoi valori. Si lavora su una copia privata.
   */
  fun decode(bytes: ByteArray, ref: SegmentRef, expected: AiClass): Verdict<AiSegmentContent> = verdictOf {
    ensure(bytes.size.toLong() == ref.size, RejectReason.HASH_MISMATCH, "segmento ai: dimensione diversa")
    val own = bytes.copyOf()
    ensure(Hashes.sha256Name(own) == ref.hash, RejectReason.HASH_MISMATCH, "segmento ai: impronta diversa")
    ensure(own.size > MAGIC.size && MAGIC.indices.all { own[it] == MAGIC[it] }, RejectReason.MALFORMED, "segmento ai: contenitore")
    val version = own[MAGIC.size].toInt() and 0xff
    if (version > TrustFormat.NEWEST_READABLE_VERSION) violation(RejectReason.NEWER_FORMAT, "segmento ai: formato piu' nuovo")
    ensure(version == TrustFormat.AI_VERSION, RejectReason.MALFORMED, "segmento ai: versione diversa da quella dei dati AI")
    val base = IndexSegments.CONTAINER_BYTES
    ensure(own.size >= base + IndexSegments.CONTENT_HEADER_BYTES, RejectReason.MALFORMED, "segmento ai: intestazione troncata")
    ensure((own[base].toInt() and 0xff) == expected.code, RejectReason.WRONG_TYPE, "segmento ai: classe diversa da quella della testa")
    ensure(
      own[base + 1].toInt() == 0 && own[base + 2].toInt() == 0 && own[base + 3].toInt() == 0,
      RejectReason.MALFORMED,
      "segmento ai: byte riservati",
    )
    val n = ByteBuffer.wrap(own, base + 4, 4).int.toLong() and 0xffffffffL
    ensure(n >= 1, RejectReason.MALFORMED, "segmento ai: nessuna voce")
    ensure(n <= TrustLimits.MAX_SEGMENT_ENTRIES, RejectReason.TOO_LARGE, "segmento ai: troppe voci")
    val count = n.toInt()
    val tableStart = base + IndexSegments.CONTENT_HEADER_BYTES
    val tableEnd = tableStart.toLong() + IndexSegments.TABLE_ENTRY_BYTES.toLong() * count
    ensure(tableEnd <= own.size, RejectReason.MALFORMED, "segmento ai: tabella troncata")
    val maxBody = maxBodyBytes(expected)
    var sum = 0L
    for (i in 0 until count) {
      val row = tableStart + IndexSegments.TABLE_ENTRY_BYTES * i
      val length = lengthAt(own, row)
      ensure(length >= 1, RejectReason.MALFORMED, "segmento ai: corpo vuoto")
      ensure(length <= maxBody, RejectReason.TOO_LARGE, "segmento ai: corpo oltre il tetto")
      sum += length
      if (i > 0) ensure(compareRows(own, row - IndexSegments.TABLE_ENTRY_BYTES, row) < 0, RejectReason.MALFORMED, "segmento ai: chiavi non crescenti")
    }
    ensure(sum == own.size - tableEnd, RejectReason.MALFORMED, "segmento ai: corpi e tabella non coincidono")
    var offset = tableEnd.toInt()
    val entries = ArrayList<AiSegmentEntry>(count)
    for (i in 0 until count) {
      val row = tableStart + IndexSegments.TABLE_ENTRY_BYTES * i
      val length = lengthAt(own, row).toInt()
      val body = parseBody(expected, own, offset, length)
      entries += AiSegmentEntry(ContentKey(Hex.of(own, row, SegmentEntry.KEY_BYTES)), body, own.copyOfRange(offset, offset + length))
      offset += length
    }
    AiSegmentContent(expected, entries)
  }

  // --- Varie ---------------------------------------------------------------------------------------------------------

  /** I `require` dei tipi diventano un rifiuto del campo, mai un'eccezione verso fuori. */
  private inline fun <T> fieldOf(block: () -> T): T =
    try {
      block()
    } catch (e: IllegalArgumentException) {
      violation(RejectReason.INVALID_FIELD, "corpo ai: valore non ammesso")
    }

  private fun lengthAt(b: ByteArray, row: Int): Long = ByteBuffer.wrap(b, row + SegmentEntry.KEY_BYTES, 4).int.toLong() and 0xffffffffL

  private fun compareRows(b: ByteArray, a: Int, c: Int): Int {
    for (i in 0 until SegmentEntry.KEY_BYTES) {
      val d = (b[a + i].toInt() and 0xff) - (b[c + i].toInt() and 0xff)
      if (d != 0) return d
    }
    return 0
  }

  private fun writeU16(out: java.io.ByteArrayOutputStream, value: Int) {
    require(value in 0..0xffff) { "Valore oltre 2 byte." }
    out.write(value ushr 8)
    out.write(value and 0xff)
  }

  private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xff

  private fun u16(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)
}

/**
 * Le regole del TESTO letto (D13, §2.3): UTF-8 **ben formato** (niente forme lunghe, niente surrogati, niente byte oltre U+10FFFF),
 * in forma **NFC**, senza i caratteri che [TextRules] vieta in ogni testo del NAS (controlli C0 e C1, caratteri di direzione
 * U+200E, U+200F, U+202A–U+202E, U+2066–U+2069, U+FEFF e gli altri invisibili e non caratteri dell'elenco). L'elenco e' esplicito e
 * non dipende dalla versione di Unicode del dispositivo: tutti i dispositivi della famiglia decidono allo stesso modo.
 */
object OcrText {

  /** Vero se [text] si puo' pubblicare come testo di una riga (escluse lunghezza e parole, che controlla la riga). */
  fun isAllowed(text: String): Boolean = !TextRules.hasForbidden(text) && Normalizer.isNormalized(text, Normalizer.Form.NFC)

  /**
   * Per chi legge il testo con il motore (P4, P5): una parola cosi' come la scrive il formato, o null se non ne resta niente. NFC,
   * senza i caratteri vietati e senza spazi (uno spazio separerebbe due parole). Le parole nulle si tolgono con i loro estremi.
   */
  fun cleanWord(raw: String): String? {
    val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
    val out = StringBuilder(nfc.length)
    var i = 0
    while (i < nfc.length) {
      val cp = nfc.codePointAt(i)
      val size = Character.charCount(cp)
      val lone = size == 1 && Character.isSurrogate(nfc[i])
      if (!lone && cp != ' '.code && !TextRules.isForbiddenCodePoint(cp)) out.appendCodePoint(cp)
      i += size
    }
    val word = Normalizer.normalize(out.toString(), Normalizer.Form.NFC)
    return word.takeIf { it.isNotEmpty() && isAllowed(it) }
  }

  /** Legge [length] byte di testo da [b] a [off] con tutte le regole; ogni violazione rifiuta il segmento. */
  internal fun read(b: ByteArray, off: Int, length: Int): String {
    checkUtf8(b, off, length)
    val text = String(b, off, length, Charsets.UTF_8)
    ensure(!TextRules.hasForbidden(text), RejectReason.INVALID_FIELD, "ocr: carattere di controllo, di direzione o invisibile")
    ensure(Normalizer.isNormalized(text, Normalizer.Form.NFC), RejectReason.NOT_CANONICAL, "ocr: testo non in NFC")
    return text
  }

  /** UTF-8 ben formato secondo la tabella 3-7 di Unicode: una sequenza alla volta, nessuna eccezione per i casi strani. */
  private fun checkUtf8(b: ByteArray, off: Int, length: Int) {
    val end = off + length
    var i = off
    while (i < end) {
      val lead = b[i].toInt() and 0xff
      val (size, low, high) = when (lead) {
        in 0x00..0x7f -> Triple(1, 0, 0)
        in 0xc2..0xdf -> Triple(2, 0x80, 0xbf)
        0xe0 -> Triple(3, 0xa0, 0xbf) // niente forme lunghe
        in 0xe1..0xec, 0xee, 0xef -> Triple(3, 0x80, 0xbf)
        0xed -> Triple(3, 0x80, 0x9f) // niente surrogati (U+D800..U+DFFF)
        0xf0 -> Triple(4, 0x90, 0xbf) // niente forme lunghe
        in 0xf1..0xf3 -> Triple(4, 0x80, 0xbf)
        0xf4 -> Triple(4, 0x80, 0x8f) // niente oltre U+10FFFF
        else -> violation(RejectReason.INVALID_FIELD, "ocr: UTF-8 non valido")
      }
      ensure(i + size <= end, RejectReason.INVALID_FIELD, "ocr: UTF-8 troncato")
      if (size > 1) {
        val second = b[i + 1].toInt() and 0xff
        ensure(second in low..high, RejectReason.INVALID_FIELD, "ocr: UTF-8 non valido")
        for (k in 2 until size) ensure((b[i + k].toInt() and 0xc0) == 0x80, RejectReason.INVALID_FIELD, "ocr: UTF-8 non valido")
      }
      i += size
    }
  }
}
