package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sensitive
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Il CONTENUTO dei segmenti dell'indice condiviso (docs/formato-nas.md §5.5-bis, modifiche M1 e M2; docs/fase4-progetto.md
// §2.1, D4, D5). P0 fissa qui i TIPI, perche' li usano in parallelo P3 (codec e pubblicazione), P4 (replica), P5
// (adozione dei record con lo stesso timbro) e P6 (preparazione). Il codec stretto (`IndexSegments.encode/decode`,
// `MetaRecords.verify`), le serie di teste (`HeadSeries`) e la politica dei propri file (`OwnFilesPolicy`) li scrive
// P3 in questo stesso package. Qui nessuna lettura di dati remoti: solo forme e invarianti locali.

/**
 * La classe di un segmento (M1, §5.5-bis): ogni segmento contiene voci di **una** classe, e un dispositivo scarica solo
 * le classi che gli servono (D4, D16). [wire] e' il nome nella testa firmata, [code] il byte all'inizio del contenuto
 * del segmento. Codici 7–15 e 17–31 riservati: `vec`, `ocr`, `faces` (Fase 6/8) sono **rifiutati** finche' una versione
 * del formato non li definisce (E-I4).
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

  /** Dati personali (4B, §2.9): solo nelle teste `personal`, solo nell'area privata dell'account. */
  PERSONAL("personal", 16),
  ;

  /** Il bit nella maschera `prep` di un record (le classi pubblicate per quella chiave di contenuto). */
  val mask: Int get() = 1 shl code

  /** Le voci sono immagini per chiave di contenuto (JPEG o WebP, dimensioni lette prima di decodificare). */
  val isContentBlob: Boolean get() = this != META && this != PERSONAL

  companion object {
    /** Nomi riservati: una testa che li elenca e' rifiutata (nessuna classe "sconosciuta ma accettata"). */
    val RESERVED_WIRE: Set<String> = setOf("vec", "ocr", "faces")

    /** Le classi che una testa `index` puo' elencare (una testa `personal` elenca solo [PERSONAL]). */
    val INDEX_CLASSES: Set<SegmentClass> = entries.filter { it != PERSONAL }.toSet()

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
