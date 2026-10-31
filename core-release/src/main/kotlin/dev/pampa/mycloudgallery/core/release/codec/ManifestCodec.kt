package dev.pampa.mycloudgallery.core.release.codec

import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ModelHint
import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.VersionBlock
import java.time.Instant

// LA BUSTA E IL CONTENUTO DEL MANIFEST (docs/fase10-progetto.md §1.2 "Busta", D29; docs/formato-nas.md §15). P1.
//
// Busta `MCGR1` (al piu' `ManifestLimits.MAX_BYTES`, 64 KiB):
//   "MCGR1" (5 byte ASCII) ‖ lunghezza del JSON (4 byte, big-endian) ‖ JSON canonico ‖ keyId (1 byte) ‖ firma DER ECDSA P-256
// La firma copre **tutti i byte prima di lei** (intestazione, lunghezza, JSON e keyId); va fino alla fine del file, in forma canonica.
//
// Il JSON (chiavi in ordine, vedi [ReleaseJson]):
//   {"app":"dev.pampa.mycloudgallery","blocks":[{"from":5,"level":"writes","message":"security","to":8}],"expires":1735000000,
//    "issued":1730000000,"minVersionCode":3,"models":[{"id":"...","sha256":"<64 hex>","urls":["https://..."]}],"schema":1,"seq":4}
// `issued` ed `expires` in secondi dall'epoca (UTC); `minVersionCode` facoltativo; `blocks` e `models` sempre presenti (anche vuoti).
// Campi sconosciuti: si ignorano, salvo quelli elencati nel `critical` dello stesso oggetto (un array di nomi): un nome che questa versione
// non conosce rifiuta la busta intera (`CRITICAL_FIELD`). Un livello sconosciuto vale `all`, un messaggio sconosciuto `update` (catalogo
// chiuso: un blocco non si perde e non si ammorbidisce per un campo nuovo). L'ordine dei controlli e' fisso: tetto, forma della busta, chiave,
// firma (il JSON si legge **solo dopo** la firma), schema, campi `critical`, app, campi.

/** La busta aperta: niente e' ancora verificato. */
internal class OpenedEnvelope(val signed: ByteArray, val json: ByteArray, val keyId: Int, val signature: ByteArray)

object ManifestCodec {
  const val HEADER_BYTES: Int = 5 + 4

  private val MAGIC: ByteArray = ManifestLimits.MAGIC.toByteArray(Charsets.US_ASCII)

  private val KNOWN_TOP = setOf("app", "blocks", "critical", "expires", "issued", "minVersionCode", "models", "schema", "seq")
  private val KNOWN_BLOCK = setOf("critical", "from", "level", "message", "to")
  private val KNOWN_MODEL = setOf("critical", "id", "sha256", "urls")

  /** Il JSON canonico di [manifest] (i secondi interi: un `Instant` con frazioni non ha una forma canonica qui). */
  fun canonical(manifest: ReleaseManifest): ByteArray = ReleaseJson.encode(toJson(manifest))

  /** I byte da firmare: intestazione, lunghezza, [json] e [keyId]. */
  fun unsigned(json: ByteArray, keyId: Int): ByteArray {
    require(keyId in 0..255) { "keyId fuori da un byte." }
    require(json.isNotEmpty() && HEADER_BYTES + json.size + 1 + EcdsaP256.MAX_SIGNATURE_BYTES <= ManifestLimits.MAX_BYTES) { "Manifest troppo grande." }
    val out = ByteArray(HEADER_BYTES + json.size + 1)
    MAGIC.copyInto(out)
    out[5] = (json.size ushr 24).toByte()
    out[6] = (json.size ushr 16).toByte()
    out[7] = (json.size ushr 8).toByte()
    out[8] = json.size.toByte()
    json.copyInto(out, HEADER_BYTES)
    out[out.size - 1] = keyId.toByte()
    return out
  }

  /** La busta finita: i byte firmati seguiti dalla firma DER (gia' in forma canonica). */
  fun seal(unsigned: ByteArray, signature: ByteArray): ByteArray {
    require(EcdsaP256.isCanonical(signature)) { "Firma non canonica." }
    return unsigned + signature
  }

  internal fun open(bytes: ByteArray): OpenedEnvelope {
    if (bytes.size > ManifestLimits.MAX_BYTES) reject(RejectReason.TOO_LARGE, "busta oltre il tetto")
    if (bytes.size < HEADER_BYTES + 2 + 1 + EcdsaP256.MIN_SIGNATURE_BYTES) reject(RejectReason.MALFORMED, "busta troppo corta")
    for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) reject(RejectReason.MALFORMED, "intestazione")
    val length = ((bytes[5].toInt() and 0xff) shl 24) or ((bytes[6].toInt() and 0xff) shl 16) or
      ((bytes[7].toInt() and 0xff) shl 8) or (bytes[8].toInt() and 0xff)
    if (length < 2 || length > bytes.size - HEADER_BYTES - 1 - EcdsaP256.MIN_SIGNATURE_BYTES) reject(RejectReason.MALFORMED, "lunghezza")
    val keyAt = HEADER_BYTES + length
    val signature = bytes.copyOfRange(keyAt + 1, bytes.size)
    if (signature.size > EcdsaP256.MAX_SIGNATURE_BYTES) reject(RejectReason.MALFORMED, "firma troppo lunga")
    return OpenedEnvelope(
      signed = bytes.copyOfRange(0, keyAt + 1),
      json = bytes.copyOfRange(HEADER_BYTES, keyAt),
      keyId = bytes[keyAt].toInt() and 0xff,
      signature = signature,
    )
  }

  /** Il manifest dal JSON di una busta **gia' verificata**: schema, campi `critical`, app, tetti e campi. */
  internal fun decode(json: ByteArray, app: String): ReleaseManifest {
    val root = ReleaseJson.parseCanonical(json) as? JObj ?: reject(RejectReason.MALFORMED, "non e' un oggetto")
    return fromJson(root, app)
  }

  internal fun fromJson(root: JObj, app: String): ReleaseManifest {
    val schema = root.members["schema"] as? JInt ?: reject(RejectReason.MALFORMED, "schema")
    if (schema.value != ManifestLimits.SCHEMA.toLong()) reject(RejectReason.SCHEMA, "schema sconosciuto")
    critical(root, KNOWN_TOP)
    val appId = root.string("app")
    if (appId != app) reject(RejectReason.WRONG_APP, "altra app")
    val seq = root.long("seq")
    val issued = root.long("issued")
    val expires = root.long("expires")
    if (seq < 1 || issued < 0 || expires <= issued) reject(RejectReason.MALFORMED, "numeri")
    if (expires - issued > ManifestLimits.MAX_VALIDITY.seconds) reject(RejectReason.MALFORMED, "validita' oltre il tetto")
    val min = root.members["minVersionCode"]?.let { (it as? JInt)?.value ?: reject(RejectReason.MALFORMED, "minVersionCode") }
    if (min != null && min !in 1..Int.MAX_VALUE.toLong()) reject(RejectReason.MALFORMED, "minVersionCode")
    val blocks = root.array("blocks", ManifestLimits.MAX_BLOCKS).map { block(it) }
    val models = root.array("models", ManifestLimits.MAX_MODELS).map { model(it) }
    return try {
      ReleaseManifest(
        schema = ManifestLimits.SCHEMA, app = appId, seq = seq,
        issued = Instant.ofEpochSecond(issued), expires = Instant.ofEpochSecond(expires),
        minVersionCode = min?.toInt(), blocks = blocks, models = models,
      )
    } catch (e: IllegalArgumentException) {
      reject(RejectReason.MALFORMED, "manifest non valido")
    } catch (e: java.time.DateTimeException) {
      reject(RejectReason.MALFORMED, "data non valida")
    }
  }

  internal fun toJson(manifest: ReleaseManifest): JObj {
    require(manifest.issued.nano == 0 && manifest.expires.nano == 0) { "Date con frazioni di secondo." }
    val members = linkedMapOf<String, JValue>(
      "app" to JStr(manifest.app),
      "blocks" to JArr(manifest.blocks.map { b ->
        JObj(mapOf("from" to JInt(b.fromCode.toLong()), "level" to JStr(b.level.code), "message" to JStr(b.message.code), "to" to JInt(b.toCode.toLong())))
      }),
      "expires" to JInt(manifest.expires.epochSecond),
      "issued" to JInt(manifest.issued.epochSecond),
      "models" to JArr(manifest.models.map { m -> JObj(mapOf("id" to JStr(m.id), "sha256" to JStr(m.sha256), "urls" to JArr(m.urls.map(::JStr)))) }),
      "schema" to JInt(manifest.schema.toLong()),
      "seq" to JInt(manifest.seq),
    )
    manifest.minVersionCode?.let { members["minVersionCode"] = JInt(it.toLong()) }
    return JObj(members)
  }

  private fun block(value: JValue): VersionBlock {
    val obj = value as? JObj ?: reject(RejectReason.MALFORMED, "blocco")
    critical(obj, KNOWN_BLOCK)
    val from = obj.long("from")
    val to = obj.long("to")
    if (from !in 1..Int.MAX_VALUE.toLong() || to !in from..Int.MAX_VALUE.toLong()) reject(RejectReason.MALFORMED, "intervallo")
    return VersionBlock(from.toInt(), to.toInt(), BlockLevel.ofCode(obj.string("level")), ReleaseMessage.ofCode(obj.string("message")))
  }

  private fun model(value: JValue): ModelHint {
    val obj = value as? JObj ?: reject(RejectReason.MALFORMED, "modello")
    critical(obj, KNOWN_MODEL)
    val urls = obj.array("urls", ManifestLimits.MAX_URLS_PER_MODEL).map { (it as? JStr)?.value ?: reject(RejectReason.MALFORMED, "indirizzo") }
    if (urls.any { it.length > ManifestLimits.MAX_URL_CHARS }) reject(RejectReason.TOO_LARGE, "indirizzo troppo lungo")
    return try {
      ModelHint(obj.string("id"), obj.string("sha256"), urls)
    } catch (e: IllegalArgumentException) {
      reject(RejectReason.MALFORMED, "modello non valido")
    }
  }

  /** Il `critical` di [obj]: un array di nomi di campo; uno che questa versione non conosce rifiuta tutto. */
  private fun critical(obj: JObj, known: Set<String>) {
    val list = obj.members["critical"] ?: return
    val names = (list as? JArr)?.items ?: reject(RejectReason.MALFORMED, "critical")
    for (name in names) {
      val text = (name as? JStr)?.value ?: reject(RejectReason.MALFORMED, "critical")
      if (text !in known) reject(RejectReason.CRITICAL_FIELD, "campo critico sconosciuto")
    }
  }

  private fun JObj.string(key: String): String = (members[key] as? JStr)?.value ?: reject(RejectReason.MALFORMED, "campo $key")

  private fun JObj.long(key: String): Long = (members[key] as? JInt)?.value ?: reject(RejectReason.MALFORMED, "campo $key")

  private fun JObj.array(key: String, max: Int): List<JValue> {
    val items = (members[key] as? JArr)?.items ?: reject(RejectReason.MALFORMED, "campo $key")
    if (items.size > max) reject(RejectReason.TOO_LARGE, "troppi elementi in $key")
    return items
  }
}
