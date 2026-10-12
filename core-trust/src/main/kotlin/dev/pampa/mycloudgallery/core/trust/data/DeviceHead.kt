package dev.pampa.mycloudgallery.core.trust.data

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
import dev.pampa.mycloudgallery.core.trust.registry.DeviceTrust
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.ShareNames
import dev.pampa.mycloudgallery.core.trust.verdictOf
import dev.pampa.mycloudgallery.core.trust.violation

/** Che cosa descrive una testa: l'indice della share o i dati personali dell'account (solo area privata). */
enum class HeadKind(val wire: String) {
  INDEX("index"),
  PERSONAL("personal"),
}

/** Un segmento elencato da una testa: il nome del file e' la sua impronta. */
data class SegmentRef(val hash: String, val size: Long) {
  init {
    ensure(Hashes.isHashName(hash), RejectReason.INVALID_FIELD, "segmento: impronta non valida")
    ensure(size in 1..TrustLimits.MAX_SEGMENT_BYTES.toLong(), RejectReason.TOO_LARGE, "segmento: dimensione")
  }
}

/**
 * La testa dei dati di un dispositivo in una share (`docs/formato-nas.md` §5.4): un file firmato con un
 * numero di versione che cresce, che elenca i segmenti validi con impronta e dimensione. I segmenti non
 * sono firmati uno per uno: li autentica l'impronta nella testa firmata.
 */
data class DeviceHead(
  val familyId: String,
  val deviceId: String,
  val share: String,
  val kind: HeadKind,
  val version: Long,
  /** La versione del registro che il dispositivo conosceva: chi legge scopre se il suo e' indietro. */
  val registryVersion: Long,
  val createdAt: Long,
  /** In ordine crescente di impronta, senza doppioni. */
  val segments: List<SegmentRef>,
) {
  init {
    ensure(TrustIds.isValid(familyId) && TrustIds.isValid(deviceId), RejectReason.INVALID_FIELD, "testa: id")
    ShareNames.check(share)
    ensure(version in 1..TrustLimits.MAX_VERSION && registryVersion in 1..TrustLimits.MAX_VERSION, RejectReason.INVALID_FIELD, "testa: versione")
    ensure(segments.size <= TrustLimits.MAX_HEAD_SEGMENTS, RejectReason.TOO_LARGE, "testa: troppi segmenti")
    ensure(segments.zipWithNext().all { (a, b) -> a.hash < b.hash }, RejectReason.INVALID_FIELD, "testa: segmenti non ordinati o ripetuti")
  }

  fun sign(signer: Signer): ByteArray {
    require(TrustIds.deviceIdOf(signer.publicKey) == deviceId) { "La testa va firmata dal suo dispositivo." }
    val payload = jsonObject(
      "family" to familyId.json(),
      "device" to deviceId.json(),
      "share" to share.json(),
      "kind" to kind.wire.json(),
      "version" to version.json(),
      "registryVersion" to registryVersion.json(),
      "createdAt" to createdAt.json(),
      "segments" to segments.map { jsonObject("hash" to it.hash.json(), "size" to it.size.json()) }.json(),
    )
    return Envelope.seal(DocType.DEVICE_HEAD, payload, signer)
  }
}

/** Dove si e' trovata una testa: deve coincidere con cio' che dichiara. */
data class HeadLocation(val share: String, val deviceId: String, val kind: HeadKind, val fileVersion: Long)

/**
 * L'ultima testa accettata di un dispositivo in una share: la memoria dell'anti-rollback (in locale).
 * [contentHash] e' l'impronta dei byte firmati, non della firma (revisione 1.4, A3).
 */
data class HeadMark(val version: Long, val contentHash: String)

class AcceptedHead(
  val head: DeviceHead,
  val mark: HeadMark,
  val signer: DeviceEntry,
  /** La testa cita un registro piu' nuovo del nostro: va riletto (e se non si trova, e' un'anomalia). */
  val registryBehind: Boolean,
)

object HeadVerifier {

  /**
   * Accetta una testa letta in [location]. [previous] e' l'ultima accettata per lo stesso dispositivo,
   * share e tipo (null la prima volta). [readerAccount] e' l'account del NAS di chi legge: i dati personali
   * valgono solo se firmati da un dispositivo dello stesso account e mai nell'area comune.
   */
  fun accept(
    bytes: ByteArray,
    registry: RegistryState,
    location: HeadLocation,
    previous: HeadMark?,
    readerAccount: String,
  ): Verdict<AcceptedHead> = verdictOf {
    val trust = DeviceTrust(registry)
    val signed = trust.open(bytes, DocType.DEVICE_HEAD)
    val head = Fields.read(signed.envelope.payloadJson()) { f ->
      trust.checkCommon(f, signed)
      DeviceHead(
        familyId = registry.registry.familyId,
        deviceId = signed.signer.id,
        share = f.string("share", TrustLimits.MAX_SHARE_NAME_CHARS),
        kind = f.string("kind", TrustLimits.MAX_ENUM_CHARS).let { wire -> HeadKind.entries.firstOrNull { it.wire == wire } }
          ?: violation(RejectReason.INVALID_FIELD, "testa: tipo"),
        version = f.long("version", 1..TrustLimits.MAX_VERSION),
        registryVersion = f.long("registryVersion", 1..TrustLimits.MAX_VERSION),
        createdAt = f.time("createdAt"),
        segments = f.array("segments", TrustLimits.MAX_HEAD_SEGMENTS).map { item ->
          Fields.read(item) { s -> SegmentRef(s.string("hash", Hashes.NAME_LENGTH), s.long("size", 1..TrustLimits.MAX_SEGMENT_BYTES.toLong())) }
        },
      )
    }
    ensure(
      head.share == location.share && head.deviceId == location.deviceId && head.kind == location.kind && head.version == location.fileVersion,
      RejectReason.WRONG_LOCATION,
      "testa: non corrisponde al posto in cui si trova",
    )
    if (head.kind == HeadKind.PERSONAL) {
      ensure(TextRules.sameAccount(signed.signer.nasUser, readerAccount), RejectReason.NOT_OWNER, "dati personali di un altro account")
      ensure(!head.share.equals(registry.registry.config.commonShare, ignoreCase = true), RejectReason.WRONG_LOCATION, "dati personali nell'area comune")
    }
    if (previous != null) {
      if (head.version < previous.version) violation(RejectReason.ROLLBACK, "testa: versione piu' vecchia")
      if (head.version == previous.version && signed.envelope.contentHash != previous.contentHash) {
        violation(RejectReason.EQUIVOCATION, "testa: due contenuti con la stessa versione")
      }
    }
    AcceptedHead(
      head = head,
      mark = HeadMark(head.version, signed.envelope.contentHash),
      signer = signed.signer,
      registryBehind = head.registryVersion > registry.registry.version,
    )
  }
}

/**
 * I segmenti (`docs/formato-nas.md` §5.5): contenitori `MCGB` + versione + contenuto (definito in Fase
 * 4.1). Mai un'estensione da media, mai byte di un'immagine all'inizio del file (E-I13).
 */
object Segments {
  private val MAGIC = "MCGB".toByteArray(Charsets.US_ASCII)

  /** Avvolge un contenuto nel contenitore e restituisce byte e riferimento da mettere nella testa. */
  fun wrap(content: ByteArray): Pair<ByteArray, SegmentRef> {
    val bytes = MAGIC + byteArrayOf(TrustFormat.CURRENT_VERSION.toByte()) + content
    return bytes to SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong())
  }

  /** Verifica un segmento contro il riferimento di una testa ACCETTATA e restituisce il contenuto. */
  fun unwrap(bytes: ByteArray, ref: SegmentRef): Verdict<ByteArray> = verdictOf {
    ensure(bytes.size.toLong() == ref.size, RejectReason.HASH_MISMATCH, "segmento: dimensione diversa")
    ensure(Hashes.sha256Name(bytes) == ref.hash, RejectReason.HASH_MISMATCH, "segmento: impronta diversa")
    ensure(bytes.size > MAGIC.size && bytes.copyOf(MAGIC.size).contentEquals(MAGIC), RejectReason.MALFORMED, "segmento: contenitore")
    val version = bytes[MAGIC.size].toInt() and 0xff
    if (version > TrustFormat.CURRENT_VERSION) violation(RejectReason.NEWER_FORMAT, "segmento: formato piu' nuovo")
    ensure(TrustFormat.isReadable(version), RejectReason.MALFORMED, "segmento: versione")
    bytes.copyOfRange(MAGIC.size + 1, bytes.size)
  }
}
