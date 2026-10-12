package dev.pampa.mycloudgallery.core.trust.registry

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.codec.JsonValue
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.violation

/**
 * Lo stato di un dispositivo nel registro (`docs/formato-nas.md` §4.4). Non si torna mai ad
 * [APPROVED]: un dispositivo ritrovato o reinstallato ha una chiave nuova e quindi un id nuovo (E-S2, E-O7).
 */
enum class DeviceStatus(val wire: String) {
  /** I suoi dati sono accettati. */
  APPROVED("approved"),

  /** Dismesso in buono stato: restano validi solo i dati congelati (`frozenHeads`). */
  RETIRED("retired"),

  /** Revocato: nessun suo dato e' accettato, qualunque data dichiari. */
  REVOKED("revoked"),

  /** Perso: come revocato, e in piu' il dispositivo stesso, se si ricollega, si azzera (Fase 2.4). */
  LOST("lost"),
  ;

  fun canBecome(next: DeviceStatus): Boolean = when (this) {
    APPROVED -> next == RETIRED || next == REVOKED || next == LOST
    RETIRED -> next == REVOKED || next == LOST
    REVOKED -> next == LOST
    LOST -> false
  }

  companion object {
    fun fromWire(text: String): DeviceStatus =
      entries.firstOrNull { it.wire == text } ?: violation(RejectReason.INVALID_FIELD, "stato del dispositivo sconosciuto")
  }
}

/** La configurazione di famiglia. Si estende in Fase 2.2 (orario del NAS, preset, aree). */
data class FamilyConfig(
  /** Nome o indirizzo del NAS: mai cambiato in automatico (E-R7). */
  val nasHost: String,
  /** La share dell'area comune: contiene il registro e le richieste. */
  val commonShare: String,
) {
  init {
    TextRules.hostName(nasHost)
    ShareNames.check(commonShare)
  }

  internal fun toJson(): JsonObject = jsonObject("nasHost" to nasHost.json(), "commonShare" to commonShare.json())

  internal companion object {
    fun read(f: Fields): FamilyConfig = FamilyConfig(
      nasHost = f.string("nasHost", 253),
      commonShare = f.string("commonShare", TrustLimits.MAX_SHARE_NAME_CHARS),
    )
  }
}

/** Una voce del registro. */
data class DeviceEntry(
  val id: String,
  val key: EcPublicKey,
  val name: String,
  val model: String,
  /** L'account del NAS con cui lavora: decide di chi sono i dati personali. */
  val nasUser: String,
  /** Dispositivo admin (tiene la chiave di famiglia). Informativo: il potere vero e' la chiave. */
  val admin: Boolean,
  val status: DeviceStatus,
  /** Ora del NAS dell'ultimo cambio di stato. */
  val since: Long,
  /** Solo per [DeviceStatus.RETIRED]: impronte dei file "testa" che restano validi. In ordine crescente. */
  val frozenHeads: List<String> = emptyList(),
) {
  init {
    ensure(TrustIds.deviceIdOf(key) == id, RejectReason.INVALID_FIELD, "registro: id e chiave non corrispondono")
    TextRules.displayText(name, TrustLimits.MAX_DISPLAY_TEXT_CHARS)
    TextRules.displayText(model, TrustLimits.MAX_DISPLAY_TEXT_CHARS)
    TextRules.nasAccount(nasUser)
    ensure(status == DeviceStatus.RETIRED || frozenHeads.isEmpty(), RejectReason.INVALID_FIELD, "registro: dati congelati fuori posto")
    ensure(frozenHeads.size <= TrustLimits.MAX_FROZEN_HEADS, RejectReason.TOO_LARGE, "registro: troppi dati congelati")
    ensure(frozenHeads.all(Hashes::isHashName), RejectReason.INVALID_FIELD, "registro: impronta non valida")
    ensure(frozenHeads.zipWithNext().all { (a, b) -> a < b }, RejectReason.INVALID_FIELD, "registro: impronte non ordinate")
  }

  internal fun toJson(): JsonObject = jsonObject(
    "id" to id.json(),
    "key" to key.toText().json(),
    "name" to name.json(),
    "model" to model.json(),
    "nasUser" to nasUser.json(),
    "admin" to admin.json(),
    "status" to status.wire.json(),
    "since" to since.json(),
    "frozenHeads" to frozenHeads.takeIf { it.isNotEmpty() }?.map { it.json() }?.json(),
  )

  internal companion object {
    fun read(value: JsonValue): DeviceEntry = Fields.read(value) { f ->
      DeviceEntry(
        id = f.string("id", TrustIds.LENGTH),
        key = EcPublicKey.fromText(f.string("key", Base32.encodedLength(EcPublicKey.SPKI_BYTES))),
        name = f.string("name", TrustLimits.MAX_DISPLAY_TEXT_CHARS),
        model = f.string("model", TrustLimits.MAX_DISPLAY_TEXT_CHARS),
        nasUser = f.string("nasUser", 64),
        admin = f.bool("admin"),
        status = DeviceStatus.fromWire(f.string("status", 16)),
        since = f.time("since"),
        frozenHeads = f.optArray("frozenHeads", TrustLimits.MAX_FROZEN_HEADS)
          ?.map { Fields.asString(it, 64) }
          ?.also { ensure(it.isNotEmpty(), RejectReason.NOT_CANONICAL, "registro: elenco vuoto") }
          ?: emptyList(),
      )
    }
  }
}

/**
 * Il registro di famiglia (`docs/formato-nas.md` §4.3): configurazione + dispositivi, con un contatore
 * [version] che cresce a ogni modifica. Firmato dalla chiave di famiglia; [successorKey] annuncia la
 * rotazione della chiave (le versioni successive sono firmate da quella).
 */
data class FamilyRegistry(
  val familyId: String,
  val version: Long,
  val issuedAt: Long,
  val config: FamilyConfig,
  /** In ordine crescente di id, senza doppioni. */
  val devices: List<DeviceEntry>,
  val successorKey: EcPublicKey? = null,
) {
  init {
    ensure(TrustIds.isValid(familyId), RejectReason.INVALID_FIELD, "registro: id di famiglia")
    ensure(version >= 1, RejectReason.INVALID_FIELD, "registro: versione")
    ensure(devices.size <= TrustLimits.MAX_DEVICES, RejectReason.TOO_LARGE, "registro: troppi dispositivi")
    ensure(devices.zipWithNext().all { (a, b) -> a.id < b.id }, RejectReason.INVALID_FIELD, "registro: dispositivi non ordinati o ripetuti")
  }

  fun device(id: String): DeviceEntry? = devices.firstOrNull { it.id == id }

  fun deviceByKey(key: EcPublicKey): DeviceEntry? = devices.firstOrNull { it.key == key }

  internal fun toJson(): JsonObject = jsonObject(
    "family" to familyId.json(),
    "version" to version.json(),
    "issuedAt" to issuedAt.json(),
    "config" to config.toJson(),
    "devices" to devices.map { it.toJson() }.json(),
    "successorKey" to successorKey?.toText()?.json(),
  )

  internal companion object {
    fun read(value: JsonValue): FamilyRegistry = Fields.read(value) { f ->
      FamilyRegistry(
        familyId = f.string("family", TrustIds.LENGTH),
        version = f.long("version", 1..Long.MAX_VALUE),
        issuedAt = f.time("issuedAt"),
        config = f.obj("config") { FamilyConfig.read(it) },
        devices = f.array("devices", TrustLimits.MAX_DEVICES).map { DeviceEntry.read(it) },
        successorKey = f.optString("successorKey", Base32.encodedLength(EcPublicKey.SPKI_BYTES))?.let { EcPublicKey.fromText(it) },
      )
    }
  }
}

/** Il nome di una share: un componente di percorso valido, al massimo 80 caratteri (limite di Windows). */
object ShareNames {
  fun check(name: String): String {
    ensure(name.length <= TrustLimits.MAX_SHARE_NAME_CHARS, RejectReason.INVALID_FIELD, "share: nome troppo lungo")
    SharePath.of(name)
    return name
  }
}
