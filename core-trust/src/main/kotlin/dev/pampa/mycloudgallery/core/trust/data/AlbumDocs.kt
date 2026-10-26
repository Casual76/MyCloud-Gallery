package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceEntry
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.verdictOf
import dev.pampa.mycloudgallery.core.trust.violation
import java.nio.ByteBuffer

// =====================================================================================================================
// GLI ALBUM CONDIVISI SUL NAS (Fase 7, P2; docs/fase7-progetto.md D6-D11, §2.4, §2.5 "Ricevere"; docs/formato-nas.md §5.8, §7,
// §10). Un CONTRIBUTO per album e per dispositivo, solo nell'area comune:
//
//   albums/<id album>/<id dispositivo>/head/<versione>   testa firmata, busta di tipo 6 (SHARED_ALBUM_HEAD), versione 3
//   albums/<id album>/<id dispositivo>/seg/<impronta>    segmenti "MCGB" | 3 | classe album (17) | tabella | corpi
//
// Le voci sono per chiave, con l'ultima scrittura che vince (la fusione e i permessi stanno in core-albums/shared/). Input OSTILE per
// definizione, come i segmenti della Fase 4 e i dati AI della Fase 6:
// - prima la busta (dimensione, versione, tipo, firma), poi il firmatario (approvato; un dismesso solo per una testa congelata; un
//   revocato o perso mai), poi la versione legata al tipo (la 3, solo qui), poi il JSON con i suoi tetti, poi il posto;
// - nessuna allocazione da lunghezze dichiarate; un errore qualunque (anche un'eccezione imprevista) e' un rifiuto, mai un crash;
// - chi scrive usa gli stessi controlli di chi legge: una voce o un segmento che questo codice produce si rilegge sempre.
// Nessun dato letto qui produce un'azione sui file (§9 punto 4 del formato): sono solo descrizioni.
// =====================================================================================================================

/** I tetti degli album condivisi (A4 del §0.3 del progetto; formato §7). Uguali a `SharedAlbumLimits` di core-albums (test). */
object AlbumLimits {
  /** Il corpo JSON canonico di una voce. */
  const val MAX_ENTRY_BYTES: Int = 4 * 1024

  /** Voci di un contributo (album, dispositivo), lapidi comprese. */
  const val MAX_ENTRIES_PER_CONTRIBUTION: Int = 20_000

  /** Voci di un segmento. */
  const val MAX_ENTRIES_PER_SEGMENT: Int = 8_192

  /** Il titolo: caratteri (punti di codice), §8.4. */
  const val MAX_TITLE_CHARS: Int = 64

  /** Il percorso di un elemento e la cartella dell'album, relativi all'area comune. */
  const val MAX_PATH_BYTES: Int = 1_024

  /** La storia dei livelli di permesso. */
  const val MAX_LEVEL_CHANGES: Int = 32

  /** Un segmento d'album (un contributo onesto da 20.000 voci ne usa al piu' qualche decina di MiB in tutto). */
  const val MAX_SEGMENT_BYTES: Int = 4 * 1024 * 1024

  /** Segmenti elencati da una testa d'album. */
  const val MAX_HEAD_SEGMENTS: Int = 64

  /**
   * La somma delle dimensioni dei segmenti di un contributo, dichiarata dalla testa firmata: oltre, la testa si rifiuta **prima** di
   * scaricare. Un contributo onesto al tetto delle voci con i percorsi piu' lunghi sta sotto i 30 MiB.
   */
  const val MAX_CONTRIBUTION_BYTES: Long = 32L * 1024 * 1024

  /** Il nonce dell'id legato al creatore (D7). */
  const val NONCE_BYTES: Int = AlbumId.NONCE_BYTES

  /** La posizione di un elemento (a intervalli, D21): mai negativa, mai oltre questo. */
  const val MAX_POSITION: Long = 1L shl 53

  /** Il nome di un campo (`item:` + 26 caratteri e' il piu' lungo). */
  const val MAX_FIELD_CHARS: Int = 32

  /** Un codice (`state`, `level`, `sort`, `afterClose`). */
  const val MAX_CODE_CHARS: Int = 16
}

/**
 * Gli id a 128 bit degli album, dei media e delle versioni: in app un UUID canonico, sul NAS 26 caratteri base32 (§2.4 del formato). La
 * conversione e' una sola, per tutti i 128 bit (D7 e la nota di P0).
 */
object AlbumIds {
  private val HEX_32 = Regex("^[0-9a-f]{32}$")

  /** I 16 byte di un UUID canonico (album, media, versione). */
  fun bytesOf(uuid: String): ByteArray {
    val hex = uuid.replace("-", "")
    require(HEX_32.matches(hex)) { "Id non canonico." }
    return Hex.bytes(hex)
  }

  /** Il UUID canonico (minuscolo, con i trattini) di 16 byte. */
  fun uuidOf(bytes: ByteArray): String {
    require(bytes.size == 16) { "Un id sono 16 byte." }
    val h = Hex.of(bytes)
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}"
  }

  /** La forma sul NAS di un id: 26 caratteri base32. */
  fun text(uuid: String): String = Base32.encode(bytesOf(uuid))

  fun text(id: AlbumId): String = text(id.value)

  /** L'id di un album dalla sua forma sul NAS (decodifica stretta), o null. */
  fun parseAlbum(text: String): AlbumId? = uuidOrNull(text)?.let(::AlbumId)

  /** Il UUID di 26 caratteri base32 (decodifica stretta), o null. */
  fun uuidOrNull(text: String): String? =
    if (text.length != TrustIds.LENGTH || !Base32.isCanonical(text, 16)) null else uuidOf(Base32.decodeExact(text, 16))

  /** D7: i primi 128 bit di `SHA-256("mcg-album-id\0" ‖ account ‖ nonce)`, come `AlbumId.derived` di core-model. */
  fun derive(creator: AccountId, nonce: ByteArray): AlbumId = AlbumId.derived(creator, nonce)

  /** Vero se [id] torna con l'account e il nonce dichiarati (E-SH14: un altro familiare non rivendica un album esistente). */
  fun matches(id: AlbumId, creator: AccountId, nonce: ByteArray): Boolean = nonce.size == AlbumLimits.NONCE_BYTES && derive(creator, nonce) == id
}

/** Il campo di una voce (§5.8 del formato). La chiave della voce e' `SHA-256("mcg-album\0" ‖ id album ‖ campo)`. */
sealed interface AlbumField {
  val wire: String

  /** Titolo, cartella, stato, creatore e nonce (e la scelta per gli altri alla chiusura, Q5). */
  data object Definition : AlbumField {
    override val wire: String = "definition"
  }

  /** La storia dei livelli di permesso. */
  data object Level : AlbumField {
    override val wire: String = "level"
  }

  data object Cover : AlbumField {
    override val wire: String = "cover"
  }

  data object Sort : AlbumField {
    override val wire: String = "sort"
  }

  /** L'eredita' dell'admin (D11). */
  data object Heir : AlbumField {
    override val wire: String = "heir"
  }

  /** Un elemento: `item:<id del media in base32>`. */
  data class Item(val media: MediaId) : AlbumField {
    override val wire: String get() = ITEM_PREFIX + AlbumIds.text(media.value)

    override fun toString(): String = "AlbumField.Item"
  }

  companion object {
    const val ITEM_PREFIX: String = "item:"

    /** Il campo di [wire], o null se non e' un campo del formato (un campo sconosciuto rifiuta il segmento). */
    fun parse(wire: String): AlbumField? = when (wire) {
      Definition.wire -> Definition
      Level.wire -> Level
      Cover.wire -> Cover
      Sort.wire -> Sort
      Heir.wire -> Heir
      else -> if (wire.startsWith(ITEM_PREFIX)) AlbumIds.uuidOrNull(wire.substring(ITEM_PREFIX.length))?.let { Item(MediaId(it)) } else null
    }
  }
}

/** La chiave di una voce: 32 byte in esadecimale minuscolo (l'ordine delle stringhe e' quello dei byte senza segno). */
@JvmInline
value class AlbumKey(val hex: String) : Comparable<AlbumKey> {
  init {
    require(HEX_64.matches(hex)) { "Una chiave sono 32 byte." }
  }

  fun bytes(): ByteArray = Hex.bytes(hex)

  override fun compareTo(other: AlbumKey): Int = hex.compareTo(other.hex)

  override fun toString(): String = "AlbumKey(${hex.take(8)}…)"

  companion object {
    private val HEX_64 = Regex("^[0-9a-f]{64}$")
    private val DOMAIN: ByteArray = "mcg-album".toByteArray(Charsets.US_ASCII) + 0

    /** `SHA-256("mcg-album\0" ‖ 16 byte dell'id album ‖ campo in UTF-8)`. */
    fun of(album: AlbumId, field: AlbumField): AlbumKey =
      AlbumKey(Hex.of(Hashes.sha256(DOMAIN, AlbumIds.bytesOf(album.value), field.wire.toByteArray(Charsets.UTF_8))))
  }
}

/** Lo stato di un album nella voce `definition` (D31): `closed` e `deleted` sono definitivi per quell'id. */
enum class AlbumDefinitionState(val wire: String) {
  ACTIVE("active"),
  CLOSED("closed"),
  DELETED("deleted"),
  ;

  companion object {
    fun fromWire(wire: String): AlbumDefinitionState? = entries.firstOrNull { it.wire == wire }
  }
}

/** Un cambio di livello nella storia (D10): da [at] (ora del NAS) vale [level]. */
data class LevelChange(val at: Long, val level: ShareLevel) {
  init {
    require(at in TrustLimits.MIN_PLAUSIBLE_TIME_MILLIS..TrustLimits.MAX_PLAUSIBLE_TIME_MILLIS) { "Ora fuori dal periodo del formato." }
  }
}

/** I testi e i codici degli album: le regole di chi scrive sono quelle di chi legge. */
object AlbumTexts {
  private val CODE = Regex("^[a-z][a-z0-9_]{0,15}$")

  /**
   * Il titolo (§8.4, E-SH22): da 1 a [AlbumLimits.MAX_TITLE_CHARS] caratteri, senza spazi ai bordi, senza nessun carattere che
   * [TextRules] vieta in un testo letto dal NAS (controlli, direzione del testo, invisibili). I caratteri vietati nei nomi di file sono
   * ammessi: la cartella dell'album usa un nome corretto, il titolo resta quello scelto.
   */
  fun isTitle(title: String): Boolean =
    title.isNotEmpty() && title.trim() == title && title.codePointCount(0, title.length) <= AlbumLimits.MAX_TITLE_CHARS && !TextRules.hasForbidden(title)

  /** Un codice di [AlbumLimits.MAX_CODE_CHARS] caratteri al piu' (`afterClose` ne ammette di sconosciuti, Q5: valgono `remove`). */
  fun isCode(code: String): Boolean = CODE.matches(code)

  /** Un percorso di media relativo all'area comune, mai dentro `.mycloudgallery` (confronto NFKC, B1), al piu' 1.024 byte. */
  fun mediaPath(raw: String): SharePath {
    ensure(raw.toByteArray(Charsets.UTF_8).size <= AlbumLimits.MAX_PATH_BYTES, RejectReason.TOO_LARGE, "album: percorso troppo lungo")
    return SharePath.parseMediaPath(raw)
  }
}

/** Il valore di una voce; `null` nella voce e' la lapide (ammessa solo per `cover`, `sort` e `item:`). */
sealed interface AlbumValue {

  /**
   * `definition` (D7, D31, Q5): [title], [folder] (cartella dei file dell'album, relativa all'area comune), [state], [creator] e
   * [nonceHex] (l'id si ricalcola: `AlbumIds.matches`), e con `closed`/`deleted` la scelta per gli altri [afterClose] (un codice:
   * `remove`, `keep_copy`; uno sconosciuto vale `remove`).
   */
  data class Definition(
    val title: String,
    val folder: SharePath,
    val state: AlbumDefinitionState,
    val creator: AccountId,
    val nonceHex: String,
    val afterClose: String?,
  ) : AlbumValue {
    init {
      require(AlbumTexts.isTitle(title)) { "Titolo non ammesso." }
      require(folder.value.toByteArray(Charsets.UTF_8).size <= AlbumLimits.MAX_PATH_BYTES) { "Cartella troppo lunga." }
      require(NONCE_HEX.matches(nonceHex)) { "Il nonce sono 16 byte." }
      require((state == AlbumDefinitionState.ACTIVE) == (afterClose == null)) { "afterClose solo alla chiusura." }
      require(afterClose == null || AlbumTexts.isCode(afterClose)) { "Codice di afterClose non ammesso." }
    }

    val nonce: ByteArray get() = Hex.bytes(nonceHex)

    override fun toString(): String = "AlbumValue.Definition(${state.wire}, $afterClose)"

    private companion object {
      val NONCE_HEX = Regex("^[0-9a-f]{32}$")
    }
  }

  /** `level`: la storia dei livelli, da 1 a [AlbumLimits.MAX_LEVEL_CHANGES] cambi con l'ora strettamente crescente. */
  data class Levels(val history: List<LevelChange>) : AlbumValue {
    init {
      require(history.size in 1..AlbumLimits.MAX_LEVEL_CHANGES) { "Storia dei livelli vuota o troppo lunga." }
      require(history.zipWithNext().all { (a, b) -> a.at < b.at }) { "Storia dei livelli fuori ordine." }
    }
  }

  /** `cover`: la copertina scelta (la lapide = automatica). */
  data class Cover(val media: MediaId, val contentKey: ContentKey) : AlbumValue {
    override fun toString(): String = "AlbumValue.Cover"
  }

  /** `sort`: l'ordine dentro l'album (la lapide = quello predefinito). */
  data class Sort(val sort: AlbumSort) : AlbumValue

  /**
   * `item:<media>` (§2.5 passo 6, D9): la chiave di contenuto **intera** (e l'hash completo se chi scrive l'ha), il percorso nell'area
   * comune, la posizione, l'account che l'ha aggiunto ([addedBy], Q6) e la versione. Il media e' nel campo.
   */
  data class Item(
    val contentKey: ContentKey,
    val path: SharePath,
    val position: Long,
    val addedBy: AccountId,
    val version: VersionId? = null,
    val sha256: Sha256? = null,
  ) : AlbumValue {
    init {
      require(position in 0..AlbumLimits.MAX_POSITION) { "Posizione fuori intervallo." }
      require(path.value.toByteArray(Charsets.UTF_8).size <= AlbumLimits.MAX_PATH_BYTES) { "Percorso troppo lungo." }
    }

    override fun toString(): String = "AlbumValue.Item"
  }

  /** `heir` (D11): l'erede e la versione del registro che lo giustifica. */
  data class Heir(val account: AccountId, val registryVersion: Long) : AlbumValue {
    init {
      require(registryVersion in 1..TrustLimits.MAX_VERSION) { "Versione del registro fuori intervallo." }
    }

    override fun toString(): String = "AlbumValue.Heir"
  }
}

/**
 * Una voce di un contributo: il campo dell'album [album], l'ora del NAS [at] della modifica, il dispositivo [author] (= chi firma la testa
 * che la elenca) e il suo [account] (= `nasUser` del dispositivo nel registro, forma minuscola), il [value] (null = lapide).
 */
data class AlbumEntry(
  val album: AlbumId,
  val field: AlbumField,
  val at: Long,
  val author: String,
  val account: AccountId,
  val value: AlbumValue?,
) {
  init {
    require(at in TrustLimits.MIN_PLAUSIBLE_TIME_MILLIS..TrustLimits.MAX_PLAUSIBLE_TIME_MILLIS) { "Ora fuori dal periodo del formato." }
    require(TrustIds.isValid(author)) { "Autore non valido." }
    require(fits(field, value)) { "Valore diverso da quello del campo." }
  }

  val key: AlbumKey get() = AlbumKey.of(album, this.field)

  val isTombstone: Boolean get() = value == null

  override fun toString(): String = "AlbumEntry(${if (field is AlbumField.Item) "item" else field.wire}, lapide=${value == null})"

  companion object {
    /** Il valore di [field] (la lapide solo dove ha un senso: copertina e ordine predefiniti, elemento tolto). */
    fun fits(field: AlbumField, value: AlbumValue?): Boolean = when (field) {
      AlbumField.Definition -> value is AlbumValue.Definition
      AlbumField.Level -> value is AlbumValue.Levels
      AlbumField.Heir -> value is AlbumValue.Heir
      AlbumField.Cover -> value == null || value is AlbumValue.Cover
      AlbumField.Sort -> value == null || value is AlbumValue.Sort
      is AlbumField.Item -> value == null || value is AlbumValue.Item
    }
  }
}

/**
 * La testa di un contributo (§5.8): `family`, `album` (base32), `device` (= firmatario), `version`, `registryVersion`, `createdAt` (ora del
 * NAS), `segments` (classe `album`, in ordine crescente di impronta, senza doppioni).
 */
data class AlbumHead(
  val familyId: String,
  val album: AlbumId,
  val deviceId: String,
  val version: Long,
  val registryVersion: Long,
  val createdAt: Long,
  val segments: List<SegmentRef>,
) {
  init {
    ensure(TrustIds.isValid(familyId) && TrustIds.isValid(deviceId), RejectReason.INVALID_FIELD, "testa d'album: id")
    ensure(version in 1..TrustLimits.MAX_VERSION && registryVersion in 1..TrustLimits.MAX_VERSION, RejectReason.INVALID_FIELD, "testa d'album: versione")
    ensure(createdAt in TrustLimits.MIN_PLAUSIBLE_TIME_MILLIS..TrustLimits.MAX_PLAUSIBLE_TIME_MILLIS, RejectReason.INVALID_FIELD, "testa d'album: ora")
    ensure(segments.size <= AlbumLimits.MAX_HEAD_SEGMENTS, RejectReason.TOO_LARGE, "testa d'album: troppi segmenti")
    ensure(segments.zipWithNext().all { (a, b) -> a.hash < b.hash }, RejectReason.INVALID_FIELD, "testa d'album: segmenti non ordinati o ripetuti")
    ensure(segments.all { it.size <= AlbumLimits.MAX_SEGMENT_BYTES }, RejectReason.TOO_LARGE, "testa d'album: segmento oltre il tetto")
    ensure(segments.sumOf { it.size } <= AlbumLimits.MAX_CONTRIBUTION_BYTES, RejectReason.TOO_LARGE, "testa d'album: contributo oltre il tetto")
  }

  override fun toString(): String = "AlbumHead(v$version, ${segments.size} segmenti)"
}

/** Dove si e' trovata una testa (`albums/<album>/<device>/head/<fileVersion>`): deve coincidere con cio' che dichiara. */
data class AlbumHeadLocation(val album: AlbumId, val deviceId: String, val fileVersion: Long)

/** Una testa d'album accettata: firma, firmatario, versione del formato, posto e anti-rollback controllati. */
class AcceptedAlbumHead(
  val head: AlbumHead,
  /** Da ricordare con il contributo (anti-rollback ed equivocazione per (album, dispositivo)). */
  val mark: HeadMark,
  val signer: DeviceEntry,
  /** La testa cita un registro piu' nuovo del nostro: va riletto. */
  val registryBehind: Boolean,
) {
  /** L'account del firmatario nella forma delle voci (minuscola). */
  val account: AccountId get() = AccountId.of(signer.nasUser)

  override fun toString(): String = "AcceptedAlbumHead(v${head.version})"
}

/** Una testa propria pronta: i byte firmati e il segno da ricordare. */
class EncodedAlbumHead(val bytes: ByteArray, val mark: HeadMark) {
  override fun toString(): String = "EncodedAlbumHead(v${mark.version}, ${bytes.size} B)"
}

/** Di chi e' il segmento che si legge: ogni voce deve avere quell'album, quel dispositivo come autore e quell'account (D8). */
data class AlbumSegmentContext(val album: AlbumId, val deviceId: String, val account: AccountId)

/**
 * Il codec dei file `albums/` (§1.2 del progetto: `encodeHead`, `decodeHead`, `encodeSegment`, `decodeSegment`):
 *
 * ```
 * testa:    busta "MCGS" | 3 | 6 | chiave | JSON {album, createdAt, device, family, registryVersion, segments, version} | firma
 * segmento: "MCGB" | 3 | 17 | 0 0 0 | N (4, BE) | N x (chiave 32 + lunghezza 4, BE) | corpi JSON canonici
 * voce:     {account, at, author, field, value?}   (value assente = lapide)
 * ```
 */
object AlbumDocs {
  private val MAGIC = "MCGB".toByteArray(Charsets.US_ASCII)

  // --- Teste ---------------------------------------------------------------------------------------------------------

  /** Firma la testa [head] con [signer] (il suo dispositivo), versione 3 del formato. */
  fun encodeHead(head: AlbumHead, signer: Signer): EncodedAlbumHead {
    require(TrustIds.deviceIdOf(signer.publicKey) == head.deviceId) { "La testa va firmata dal suo dispositivo." }
    val payload = jsonObject(
      "album" to AlbumIds.text(head.album).json(),
      "createdAt" to head.createdAt.json(),
      "device" to head.deviceId.json(),
      "family" to head.familyId.json(),
      "registryVersion" to head.registryVersion.json(),
      "segments" to head.segments.map { jsonObject("class" to SegmentClass.ALBUM.wire.json(), "hash" to it.hash.json(), "size" to it.size.json()) }.json(),
      "version" to head.version.json(),
    )
    val bytes = Envelope.seal(DocType.SHARED_ALBUM_HEAD, payload, signer, TrustFormat.ALBUM_VERSION)
    return EncodedAlbumHead(bytes, HeadMark(head.version, Envelope.open(bytes, DocType.SHARED_ALBUM_HEAD).contentHash))
  }

  /**
   * Accetta una testa letta in [location] (D8): busta valida → firmatario nel registro accettato (approvato; dismesso solo se la testa e'
   * fra le sue congelate; revocato, perso, sconosciuto o in attesa: rifiuto) → versione 3 (una testa d'album alla 1 o alla 2 e'
   * `INVALID_FIELD`, una piu' nuova `NEWER_FORMAT`) → famiglia, dispositivo = firmatario, campi e tetti → posto (album, dispositivo,
   * versione del nome) → anti-rollback ed equivocazione rispetto a [previous] (l'ultima accettata per lo stesso album e dispositivo).
   */
  fun decodeHead(bytes: ByteArray, registry: RegistryState, location: AlbumHeadLocation, previous: HeadMark?): Verdict<AcceptedAlbumHead> = verdictOf {
    val envelope = Envelope.open(bytes, DocType.SHARED_ALBUM_HEAD)
    val signer = registry.registry.deviceByKey(envelope.signer) ?: violation(RejectReason.SIGNER_NOT_APPROVED, "testa d'album: firmatario sconosciuto")
    when (signer.status) {
      DeviceStatus.APPROVED -> Unit
      // A7: di un dismesso valgono solo le teste congelate dall'admin (l'impronta dei byte firmati).
      DeviceStatus.RETIRED -> ensure(envelope.contentHash in signer.frozenHeads, RejectReason.SIGNER_RETIRED, "testa d'album: dismesso, non congelata")
      DeviceStatus.REVOKED, DeviceStatus.LOST -> violation(RejectReason.SIGNER_REVOKED, "testa d'album: firmatario revocato")
    }
    ensure(envelope.version == TrustFormat.ALBUM_VERSION, RejectReason.INVALID_FIELD, "testa d'album: versione del formato diversa dalla 3")
    val head = Fields.read(envelope.payloadJson()) { f ->
      ensure(f.string("family", TrustIds.LENGTH) == registry.registry.familyId, RejectReason.WRONG_FAMILY, "testa d'album: altra famiglia")
      ensure(f.string("device", TrustIds.LENGTH) == signer.id, RejectReason.WRONG_LOCATION, "testa d'album: attribuita a un altro dispositivo")
      val album = AlbumIds.parseAlbum(f.string("album", TrustIds.LENGTH)) ?: violation(RejectReason.INVALID_FIELD, "testa d'album: id dell'album")
      AlbumHead(
        familyId = registry.registry.familyId,
        album = album,
        deviceId = signer.id,
        version = f.long("version", 1..TrustLimits.MAX_VERSION),
        registryVersion = f.long("registryVersion", 1..TrustLimits.MAX_VERSION),
        createdAt = f.time("createdAt"),
        segments = f.array("segments", AlbumLimits.MAX_HEAD_SEGMENTS).map { item ->
          Fields.read(item) { s ->
            // Una testa d'album elenca solo segmenti `album`; la classe 17 non vale in nessun'altra testa (DeviceHead la rifiuta).
            ensure(s.string("class", TrustLimits.MAX_ENUM_CHARS) == SegmentClass.ALBUM.wire, RejectReason.INVALID_FIELD, "testa d'album: classe di segmento")
            SegmentRef(s.string("hash", Hashes.NAME_LENGTH), s.long("size", 1..TrustLimits.MAX_SEGMENT_BYTES.toLong()))
          }
        },
      )
    }
    ensure(
      head.album == location.album && head.deviceId == location.deviceId && head.version == location.fileVersion,
      RejectReason.WRONG_LOCATION,
      "testa d'album: non corrisponde al posto in cui si trova",
    )
    if (previous != null) {
      if (head.version < previous.version) violation(RejectReason.ROLLBACK, "testa d'album: versione piu' vecchia")
      if (head.version == previous.version && envelope.contentHash != previous.contentHash) violation(RejectReason.EQUIVOCATION, "testa d'album: due contenuti")
    }
    AcceptedAlbumHead(head, HeadMark(head.version, envelope.contentHash), signer, head.registryVersion > registry.registry.version)
  }

  // --- Voci ----------------------------------------------------------------------------------------------------------

  /** Il JSON canonico di [entry]; [IllegalArgumentException] oltre [AlbumLimits.MAX_ENTRY_BYTES]. */
  fun encodeEntry(entry: AlbumEntry): ByteArray {
    val bytes = CanonicalJson.encode(entryJson(entry))
    require(bytes.size <= AlbumLimits.MAX_ENTRY_BYTES) { "Voce oltre il tetto." }
    return bytes
  }

  /**
   * I byte di [entry] **solo** se chi legge li rileggerebbe uguali (tetti, testi, percorsi): altrimenti null. Chi pubblica non mette mai
   * una voce che farebbe rifiutare agli altri il contributo intero.
   */
  fun entryBytesOrNull(entry: AlbumEntry): ByteArray? {
    val bytes = try {
      encodeEntry(entry)
    } catch (e: IllegalArgumentException) {
      return null
    }
    val back = decodeEntry(bytes, AlbumSegmentContext(entry.album, entry.author, entry.account)) as? Verdict.Accepted ?: return null
    return bytes.takeIf { back.value == entry }
  }

  /** Vero se [entry] si puo' pubblicare ([entryBytesOrNull] non null). */
  fun admits(entry: AlbumEntry): Boolean = entryBytesOrNull(entry) != null

  /** Legge una voce isolata con tutte le regole (per esempio dalla replica locale). */
  fun decodeEntry(json: ByteArray, context: AlbumSegmentContext): Verdict<AlbumEntry> = verdictOf { readEntry(json, context) }

  internal fun readEntry(json: ByteArray, context: AlbumSegmentContext): AlbumEntry {
    ensure(json.size <= AlbumLimits.MAX_ENTRY_BYTES, RejectReason.TOO_LARGE, "voce d'album: oltre il tetto")
    val obj = CanonicalJson.parseCanonical(json) as? JsonObject ?: violation(RejectReason.MALFORMED, "voce d'album: oggetto atteso")
    return Fields.read(obj) { f ->
      // D8: l'account della voce e' il `nasUser` del firmatario (forma minuscola: una sola scrittura); l'autore e' il firmatario.
      ensure(f.string("account", TrustLimits.MAX_NAS_ACCOUNT_CHARS) == context.account.value, RejectReason.NOT_OWNER, "voce d'album: account diverso dal firmatario")
      val at = f.time("at")
      ensure(f.string("author", TrustIds.LENGTH) == context.deviceId, RejectReason.WRONG_LOCATION, "voce d'album: autore diverso dal firmatario")
      val field = AlbumField.parse(f.string("field", AlbumLimits.MAX_FIELD_CHARS)) ?: violation(RejectReason.INVALID_FIELD, "voce d'album: campo sconosciuto")
      val value = if ("value" in obj.members) f.obj("value") { g -> readValue(field, g, at) } else null
      fieldOf { AlbumEntry(context.album, field, at, context.deviceId, context.account, value) }
    }
  }

  private fun readValue(field: AlbumField, g: Fields, at: Long): AlbumValue = when (field) {
    AlbumField.Definition -> {
      val state = AlbumDefinitionState.fromWire(g.string("state", AlbumLimits.MAX_CODE_CHARS))
        ?: violation(RejectReason.INVALID_FIELD, "definizione: stato sconosciuto")
      val afterClose = g.optString("afterClose", AlbumLimits.MAX_CODE_CHARS)
      val creator = account(g.string("creator", TrustLimits.MAX_NAS_ACCOUNT_CHARS))
      val nonce = Base32.decodeExact(g.string("nonce", Base32.encodedLength(AlbumLimits.NONCE_BYTES)), AlbumLimits.NONCE_BYTES)
      val folder = AlbumTexts.mediaPath(g.string("folder", AlbumLimits.MAX_PATH_BYTES))
      val title = g.string("title", 2 * AlbumLimits.MAX_TITLE_CHARS)
      ensure(AlbumTexts.isTitle(title), RejectReason.INVALID_FIELD, "definizione: titolo non ammesso")
      fieldOf { AlbumValue.Definition(title, folder, state, creator, Hex.of(nonce), afterClose) }
    }
    AlbumField.Level -> {
      val history = g.array("history", AlbumLimits.MAX_LEVEL_CHANGES).map { item ->
        Fields.read(item) { h ->
          val changeAt = h.time("at")
          val level = ShareLevel.fromCode(h.string("level", AlbumLimits.MAX_CODE_CHARS)) ?: violation(RejectReason.INVALID_FIELD, "livello sconosciuto")
          LevelChange(changeAt, level)
        }
      }
      // Un cambio dopo la voce che lo porta e' una data inventata: la storia la scrive il creatore nel momento in cui cambia il livello.
      ensure(history.all { it.at <= at }, RejectReason.INVALID_FIELD, "livelli: cambio dopo la voce")
      fieldOf { AlbumValue.Levels(history) }
    }
    AlbumField.Cover -> fieldOf { AlbumValue.Cover(MediaId(uuid(g.string("media", TrustIds.LENGTH))), contentKey(g.string("key", Hashes.NAME_LENGTH))) }
    AlbumField.Sort -> AlbumValue.Sort(AlbumSort.fromCode(g.string("sort", AlbumLimits.MAX_CODE_CHARS)) ?: violation(RejectReason.INVALID_FIELD, "ordine sconosciuto"))
    AlbumField.Heir -> fieldOf { AlbumValue.Heir(account(g.string("account", TrustLimits.MAX_NAS_ACCOUNT_CHARS)), g.long("registryVersion", 1..TrustLimits.MAX_VERSION)) }
    is AlbumField.Item -> {
      val addedBy = account(g.string("addedBy", TrustLimits.MAX_NAS_ACCOUNT_CHARS))
      val sha256 = g.optString("fh", Hashes.NAME_LENGTH)?.let { Sha256(Hex.of(Base32.decodeExact(it, 32))) }
      val key = contentKey(g.string("key", Hashes.NAME_LENGTH))
      val path = AlbumTexts.mediaPath(g.string("path", AlbumLimits.MAX_PATH_BYTES))
      val position = g.long("position", 0..AlbumLimits.MAX_POSITION)
      val version = g.optString("version", TrustIds.LENGTH)?.let { VersionId(uuid(it)) }
      fieldOf { AlbumValue.Item(key, path, position, addedBy, version, sha256) }
    }
  }

  private fun entryJson(entry: AlbumEntry): JsonObject = jsonObject(
    "account" to entry.account.value.json(),
    "at" to entry.at.json(),
    "author" to entry.author.json(),
    "field" to entry.field.wire.json(),
    "value" to entry.value?.let(::valueJson),
  )

  private fun valueJson(value: AlbumValue): JsonObject = when (value) {
    is AlbumValue.Definition -> jsonObject(
      "afterClose" to value.afterClose?.json(),
      "creator" to value.creator.value.json(),
      "folder" to value.folder.value.json(),
      "nonce" to Base32.encode(value.nonce).json(),
      "state" to value.state.wire.json(),
      "title" to value.title.json(),
    )
    is AlbumValue.Levels -> jsonObject("history" to value.history.map { jsonObject("at" to it.at.json(), "level" to it.level.code.json()) }.json())
    is AlbumValue.Cover -> jsonObject("key" to Base32.encode(value.contentKey.bytes()).json(), "media" to AlbumIds.text(value.media.value).json())
    is AlbumValue.Sort -> jsonObject("sort" to value.sort.code.json())
    is AlbumValue.Heir -> jsonObject("account" to value.account.value.json(), "registryVersion" to value.registryVersion.json())
    is AlbumValue.Item -> jsonObject(
      "addedBy" to value.addedBy.value.json(),
      "fh" to value.sha256?.let { Base32.encode(Hex.bytes(it.hex)).json() },
      "key" to Base32.encode(value.contentKey.bytes()).json(),
      "path" to value.path.value.json(),
      "position" to value.position.json(),
      "version" to value.version?.let { AlbumIds.text(it.value).json() },
    )
  }

  /** Un account nella sua sola scrittura (minuscola, come `AccountId`): `Test` e `test` sarebbero due scritture dello stesso account. */
  private fun account(text: String): AccountId {
    ensure(ACCOUNT.matches(text), RejectReason.INVALID_FIELD, "account non ammesso")
    return AccountId(text)
  }

  private fun uuid(text: String): String = AlbumIds.uuidOrNull(text) ?: violation(RejectReason.INVALID_FIELD, "id non valido")

  private fun contentKey(text: String): ContentKey = ContentKey(Hex.of(Base32.decodeExact(text, 32)))

  /** I `require` dei tipi diventano un rifiuto del campo, mai un'eccezione verso fuori. */
  private inline fun <T> fieldOf(block: () -> T): T =
    try {
      block()
    } catch (e: IllegalArgumentException) {
      violation(RejectReason.INVALID_FIELD, "album: valore non ammesso")
    }

  private val ACCOUNT = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

  // --- Segmenti ------------------------------------------------------------------------------------------------------

  /** La dimensione del file di un segmento con [entries] voci e [bodyBytes] byte di corpi (la stessa tabella di §5.5-bis). */
  fun segmentSize(entries: Int, bodyBytes: Long): Long = IndexSegments.sizeOf(entries, bodyBytes)

  /**
   * Scrive un segmento di [album] con [entries] (in qualunque ordine: si ordinano per chiave). Ogni voce passa dagli stessi controlli di
   * chi legge; una voce non ammessa, di un altro album, chiavi ripetute o tetti superati sono errori di chi chiama
   * ([IllegalArgumentException]).
   */
  fun encodeSegment(album: AlbumId, entries: List<AlbumEntry>): EncodedSegment {
    require(entries.size in 1..AlbumLimits.MAX_ENTRIES_PER_SEGMENT) { "Da 1 a ${AlbumLimits.MAX_ENTRIES_PER_SEGMENT} voci." }
    require(entries.all { it.album == album }) { "Voci di un altro album." }
    val rows = entries.map { entry -> entry.key to requireNotNull(entryBytesOrNull(entry)) { "Voce non ammessa dal formato." } }.sortedBy { it.first }
    for (i in 1 until rows.size) require(rows[i - 1].first < rows[i].first) { "Chiavi ripetute." }
    val total = segmentSize(rows.size, rows.sumOf { it.second.size.toLong() })
    require(total <= AlbumLimits.MAX_SEGMENT_BYTES) { "Segmento oltre il tetto." }
    val out = ByteBuffer.allocate(total.toInt())
    out.put(MAGIC).put(TrustFormat.ALBUM_VERSION.toByte())
    out.put(SegmentClass.ALBUM.code.toByte()).put(0).put(0).put(0).putInt(rows.size)
    for ((key, body) in rows) out.put(key.bytes()).putInt(body.size)
    for ((_, body) in rows) out.put(body)
    val bytes = out.array()
    return EncodedSegment(SegmentClass.ALBUM, bytes, SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong()), rows.size)
  }

  /**
   * Legge un segmento elencato da una testa d'album ACCETTATA come [ref], del contributo [context]: tetto → dimensione e impronta →
   * contenitore e versione (la 3; piu' nuova = `NEWER_FORMAT`, la 1 o la 2 = un segmento d'altro tipo spacciato per album) → classe 17
   * (`WRONG_TYPE`) → `N` entro il tetto → tabella coerente (chiavi strettamente crescenti, somma delle lunghezze = resto del file) → ogni
   * voce con i suoi campi, il suo autore e il suo account, e la chiave che torna con l'album e il campo. Si lavora su una copia privata.
   * Un errore qualunque rifiuta il segmento intero (e il contributo, D8).
   */
  fun decodeSegment(bytes: ByteArray, ref: SegmentRef, context: AlbumSegmentContext): Verdict<List<AlbumEntry>> = verdictOf {
    ensure(ref.size <= AlbumLimits.MAX_SEGMENT_BYTES, RejectReason.TOO_LARGE, "segmento d'album: oltre il tetto")
    ensure(bytes.size.toLong() == ref.size, RejectReason.HASH_MISMATCH, "segmento d'album: dimensione diversa")
    val own = bytes.copyOf()
    ensure(Hashes.sha256Name(own) == ref.hash, RejectReason.HASH_MISMATCH, "segmento d'album: impronta diversa")
    ensure(own.size > MAGIC.size && MAGIC.indices.all { own[it] == MAGIC[it] }, RejectReason.MALFORMED, "segmento d'album: contenitore")
    val version = own[MAGIC.size].toInt() and 0xff
    if (version > TrustFormat.ALBUM_VERSION) violation(RejectReason.NEWER_FORMAT, "segmento d'album: formato piu' nuovo")
    ensure(version == TrustFormat.ALBUM_VERSION, RejectReason.MALFORMED, "segmento d'album: versione diversa dalla 3")
    val base = IndexSegments.CONTAINER_BYTES
    ensure(own.size >= base + IndexSegments.CONTENT_HEADER_BYTES, RejectReason.MALFORMED, "segmento d'album: intestazione troncata")
    ensure((own[base].toInt() and 0xff) == SegmentClass.ALBUM.code, RejectReason.WRONG_TYPE, "segmento d'album: classe diversa da album")
    ensure(own[base + 1].toInt() == 0 && own[base + 2].toInt() == 0 && own[base + 3].toInt() == 0, RejectReason.MALFORMED, "segmento d'album: byte riservati")
    val n = ByteBuffer.wrap(own, base + 4, 4).int.toLong() and 0xffffffffL
    ensure(n >= 1, RejectReason.MALFORMED, "segmento d'album: nessuna voce")
    ensure(n <= AlbumLimits.MAX_ENTRIES_PER_SEGMENT, RejectReason.TOO_LARGE, "segmento d'album: troppe voci")
    val count = n.toInt()
    val tableStart = base + IndexSegments.CONTENT_HEADER_BYTES
    val tableEnd = tableStart.toLong() + IndexSegments.TABLE_ENTRY_BYTES.toLong() * count
    ensure(tableEnd <= own.size, RejectReason.MALFORMED, "segmento d'album: tabella troncata")
    var sum = 0L
    for (i in 0 until count) {
      val row = tableStart + IndexSegments.TABLE_ENTRY_BYTES * i
      val length = lengthAt(own, row)
      ensure(length >= 1, RejectReason.MALFORMED, "segmento d'album: corpo vuoto")
      ensure(length <= AlbumLimits.MAX_ENTRY_BYTES, RejectReason.TOO_LARGE, "segmento d'album: voce oltre il tetto")
      sum += length
      if (i > 0) ensure(compareRows(own, row - IndexSegments.TABLE_ENTRY_BYTES, row) < 0, RejectReason.MALFORMED, "segmento d'album: chiavi non crescenti")
    }
    ensure(sum == own.size - tableEnd, RejectReason.MALFORMED, "segmento d'album: corpi e tabella non coincidono")
    var offset = tableEnd.toInt()
    val entries = ArrayList<AlbumEntry>(count)
    for (i in 0 until count) {
      val row = tableStart + IndexSegments.TABLE_ENTRY_BYTES * i
      val length = lengthAt(own, row).toInt()
      val entry = readEntry(own.copyOfRange(offset, offset + length), context)
      ensure(Hex.of(own, row, SegmentEntry.KEY_BYTES) == entry.key.hex, RejectReason.INVALID_FIELD, "segmento d'album: chiave diversa da album e campo")
      entries += entry
      offset += length
    }
    entries
  }

  // --- Contributo ----------------------------------------------------------------------------------------------------

  /**
   * Le regole del contributo intero, dopo aver letto tutti i suoi segmenti (D7, D8, A4): al piu' [AlbumLimits.MAX_ENTRIES_PER_CONTRIBUTION]
   * voci, una sola voce per chiave, e ogni `definition` con l'account e il nonce da cui si ricalcola **questo** id (E-SH14). Una
   * violazione rifiuta il contributo intero.
   */
  fun checkContribution(album: AlbumId, entries: List<AlbumEntry>): Verdict<Unit> = verdictOf {
    ensure(entries.size <= AlbumLimits.MAX_ENTRIES_PER_CONTRIBUTION, RejectReason.TOO_LARGE, "contributo d'album: troppe voci")
    val keys = HashSet<AlbumKey>(entries.size * 2)
    for (entry in entries) {
      ensure(entry.album == album, RejectReason.INVALID_FIELD, "contributo d'album: voce di un altro album")
      ensure(keys.add(entry.key), RejectReason.INVALID_FIELD, "contributo d'album: chiave ripetuta")
      val definition = entry.value as? AlbumValue.Definition ?: continue
      ensure(AlbumIds.matches(album, definition.creator, definition.nonce), RejectReason.INVALID_FIELD, "contributo d'album: id che non torna con account e nonce")
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
}
