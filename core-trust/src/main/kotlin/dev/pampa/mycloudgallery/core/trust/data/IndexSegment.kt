package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sensitive
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.codec.JsonInt
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.codec.JsonValue
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.nas.ImageBounds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.verdictOf
import dev.pampa.mycloudgallery.core.trust.violation
import java.nio.ByteBuffer
import java.text.Normalizer
import java.util.Locale

// Il CONTENUTO dei segmenti dell'indice condiviso (docs/formato-nas.md §5.5-bis, modifiche M1 e M2; docs/fase4-progetto.md
// §2.1, D4, D5). P0 fissa qui i TIPI, perche' li usano in parallelo P3 (codec e pubblicazione), P4 (replica), P5
// (adozione dei record con lo stesso timbro) e P6 (preparazione). Il codec stretto (`IndexSegments.encode/decode`,
// `MetaRecords.verify`), le serie di teste (`HeadSeries`) e la politica dei propri file (`OwnFilesPolicy`) li scrive
// P3 in questo stesso package. Qui nessuna lettura di dati remoti: solo forme e invarianti locali.

/**
 * La classe di un segmento (M1, §5.5-bis): ogni segmento contiene voci di **una** classe, e un dispositivo scarica solo
 * le classi che gli servono (D4, D16). [wire] e' il nome nella testa firmata, [code] il byte all'inizio del contenuto
 * del segmento. Codici 9–15 e 17–31 riservati: `faces` (Fase 8) e' **rifiutato** finche' una versione del formato non lo
 * definisce (E-I4). `vec` (7) e `ocr` (8) li definisce la versione 2 (Fase 6, D6): valgono **solo** nelle teste `ai`
 * ([AI_CLASSES]), mai in una testa `index` ([INDEX_CLASSES]) o `personal`, e il loro codec e' `AiSegments`, non [IndexSegments].
 */
enum class SegmentClass(val wire: String, val code: Int) {
  /** Record dei file (JSON canonico, chiave = impronta del percorso). */
  META("meta", 1),

  /** Miniatura piccola: lato corto 200 px, WebP (livello Risparmio). */
  THUMB_S("thumbS", 2),

  /** Miniatura media: lato corto 400 px, WebP (livello Standard). */
  THUMB_M("thumbM", 3),

  /** Anteprima di analisi: lato lungo 1024 px, JPEG q90 (Indicizzatori, Fase 6; anche OCR). */
  ANALYSIS("analysis", 4),

  /** Anteprima HQ: lato lungo 2048 px, WebP (livello Alta, viewer nitido offline). */
  HQ("hq", 5),

  /** Fotogrammi chiave di un video: copertina e uno ogni 30 s, al massimo 9 immagini JPEG. */
  FRAMES("frames", 6),

  /** Fase 6 (D6): i vettori di un contenuto (corpo binario `vec`, §2.3). Solo nelle teste `ai`, versione 2 del formato. */
  VEC("vec", 7),

  /** Fase 6 (D6, D13, D43): il testo letto in un contenuto (corpo binario `ocr`, §2.3). Solo nelle teste `ai`, versione 2. */
  OCR("ocr", 8),

  /** Dati personali (4B, §2.9): solo nelle teste `personal`, solo nell'area privata dell'account. */
  PERSONAL("personal", 16),
  ;

  /** Il bit nella maschera `prep` di un record (le classi pubblicate per quella chiave di contenuto). */
  val mask: Int get() = 1 shl code

  /** Una classe dei dati AI (Fase 6): `vec` o `ocr`, solo nelle teste `ai`. */
  val isAi: Boolean get() = this == VEC || this == OCR

  /** Le voci sono immagini per chiave di contenuto (JPEG o WebP, dimensioni lette prima di decodificare). */
  val isContentBlob: Boolean get() = this != META && this != PERSONAL && !isAi

  companion object {
    /** Nomi riservati: una testa che li elenca e' rifiutata (nessuna classe "sconosciuta ma accettata"). */
    val RESERVED_WIRE: Set<String> = setOf("faces")

    /**
     * Le classi che una testa `index` puo' elencare: mai [PERSONAL] (solo nelle teste `personal`) e mai `vec` e `ocr` (solo
     * nelle teste `ai`, [AI_CLASSES]): un'app senza la Fase 6 rifiuterebbe la testa intera, miniature comprese (D6).
     */
    val INDEX_CLASSES: Set<SegmentClass> = entries.filter { it != PERSONAL && !it.isAi }.toSet()

    /** Fase 6 (D6): le sole classi di una testa `ai`. */
    val AI_CLASSES: Set<SegmentClass> = setOf(VEC, OCR)

    fun fromWire(wire: String): SegmentClass? = entries.firstOrNull { it.wire == wire }

    fun fromCode(code: Int): SegmentClass? = entries.firstOrNull { it.code == code }

    /** La maschera delle classi in [classes]. */
    fun maskOf(classes: Collection<SegmentClass>): Int = classes.fold(0) { acc, c -> acc or c.mask }

    /** Le classi in una maschera `prep`; i bit sconosciuti si ignorano (li controlla il codec). */
    fun fromMask(mask: Int): Set<SegmentClass> = entries.filterTo(LinkedHashSet()) { mask and it.mask != 0 }
  }
}

/** Un segmento elencato da una testa, con la sua classe (M1: ogni voce di `segments` e' `{class, hash, size}`). */
data class ClassedSegment(val cls: SegmentClass, val ref: SegmentRef)

/** Un punto GPS come lo scrive un record: gradi × 10^7, interi (nessun arrotondamento diverso fra dispositivi). */
@Sensitive("La posizione rivela dove si abita e dove si va.")
class GeoE7(val latE7: Int, val lonE7: Int) {
  init {
    require(latE7 in -900_000_000..900_000_000 && lonE7 in -1_800_000_000..1_800_000_000) { "Coordinate fuori intervallo." }
  }

  override fun equals(other: Any?): Boolean = other is GeoE7 && other.latE7 == latE7 && other.lonE7 == lonE7

  override fun hashCode(): Int = latE7 * 31 + lonE7

  override fun toString(): String = "GeoE7([posizione nascosta])"
}

/**
 * Un fallimento di preparazione pubblicato nel record (D14, campo `fail`): il [code] (es. `DECODER_MISSING`) e la
 * famiglia del decoder del dispositivo che ci ha provato ([decoder], es. `c2_qti`, `c2_exynos`, `sw`). Un altro
 * Preparatore con una famiglia diversa ci prova una volta; quando nessuno ci riesce il media e' "non leggibile".
 */
data class PrepFailure(val code: String, val decoder: String) {
  init {
    require(CODE.matches(code)) { "Codice di fallimento non valido." }
    require(DECODER.matches(decoder)) { "Famiglia di decoder non valida." }
  }

  private companion object {
    val CODE = Regex("^[A-Z][A-Z0-9_]{0,31}$")
    val DECODER = Regex("^[a-z0-9][a-z0-9_.-]{0,31}$")
  }
}

/**
 * Cio' che un dispositivo sa di **un file** di una share (§2.1, tabella "Record meta"): il timbro (D6), le impronte, le
 * identita' condivise (D7), i metadati gia' orientati, il segnaposto e le classi preparate.
 *
 * Fiducia (D8): valgono per **mostrare** e per coordinare il lavoro; non bastano per unire un media del telefono a un
 * file del NAS, per dire "al sicuro" ne' per nessuna azione sui file. Il record vale solo se [stamp] coincide con
 * l'ultimo elenco di quel file (D6, E-N12).
 */
data class MetaFile(
  /** `size`, `mtime`, `fid` (facoltativo). */
  val stamp: NasStamp,
  /** `qh`: l'impronta rapida `qh1`. */
  val quickHash: QuickHash,
  /** `fh`: SHA-256 dell'intero file, se chi pubblica l'ha calcolato (foto preparate: gratis, §2.4). */
  val fullHash: Sha256? = null,
  /** `media`, `version`, `parent` (facoltativo), `vkind`. */
  val mediaId: MediaId,
  val versionId: VersionId,
  val parentVersionId: VersionId? = null,
  val versionKind: VersionKind = VersionKind.ORIGINAL,
  /** `kind`, `src`, `mime`. */
  val kind: MediaKind,
  val source: MediaSource,
  val mime: String? = null,
  /** `taken`, `offset`, `conf`, `origin`. */
  val capture: CaptureTime? = null,
  /** `w`, `h` gia' orientati, `dur` in ms, `traits` (maschera di `MediaTrait`). */
  val width: Int? = null,
  val height: Int? = null,
  val durationMs: Long? = null,
  val traits: Int = 0,
  /** `geo`: sta nella stessa share del file che lo contiene gia' (C19). */
  val geo: GeoE7? = null,
  /** `th`: il segnaposto sfocato. */
  val thumbHash: ThumbHash? = null,
  /** `prep`: maschera delle classi pubblicate per la chiave di contenuto ([SegmentClass.mask]). */
  val prepared: Int = 0,
  /** `fail` (D14). */
  val failure: PrepFailure? = null,
) {
  init {
    require((width == null || width > 0) && (height == null || height > 0)) { "Dimensioni non valide." }
    require(durationMs == null || durationMs >= 0) { "Durata negativa." }
    require(traits >= 0 && prepared >= 0) { "Maschere negative." }
    require(mime == null || (mime.length <= MAX_MIME_CHARS && MIME.matches(mime))) { "Tipo MIME non valido." }
    require(prepared and SegmentClass.PERSONAL.mask == 0 && prepared and SegmentClass.META.mask == 0) {
      "La maschera prep elenca solo classi di contenuto."
    }
  }

  /** La chiave di contenuto (D4): con questa si trovano le miniature, anche dopo una rinomina dal PC. */
  val contentKey: ContentKey get() = ContentKey.of(quickHash, stamp.size)

  val preparedClasses: Set<SegmentClass> get() = SegmentClass.fromMask(prepared)

  override fun toString(): String = "MetaFile(size=${stamp.size}, kind=$kind, prep=$prepared)"

  private companion object {
    const val MAX_MIME_CHARS = 64
    val MIME = Regex("^[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*$")
  }
}

/**
 * Un record `meta` (D5): lo stato di un percorso della share **secondo un dispositivo**. [seq] cresce per
 * (dispositivo, share) e lo assegna chi pubblica (l'outbox di `core-index`, nella stessa transazione): fra piu' record
 * dello stesso dispositivo per lo stesso percorso vince il [seq] piu' alto. Il percorso e' relativo alla share della
 * testa, gia' validato come percorso di media (`SharePath.parseMediaPath`: mai dentro `.mycloudgallery`).
 */
sealed interface MetaRecord {
  val path: SharePath
  val seq: Long

  /** Il file c'e' e si sa questo di lui. */
  data class Present(override val path: SharePath, override val seq: Long, val file: MetaFile) : MetaRecord {
    init {
      require(seq in 1..TrustLimits.MAX_RECORD_SEQ) { "seq fuori intervallo." }
    }

    override fun toString(): String = "MetaRecord.Present(seq=$seq, $file)"
  }

  /** Lapide (`gone: true`): il file non c'e' piu' secondo questo dispositivo. Solo percorso e [seq]. */
  data class Gone(override val path: SharePath, override val seq: Long) : MetaRecord {
    init {
      require(seq in 1..TrustLimits.MAX_RECORD_SEQ) { "seq fuori intervallo." }
    }

    override fun toString(): String = "MetaRecord.Gone(seq=$seq)"
  }
}

/** Una voce di un segmento di contenuto: la chiave e dove stanno i suoi byte dentro [SegmentContent.Blobs.bytes]. */
data class BlobEntry(val contentKey: ContentKey, val offset: Int, val length: Int) {
  init {
    require(offset >= 0 && length > 0) { "Voce non valida." }
  }
}

/**
 * Il contenuto di un segmento gia' verificato (impronta e dimensione della testa, classe attesa, tabella coerente, tetti
 * di ogni corpo): cio' che `IndexSegments.decode` (P3) restituisce dentro un `Verdict`. Un errore scarta il segmento
 * intero (E-I5) e non ferma gli altri.
 */
sealed interface SegmentContent {
  val cls: SegmentClass

  data class Meta(val records: List<MetaRecord>) : SegmentContent {
    override val cls: SegmentClass get() = SegmentClass.META
  }

  /**
   * Immagini (o fotogrammi) per chiave di contenuto. [bytes] e' il segmento cosi' com'e' (la replica lo tiene come pacco
   * immutabile e lo legge mappato, D15); le [entries] puntano dentro, nell'ordine crescente delle chiavi.
   */
  class Blobs(override val cls: SegmentClass, val entries: List<BlobEntry>, val bytes: ByteArray) : SegmentContent {
    init {
      require(cls.isContentBlob) { "Classe senza blob." }
      require(entries.all { it.offset.toLong() + it.length <= bytes.size }) { "Voce oltre la fine del segmento." }
    }

    override fun toString(): String = "SegmentContent.Blobs($cls, ${entries.size} voci)"
  }
}

// =====================================================================================================================
// Codec stretto dei segmenti (P3, docs/formato-nas.md §5.5-bis). Input OSTILE per definizione: chiunque scriva via SMB
// (e un dispositivo approvato ma compromesso) puo' mettere qualunque byte nella share. Regole:
// - prima dimensione e impronta della testa, poi classe, poi tabella, poi corpi, ognuno con i suoi tetti;
// - nessuna allocazione da lunghezze dichiarate: N e le lunghezze si controllano contro i byte che ci sono davvero;
// - un errore qualunque (anche un'eccezione imprevista) scarta il segmento intero ([verdictOf]), mai un crash;
// - chi scrive usa gli stessi controlli di chi legge: un segmento che questo codice produce si rilegge sempre.
// =====================================================================================================================

/** Le chiavi delle voci (32 byte, §5.5-bis). */
object IndexKeys {
  private val PATH_DOMAIN: ByteArray = "mcg-path".toByteArray(Charsets.US_ASCII) + 0

  /**
   * La forma del percorso che decide la chiave `meta`: prima NFC, poi minuscolo con `Locale.ROOT`. Due nomi diversi solo
   * per maiuscole sono lo stesso posto per Samba (E-N7), quindi la stessa chiave: in un segmento ce n'e' uno solo.
   */
  fun foldPath(path: String): String = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)

  /** `SHA-256("mcg-path" ‖ 0x00 ‖ percorso piegato)`. */
  fun meta(path: SharePath): ByteArray = Hashes.sha256(PATH_DOMAIN, foldPath(path.value).toByteArray(Charsets.UTF_8))

  /** La chiave `meta` in esadecimale (per le mappe di chi pubblica e di chi legge). */
  fun metaHex(path: SharePath): String = Hex.of(meta(path))

  /** La chiave di contenuto come 32 byte (`thumbS`, `thumbM`, `analysis`, `hq`, `frames`). */
  fun content(key: ContentKey): ByteArray = key.bytes()

  /** Confronto senza segno, byte per byte: l'ordine della tabella. */
  fun compare(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
      val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
      if (d != 0) return d
    }
    return a.size - b.size
  }
}

/** Esadecimale minuscolo (gli hash del modello sono in questa forma). */
internal object Hex {
  private const val DIGITS = "0123456789abcdef"

  fun of(bytes: ByteArray, from: Int = 0, length: Int = bytes.size - from): String {
    val out = CharArray(length * 2)
    for (i in 0 until length) {
      val v = bytes[from + i].toInt() and 0xff
      out[i * 2] = DIGITS[v ushr 4]
      out[i * 2 + 1] = DIGITS[v and 0x0f]
    }
    return String(out)
  }

  fun bytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/**
 * I record `meta` in JSON canonico (§5.5-bis, tabella "Il record meta"). I campi facoltativi si **omettono**; anche i
 * valori di default (`vkind` = `original`, `traits` = 0, `prep` = 0) si omettono, e scriverli e' `NOT_CANONICAL`: ogni
 * record ha una sola scrittura.
 */
object MetaRecords {

  /** I campi di una lapide: nient'altro. */
  private val TOMBSTONE_FIELDS = setOf("path", "seq", "gone")

  /** Le quattro parti della data di scatto: tutte o nessuna. */
  private val CAPTURE_FIELDS = listOf("taken", "offset", "conf", "origin")

  /** Le classi che la maschera `prep` puo' nominare (mai `meta` ne' `personal`). */
  val PREP_MASK_ALLOWED: Int = SegmentClass.maskOf(SegmentClass.entries.filter { it.isContentBlob })

  /** Il JSON canonico di [record]. Lancia [IllegalArgumentException] se supera [TrustLimits.MAX_META_RECORD_BYTES]. */
  fun encode(record: MetaRecord): ByteArray {
    val bytes = CanonicalJson.encode(toJson(record))
    require(bytes.size <= TrustLimits.MAX_META_RECORD_BYTES) { "Record oltre il tetto." }
    return bytes
  }

  /**
   * Il JSON di [record] solo se chi legge lo rileggerebbe identico (tetto, forma, campi): chi pubblica scarta il resto
   * invece di produrre un segmento che gli altri rifiuterebbero intero.
   */
  fun encodeOrNull(record: MetaRecord): ByteArray? {
    val bytes = try {
      encode(record)
    } catch (e: IllegalArgumentException) {
      return null
    }
    val back = (verify(bytes) as? Verdict.Accepted)?.value ?: return null
    return bytes.takeIf { back == record }
  }

  /** Legge un record letto dal NAS: forma canonica, campi del formato, percorso di media (§8.1). */
  fun verify(json: ByteArray): Verdict<MetaRecord> = verdictOf { read(json) }

  internal fun read(json: ByteArray): MetaRecord {
    ensure(json.size <= TrustLimits.MAX_META_RECORD_BYTES, RejectReason.TOO_LARGE, "record: oltre il tetto")
    val obj = CanonicalJson.parseCanonical(json) as? JsonObject ?: violation(RejectReason.MALFORMED, "record: oggetto atteso")
    val m = obj.members
    if ("gone" in m) {
      ensure(m.keys == TOMBSTONE_FIELDS, RejectReason.INVALID_FIELD, "lapide: campi in piu'")
    }
    return Fields.read(obj) { f ->
      val path = SharePath.parseMediaPath(f.string("path", TrustLimits.MAX_JSON_STRING_CHARS))
      val seq = f.long("seq", 1..TrustLimits.MAX_RECORD_SEQ)
      if ("gone" in m) {
        ensure(f.bool("gone"), RejectReason.NOT_CANONICAL, "lapide: gone si scrive solo true")
        MetaRecord.Gone(path, seq)
      } else {
        MetaRecord.Present(path, seq, readFile(f, m))
      }
    }
  }

  private fun readFile(f: Fields, m: Map<String, JsonValue>): MetaFile {
    val stamp = NasStamp(
      size = f.long("size", 0..Long.MAX_VALUE),
      modifiedAtMillis = f.long("mtime", Long.MIN_VALUE..Long.MAX_VALUE),
      fileId = if ("fid" in m) f.long("fid", Long.MIN_VALUE..Long.MAX_VALUE) else null,
    )
    val quickHash = QuickHash(Hex.of(Base32.decodeExact(f.string("qh", HASH_CHARS), 32)))
    val fullHash = if ("fh" in m) Sha256(Hex.of(Base32.decodeExact(f.string("fh", HASH_CHARS), 32))) else null
    val mediaId = MediaId(uuidOf(Base32.decodeExact(f.string("media", ID_CHARS), 16)))
    val versionId = VersionId(uuidOf(Base32.decodeExact(f.string("version", ID_CHARS), 16)))
    val parent = if ("parent" in m) VersionId(uuidOf(Base32.decodeExact(f.string("parent", ID_CHARS), 16))) else null
    val versionKind = if ("vkind" in m) {
      val kind = code<VersionKind>(f.string("vkind", TrustLimits.MAX_ENUM_CHARS))
      ensure(kind != VersionKind.ORIGINAL, RejectReason.NOT_CANONICAL, "record: vkind di default scritto")
      kind
    } else {
      VersionKind.ORIGINAL
    }
    val kind = code<MediaKind>(f.string("kind", MAX_CODE_CHARS))
    val source = code<MediaSource>(f.string("src", MAX_CODE_CHARS))
    val mime = f.optString("mime", MAX_MIME_CHARS)
    val captureParts = CAPTURE_FIELDS.count { it in m }
    ensure(captureParts == 0 || captureParts == CAPTURE_FIELDS.size, RejectReason.INVALID_FIELD, "record: data di scatto parziale")
    val capture = if (captureParts == 0) {
      null
    } else {
      val taken = f.long("taken", Long.MIN_VALUE..Long.MAX_VALUE)
      val offset = f.long("offset", -MAX_OFFSET_MINUTES..MAX_OFFSET_MINUTES).toInt()
      val conf = code<CaptureTimeConfidence>(f.string("conf", MAX_CODE_CHARS))
      val originCode = f.string("origin", MAX_CODE_CHARS)
      // Il tipo dell'origine si ricava da CaptureTime (il nome non compare in questo modulo: TrustFormatTest vieta
      // la parola dell'orologio monotono di Kotlin, che ne e' un pezzo).
      fieldOf { CaptureTime(taken, offset, conf, enumByCodeOrNull(originCode) ?: violation(RejectReason.INVALID_FIELD, "record: origine")) }
    }
    val width = if ("w" in m) f.long("w", 1L..Int.MAX_VALUE).toInt() else null
    val height = if ("h" in m) f.long("h", 1L..Int.MAX_VALUE).toInt() else null
    val duration = if ("dur" in m) f.long("dur", 0..Long.MAX_VALUE) else null
    val traits = if ("traits" in m) f.long("traits", 1L..Int.MAX_VALUE).toInt() else 0
    val geo = f.optArray("geo", 2)?.let { items ->
      ensure(items.size == 2, RejectReason.INVALID_FIELD, "record: geo sono due interi")
      val lat = (items[0] as? JsonInt)?.value ?: violation(RejectReason.MALFORMED, "record: geo")
      val lon = (items[1] as? JsonInt)?.value ?: violation(RejectReason.MALFORMED, "record: geo")
      ensure(lat in Int.MIN_VALUE..Int.MAX_VALUE && lon in Int.MIN_VALUE..Int.MAX_VALUE, RejectReason.INVALID_FIELD, "record: geo")
      fieldOf { GeoE7(lat.toInt(), lon.toInt()) }
    }
    val thumbHash = if ("th" in m) {
      val raw = Base32.decode(f.string("th", TrustLimits.MAX_THUMBHASH_BASE32_CHARS))
      ensure(raw.size in 1..MAX_THUMBHASH_BYTES, RejectReason.INVALID_FIELD, "record: segnaposto")
      fieldOf { ThumbHash.of(raw) }
    } else {
      null
    }
    val prepared = if ("prep" in m) f.long("prep", 1L..Int.MAX_VALUE).toInt() else 0
    ensure(prepared and PREP_MASK_ALLOWED.inv() == 0, RejectReason.INVALID_FIELD, "record: classe sconosciuta in prep")
    val failure = if ("fail" in m) {
      f.obj("fail") { g -> fieldOf { PrepFailure(g.string("code", MAX_CODE_CHARS), g.string("dec", MAX_CODE_CHARS)) } }
    } else {
      null
    }
    return fieldOf {
      MetaFile(
        stamp = stamp,
        quickHash = quickHash,
        fullHash = fullHash,
        mediaId = mediaId,
        versionId = versionId,
        parentVersionId = parent,
        versionKind = versionKind,
        kind = kind,
        source = source,
        mime = mime,
        capture = capture,
        width = width,
        height = height,
        durationMs = duration,
        traits = traits,
        geo = geo,
        thumbHash = thumbHash,
        prepared = prepared,
        failure = failure,
      )
    }
  }

  private fun toJson(record: MetaRecord): JsonObject = when (record) {
    is MetaRecord.Gone -> jsonObject("path" to record.path.value.json(), "seq" to record.seq.json(), "gone" to true.json())
    is MetaRecord.Present -> {
      val f = record.file
      val capture = f.capture
      jsonObject(
        "path" to record.path.value.json(),
        "seq" to record.seq.json(),
        "size" to f.stamp.size.json(),
        "mtime" to f.stamp.modifiedAtMillis.json(),
        "fid" to f.stamp.fileId?.json(),
        "qh" to Base32.encode(Hex.bytes(f.quickHash.hex)).json(),
        "fh" to f.fullHash?.let { Base32.encode(Hex.bytes(it.hex)).json() },
        "media" to idText(f.mediaId.value).json(),
        "version" to idText(f.versionId.value).json(),
        "parent" to f.parentVersionId?.let { idText(it.value).json() },
        "vkind" to f.versionKind.takeIf { it != VersionKind.ORIGINAL }?.code?.json(),
        "kind" to f.kind.code.json(),
        "src" to f.source.code.json(),
        "mime" to f.mime?.json(),
        "taken" to capture?.utcMillis?.json(),
        "offset" to capture?.offsetMinutes?.json(),
        "conf" to capture?.confidence?.code?.json(),
        "origin" to capture?.source?.code?.json(),
        "w" to f.width?.json(),
        "h" to f.height?.json(),
        "dur" to f.durationMs?.json(),
        "traits" to f.traits.takeIf { it != 0 }?.json(),
        "geo" to f.geo?.let { listOf(it.latE7.json(), it.lonE7.json()).json() },
        "th" to f.thumbHash?.let { Base32.encode(it.bytes()).json() },
        "prep" to f.prepared.takeIf { it != 0 }?.json(),
        "fail" to f.failure?.let { jsonObject("code" to it.code.json(), "dec" to it.decoder.json()) },
      )
    }
  }

  private inline fun <reified E> code(text: String): E where E : Enum<E>, E : StableCode =
    enumByCodeOrNull<E>(text) ?: violation(RejectReason.INVALID_FIELD, "record: codice sconosciuto")

  /** I `require` dei tipi del modello diventano un rifiuto del campo, mai un'eccezione verso fuori. */
  private inline fun <T> fieldOf(block: () -> T): T =
    try {
      block()
    } catch (e: IllegalArgumentException) {
      violation(RejectReason.INVALID_FIELD, "record: valore non ammesso")
    }

  private fun uuidOf(bytes: ByteArray): String {
    val h = Hex.of(bytes)
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}"
  }

  private fun idText(uuid: String): String = Base32.encode(Hex.bytes(uuid.replace("-", "")))

  private const val HASH_CHARS = 52
  private const val ID_CHARS = 26
  private const val MAX_CODE_CHARS = 32
  private const val MAX_MIME_CHARS = 64
  private const val MAX_OFFSET_MINUTES = 18L * 60
  private const val MAX_THUMBHASH_BYTES = 36
}

/**
 * Le immagini dentro i segmenti (§5.5-bis): **solo** JPEG (`FF D8 FF`) o WebP (`RIFF…WEBP`), dimensioni lette
 * dall'intestazione e controllate con [ImageBounds] **prima** di qualunque decodifica. Un altro formato, un'intestazione
 * troncata o dimensioni oltre il tetto rifiutano il segmento.
 */
object IndexImages {
  enum class Format { JPEG, WEBP }

  class Info(val format: Format, val width: Int, val height: Int) {
    override fun toString(): String = "IndexImages.Info($format, ${width}x$height)"
  }

  /** Per chi decodifica (P7, P8) o pubblica (P3): l'immagine e le sue dimensioni, o il motivo del rifiuto. */
  fun inspect(bytes: ByteArray, maxBytes: Int = TrustLimits.MAX_BLOB_BYTES): Verdict<Info> = verdictOf {
    check(bytes, 0, bytes.size, maxBytes, ANY)
  }

  internal val ANY: Set<Format> = setOf(Format.JPEG, Format.WEBP)
  internal val JPEG_ONLY: Set<Format> = setOf(Format.JPEG)

  internal fun check(bytes: ByteArray, offset: Int, length: Int, maxBytes: Int, allowed: Set<Format>): Info {
    ensure(length >= 1, RejectReason.MALFORMED, "immagine: vuota")
    ensure(length <= maxBytes, RejectReason.TOO_LARGE, "immagine: oltre il tetto")
    ensure(offset >= 0 && offset.toLong() + length <= bytes.size, RejectReason.MALFORMED, "immagine: fuori dal segmento")
    val info = when {
      isJpeg(bytes, offset, length) -> jpeg(bytes, offset, length)
      isWebp(bytes, offset, length) -> webp(bytes, offset, length)
      else -> violation(RejectReason.INVALID_FIELD, "immagine: ne' JPEG ne' WebP")
    }
    ensure(info.format in allowed, RejectReason.INVALID_FIELD, "immagine: formato non ammesso qui")
    ensure(ImageBounds.isDecodable(info.width, info.height), RejectReason.TOO_LARGE, "immagine: dimensioni oltre il tetto")
    return info
  }

  private fun isJpeg(b: ByteArray, off: Int, len: Int): Boolean =
    len >= 3 && u8(b, off) == 0xFF && u8(b, off + 1) == 0xD8 && u8(b, off + 2) == 0xFF

  private fun isWebp(b: ByteArray, off: Int, len: Int): Boolean =
    len >= 12 && ascii(b, off, "RIFF") && ascii(b, off + 8, "WEBP")

  /** Segue i marcatori fino al primo SOF (saltando APPn, DQT, DHT...): ogni passo avanza, il ciclo e' limitato. */
  private fun jpeg(b: ByteArray, off: Int, len: Int): Info {
    val end = off + len
    var p = off + 2
    while (p < end) {
      ensure(u8(b, p) == 0xFF, RejectReason.MALFORMED, "jpeg: marcatore atteso")
      while (p < end && u8(b, p) == 0xFF) p++
      ensure(p < end, RejectReason.MALFORMED, "jpeg: troncato")
      val marker = u8(b, p)
      p++
      when (marker) {
        0x01, in 0xD0..0xD7 -> Unit
        0xD8, 0xD9, 0xDA -> violation(RejectReason.MALFORMED, "jpeg: nessuna intestazione di frame")
        else -> {
          ensure(p + 2 <= end, RejectReason.MALFORMED, "jpeg: troncato")
          val segLen = u16be(b, p)
          ensure(segLen >= 2 && p + segLen <= end, RejectReason.MALFORMED, "jpeg: segmento troncato")
          if (marker in SOF_MARKERS) {
            ensure(segLen >= 8, RejectReason.MALFORMED, "jpeg: intestazione di frame corta")
            val height = u16be(b, p + 3)
            val width = u16be(b, p + 5)
            ensure(height > 0 && width > 0, RejectReason.INVALID_FIELD, "jpeg: dimensioni nulle")
            return Info(Format.JPEG, width, height)
          }
          p += segLen
        }
      }
    }
    violation(RejectReason.MALFORMED, "jpeg: nessuna intestazione di frame")
  }

  private val SOF_MARKERS = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)

  /**
   * WebP: la dimensione RIFF deve coprire esattamente il corpo (niente byte in piu'); semplice (`VP8 `, `VP8L`) o esteso
   * (`VP8X`, non animato, con l'immagine della stessa dimensione della tela).
   */
  private fun webp(b: ByteArray, off: Int, len: Int): Info {
    ensure(len >= 20, RejectReason.MALFORMED, "webp: troncato")
    val riff = u32le(b, off + 4)
    ensure(riff + 8 == len.toLong(), RejectReason.MALFORMED, "webp: dimensione RIFF diversa dal corpo")
    val end = off + len
    val first = chunkAt(b, off + 12, end)
    return when (first.fourcc) {
      "VP8 " -> vp8(b, first)
      "VP8L" -> vp8l(b, first)
      "VP8X" -> {
        ensure(first.size >= 10, RejectReason.MALFORMED, "webp: VP8X corto")
        val flags = u8(b, first.data)
        ensure(flags and ANIMATION_FLAG == 0, RejectReason.INVALID_FIELD, "webp: animata")
        val canvasW = u24le(b, first.data + 4) + 1
        val canvasH = u24le(b, first.data + 7) + 1
        ensure(ImageBounds.isDecodable(canvasW, canvasH), RejectReason.TOO_LARGE, "webp: tela oltre il tetto")
        var next = first.next
        var image: Info? = null
        while (next < end && image == null) {
          val chunk = chunkAt(b, next, end)
          when (chunk.fourcc) {
            "VP8 " -> image = vp8(b, chunk)
            "VP8L" -> image = vp8l(b, chunk)
            "ANIM", "ANMF" -> violation(RejectReason.INVALID_FIELD, "webp: animata")
            else -> Unit // ICCP, ALPH, EXIF, XMP e simili: si saltano
          }
          next = chunk.next
        }
        val found = image ?: violation(RejectReason.MALFORMED, "webp: nessuna immagine")
        ensure(found.width == canvasW && found.height == canvasH, RejectReason.INVALID_FIELD, "webp: immagine diversa dalla tela")
        found
      }
      else -> violation(RejectReason.INVALID_FIELD, "webp: variante sconosciuta")
    }
  }

  private class Chunk(val fourcc: String, val data: Int, val size: Int, val next: Int)

  private fun chunkAt(b: ByteArray, at: Int, end: Int): Chunk {
    ensure(at.toLong() + 8 <= end, RejectReason.MALFORMED, "webp: blocco troncato")
    val fourcc = String(CharArray(4) { (b[at + it].toInt() and 0xff).toChar() })
    val size = u32le(b, at + 4)
    val data = at + 8
    ensure(data + size <= end, RejectReason.MALFORMED, "webp: blocco oltre la fine")
    val padded = data + size + (size and 1L)
    ensure(padded <= end, RejectReason.MALFORMED, "webp: riempimento oltre la fine")
    return Chunk(fourcc, data, size.toInt(), padded.toInt())
  }

  private fun vp8(b: ByteArray, c: Chunk): Info {
    ensure(c.size >= 10, RejectReason.MALFORMED, "webp: VP8 corto")
    ensure(
      u8(b, c.data + 3) == 0x9D && u8(b, c.data + 4) == 0x01 && u8(b, c.data + 5) == 0x2A,
      RejectReason.MALFORMED,
      "webp: VP8 senza codice d'inizio",
    )
    val width = u16le(b, c.data + 6) and 0x3FFF
    val height = u16le(b, c.data + 8) and 0x3FFF
    ensure(width > 0 && height > 0, RejectReason.INVALID_FIELD, "webp: dimensioni nulle")
    return Info(Format.WEBP, width, height)
  }

  private fun vp8l(b: ByteArray, c: Chunk): Info {
    ensure(c.size >= 5, RejectReason.MALFORMED, "webp: VP8L corto")
    ensure(u8(b, c.data) == 0x2F, RejectReason.MALFORMED, "webp: VP8L senza firma")
    val bits = u32le(b, c.data + 1)
    val width = (bits and 0x3FFF).toInt() + 1
    val height = ((bits ushr 14) and 0x3FFF).toInt() + 1
    return Info(Format.WEBP, width, height)
  }

  private const val ANIMATION_FLAG = 0x02

  private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xff

  private fun u16be(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)

  private fun u16le(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8)

  private fun u24le(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8) or (u8(b, at + 2) shl 16)

  private fun u32le(b: ByteArray, at: Int): Long = u24le(b, at).toLong() or (u8(b, at + 3).toLong() shl 24)

  private fun ascii(b: ByteArray, at: Int, text: String): Boolean = text.indices.all { b[at + it].toInt() == text[it].code }
}

/**
 * Il corpo di una voce `frames` (§5.5-bis): conteggio (1 byte, da 1 a [TrustLimits.MAX_FRAMES_PER_MEDIA]) e, per ogni
 * fotogramma, istante nel video in ms (4 byte, non negativo) + lunghezza (4 byte) + JPEG; niente byte in piu'.
 */
object FramesBody {
  /** Un fotogramma dentro i byte di un segmento: [offset] e [length] puntano al JPEG. */
  data class Frame(val atMs: Int, val offset: Int, val length: Int)

  fun encode(frames: List<Pair<Int, ByteArray>>): ByteArray {
    require(frames.size in 1..TrustLimits.MAX_FRAMES_PER_MEDIA) { "Da 1 a ${TrustLimits.MAX_FRAMES_PER_MEDIA} fotogrammi." }
    val size = 1 + frames.sumOf { 8L + it.second.size }
    require(size <= TrustLimits.MAX_FRAMES_BODY_BYTES) { "Fotogrammi oltre il tetto." }
    val out = ByteBuffer.allocate(size.toInt())
    out.put(frames.size.toByte())
    for ((at, jpeg) in frames) {
      require(at >= 0) { "Istante negativo." }
      out.putInt(at).putInt(jpeg.size).put(jpeg)
    }
    return out.array()
  }

  /** I fotogrammi della voce [entry] di un segmento gia' verificato. */
  fun read(bytes: ByteArray, entry: BlobEntry): Verdict<List<Frame>> = verdictOf { parse(bytes, entry.offset, entry.length) }

  internal fun parse(b: ByteArray, off: Int, len: Int): List<Frame> {
    ensure(len >= 1 && off >= 0 && off.toLong() + len <= b.size, RejectReason.MALFORMED, "fotogrammi: fuori dal segmento")
    ensure(len <= TrustLimits.MAX_FRAMES_BODY_BYTES, RejectReason.TOO_LARGE, "fotogrammi: oltre il tetto")
    val count = b[off].toInt() and 0xff
    ensure(count >= 1, RejectReason.INVALID_FIELD, "fotogrammi: nessuno")
    ensure(count <= TrustLimits.MAX_FRAMES_PER_MEDIA, RejectReason.TOO_LARGE, "fotogrammi: troppi")
    val end = off.toLong() + len
    var p = off + 1
    val frames = ArrayList<Frame>(count)
    repeat(count) {
      ensure(p.toLong() + 8 <= end, RejectReason.MALFORMED, "fotogrammi: troncati")
      val at = ByteBuffer.wrap(b, p, 4).int
      val length = ByteBuffer.wrap(b, p + 4, 4).int.toLong() and 0xffffffffL
      ensure(at >= 0, RejectReason.INVALID_FIELD, "fotogrammi: istante negativo")
      ensure(length <= TrustLimits.MAX_BLOB_BYTES, RejectReason.TOO_LARGE, "fotogrammi: immagine oltre il tetto")
      ensure(p + 8 + length <= end, RejectReason.MALFORMED, "fotogrammi: immagine oltre la fine")
      IndexImages.check(b, p + 8, length.toInt(), TrustLimits.MAX_BLOB_BYTES, IndexImages.JPEG_ONLY)
      frames += Frame(at, p + 8, length.toInt())
      p += 8 + length.toInt()
    }
    ensure(p.toLong() == end, RejectReason.MALFORMED, "fotogrammi: byte in piu'")
    return frames
  }
}

/** Una voce da scrivere in un segmento: chiave di 32 byte e corpo. */
class SegmentEntry(key: ByteArray, val body: ByteArray) {
  val key: ByteArray = key.copyOf()

  init {
    require(key.size == KEY_BYTES) { "Una chiave sono 32 byte." }
    require(body.isNotEmpty()) { "Corpo vuoto." }
  }

  override fun toString(): String = "SegmentEntry(${body.size} B)"

  companion object {
    const val KEY_BYTES: Int = 32
  }
}

/** Un segmento pronto da pubblicare: i byte del file (`MCGB` + versione + contenuto) e il riferimento per la testa. */
class EncodedSegment(val cls: SegmentClass, val bytes: ByteArray, val ref: SegmentRef, val entries: Int) {
  val classed: ClassedSegment get() = ClassedSegment(cls, ref)

  override fun toString(): String = "EncodedSegment($cls, $entries voci, ${bytes.size} B)"
}

/**
 * Il codec dei segmenti (`IndexSegments.encode/decode`, §5.5-bis):
 *
 * ```
 * "MCGB" | versione | classe (1) | 0 0 0 | N (4, BE) | N × (chiave 32 + lunghezza 4, BE) | corpi
 * ```
 *
 * Gli stessi byte di `Segments.wrap(contenuto)`: un segmento scritto qui passa anche da `Segments.unwrap`.
 */
object IndexSegments {
  private val MAGIC = "MCGB".toByteArray(Charsets.US_ASCII)

  /** `MCGB` + versione: il contenitore di `Segments.wrap`. */
  const val CONTAINER_BYTES: Int = 5

  /** Classe, tre byte riservati e `N`. */
  const val CONTENT_HEADER_BYTES: Int = 8

  /** Una riga della tabella: chiave + lunghezza. */
  const val TABLE_ENTRY_BYTES: Int = SegmentEntry.KEY_BYTES + 4

  /** La dimensione del file di un segmento con [entries] voci e [bodyBytes] byte di corpi. */
  fun sizeOf(entries: Int, bodyBytes: Long): Long =
    CONTAINER_BYTES.toLong() + CONTENT_HEADER_BYTES + TABLE_ENTRY_BYTES.toLong() * entries + bodyBytes

  /** Il tetto del corpo di una voce di [cls]. */
  fun maxBodyBytes(cls: SegmentClass): Int = when (cls) {
    SegmentClass.META, SegmentClass.PERSONAL -> TrustLimits.MAX_META_RECORD_BYTES
    SegmentClass.FRAMES -> TrustLimits.MAX_FRAMES_BODY_BYTES
    SegmentClass.VEC -> TrustLimits.MAX_VEC_BODY_BYTES
    SegmentClass.OCR -> TrustLimits.MAX_OCR_BODY_BYTES
    else -> TrustLimits.MAX_BLOB_BYTES
  }

  /**
   * Vero se [body] puo' stare in un segmento di [cls] (gli stessi controlli di chi legge). Chi pubblica scarta il resto
   * invece di produrre un segmento che gli altri rifiuterebbero intero.
   */
  fun admits(cls: SegmentClass, body: ByteArray): Boolean =
    cls.isContentBlob && body.isNotEmpty() && body.size <= maxBodyBytes(cls) &&
      verdictOf { checkBody(cls, null, body, 0, body.size) } is Verdict.Accepted

  /**
   * Scrive un segmento di [cls] con [entries] (in qualunque ordine: si ordinano per chiave). Ogni corpo passa dagli
   * stessi controlli di chi legge; un corpo non ammesso, chiavi ripetute o tetti superati sono errori di chi chiama
   * ([IllegalArgumentException]). La classe `personal` arriva con la 4B (§2.9): qui non si scrive.
   */
  fun encode(cls: SegmentClass, entries: List<SegmentEntry>): EncodedSegment {
    require(cls != SegmentClass.PERSONAL) { "La classe personal non e' ancora definita (4B)." }
    require(!cls.isAi) { "I segmenti vec e ocr li scrive AiSegments (versione 2)." }
    require(entries.size in 1..TrustLimits.MAX_SEGMENT_ENTRIES) { "Da 1 a ${TrustLimits.MAX_SEGMENT_ENTRIES} voci." }
    val sorted = entries.sortedWith { a, b -> IndexKeys.compare(a.key, b.key) }
    for (i in 1 until sorted.size) require(IndexKeys.compare(sorted[i - 1].key, sorted[i].key) < 0) { "Chiavi ripetute." }
    val bodyBytes = sorted.sumOf { it.body.size.toLong() }
    val total = sizeOf(sorted.size, bodyBytes)
    require(total <= TrustLimits.MAX_SEGMENT_BYTES) { "Segmento oltre il tetto." }
    for (entry in sorted) {
      require(entry.body.size <= maxBodyBytes(cls)) { "Corpo oltre il tetto." }
      try {
        checkBody(cls, entry.key, entry.body, 0, entry.body.size)
      } catch (v: TrustViolation) {
        throw IllegalArgumentException("Corpo non ammesso dal formato (${v.reason}).")
      }
    }
    val out = ByteBuffer.allocate(total.toInt())
    out.put(MAGIC).put(TrustFormat.CURRENT_VERSION.toByte())
    out.put(cls.code.toByte()).put(0).put(0).put(0).putInt(sorted.size)
    for (entry in sorted) out.put(entry.key).putInt(entry.body.size)
    for (entry in sorted) out.put(entry.body)
    val bytes = out.array()
    return EncodedSegment(cls, bytes, SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong()), sorted.size)
  }

  /** Un segmento `meta`: un record per chiave (lo stesso percorso due volte e' un errore di chi chiama). */
  fun meta(records: List<MetaRecord>): EncodedSegment =
    encode(SegmentClass.META, records.map { SegmentEntry(IndexKeys.meta(it.path), MetaRecords.encode(it)) })

  /** Un segmento d'immagini (o di fotogrammi, con il corpo di [FramesBody.encode]) per chiave di contenuto. */
  fun blobs(cls: SegmentClass, items: List<Pair<ContentKey, ByteArray>>): EncodedSegment {
    require(cls.isContentBlob) { "Classe senza blob." }
    return encode(cls, items.map { (key, body) -> SegmentEntry(IndexKeys.content(key), body) })
  }

  /**
   * Legge un segmento elencato da una testa ACCETTATA come [ref] con la classe [expected]: dimensione e impronta →
   * contenitore e versione (E-I3) → classe (`WRONG_TYPE`) → `N` entro il tetto → tabella coerente (chiavi strettamente
   * crescenti, somma delle lunghezze = resto del file) → ogni corpo con i suoi tetti. Si lavora su una copia privata
   * (chi chiama non puo' cambiare i byte fra verifica e lettura); i blob puntano dentro quella copia.
   */
  fun decode(bytes: ByteArray, ref: SegmentRef, expected: SegmentClass): Verdict<SegmentContent> = verdictOf {
    ensure(bytes.size.toLong() == ref.size, RejectReason.HASH_MISMATCH, "segmento: dimensione diversa")
    val own = bytes.copyOf()
    ensure(Hashes.sha256Name(own) == ref.hash, RejectReason.HASH_MISMATCH, "segmento: impronta diversa")
    ensure(own.size > MAGIC.size && MAGIC.indices.all { own[it] == MAGIC[it] }, RejectReason.MALFORMED, "segmento: contenitore")
    val version = own[MAGIC.size].toInt() and 0xff
    if (version > TrustFormat.CURRENT_VERSION) violation(RejectReason.NEWER_FORMAT, "segmento: formato piu' nuovo")
    ensure(TrustFormat.isReadable(version), RejectReason.MALFORMED, "segmento: versione")
    val base = CONTAINER_BYTES
    ensure(own.size >= base + CONTENT_HEADER_BYTES, RejectReason.MALFORMED, "segmento: intestazione troncata")
    val code = own[base].toInt() and 0xff
    ensure(code == expected.code, RejectReason.WRONG_TYPE, "segmento: classe diversa da quella della testa")
    ensure(expected != SegmentClass.PERSONAL, RejectReason.INVALID_FIELD, "segmento: classe non ancora definita in questa build")
    // Fase 6: `vec` e `ocr` si leggono solo con AiSegments (versione 2, corpi binari); qui sarebbero un segmento `index` falso.
    ensure(!expected.isAi, RejectReason.INVALID_FIELD, "segmento: classe dei dati AI fuori dalla testa ai")
    ensure(
      own[base + 1].toInt() == 0 && own[base + 2].toInt() == 0 && own[base + 3].toInt() == 0,
      RejectReason.MALFORMED,
      "segmento: byte riservati",
    )
    val n = ByteBuffer.wrap(own, base + 4, 4).int.toLong() and 0xffffffffL
    ensure(n >= 1, RejectReason.MALFORMED, "segmento: nessuna voce")
    ensure(n <= TrustLimits.MAX_SEGMENT_ENTRIES, RejectReason.TOO_LARGE, "segmento: troppe voci")
    val count = n.toInt()
    val tableStart = base + CONTENT_HEADER_BYTES
    val tableEnd = tableStart + TABLE_ENTRY_BYTES * count
    ensure(tableEnd <= own.size, RejectReason.MALFORMED, "segmento: tabella troncata")
    val maxBody = maxBodyBytes(expected)
    var sum = 0L
    for (i in 0 until count) {
      val row = tableStart + TABLE_ENTRY_BYTES * i
      val length = lengthAt(own, row)
      ensure(length >= 1, RejectReason.MALFORMED, "segmento: corpo vuoto")
      ensure(length <= maxBody, RejectReason.TOO_LARGE, "segmento: corpo oltre il tetto")
      sum += length
      if (i > 0) ensure(compareRows(own, row - TABLE_ENTRY_BYTES, row) < 0, RejectReason.MALFORMED, "segmento: chiavi non crescenti")
    }
    ensure(sum == (own.size - tableEnd).toLong(), RejectReason.MALFORMED, "segmento: corpi e tabella non coincidono")
    var offset = tableEnd
    if (expected == SegmentClass.META) {
      val records = ArrayList<MetaRecord>(count)
      var tombstones = 0
      for (i in 0 until count) {
        val row = tableStart + TABLE_ENTRY_BYTES * i
        val length = lengthAt(own, row).toInt()
        val record = MetaRecords.read(own.copyOfRange(offset, offset + length))
        ensure(sameKey(own, row, IndexKeys.meta(record.path)), RejectReason.INVALID_FIELD, "segmento: chiave diversa dal percorso")
        if (record is MetaRecord.Gone) tombstones++
        records += record
        offset += length
      }
      ensure(tombstones <= TrustLimits.MAX_SEGMENT_TOMBSTONES, RejectReason.TOO_LARGE, "segmento: troppe lapidi")
      SegmentContent.Meta(records)
    } else {
      val entries = ArrayList<BlobEntry>(count)
      for (i in 0 until count) {
        val row = tableStart + TABLE_ENTRY_BYTES * i
        val length = lengthAt(own, row).toInt()
        checkBody(expected, null, own, offset, length)
        entries += BlobEntry(ContentKey(Hex.of(own, row, SegmentEntry.KEY_BYTES)), offset, length)
        offset += length
      }
      SegmentContent.Blobs(expected, entries, own)
    }
  }

  /** I controlli di un corpo (gli stessi in scrittura e in lettura). Per `meta` anche la chiave, se nota. */
  private fun checkBody(cls: SegmentClass, key: ByteArray?, b: ByteArray, off: Int, len: Int) {
    when (cls) {
      SegmentClass.META -> {
        val record = MetaRecords.read(b.copyOfRange(off, off + len))
        if (key != null) ensure(IndexKeys.compare(key, IndexKeys.meta(record.path)) == 0, RejectReason.INVALID_FIELD, "chiave diversa dal percorso")
      }
      SegmentClass.FRAMES -> FramesBody.parse(b, off, len)
      SegmentClass.PERSONAL -> violation(RejectReason.INVALID_FIELD, "classe non ancora definita")
      SegmentClass.VEC, SegmentClass.OCR -> violation(RejectReason.INVALID_FIELD, "classe dei dati AI fuori dalla testa ai")
      else -> IndexImages.check(b, off, len, TrustLimits.MAX_BLOB_BYTES, IndexImages.ANY)
    }
  }

  private fun lengthAt(b: ByteArray, row: Int): Long = ByteBuffer.wrap(b, row + SegmentEntry.KEY_BYTES, 4).int.toLong() and 0xffffffffL

  private fun compareRows(b: ByteArray, a: Int, c: Int): Int {
    for (i in 0 until SegmentEntry.KEY_BYTES) {
      val d = (b[a + i].toInt() and 0xff) - (b[c + i].toInt() and 0xff)
      if (d != 0) return d
    }
    return 0
  }

  private fun sameKey(b: ByteArray, row: Int, key: ByteArray): Boolean = key.indices.all { b[row + it] == key[it] }
}
