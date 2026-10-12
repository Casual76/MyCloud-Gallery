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
 * Lo stato di un dispositivo nel registro (`docs/formato-nas.md` §4.6). Gli stati sono in ordine di
 * severita' e si va solo avanti: non si torna mai ad [APPROVED]; un dispositivo ritrovato o reinstallato ha
 * una chiave nuova e quindi un id nuovo (E-S2, E-O7). La regola vale per chi firma E per chi verifica
 * ([FamilyRegistry.checkNoRegressionFrom]).
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

  /** Si puo' passare solo a uno stato piu' severo (relazione transitiva: vale anche saltando versioni). */
  fun canBecome(next: DeviceStatus): Boolean = next.ordinal > ordinal

  /** Revocato o perso: nessun suo dato vale, qualunque data dichiari. */
  val isBarred: Boolean get() = ordinal >= REVOKED.ordinal

  companion object {
    fun fromWire(text: String): DeviceStatus =
      entries.firstOrNull { it.wire == text } ?: violation(RejectReason.INVALID_FIELD, "stato del dispositivo sconosciuto")
  }
}

/** Preset e qualita' delle anteprime proposti ai dispositivi nuovi (passo 8): valori di [ConfigTokens]. */
data class ConfigDefaults(val preset: String, val quality: String) {
  init {
    ConfigTokens.check(preset)
    ConfigTokens.check(quality)
  }

  internal fun toJson(): JsonObject = jsonObject("preset" to preset.json(), "quality" to quality.json())

  companion object {
    val STANDARD = ConfigDefaults(preset = "balanced", quality = "standard")

    internal fun read(f: Fields): ConfigDefaults =
      ConfigDefaults(f.string("preset", TrustLimits.MAX_ENUM_CHARS), f.string("quality", TrustLimits.MAX_ENUM_CHARS))
  }
}

/**
 * La configurazione di famiglia (`docs/formato-nas.md` §5.3), firmata nel registro: nasce alla fondazione
 * (passo 12(e)) e la cambia solo l'admin. I campi facoltativi hanno un valore "assente" che nel JSON si
 * omette (liste vuote, null): ogni configurazione ha una sola scrittura.
 *
 * @property nasHost l'IP **letterale** del NAS: ci si connette sempre li', mai cambiato in automatico (E-R7).
 * @property commonShare la share dell'area comune: contiene il registro, le richieste e le segnalazioni.
 * @property nasName il nome del NAS (informativo: non si risolve mai, E-R7).
 * @property nasGuid il GUID del server SMB letto alla NEGOTIATE (D3). Le configurazioni scritte dall'app lo
 *   portano sempre; un dispositivo che lo trova diverso da quello memorizzato non manda la password.
 * @property homeUla i prefissi ULA /48 di casa ([UlaPrefix]), in ordine crescente: la "casa" (§3.2, E-R16).
 * @property nasPower l'alimentazione del NAS ([NasPowerRule]): da qui deriva la fascia notte.
 * @property wolMac il MAC per il Wake-on-LAN (dietro `FeatureFlags.wol`), se inserito.
 * @property commonExclusions le cartelle dell'area comune escluse dall'indice (passo 5), percorsi di media
 *   validati da `SharePath`, in ordine crescente.
 * @property defaults preset e qualita' proposti ai dispositivi nuovi.
 */
data class FamilyConfig(
  val nasHost: String,
  val commonShare: String,
  val nasName: String? = null,
  val nasGuid: String? = null,
  val homeUla: List<String> = emptyList(),
  val nasPower: String = NasPowerRule.ALWAYS,
  val wolMac: String? = null,
  val commonExclusions: List<String> = emptyList(),
  val defaults: ConfigDefaults = ConfigDefaults.STANDARD,
) {
  init {
    TextRules.ipLiteral(nasHost)
    ShareNames.check(commonShare)
    nasName?.let { TextRules.hostName(it) }
    nasGuid?.let { NasGuids.check(it) }
    ensure(homeUla.size <= TrustLimits.MAX_HOME_ULA, RejectReason.TOO_LARGE, "configurazione: troppi prefissi ULA")
    homeUla.forEach { UlaPrefix.check(it) }
    ensure(homeUla.zipWithNext().all { (a, b) -> a < b }, RejectReason.INVALID_FIELD, "configurazione: ULA non ordinati o ripetuti")
    NasPowerRule.check(nasPower)
    wolMac?.let { MacAddresses.check(it) }
    ensure(commonExclusions.size <= TrustLimits.MAX_COMMON_EXCLUSIONS, RejectReason.TOO_LARGE, "configurazione: troppe esclusioni")
    for (path in commonExclusions) {
      ensure(path.toByteArray(Charsets.UTF_8).size <= TrustLimits.MAX_EXCLUSION_UTF8_BYTES, RejectReason.TOO_LARGE, "configurazione: esclusione troppo lunga")
      SharePath.parseMediaPath(path)
    }
    ensure(commonExclusions.zipWithNext().all { (a, b) -> a < b }, RejectReason.INVALID_FIELD, "configurazione: esclusioni non ordinate o ripetute")
  }

  internal fun toJson(): JsonObject = jsonObject(
    "nasHost" to nasHost.json(),
    "commonShare" to commonShare.json(),
    "nasName" to nasName?.json(),
    "nasGuid" to nasGuid?.json(),
    "homeUla" to homeUla.takeIf { it.isNotEmpty() }?.map { it.json() }?.json(),
    "nasPower" to nasPower.json(),
    "wolMac" to wolMac?.json(),
    "commonExclusions" to commonExclusions.takeIf { it.isNotEmpty() }?.map { it.json() }?.json(),
    "defaults" to defaults.toJson(),
  )

  internal companion object {
    fun read(f: Fields): FamilyConfig = FamilyConfig(
      nasHost = f.string("nasHost", TrustLimits.MAX_HOST_CHARS),
      commonShare = f.string("commonShare", TrustLimits.MAX_SHARE_NAME_CHARS),
      nasName = f.optString("nasName", TrustLimits.MAX_HOST_CHARS),
      nasGuid = f.optString("nasGuid", GUID_CHARS),
      homeUla = f.optArray("homeUla", TrustLimits.MAX_HOME_ULA)
        ?.map { Fields.asString(it, ULA_CHARS) }
        ?.also { ensure(it.isNotEmpty(), RejectReason.NOT_CANONICAL, "configurazione: elenco vuoto") }
        ?: emptyList(),
      nasPower = f.string("nasPower", TrustLimits.MAX_ENUM_CHARS),
      wolMac = f.optString("wolMac", MAC_CHARS),
      commonExclusions = f.optArray("commonExclusions", TrustLimits.MAX_COMMON_EXCLUSIONS)
        ?.map { Fields.asString(it, TrustLimits.MAX_EXCLUSION_UTF8_BYTES) }
        ?.also { ensure(it.isNotEmpty(), RejectReason.NOT_CANONICAL, "configurazione: elenco vuoto") }
        ?: emptyList(),
      defaults = f.obj("defaults") { ConfigDefaults.read(it) },
    )

    private const val GUID_CHARS = 36
    private const val ULA_CHARS = 19
    private const val MAC_CHARS = 17
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
        nasUser = f.string("nasUser", TrustLimits.MAX_NAS_ACCOUNT_CHARS),
        admin = f.bool("admin"),
        status = DeviceStatus.fromWire(f.string("status", TrustLimits.MAX_ENUM_CHARS)),
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
    ensure(version in 1..TrustLimits.MAX_VERSION, RejectReason.INVALID_FIELD, "registro: versione")
    ensure(devices.size <= TrustLimits.MAX_DEVICES, RejectReason.TOO_LARGE, "registro: troppi dispositivi")
    ensure(devices.zipWithNext().all { (a, b) -> a.id < b.id }, RejectReason.INVALID_FIELD, "registro: dispositivi non ordinati o ripetuti")
  }

  fun device(id: String): DeviceEntry? = devices.firstOrNull { it.id == id }

  fun deviceByKey(key: EcPublicKey): DeviceEntry? = devices.firstOrNull { it.key == key }

  /**
   * Il registro puo' solo diventare piu' severo verso i dispositivi che [older] conosce (revisione 1.4, A1):
   * ognuno resta, con la stessa chiave e lo stesso account, con uno stato uguale o piu' severo; un
   * dispositivo che resta dismesso puo' solo perdere teste congelate; l'ora di emissione non torna
   * indietro. Dispositivi nuovi e configurazione possono cambiare. Lancia [RejectReason.REGRESSION].
   *
   * Unica eccezione (verifica della revisione, R3): l'account di un dispositivo puo' cambiare solo se diventa
   * (o e') revocato o perso. Di un dispositivo cosi' nessun dato vale piu', quindi l'account non da' potere a
   * nessuno; e senza l'eccezione un dispositivo approvato in due rami con account diversi (un dispositivo
   * compromesso che si fa approvare due volte da un admin rimasto indietro) non si potrebbe piu' revocare.
   */
  internal fun checkNoRegressionFrom(older: FamilyRegistry, checkIssuedAt: Boolean = true) {
    if (checkIssuedAt) ensure(issuedAt >= older.issuedAt, RejectReason.REGRESSION, "registro: emesso prima di quello noto")
    for (old in older.devices) {
      val now = device(old.id) ?: violation(RejectReason.REGRESSION, "registro: un dispositivo e' sparito")
      ensure(now.key == old.key, RejectReason.REGRESSION, "registro: identita' cambiata")
      ensure(
        TextRules.sameAccount(now.nasUser, old.nasUser) || now.status.isBarred,
        RejectReason.REGRESSION,
        "registro: account cambiato a un dispositivo che vale ancora",
      )
      ensure(now.status == old.status || old.status.canBecome(now.status), RejectReason.REGRESSION, "registro: stato tornato indietro")
      if (now.status == DeviceStatus.RETIRED && old.status == DeviceStatus.RETIRED) {
        ensure(old.frozenHeads.containsAll(now.frozenHeads), RejectReason.REGRESSION, "registro: teste congelate aggiunte")
      }
    }
  }

  /**
   * Vero se questo registro contiene gia' tutto cio' che dice [other] (un ramo): stessa famiglia, nessuna
   * rotazione nel ramo, e nessun dispositivo del ramo che qui manchi o sia meno severo. L'ora di emissione
   * non conta (chi pubblica la porta al massimo dei rami che scavalca). Un registro cosi' e' accettato
   * anche dai dispositivi che stanno su quel ramo (revisione 1.4, verifica N1).
   */
  internal fun dominates(other: FamilyRegistry): Boolean =
    other.familyId == familyId && other.successorKey == null &&
      runCatching { checkNoRegressionFrom(other, checkIssuedAt = false) }.isSuccess

  /**
   * L'unione di questo registro con un ramo firmato dalla famiglia: ogni dispositivo con lo stato piu'
   * severo dei due, le teste congelate in comune, la configurazione e i nomi di questo ramo. Con
   * [revokeOnlyInOther] i dispositivi che esistono SOLO nel ramo entrano come revocati: e' l'uscita per un
   * ramo che l'admin non riconosce (chiave di famiglia usata da altri) senza adottarne i dispositivi. In
   * entrambi i casi il risultato [dominates] i due rami, quindi lo accettano tutti i dispositivi.
   *
   * Un dispositivo che nei due rami ha **account diversi** (R3: lo stesso dispositivo approvato due volte,
   * con due account, da due admin) esce almeno revocato, con l'account di questo ramo e senza segno di
   * admin: e' un dispositivo sospetto, e la regola anti-regressione ammette il cambio d'account solo cosi'.
   */
  internal fun joinWith(other: FamilyRegistry, revokeOnlyInOther: Boolean = false): FamilyRegistry {
    require(other.familyId == familyId) { "Rami di famiglie diverse." }
    val ids = (devices.map { it.id } + other.devices.map { it.id }).distinct().sorted()
    val merged = ids.map { id ->
      val mine = device(id)
      val theirs = other.device(id)
      when {
        mine == null && revokeOnlyInOther -> {
          val status = if (theirs!!.status.ordinal < DeviceStatus.REVOKED.ordinal) DeviceStatus.REVOKED else theirs.status
          theirs.copy(status = status, admin = false, frozenHeads = emptyList())
        }
        mine == null -> theirs!!
        theirs == null -> mine
        else -> {
          // L'id deriva dalla chiave: stesso id vuol dire stessa chiave.
          require(mine.key == theirs.key) { "Chiavi diverse con lo stesso id." }
          val accountConflict = !TextRules.sameAccount(mine.nasUser, theirs.nasUser)
          var status = if (theirs.status.ordinal > mine.status.ordinal) theirs.status else mine.status
          if (accountConflict && !status.isBarred) status = DeviceStatus.REVOKED
          val frozen = when {
            status != DeviceStatus.RETIRED -> emptyList()
            mine.status == DeviceStatus.RETIRED && theirs.status == DeviceStatus.RETIRED -> mine.frozenHeads.filter { it in theirs.frozenHeads }
            else -> (if (mine.status == DeviceStatus.RETIRED) mine else theirs).frozenHeads
          }
          val admin = !accountConflict && (mine.admin || theirs.admin)
          mine.copy(status = status, since = maxOf(mine.since, theirs.since), admin = admin, frozenHeads = frozen)
        }
      }
    }
    return copy(devices = merged, issuedAt = maxOf(issuedAt, other.issuedAt))
  }

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
        version = f.long("version", 1..TrustLimits.MAX_VERSION),
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
