package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.registry.DeviceEntry
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.verdictOf
import dev.pampa.mycloudgallery.core.trust.violation
import java.nio.ByteBuffer
import java.util.Locale

// =====================================================================================================================
// I DATI DEI VOLTI SUL NAS (Fase 8, P3; docs/fase8-progetto.md §2.4, D11-D13, D21, D30, D31; docs/formato-nas.md §5.5-bis, §5.11, §7, §10).
// Solo nell'AREA PRIVATA dell'account che ha attivato Persone, una cartella per generazione e per dispositivo:
//
//   faces/<G>/<dispositivo>/head/<versione>   testa firmata, busta di tipo 9 (FACES_HEAD), versione 4 del formato
//   faces/<G>/<dispositivo>/seg/<impronta>    segmenti "MCGB" | 4 | classe (18 faces, 19 cluster, 20 people) | tabella | corpi
//
// Dati biometrici, input OSTILE per definizione (un dispositivo compromesso dell'account, un file scritto a mano sul NAS, le teste di un
// altro account messe nella mia area):
// - prima la busta (dimensione, versione, tipo, firma), poi la versione legata al tipo (la 4, solo qui), poi il firmatario (approvato; un
//   dismesso solo per una testa congelata; revocato o perso mai) **dello stesso account di chi legge** (`NOT_OWNER`), poi il JSON con i
//   suoi tetti, poi il posto (generazione, dispositivo, numero), poi l'anti-rollback e l'equivocazione;
// - nessuna allocazione da lunghezze dichiarate; un errore qualunque (anche un'eccezione imprevista) e' un rifiuto, mai un crash;
// - chi scrive usa gli stessi controlli di chi legge: una voce o un segmento che questo codice produce si rilegge sempre.
// La generazione corrente la decide chi legge (lo stato fresco dei dati personali, D30): una testa di un'altra generazione non si legge.
// Nessun dato letto qui produce un'azione sui file (formato §9): le sole cancellazioni dei volti sono quelle del punto 6-bis (core-nasops).
// =====================================================================================================================

/** Il segno di un'istantanea nella testa (§2.4): [seq] (da 1) e [base], la `seq` da cui e' partita (minore di [seq]; 0 per la prima). */
data class SnapshotMark(val seq: Long, val base: Long) {
  init {
    ensure(seq in 1..TrustLimits.MAX_VERSION && base in 0 until seq, RejectReason.INVALID_FIELD, "istantanea: numeri non validi")
  }
}

/**
 * La testa dei dati dei volti di un dispositivo in una generazione (§2.4, formato §5.11): `account` (il `nasUser` del firmatario, in
 * minuscolo), `createdAt` (ora del NAS), `device`, `family`, `generation`, `registryVersion`, `segments` (da 0 a 8.192 `{class, hash, size}`
 * delle sole classi dei volti, in ordine di impronta, ognuno al piu' 12 MiB, in tutto al piu' `MAX_FACES_HEAD_BYTES`), `snapshot` **se e solo
 * se** la testa elenca segmenti `cluster`, `version`.
 */
data class FacesHead(
  val familyId: String,
  val account: String,
  val generation: FaceGeneration,
  val deviceId: String,
  val version: Long,
  val registryVersion: Long,
  val createdAt: Long,
  val snapshot: SnapshotMark?,
  val segments: List<ClassedSegment>,
) {
  init {
    ensure(TrustIds.isValid(familyId) && TrustIds.isValid(deviceId), RejectReason.INVALID_FIELD, "testa dei volti: id")
    ensure(ACCOUNT.matches(account), RejectReason.INVALID_FIELD, "testa dei volti: account")
    ensure(version in 1..TrustLimits.MAX_VERSION && registryVersion in 1..TrustLimits.MAX_VERSION, RejectReason.INVALID_FIELD, "testa dei volti: versione")
    ensure(createdAt in TrustLimits.MIN_PLAUSIBLE_TIME_MILLIS..TrustLimits.MAX_PLAUSIBLE_TIME_MILLIS, RejectReason.INVALID_FIELD, "testa dei volti: ora")
    ensure(segments.size <= TrustLimits.MAX_HEAD_SEGMENTS, RejectReason.TOO_LARGE, "testa dei volti: troppi segmenti")
    ensure(segments.zipWithNext().all { (a, b) -> a.ref.hash < b.ref.hash }, RejectReason.INVALID_FIELD, "testa dei volti: segmenti non ordinati o ripetuti")
    ensure(segments.all { it.cls.isFaces }, RejectReason.INVALID_FIELD, "testa dei volti: classe di segmento fuori posto")
    ensure(segments.all { it.ref.size <= TrustLimits.MAX_FACES_SEGMENT_BYTES }, RejectReason.TOO_LARGE, "testa dei volti: segmento oltre il tetto")
    ensure(segments.sumOf { it.ref.size } <= TrustLimits.MAX_FACES_HEAD_BYTES, RejectReason.TOO_LARGE, "testa dei volti: contributo oltre il tetto")
    ensure(
      (snapshot != null) == segments.any { it.cls == SegmentClass.CLUSTER },
      RejectReason.INVALID_FIELD,
      "testa dei volti: istantanea se e solo se ci sono segmenti cluster",
    )
  }

  override fun toString(): String = "FacesHead(v$version, ${segments.size} segmenti, istantanea=${snapshot != null})"

  internal companion object {
    /** Un account nella sua sola scrittura (minuscola): `Test` e `test` sono lo stesso account (Samba). */
    val ACCOUNT = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
  }
}

/** Dove si e' trovata una testa dei volti (`faces/<generation>/<deviceId>/head/<fileVersion>`): deve coincidere con cio' che dichiara. */
data class FacesHeadLocation(val generation: FaceGeneration, val deviceId: String, val fileVersion: Long) {
  override fun toString(): String = "FacesHeadLocation(v$fileVersion)"
}

/** Una testa dei volti accettata: firma, versione, firmatario dell'account di chi legge, campi, posto e anti-rollback controllati. */
class AcceptedFacesHead(
  val head: FacesHead,
  /** Da ricordare per (generazione, dispositivo): anti-rollback ed equivocazione. */
  val mark: HeadMark,
  val signer: DeviceEntry,
  /** La testa cita un registro piu' nuovo del nostro: va riletto. */
  val registryBehind: Boolean,
) {
  override fun toString(): String = "AcceptedFacesHead(v${head.version})"
}

/** Una testa propria pronta: i byte firmati e il segno da ricordare. */
class EncodedFacesHead(val bytes: ByteArray, val mark: HeadMark) {
  override fun toString(): String = "EncodedFacesHead(v${mark.version}, ${bytes.size} B)"
}

/** Un corpo `faces` letto da un segmento: la chiave di contenuto, il corpo e la sua impronta (prime 8 byte dello SHA-256 dei suoi byte). */
class FacesBodyEntry(val contentKey: ContentKey, val body: FacesBody, val digest: Long) {
  override fun toString(): String = "FacesBodyEntry(${body.faces.size} volti)"
}

/** Il contenuto di un segmento dei volti, per classe. */
sealed interface FacesSegmentContent {
  val size: Int

  class Bodies(val entries: List<FacesBodyEntry>) : FacesSegmentContent {
    override val size: Int get() = entries.size

    override fun toString(): String = "FacesSegmentContent.Bodies($size)"
  }

  class Cluster(val entries: List<ClusterEntry>) : FacesSegmentContent {
    override val size: Int get() = entries.size

    override fun toString(): String = "FacesSegmentContent.Cluster($size)"
  }

  class People(val entries: List<PeopleDoc>) : FacesSegmentContent {
    override val size: Int get() = entries.size

    override fun toString(): String = "FacesSegmentContent.People($size)"
  }
}

/**
 * Un'istantanea completa e coerente (§2.4, D21): tutte le voci `cluster` di una testa con [mark]. Ogni assegnazione nomina una persona
 * dell'istantanea, ogni alias porta a una persona dell'istantanea, ogni persona dice quante assegnazioni non ignorate ha e nessun numero
 * supera [SnapshotMark.seq]. Una per persona e una per volto.
 */
class FacesSnapshotDoc(val mark: SnapshotMark, val persons: List<ClusterEntry.Person>, val assignments: List<ClusterEntry.Assignment>) {
  override fun toString(): String = "FacesSnapshotDoc(seq=${mark.seq}, ${persons.size} persone, ${assignments.size} assegnazioni)"
}

/**
 * IL CODEC DEI FILE `faces/` (§1.2 del progetto: `encodeHead`, `decodeHead`, `encodeSegment`, `decodeSegment`):
 *
 * ```
 * testa:    busta "MCGS" | 4 | 9 | chiave | JSON {account, createdAt, device, family, generation, registryVersion, segments, snapshot?, version} | firma
 * segmento: "MCGB" | 4 | classe (18, 19, 20) | 0 0 0 | N (4, BE) | N x (chiave 32 + lunghezza 4, BE) | corpi
 * ```
 *
 * Un corpo `faces` ha per chiave la chiave di contenuto; una voce `cluster` l'impronta del suo id o del suo riferimento; una voce `people`
 * l'impronta di soggetto e campo. L'impronta di un corpo ([bodyDigest]) sono le **prime 8 byte, big endian, dello SHA-256 dei suoi byte
 * codificati**: la portano i riferimenti dell'istantanea (`FaceRef.bodyDigest`), cosi' un corpo vincente che cambia rende "nuovi" i suoi volti.
 */
object FacesDocs {
  private val MAGIC = "MCGB".toByteArray(Charsets.US_ASCII)

  // --- Teste ---------------------------------------------------------------------------------------------------------

  /** Firma la testa [head] con [signer] (il suo dispositivo), versione 4 del formato. */
  fun encodeHead(head: FacesHead, signer: Signer): EncodedFacesHead {
    require(TrustIds.deviceIdOf(signer.publicKey) == head.deviceId) { "La testa va firmata dal suo dispositivo." }
    val payload = jsonObject(
      "account" to head.account.json(),
      "createdAt" to head.createdAt.json(),
      "device" to head.deviceId.json(),
      "family" to head.familyId.json(),
      "generation" to head.generation.base32.json(),
      "registryVersion" to head.registryVersion.json(),
      "segments" to head.segments.map { jsonObject("class" to it.cls.wire.json(), "hash" to it.ref.hash.json(), "size" to it.ref.size.json()) }.json(),
      "snapshot" to head.snapshot?.let { jsonObject("base" to it.base.json(), "seq" to it.seq.json()) },
      "version" to head.version.json(),
    )
    val bytes = Envelope.seal(DocType.FACES_HEAD, payload, signer, TrustFormat.FACES_VERSION)
    return EncodedFacesHead(bytes, HeadMark(head.version, Envelope.open(bytes, DocType.FACES_HEAD).contentHash))
  }

  /**
   * Accetta una testa letta in [location], con le regole del §2.4 in quest'ordine: busta (tipo 9; oltre la 4 `NEWER_FORMAT`) → versione
   * esattamente 4 (`INVALID_FIELD`) → firmatario nel registro accettato (approvato; dismesso solo se la testa e' fra le sue congelate;
   * revocato o perso `SIGNER_REVOKED`; sconosciuto `SIGNER_NOT_APPROVED`) **del mio account** ([readerAccount], altrimenti `NOT_OWNER`) →
   * campi (famiglia, `device` = firmatario, `account` = il suo `nasUser` in minuscolo, tetti; un campo sconosciuto rifiuta) → posto
   * (generazione, dispositivo e numero del nome: `WRONG_LOCATION`) → ora non oltre 2 minuti nel futuro rispetto a [nasNow] → anti-rollback
   * ed equivocazione rispetto a [previous] (l'ultima accettata per la stessa generazione e lo stesso dispositivo).
   */
  fun decodeHead(
    bytes: ByteArray,
    registry: RegistryState,
    location: FacesHeadLocation,
    readerAccount: String,
    previous: HeadMark?,
    nasNow: Long? = null,
  ): Verdict<AcceptedFacesHead> = verdictOf {
    val envelope = Envelope.open(bytes, DocType.FACES_HEAD)
    ensure(envelope.version == TrustFormat.FACES_VERSION, RejectReason.INVALID_FIELD, "testa dei volti: versione del formato diversa dalla 4")
    val signer = registry.registry.deviceByKey(envelope.signer) ?: violation(RejectReason.SIGNER_NOT_APPROVED, "testa dei volti: firmatario sconosciuto")
    when (signer.status) {
      DeviceStatus.APPROVED -> Unit
      // Di un dismesso valgono solo le teste congelate dall'admin (l'impronta dei byte firmati, A7 della Fase 7).
      DeviceStatus.RETIRED -> ensure(envelope.contentHash in signer.frozenHeads, RejectReason.SIGNER_RETIRED, "testa dei volti: dismesso, non congelata")
      DeviceStatus.REVOKED, DeviceStatus.LOST -> violation(RejectReason.SIGNER_REVOKED, "testa dei volti: firmatario revocato")
    }
    // D11, C37: i dati dei volti di un altro account non si leggono, nemmeno se finiscono nella mia area privata.
    ensure(TextRules.sameAccount(signer.nasUser, readerAccount), RejectReason.NOT_OWNER, "testa dei volti di un altro account")
    val payload = envelope.payloadJson()
    val head = Fields.read(payload) { f ->
      ensure(f.string("family", TrustIds.LENGTH) == registry.registry.familyId, RejectReason.WRONG_FAMILY, "testa dei volti: altra famiglia")
      ensure(f.string("device", TrustIds.LENGTH) == signer.id, RejectReason.WRONG_LOCATION, "testa dei volti: attribuita a un altro dispositivo")
      val account = f.string("account", TrustLimits.MAX_NAS_ACCOUNT_CHARS)
      ensure(account == signer.nasUser.lowercase(Locale.ROOT), RejectReason.NOT_OWNER, "testa dei volti: account diverso dal firmatario")
      val generation = FaceGeneration.parse(f.string("generation", FaceGeneration.CHARS))
        ?: violation(RejectReason.INVALID_FIELD, "testa dei volti: generazione non canonica")
      val snapshot = if ("snapshot" in payload.members) {
        f.obj("snapshot") { s -> SnapshotMark(s.long("seq", 1..TrustLimits.MAX_VERSION), s.long("base", 0..TrustLimits.MAX_VERSION)) }
      } else {
        null
      }
      FacesHead(
        familyId = registry.registry.familyId,
        account = account,
        generation = generation,
        deviceId = signer.id,
        version = f.long("version", 1..TrustLimits.MAX_VERSION),
        registryVersion = f.long("registryVersion", 1..TrustLimits.MAX_VERSION),
        createdAt = f.time("createdAt"),
        snapshot = snapshot,
        segments = f.array("segments", TrustLimits.MAX_HEAD_SEGMENTS).map { item ->
          Fields.read(item) { s ->
            // Una testa dei volti elenca solo `faces`, `cluster` e `people`; quelle classi non valgono in nessun'altra testa.
            val cls = SegmentClass.fromWire(s.string("class", TrustLimits.MAX_ENUM_CHARS))?.takeIf { it.isFaces }
              ?: violation(RejectReason.INVALID_FIELD, "testa dei volti: classe di segmento")
            ClassedSegment(cls, SegmentRef(s.string("hash", Hashes.NAME_LENGTH), s.long("size", 1..TrustLimits.MAX_SEGMENT_BYTES.toLong())))
          }
        },
      )
    }
    ensure(
      head.generation == location.generation && head.deviceId == location.deviceId && head.version == location.fileVersion,
      RejectReason.WRONG_LOCATION,
      "testa dei volti: non corrisponde al posto in cui si trova",
    )
    if (nasNow != null) {
      ensure(head.createdAt <= nasNow + TrustLimits.MAX_FUTURE_SKEW_MILLIS, RejectReason.INVALID_FIELD, "testa dei volti: ora nel futuro")
    }
    if (previous != null) {
      if (head.version < previous.version) violation(RejectReason.ROLLBACK, "testa dei volti: versione piu' vecchia")
      if (head.version == previous.version && envelope.contentHash != previous.contentHash) violation(RejectReason.EQUIVOCATION, "testa dei volti: due contenuti")
    }
    AcceptedFacesHead(head, HeadMark(head.version, envelope.contentHash), signer, head.registryVersion > registry.registry.version)
  }

  // --- Corpi e voci ----------------------------------------------------------------------------------------------------

  /** I byte del corpo `faces` [body]: un [FacesBody] esiste solo se rispetta le regole di chi legge, quindi si rilegge sempre uguale. */
  fun encodeBody(body: FacesBody): ByteArray = FacesBinary.writeBody(body)

  /** Legge un corpo `faces` isolato con tutte le regole (per esempio dall'archivio locale o in un test). */
  fun decodeBody(bytes: ByteArray): Verdict<FacesBody> = verdictOf {
    ensure(bytes.size <= TrustLimits.MAX_FACES_BODY_BYTES, RejectReason.TOO_LARGE, "corpo faces: oltre il tetto")
    FacesBinary.readBody(bytes, 0, bytes.size)
  }

  /** L'impronta di un corpo (§2.4, `FaceRef.bodyDigest`): le prime 8 byte, big endian, dello SHA-256 dei byte codificati. */
  fun bodyDigest(bytes: ByteArray): Long = ByteBuffer.wrap(Hashes.sha256(bytes), 0, 8).long

  fun digestOf(body: FacesBody): Long = bodyDigest(encodeBody(body))

  /** I byte di una voce `cluster` (persona al piu' 983 byte, assegnazione 62: sotto `MAX_CLUSTER_ENTRY_BYTES`). */
  fun encodeCluster(entry: ClusterEntry): ByteArray = FacesBinary.write(entry)

  /** I byte di una voce `people` (JSON canonico). */
  fun encodePeople(doc: PeopleDoc): ByteArray = PeopleJson.write(doc)

  // --- Segmenti ------------------------------------------------------------------------------------------------------

  /** Il tetto del corpo di una voce di [cls] (formato §7). */
  fun maxEntryBytes(cls: SegmentClass): Int = when (cls) {
    SegmentClass.FACES -> TrustLimits.MAX_FACES_BODY_BYTES
    SegmentClass.CLUSTER -> TrustLimits.MAX_CLUSTER_ENTRY_BYTES
    SegmentClass.PEOPLE -> TrustLimits.MAX_PEOPLE_ENTRY_BYTES
    else -> throw IllegalArgumentException("Classe che non e' dei volti.")
  }

  /** La dimensione del file di un segmento con [entries] voci e [bodyBytes] byte di corpi (la stessa tabella del §5.5-bis). */
  fun segmentSize(entries: Int, bodyBytes: Long): Long = IndexSegments.sizeOf(entries, bodyBytes)

  /** Un segmento `faces` con i corpi [items] (chiave di contenuto, corpo), in qualunque ordine; chiavi ripetute = errore di chi chiama. */
  fun bodiesSegment(items: List<Pair<ContentKey, FacesBody>>): EncodedSegment =
    container(SegmentClass.FACES, items.map { (key, body) -> key.bytes() to FacesBinary.writeBody(body) })

  /** Un segmento `cluster` con le voci [entries] (persone e assegnazioni di un'istantanea). */
  fun clusterSegment(entries: List<ClusterEntry>): EncodedSegment = container(SegmentClass.CLUSTER, entries.map { it.key to FacesBinary.write(it) })

  /** Un segmento `people` con le voci [entries] (una per chiave). */
  fun peopleSegment(entries: List<PeopleDoc>): EncodedSegment = container(SegmentClass.PEOPLE, entries.map { it.key to PeopleJson.write(it) })

  /**
   * Scrive un contenitore della classe [cls]. Ogni corpo e' gia' passato dai controlli di chi legge (i tipi non esistono altrimenti);
   * qui i tetti della tabella: da 1 a `MAX_FACES_SEGMENT_ENTRIES` voci, chiavi distinte, al piu' `MAX_FACES_SEGMENT_BYTES` in tutto
   * ([IllegalArgumentException] per un errore di chi chiama, mai un segmento che gli altri rifiuterebbero).
   */
  private fun container(cls: SegmentClass, rows: List<Pair<ByteArray, ByteArray>>): EncodedSegment {
    require(rows.size in 1..TrustLimits.MAX_FACES_SEGMENT_ENTRIES) { "Da 1 a ${TrustLimits.MAX_FACES_SEGMENT_ENTRIES} voci." }
    val sorted = rows.sortedWith { a, b -> IndexKeys.compare(a.first, b.first) }
    for (i in 1 until sorted.size) require(IndexKeys.compare(sorted[i - 1].first, sorted[i].first) < 0) { "Chiavi ripetute." }
    val max = maxEntryBytes(cls)
    require(sorted.all { it.first.size == SegmentEntry.KEY_BYTES && it.second.size in 1..max }) { "Voce oltre il tetto." }
    val total = segmentSize(sorted.size, sorted.sumOf { it.second.size.toLong() })
    require(total <= TrustLimits.MAX_FACES_SEGMENT_BYTES) { "Segmento oltre il tetto." }
    val out = ByteBuffer.allocate(total.toInt())
    out.put(MAGIC).put(TrustFormat.FACES_VERSION.toByte())
    out.put(cls.code.toByte()).put(0).put(0).put(0).putInt(sorted.size)
    for ((key, body) in sorted) out.put(key).putInt(body.size)
    for ((_, body) in sorted) out.put(body)
    val bytes = out.array()
    return EncodedSegment(cls, bytes, SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong()), sorted.size)
  }

  /**
   * Legge un segmento elencato da una testa dei volti ACCETTATA come [ref] con la classe [expected]: tetto → dimensione e impronta →
   * contenitore e versione (la 4; piu' nuova `NEWER_FORMAT`, la 1, 2 o 3 un segmento d'altro tipo spacciato per volti: `MALFORMED`) → classe
   * (`WRONG_TYPE`) → `N` entro il tetto → tabella coerente (chiavi strettamente crescenti, somma delle lunghezze = resto del file) → ogni
   * voce con i suoi campi e la chiave che torna. Si lavora su una copia privata. Un errore qualunque rifiuta il segmento intero.
   */
  fun decodeSegment(bytes: ByteArray, ref: SegmentRef, expected: SegmentClass): Verdict<FacesSegmentContent> = verdictOf {
    ensure(expected.isFaces, RejectReason.INVALID_FIELD, "segmento dei volti: classe d'altro tipo")
    ensure(ref.size <= TrustLimits.MAX_FACES_SEGMENT_BYTES, RejectReason.TOO_LARGE, "segmento dei volti: oltre il tetto")
    ensure(bytes.size.toLong() == ref.size, RejectReason.HASH_MISMATCH, "segmento dei volti: dimensione diversa")
    val own = bytes.copyOf()
    ensure(Hashes.sha256Name(own) == ref.hash, RejectReason.HASH_MISMATCH, "segmento dei volti: impronta diversa")
    ensure(own.size > MAGIC.size && MAGIC.indices.all { own[it] == MAGIC[it] }, RejectReason.MALFORMED, "segmento dei volti: contenitore")
    val version = own[MAGIC.size].toInt() and 0xff
    if (version > TrustFormat.FACES_VERSION) violation(RejectReason.NEWER_FORMAT, "segmento dei volti: formato piu' nuovo")
    ensure(version == TrustFormat.FACES_VERSION, RejectReason.MALFORMED, "segmento dei volti: versione diversa dalla 4")
    val base = IndexSegments.CONTAINER_BYTES
    ensure(own.size >= base + IndexSegments.CONTENT_HEADER_BYTES, RejectReason.MALFORMED, "segmento dei volti: intestazione troncata")
    ensure((own[base].toInt() and 0xff) == expected.code, RejectReason.WRONG_TYPE, "segmento dei volti: classe diversa da quella della testa")
    ensure(own[base + 1].toInt() == 0 && own[base + 2].toInt() == 0 && own[base + 3].toInt() == 0, RejectReason.MALFORMED, "segmento dei volti: byte riservati")
    val n = ByteBuffer.wrap(own, base + 4, 4).int.toLong() and 0xffffffffL
    ensure(n >= 1, RejectReason.MALFORMED, "segmento dei volti: nessuna voce")
    ensure(n <= TrustLimits.MAX_FACES_SEGMENT_ENTRIES, RejectReason.TOO_LARGE, "segmento dei volti: troppe voci")
    val count = n.toInt()
    val tableStart = base + IndexSegments.CONTENT_HEADER_BYTES
    val tableEnd = tableStart.toLong() + IndexSegments.TABLE_ENTRY_BYTES.toLong() * count
    ensure(tableEnd <= own.size, RejectReason.MALFORMED, "segmento dei volti: tabella troncata")
    val max = maxEntryBytes(expected)
    var sum = 0L
    for (i in 0 until count) {
      val row = tableStart + IndexSegments.TABLE_ENTRY_BYTES * i
      val length = lengthAt(own, row)
      ensure(length >= 1, RejectReason.MALFORMED, "segmento dei volti: corpo vuoto")
      ensure(length <= max, RejectReason.TOO_LARGE, "segmento dei volti: voce oltre il tetto")
      sum += length
      if (i > 0) ensure(compareRows(own, row - IndexSegments.TABLE_ENTRY_BYTES, row) < 0, RejectReason.MALFORMED, "segmento dei volti: chiavi non crescenti")
    }
    ensure(sum == own.size - tableEnd, RejectReason.MALFORMED, "segmento dei volti: corpi e tabella non coincidono")
    var offset = tableEnd.toInt()
    val rows = (0 until count).map { i ->
      val row = tableStart + IndexSegments.TABLE_ENTRY_BYTES * i
      val length = lengthAt(own, row).toInt()
      Triple(row, offset, length).also { offset += length }
    }
    when (expected) {
      SegmentClass.FACES -> FacesSegmentContent.Bodies(
        rows.map { (row, at, length) ->
          val body = FacesBinary.readBody(own, at, length)
          FacesBodyEntry(ContentKey(Hex.of(own, row, SegmentEntry.KEY_BYTES)), body, bodyDigest(own.copyOfRange(at, at + length)))
        },
      )
      SegmentClass.CLUSTER -> FacesSegmentContent.Cluster(
        rows.map { (row, at, length) ->
          FacesBinary.readCluster(own, at, length).also { entry ->
            ensure(sameKey(own, row, entry.key), RejectReason.INVALID_FIELD, "segmento dei volti: chiave diversa dalla voce cluster")
          }
        },
      )
      else -> FacesSegmentContent.People(
        rows.map { (row, at, length) ->
          PeopleJson.read(own.copyOfRange(at, at + length)).also { doc ->
            ensure(sameKey(own, row, doc.key), RejectReason.INVALID_FIELD, "segmento dei volti: chiave diversa da soggetto e campo")
          }
        },
      )
    }
  }

  // --- Istantanea ----------------------------------------------------------------------------------------------------

  /**
   * Le regole dell'istantanea intera (§2.4, D21), dopo aver letto **tutti** i segmenti `cluster` di una testa con [mark]: al piu'
   * `MAX_SNAPSHOT_PERSONS` persone e `MAX_SNAPSHOT_ASSIGNMENTS` assegnazioni; una voce per persona e una per volto; ogni assegnazione nomina
   * una persona dell'istantanea e ogni alias porta a una persona dell'istantanea; il numero dei volti di ogni persona e' quello delle sue
   * assegnazioni non ignorate; nessun numero dopo [SnapshotMark.seq]. Una violazione rifiuta l'istantanea intera.
   */
  fun checkSnapshot(mark: SnapshotMark, entries: List<ClusterEntry>): Verdict<FacesSnapshotDoc> = verdictOf {
    val persons = entries.filterIsInstance<ClusterEntry.Person>()
    val assignments = entries.filterIsInstance<ClusterEntry.Assignment>()
    ensure(persons.isNotEmpty(), RejectReason.INVALID_FIELD, "istantanea: nessuna persona")
    // R4 (Fase 8, correzioni formato): una `seq` oltre il tetto delle istantanee non vale ne' per chi legge ne' per chi scrive. Il tetto sta molto
    // sotto `MAX_VERSION`: un custode che ne adotta una vicina ha ancora spazio per andare avanti, e una vincente oltre non si adotta mai.
    ensure(mark.seq <= TrustLimits.MAX_SNAPSHOT_SEQ, RejectReason.TOO_LARGE, "istantanea: numero oltre il tetto")
    ensure(persons.size <= TrustLimits.MAX_SNAPSHOT_PERSONS, RejectReason.TOO_LARGE, "istantanea: troppe persone")
    ensure(assignments.size <= TrustLimits.MAX_SNAPSHOT_ASSIGNMENTS, RejectReason.TOO_LARGE, "istantanea: troppe assegnazioni")
    val byId = HashMap<PersonId, ClusterEntry.Person>(persons.size * 2)
    for (person in persons) ensure(byId.put(person.id, person) == null, RejectReason.INVALID_FIELD, "istantanea: persona ripetuta")
    val faces = HashSet<dev.pampa.mycloudgallery.core.model.FaceRef>(assignments.size * 2)
    val counts = HashMap<PersonId, Int>()
    for (assignment in assignments) {
      ensure(faces.add(assignment.face), RejectReason.INVALID_FIELD, "istantanea: volto assegnato due volte")
      ensure(assignment.person in byId, RejectReason.INVALID_FIELD, "istantanea: assegnazione a una persona che non c'e'")
      if (assignment.origin != ClusterOrigin.IGNORED) counts.merge(assignment.person, 1, Int::plus)
    }
    for (person in persons) {
      ensure(person.changedSeq <= mark.seq, RejectReason.INVALID_FIELD, "istantanea: persona cambiata dopo l'istantanea")
      ensure(person.aliasOf == null || person.aliasOf in byId, RejectReason.INVALID_FIELD, "istantanea: alias verso una persona che non c'e'")
      ensure(person.faces == (counts[person.id] ?: 0), RejectReason.INVALID_FIELD, "istantanea: volti della persona diversi dalle assegnazioni")
    }
    FacesSnapshotDoc(mark, persons.sortedBy { it.id }, assignments.sortedBy { it.face })
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
