package dev.pampa.mycloudgallery.core.trust.envelope

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.EcdsaDer
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustAlgorithms
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.violation
import java.nio.ByteBuffer

/** I tipi di file firmati (`docs/formato-nas.md` §5). Il codice e' nel sesto byte e fa parte della firma. */
enum class DocType(val code: Int, val maxPayload: Int) {
  /** Registro di famiglia: configurazione + dispositivi. Firmato dalla chiave di famiglia. */
  FAMILY_REGISTRY(1, TrustLimits.MAX_REGISTRY_PAYLOAD),

  /** Richiesta di registrazione di un dispositivo nuovo. Firmata dalla sua stessa chiave. */
  REGISTRATION_REQUEST(2, TrustLimits.MAX_REQUEST_PAYLOAD),

  /** Testa dei dati di un dispositivo in una share (indice o dati personali). */
  DEVICE_HEAD(3, TrustLimits.MAX_HEAD_PAYLOAD),

  /** Prenotazione di un lavoro. */
  LEASE(4, TrustLimits.MAX_LEASE_PAYLOAD),

  /** Record di un file messo nel cestino del NAS. */
  TRASH_RECORD(5, TrustLimits.MAX_TRASH_RECORD_PAYLOAD),

  /** Testa di un album condiviso (contenuto definito in Fase 7). */
  SHARED_ALBUM_HEAD(6, TrustLimits.MAX_ALBUM_HEAD_PAYLOAD),

  /** Segnalazione "dispositivo perso" da un altro dispositivo dello stesso account (Fase 2.4). */
  LOST_REPORT(7, TrustLimits.MAX_LOST_REPORT_PAYLOAD),

  /** Lo stato che un dispositivo approvato pubblica di se' (versione dell'app, ruoli, ultima attivita'). */
  DEVICE_STATUS(8, TrustLimits.MAX_DEVICE_STATUS_PAYLOAD),
  ;

  /**
   * Il tetto di un file firmato di questo tipo (busta + contenuto + firma): quanto leggere al massimo dal NAS prima di
   * verificarlo. Aggiunta della Fase 4B (P12, pulizia dei propri file): la busta resta interna.
   */
  val maxFileBytes: Int get() = Envelope.maxFileBytes(this)

  companion object {
    fun fromCode(code: Int): DocType? = entries.firstOrNull { it.code == code }
  }
}

/** Un file firmato con struttura e firma verificate. CHI ha firmato va ancora giudicato dal chiamante. */
internal class SignedEnvelope(
  val type: DocType,
  val signer: EcPublicKey,
  val payload: ByteArray,
  /**
   * La versione del formato nel quinto byte (firmata). Fase 6: la 2 solo per una testa di tipo `ai`; chi interpreta il contenuto
   * controlla che versione e tipo vadano insieme (`HeadVerifier`).
   */
  val version: Int,
  /**
   * SHA-256 (base32) dei soli byte FIRMATI (intestazione + contenuto, senza la firma): l'identita' di
   * questa versione. Non dipende dai byte della firma, che non sono firmati (revisione 1.4, A3): due firme
   * valide dello stesso contenuto sono lo stesso dato.
   */
  val contentHash: String,
) {
  /** Il payload come JSON canonico; da chiamare solo DOPO aver deciso che il firmatario e' fidato. */
  fun payloadJson(): JsonObject {
    val value = CanonicalJson.parseCanonical(payload)
    return value as? JsonObject ?: violation(RejectReason.MALFORMED, "payload: oggetto atteso")
  }
}

/**
 * La busta binaria di ogni file firmato (`docs/formato-nas.md` §5.1):
 *
 * ```
 * 0    4   "MCGS"
 * 4    1   versione del formato
 * 5    1   tipo (DocType)
 * 6    91  chiave pubblica del firmatario (SPKI P-256)
 * 97   4   lunghezza del payload N (big endian)
 * 101  N   payload: JSON canonico UTF-8
 * 101+N 1  lunghezza della firma S (8..72)
 * 102+N S  firma ECDSA P-256/SHA-256 in DER
 * ```
 *
 * Si firma `DOMINIO || byte[0, 101+N)`. La firma e' in forma canonica (DER minimo, `s` basso). La lettura
 * controlla dimensioni, versione, tipo e firma PRIMA di interpretare il payload, e non alloca mai in base a
 * lunghezze dichiarate dal file. L'identita' di un file e' l'impronta dei byte firmati ([SignedEnvelope.contentHash]).
 */
internal object Envelope {

  private val MAGIC = "MCGS".toByteArray(Charsets.US_ASCII)
  private val DOMAIN = "mycloudgallery/signed\u0000".toByteArray(Charsets.US_ASCII)
  const val HEADER_BYTES = 4 + 1 + 1 + EcPublicKey.SPKI_BYTES + 4
  private const val MIN_SIGNATURE_BYTES = 8

  fun maxFileBytes(type: DocType): Int = HEADER_BYTES + type.maxPayload + 1 + TrustAlgorithms.MAX_SIGNATURE_BYTES

  /**
   * La versione piu' nuova ammessa per [type]: la [TrustFormat.AI_VERSION] solo per le teste (tipo 3, perche' una testa `ai` e'
   * una testa), la [TrustFormat.ALBUM_VERSION] solo per le teste d'album (tipo 6, Fase 7, A6), la [TrustFormat.CURRENT_VERSION] per
   * tutti gli altri tipi (Fase 6, A7: la versione 2 esiste solo per i file `ai`).
   */
  fun newestVersion(type: DocType): Int = when (type) {
    DocType.DEVICE_HEAD -> TrustFormat.AI_VERSION
    DocType.SHARED_ALBUM_HEAD -> TrustFormat.ALBUM_VERSION
    else -> TrustFormat.CURRENT_VERSION
  }

  fun seal(type: DocType, payload: JsonObject, signer: Signer, version: Int = TrustFormat.CURRENT_VERSION): ByteArray {
    require(
      version == TrustFormat.CURRENT_VERSION ||
        (version == TrustFormat.AI_VERSION && type == DocType.DEVICE_HEAD) ||
        (version == TrustFormat.ALBUM_VERSION && type == DocType.SHARED_ALBUM_HEAD),
    ) {
      "Versione del formato non ammessa per questo tipo."
    }
    val body = CanonicalJson.encode(payload)
    require(body.size <= type.maxPayload) { "Payload oltre il tetto del tipo." }
    val signed = ByteBuffer.allocate(HEADER_BYTES + body.size)
      .put(MAGIC)
      .put(version.toByte())
      .put(type.code.toByte())
      .put(signer.publicKey.encoded)
      .putInt(body.size)
      .put(body)
      .array()
    // Forma canonica anche se il Signer non la garantisce (s basso, DER minimo).
    val signature = EcdsaDer.normalize(signer.sign(DOMAIN + signed))
    require(signature.size in MIN_SIGNATURE_BYTES..TrustAlgorithms.MAX_SIGNATURE_BYTES) { "Firma di lunghezza inattesa." }
    return signed + byteArrayOf(signature.size.toByte()) + signature
  }

  /** Lancia [dev.pampa.mycloudgallery.core.trust.TrustViolation]. */
  fun open(input: ByteArray, expected: DocType): SignedEnvelope {
    ensure(input.size <= maxFileBytes(expected), RejectReason.TOO_LARGE, "busta: file troppo grande")
    // Una copia privata: verifica e interpretazione lavorano sugli stessi byte anche se il chiamante
    // modifica il suo array nel frattempo.
    val bytes = input.copyOf()
    ensure(bytes.size <= maxFileBytes(expected), RejectReason.TOO_LARGE, "busta: file troppo grande")
    ensure(bytes.size >= HEADER_BYTES + 1 + 1 + MIN_SIGNATURE_BYTES, RejectReason.MALFORMED, "busta: troppo corta")
    for (i in MAGIC.indices) ensure(bytes[i] == MAGIC[i], RejectReason.MALFORMED, "busta: firma del formato")
    val version = bytes[4].toInt() and 0xff
    if (version > newestVersion(expected)) violation(RejectReason.NEWER_FORMAT, "busta: formato piu' nuovo")
    ensure(version >= TrustFormat.OLDEST_READABLE_VERSION, RejectReason.MALFORMED, "busta: versione")
    val type = DocType.fromCode(bytes[5].toInt() and 0xff) ?: violation(RejectReason.MALFORMED, "busta: tipo sconosciuto")
    ensure(type == expected, RejectReason.WRONG_TYPE, "busta: tipo inatteso")
    val signer = EcPublicKey.fromSpki(bytes.copyOfRange(6, 6 + EcPublicKey.SPKI_BYTES))
    val payloadLength = ByteBuffer.wrap(bytes, 6 + EcPublicKey.SPKI_BYTES, 4).int
    ensure(payloadLength in 1..type.maxPayload, RejectReason.TOO_LARGE, "busta: payload fuori tetto")
    val signatureLengthAt = HEADER_BYTES + payloadLength
    ensure(signatureLengthAt < bytes.size, RejectReason.MALFORMED, "busta: troncata")
    val signatureLength = bytes[signatureLengthAt].toInt() and 0xff
    ensure(signatureLength in MIN_SIGNATURE_BYTES..TrustAlgorithms.MAX_SIGNATURE_BYTES, RejectReason.MALFORMED, "busta: firma")
    ensure(bytes.size == signatureLengthAt + 1 + signatureLength, RejectReason.MALFORMED, "busta: lunghezza totale")
    val message = DOMAIN + bytes.copyOfRange(0, signatureLengthAt)
    val signature = bytes.copyOfRange(signatureLengthAt + 1, bytes.size)
    // Solo la forma canonica (EcdsaDer): la variante s → n − s di una firma valida e' rifiutata.
    ensure(signer.verify(message, signature), RejectReason.BAD_SIGNATURE, "busta: firma non valida")
    return SignedEnvelope(
      type = type,
      signer = signer,
      payload = bytes.copyOfRange(HEADER_BYTES, signatureLengthAt),
      version = version,
      contentHash = Hashes.sha256Name(bytes.copyOfRange(0, signatureLengthAt)),
    )
  }
}
