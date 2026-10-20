package dev.pampa.mycloudgallery.core.personal.codec

import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalLimits
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.SimilarCopiesRecords
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.codec.JsonBool
import dev.pampa.mycloudgallery.core.trust.codec.JsonInt
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.codec.JsonString
import dev.pampa.mycloudgallery.core.trust.codec.JsonValue
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.data.EncodedSegment
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.data.IndexSegments
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.data.Segments
import java.nio.ByteBuffer

// Il contenuto della classe `personal` dei segmenti (docs/formato-nas.md §5.5-bis, docs/fase4-progetto.md §2.9). Lo stesso contenitore
// degli altri segmenti (`"MCGB" | versione | classe | 0 0 0 | N | N × (chiave 32 + lunghezza 4) | corpi`), con la classe 16: per
// questo si appoggia a `Segments.wrap/unwrap` di core-trust (impronta, dimensione, versione) e scrive qui solo la tabella e i corpi.
//
// Input OSTILE come ogni segmento (§4.3 "lettura difensiva"): chiunque abbia la password dell'account (o un dispositivo approvato
// compromesso, L29) puo' scrivere qualunque byte nell'area privata. Regole come `IndexSegments.decode`: dimensione e impronta della
// testa, classe, `N` entro il tetto, tabella coerente con i byte che ci sono davvero (mai un'allocazione da una lunghezza
// dichiarata), corpi JSON canonici entro 4 KiB, chiave uguale all'impronta del contenuto. Un errore scarta il segmento intero.

private fun violation(reason: RejectReason, message: String): Nothing = throw TrustViolation(reason, message)

private fun ensure(condition: Boolean, reason: RejectReason, message: String) {
  if (!condition) violation(reason, message)
}

/** Esegue un parser su dati remoti: qualunque eccezione e' un rifiuto, mai un crash (E-I14). */
private inline fun <T> verdictOf(block: () -> T): Verdict<T> =
  try {
    Verdict.Accepted(block())
  } catch (v: TrustViolation) {
    Verdict.Rejected(v.reason)
  } catch (e: RuntimeException) {
    Verdict.Rejected(RejectReason.MALFORMED)
  }

/** I `require` dei tipi diventano un rifiuto del campo, mai un'eccezione verso fuori. */
private inline fun <T> fieldOf(block: () -> T): T =
  try {
    block()
  } catch (e: IllegalArgumentException) {
    violation(RejectReason.INVALID_FIELD, "voce: valore non ammesso")
  }

/**
 * Il corpo di una voce: JSON canonico `{"at":…,"author":…,"field":…,"fp":…,"media":…,"value":…}`.
 *
 * - `at`: ora del NAS della modifica (ms, entro il periodo plausibile del formato);
 * - `author`: l'id del dispositivo che ha fatto la modifica (non quello che pubblica: lo stato e' unito);
 * - `media`: il soggetto (id del media o dell'album, o [dev.pampa.mycloudgallery.core.personal.contract.PersonalFields.SETTINGS_SUBJECT]);
 * - `field`: il campo;
 * - `fp` (facoltativo): `{"qh":…,"size":…}`, `qh1` in base32 e dimensione del media, per ritrovarlo se le identita' divergono;
 * - `value` (assente = lapide): vero/falso, intero o testo. Nella voce di una separazione fra copie simili (chiave
 *   [dev.pampa.mycloudgallery.core.personal.contract.SimilarCopiesRecords.separation]) un testo e' l'impronta dell'altro media,
 *   `<qh1 in base32>:<dimensione>` ([PersonalValue.Apart]): un testo d'altra forma li' scarta il segmento come ogni campo fuori forma.
 *   E' un testo per il formato: nessun campo e nessun tipo di valore nuovo, quindi nessuna versione nuova (§10); una versione
 *   precedente lo legge come un testo e non separa niente.
 *
 * Il **tipo** non e' nel corpo: lo dice la chiave della tabella (impronta di tipo, soggetto e campo), che chi legge ricalcola per
 * ogni tipo. Una chiave che non e' l'impronta di nessun tipo noto scarta il segmento.
 */
object PersonalRecords {

  /** Il JSON canonico di [entry]. Lancia [IllegalArgumentException] se non sta in [TrustLimits.MAX_META_RECORD_BYTES]. */
  fun encode(entry: PersonalEntry): ByteArray {
    val bytes = CanonicalJson.encode(toJson(entry))
    require(bytes.size <= TrustLimits.MAX_META_RECORD_BYTES) { "Voce oltre il tetto." }
    return bytes
  }

  /**
   * Il JSON di [entry] solo se chi legge lo rileggerebbe identico (tetto, forma, ora plausibile, testo senza caratteri vietati):
   * chi pubblica scarta il resto invece di produrre un segmento che gli altri rifiuterebbero intero.
   */
  fun encodeOrNull(entry: PersonalEntry): ByteArray? {
    val bytes = try {
      encode(entry)
    } catch (e: IllegalArgumentException) {
      return null
    }
    val back = (decode(bytes, entry.key.digest()) as? Verdict.Accepted)?.value ?: return null
    return bytes.takeIf { back == entry }
  }

  /** Legge il corpo di una voce la cui chiave nella tabella e' [tableKey] (32 byte). */
  fun decode(body: ByteArray, tableKey: ByteArray): Verdict<PersonalEntry> = verdictOf { read(body, tableKey) }

  internal fun read(body: ByteArray, tableKey: ByteArray): PersonalEntry {
    ensure(body.size <= TrustLimits.MAX_META_RECORD_BYTES, RejectReason.TOO_LARGE, "voce: oltre il tetto")
    ensure(tableKey.size == SEGMENT_KEY_BYTES, RejectReason.MALFORMED, "voce: chiave")
    val obj = CanonicalJson.parseCanonical(body) as? JsonObject ?: violation(RejectReason.MALFORMED, "voce: oggetto atteso")
    // `value` puo' essere di tre tipi (o mancare): lo si legge a mano, il resto con il lettore stretto dei campi.
    val rawValue = obj.members["value"]
    val rest = JsonObject(obj.members - "value")
    return Fields.read(rest) { f ->
      val at = f.time("at")
      val author = f.string("author", TRUST_ID_CHARS)
      val field = f.string("field", PersonalKey.MAX_PART)
      val subject = f.string("media", PersonalKey.MAX_PART)
      val fingerprint = if ("fp" in rest.members) f.obj("fp") { g -> readFingerprint(g) } else null
      ensure(!TextRules.hasForbidden(field) && !TextRules.hasForbidden(subject), RejectReason.INVALID_FIELD, "voce: caratteri non ammessi")
      val key = keyOf(tableKey, subject, field)
      fieldOf { PersonalEntry(key, at, author, valueOf(rawValue, key), fingerprint) }
    }
  }

  /** Il tipo e' quello la cui impronta e' la chiave della tabella: nessun'altra via per dichiararlo. */
  private fun keyOf(tableKey: ByteArray, subject: String, field: String): PersonalKey {
    for (kind in PersonalKind.entries) {
      val candidate = fieldOf { PersonalKey(kind, subject, field) }
      if (IndexKeys.compare(candidate.digest(), tableKey) == 0) return candidate
    }
    violation(RejectReason.INVALID_FIELD, "voce: la chiave non e' quella del suo contenuto")
  }

  private fun readFingerprint(g: Fields): PersonalFingerprint {
    val size = g.long("size", 0..Long.MAX_VALUE)
    val quick = g.string("qh", QUICK_HASH_CHARS)
    val bytes = Base32.decodeExact(quick, QUICK_HASH_BYTES)
    return fieldOf { PersonalFingerprint(size, QuickHash(hex(bytes))) }
  }

  private fun valueOf(raw: JsonValue?, key: PersonalKey): PersonalValue = when (raw) {
    null -> PersonalValue.Removed
    is JsonBool -> PersonalValue.Flag(raw.value)
    is JsonInt -> PersonalValue.Number(raw.value)
    is JsonString -> {
      ensure(raw.value.length <= PersonalValue.MAX_TEXT, RejectReason.TOO_LARGE, "voce: testo troppo lungo")
      ensure(!TextRules.hasForbidden(raw.value), RejectReason.INVALID_FIELD, "voce: caratteri non ammessi")
      // Il testo di una separazione e' l'impronta dell'altro media, in una forma sola.
      if (SimilarCopiesRecords.separatedPair(key) == null) {
        PersonalValue.Text(raw.value)
      } else {
        PersonalValue.Apart.parse(raw.value) ?: violation(RejectReason.INVALID_FIELD, "voce: impronta della separazione")
      }
    }
    else -> violation(RejectReason.MALFORMED, "voce: valore")
  }

  private fun toJson(entry: PersonalEntry): JsonObject {
    val fingerprint = entry.fingerprint
    return jsonObject(
      "at" to entry.atNas.json(),
      "author" to entry.author.json(),
      "field" to entry.key.field.json(),
      "fp" to fingerprint?.let { jsonObject("qh" to Base32.encode(bytesOfHex(it.quickHash.hex)).json(), "size" to it.size.json()) },
      "media" to entry.key.subject.json(),
      "value" to when (val value = entry.value) {
        is PersonalValue.Flag -> value.on.json()
        is PersonalValue.Number -> value.value.json()
        is PersonalValue.Text -> value.text.json()
        is PersonalValue.Apart -> value.text.json()
        PersonalValue.Removed -> null
      },
    )
  }

  private fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val out = CharArray(bytes.size * 2)
    bytes.forEachIndexed { i, b ->
      val v = b.toInt() and 0xff
      out[i * 2] = digits[v ushr 4]
      out[i * 2 + 1] = digits[v and 0x0f]
    }
    return String(out)
  }

  private fun bytesOfHex(text: String): ByteArray = ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

  internal const val SEGMENT_KEY_BYTES = 32
  private const val TRUST_ID_CHARS = 26
  private const val QUICK_HASH_BYTES = 32
  private const val QUICK_HASH_CHARS = 52
}

/** Una voce pronta per un segmento: la chiave della tabella e il corpo. */
internal class EncodedItem(val entry: PersonalEntry, val key: ByteArray, val body: ByteArray)

/** Lo stato da pubblicare, diviso in segmenti. [skipped] sono le voci che non si possono scrivere (mai pubblicate, contate). */
class PersonalPublishPlan(val segments: List<EncodedSegment>, val entries: Int, val skipped: Int) {
  /** I nomi dei segmenti: lo stato pubblicato si riconosce da questo insieme. */
  val names: Set<String> get() = segments.mapTo(LinkedHashSet()) { it.ref.hash }

  override fun toString(): String = "PersonalPublishPlan(${segments.size} segmenti, $entries voci, $skipped scartate)"
}

/** Il contenitore dei segmenti `personal` (classe 16). */
object PersonalSegments {

  private const val CLASS_BYTES = 1
  private const val RESERVED_BYTES = 3
  private const val COUNT_BYTES = 4
  private const val HEADER_BYTES = CLASS_BYTES + RESERVED_BYTES + COUNT_BYTES
  private const val TABLE_ENTRY_BYTES = PersonalRecords.SEGMENT_KEY_BYTES + 4

  /**
   * Un segmento con [entries] (da 1 a [TrustLimits.MAX_SEGMENT_ENTRIES], qualunque ordine, nessuna chiave ripetuta). Ogni voce deve
   * essere scrivibile ([PersonalRecords.encodeOrNull]); errori di chi chiama sono [IllegalArgumentException].
   */
  fun encode(entries: List<PersonalEntry>): EncodedSegment {
    val items = entries.map { entry ->
      EncodedItem(entry, entry.key.digest(), PersonalRecords.encodeOrNull(entry) ?: throw IllegalArgumentException("Voce non scrivibile."))
    }
    return build(items.sortedWith { a, b -> IndexKeys.compare(a.key, b.key) })
  }

  /** [items] gia' in ordine di chiave. */
  internal fun build(items: List<EncodedItem>): EncodedSegment {
    require(items.size in 1..TrustLimits.MAX_SEGMENT_ENTRIES) { "Da 1 a ${TrustLimits.MAX_SEGMENT_ENTRIES} voci." }
    for (i in 1 until items.size) require(IndexKeys.compare(items[i - 1].key, items[i].key) < 0) { "Chiavi ripetute." }
    val bodyBytes = items.sumOf { it.body.size.toLong() }
    val content = HEADER_BYTES.toLong() + TABLE_ENTRY_BYTES.toLong() * items.size + bodyBytes
    require(content + IndexSegments.CONTAINER_BYTES <= TrustLimits.MAX_SEGMENT_BYTES) { "Segmento oltre il tetto." }
    val out = ByteBuffer.allocate(content.toInt())
    out.put(SegmentClass.PERSONAL.code.toByte()).put(0).put(0).put(0).putInt(items.size)
    for (item in items) out.put(item.key).putInt(item.body.size)
    for (item in items) out.put(item.body)
    val (bytes, ref) = Segments.wrap(out.array())
    return EncodedSegment(SegmentClass.PERSONAL, bytes, ref, items.size)
  }

  /**
   * Legge un segmento elencato da una testa ACCETTATA come [ref]. Si lavora su una copia (`Segments.unwrap`): chi chiama non puo'
   * cambiare i byte fra verifica e lettura.
   */
  fun decode(bytes: ByteArray, ref: SegmentRef): Verdict<List<PersonalEntry>> = verdictOf {
    val content = when (val unwrapped = Segments.unwrap(bytes, ref)) {
      is Verdict.Accepted -> unwrapped.value
      is Verdict.Rejected -> violation(unwrapped.reason, "segmento: contenitore")
    }
    ensure(content.size >= HEADER_BYTES, RejectReason.MALFORMED, "segmento: intestazione troncata")
    ensure((content[0].toInt() and 0xff) == SegmentClass.PERSONAL.code, RejectReason.WRONG_TYPE, "segmento: classe diversa da quella della testa")
    ensure(content[1].toInt() == 0 && content[2].toInt() == 0 && content[3].toInt() == 0, RejectReason.MALFORMED, "segmento: byte riservati")
    val n = ByteBuffer.wrap(content, CLASS_BYTES + RESERVED_BYTES, COUNT_BYTES).int.toLong() and 0xffffffffL
    ensure(n >= 1, RejectReason.MALFORMED, "segmento: nessuna voce")
    ensure(n <= TrustLimits.MAX_SEGMENT_ENTRIES, RejectReason.TOO_LARGE, "segmento: troppe voci")
    val count = n.toInt()
    val tableEnd = HEADER_BYTES + TABLE_ENTRY_BYTES * count
    ensure(tableEnd <= content.size, RejectReason.MALFORMED, "segmento: tabella troncata")
    var sum = 0L
    for (i in 0 until count) {
      val row = HEADER_BYTES + TABLE_ENTRY_BYTES * i
      val length = lengthAt(content, row)
      ensure(length >= 1, RejectReason.MALFORMED, "segmento: corpo vuoto")
      ensure(length <= TrustLimits.MAX_META_RECORD_BYTES, RejectReason.TOO_LARGE, "segmento: corpo oltre il tetto")
      sum += length
      if (i > 0) ensure(compareRows(content, row - TABLE_ENTRY_BYTES, row) < 0, RejectReason.MALFORMED, "segmento: chiavi non crescenti")
    }
    ensure(sum == (content.size - tableEnd).toLong(), RejectReason.MALFORMED, "segmento: corpi e tabella non coincidono")
    var offset = tableEnd
    val entries = ArrayList<PersonalEntry>(count)
    for (i in 0 until count) {
      val row = HEADER_BYTES + TABLE_ENTRY_BYTES * i
      val length = lengthAt(content, row).toInt()
      val key = content.copyOfRange(row, row + PersonalRecords.SEGMENT_KEY_BYTES)
      entries += PersonalRecords.read(content.copyOfRange(offset, offset + length), key)
      offset += length
    }
    entries
  }

  /**
   * Lo stato [state] diviso in segmenti **stabili**: le voci si raggruppano per i primi bit della loro chiave, quindi un segmento
   * dipende solo dalle voci che ci cadono. Una voce che cambia riscrive un segmento solo; uno che non cambia ha lo stesso nome e,
   * pubblicandolo, il NAS risponde "esiste gia'". Il numero di bit dipende da quante sono le voci, non da come cadono.
   */
  fun plan(state: Collection<PersonalEntry>): PersonalPublishPlan {
    val items = ArrayList<EncodedItem>(state.size)
    var skipped = 0
    for (entry in state) {
      val body = PersonalRecords.encodeOrNull(entry)
      if (body == null) skipped++ else items += EncodedItem(entry, entry.key.digest(), body)
    }
    if (items.isEmpty()) return PersonalPublishPlan(emptyList(), 0, skipped)
    items.sortWith { a, b -> IndexKeys.compare(a.key, b.key) }
    val bits = bucketBits(items.size)
    val segments = ArrayList<EncodedSegment>()
    var start = 0
    while (start < items.size) {
      val bucket = bucketOf(items[start].key, bits)
      var end = start + 1
      while (end < items.size && bucketOf(items[end].key, bits) == bucket) end++
      // Un gruppo troppo grande (chiavi scelte apposta per cadere nello stesso gruppo) si spezza: con al massimo
      // TARGET_SEGMENT_ENTRIES voci da 4 KiB un segmento resta sotto gli 8 MiB, quindi sotto il tetto del formato.
      var from = start
      while (from < end) {
        val to = minOf(end, from + PersonalLimits.TARGET_SEGMENT_ENTRIES)
        segments += build(items.subList(from, to))
        from = to
      }
      start = end
    }
    return PersonalPublishPlan(segments, items.size, skipped)
  }

  private fun bucketBits(entries: Int): Int {
    var bits = 0
    while (bits < PersonalLimits.MAX_BUCKET_BITS && entries.toLong() > (PersonalLimits.TARGET_SEGMENT_ENTRIES.toLong() / 2) shl bits) bits++
    return bits
  }

  private fun bucketOf(key: ByteArray, bits: Int): Int {
    if (bits == 0) return 0
    val top = ((key[0].toInt() and 0xff) shl 8) or (key[1].toInt() and 0xff)
    return top ushr (16 - bits)
  }

  private fun lengthAt(b: ByteArray, row: Int): Long =
    ByteBuffer.wrap(b, row + PersonalRecords.SEGMENT_KEY_BYTES, 4).int.toLong() and 0xffffffffL

  private fun compareRows(b: ByteArray, a: Int, c: Int): Int {
    for (i in 0 until PersonalRecords.SEGMENT_KEY_BYTES) {
      val d = (b[a + i].toInt() and 0xff) - (b[c + i].toInt() and 0xff)
      if (d != 0) return d
    }
    return 0
  }
}
