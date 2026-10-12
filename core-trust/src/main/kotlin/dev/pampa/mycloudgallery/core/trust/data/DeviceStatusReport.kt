package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.registry.DeviceEntry
import dev.pampa.mycloudgallery.core.trust.registry.DeviceTrust
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RejectionTally
import dev.pampa.mycloudgallery.core.trust.verdictOf
import dev.pampa.mycloudgallery.core.trust.violation

/** I ruoli di lavoro di un dispositivo (passo 7), come si scrivono nello stato. */
enum class DeviceRole(val wire: String) {
  INDEXER("indexer"),
  PREPARER("preparer"),
  ;

  companion object {
    fun fromWire(text: String): DeviceRole =
      entries.firstOrNull { it.wire == text } ?: violation(RejectReason.INVALID_FIELD, "stato: ruolo sconosciuto")
  }
}

/**
 * Lo stato di un dispositivo (tipo 8, `docs/formato-nas.md` §5.10): cio' che l'elenco "Dispositivi" mostra
 * oltre al registro (L11). Lo pubblica il dispositivo stesso, firmato con la sua chiave, in
 * `devices/<id>/status/<versione>` dell'area comune, **al massimo una volta al giorno** approfittando di una
 * sessione gia' aperta ([TrustLimits.DEVICE_STATUS_INTERVAL_MILLIS]). E' solo informativo: non da' potere a
 * nessuno e non decide nulla.
 *
 * @property lastSeenAt l'ultima attivita', ora del NAS.
 * @property registryVersion la versione del registro che il dispositivo conosceva: chi legge scopre se il suo
 *   e' indietro (come per le teste, §5.5).
 */
data class DeviceStatusReport(
  val familyId: String,
  val deviceId: String,
  val version: Long,
  val appVersion: String,
  val roles: Set<DeviceRole>,
  val lastSeenAt: Long,
  val registryVersion: Long,
) {
  init {
    ensure(TrustIds.isValid(familyId) && TrustIds.isValid(deviceId), RejectReason.INVALID_FIELD, "stato: id")
    ensure(version in 1..TrustLimits.MAX_VERSION && registryVersion in 1..TrustLimits.MAX_VERSION, RejectReason.INVALID_FIELD, "stato: versione")
    AppVersions.check(appVersion)
    ensure(
      lastSeenAt in TrustLimits.MIN_PLAUSIBLE_TIME_MILLIS..TrustLimits.MAX_PLAUSIBLE_TIME_MILLIS,
      RejectReason.INVALID_FIELD,
      "stato: ora non plausibile",
    )
  }

  fun sign(signer: Signer): ByteArray {
    require(TrustIds.deviceIdOf(signer.publicKey) == deviceId) { "Lo stato va firmato dal suo dispositivo." }
    return Envelope.seal(
      DocType.DEVICE_STATUS,
      jsonObject(
        "family" to familyId.json(),
        "device" to deviceId.json(),
        "version" to version.json(),
        "appVersion" to appVersion.json(),
        "roles" to roles.map { it.wire }.sorted().map { it.json() }.json(),
        "lastSeenAt" to lastSeenAt.json(),
        "registryVersion" to registryVersion.json(),
      ),
      signer,
    )
  }
}

/** La versione dell'app come la scrive lo stato: ASCII `[0-9A-Za-z][0-9A-Za-z.+_-]*`, al massimo 32 caratteri. */
object AppVersions {
  private val FORM = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,${TrustLimits.MAX_APP_VERSION_CHARS - 1}}")

  fun check(text: String): String {
    if (!FORM.matches(text)) violation(RejectReason.INVALID_FIELD, "stato: versione dell'app non ammessa")
    return text
  }
}

/**
 * Uno stato accettato. [mark] e' la memoria dell'anti-rollback (come per le teste, [HeadMark]); [registryBehind]
 * se il dispositivo conosceva un registro piu' nuovo del nostro.
 */
class AcceptedStatus(val report: DeviceStatusReport, val mark: HeadMark, val signer: DeviceEntry, val registryBehind: Boolean)

/** L'ultimo stato valido di un dispositivo trovato sul NAS (null se nessuno nuovo), con i rifiuti. */
class StatusScan(val latest: AcceptedStatus?, val rejected: RejectionTally)

object DeviceStatusVerifier {

  /**
   * Accetta uno stato letto in `devices/<deviceId>/status/<fileVersion>`: firmato da un dispositivo
   * **approvato** (`DeviceTrust`: in attesa, dismesso, revocato o perso → rifiuto, E-S14), famiglia e
   * dispositivo giusti, versione uguale al nome del file, ora non nel futuro oltre lo scarto, ruoli in ordine e
   * senza doppioni. **Anti-rollback** su [previous] (l'ultimo accettato per quel dispositivo): versione piu'
   * bassa → [RejectReason.ROLLBACK], stessa versione con contenuto firmato diverso → [RejectReason.EQUIVOCATION].
   */
  fun accept(
    bytes: ByteArray,
    registry: RegistryState,
    deviceId: String,
    fileVersion: Long,
    previous: HeadMark?,
    nasNow: Long,
  ): Verdict<AcceptedStatus> = verdictOf {
    val trust = DeviceTrust(registry)
    val signed = trust.open(bytes, DocType.DEVICE_STATUS)
    val report = Fields.read(signed.envelope.payloadJson()) { f ->
      trust.checkCommon(f, signed)
      val roles = f.array("roles", DeviceRole.entries.size).map { Fields.asString(it, TrustLimits.MAX_ENUM_CHARS) }
      ensure(roles.zipWithNext().all { (a, b) -> a < b }, RejectReason.NOT_CANONICAL, "stato: ruoli non ordinati o ripetuti")
      DeviceStatusReport(
        familyId = registry.registry.familyId,
        deviceId = signed.signer.id,
        version = f.long("version", 1..TrustLimits.MAX_VERSION),
        appVersion = f.string("appVersion", TrustLimits.MAX_APP_VERSION_CHARS),
        roles = roles.map(DeviceRole::fromWire).toSet(),
        lastSeenAt = f.time("lastSeenAt"),
        registryVersion = f.long("registryVersion", 1..TrustLimits.MAX_VERSION),
      )
    }
    ensure(report.deviceId == deviceId && report.version == fileVersion, RejectReason.WRONG_LOCATION, "stato: non corrisponde al posto")
    ensure(report.lastSeenAt <= nasNow + TrustLimits.MAX_FUTURE_SKEW_MILLIS, RejectReason.INVALID_FIELD, "stato: ora nel futuro")
    if (previous != null) {
      if (report.version < previous.version) violation(RejectReason.ROLLBACK, "stato: versione piu' vecchia")
      if (report.version == previous.version && signed.envelope.contentHash != previous.contentHash) {
        violation(RejectReason.EQUIVOCATION, "stato: due contenuti con la stessa versione")
      }
    }
    AcceptedStatus(
      report = report,
      mark = HeadMark(report.version, signed.envelope.contentHash),
      signer = signed.signer,
      registryBehind = report.registryVersion > registry.registry.version,
    )
  }
}

/**
 * La serie `devices/<id>/status/` di un dispositivo. Lo stato si pubblica ogni giorno, quindi la serie
 * cresce: chi legge non la percorre da 1 ma cerca **per nome** l'ultimo nome occupato (prima a salti
 * doppi, poi per bisezione: poche letture anche dopo anni) partendo dall'ultimo stato accettato, e prende il
 * piu' recente valido. Un buco o un nome piantato lontano da chi ha accesso SMB fa vedere uno stato vecchio o
 * nessuno: solo informazione persa (L11), mai potere.
 */
object DeviceStatusSeries {

  private const val MAX_PROBES = 64

  /**
   * L'ultimo nome occupato a partire da [from]: si guarda [from] e il successivo (un nome cancellato non
   * ferma la ricerca), poi salti doppi e bisezione. `from - 1` se sono liberi entrambi.
   */
  fun lastOccupied(share: NasFileSystem, deviceId: String, from: Long): Long {
    val maxBytes = Envelope.maxFileBytes(DocType.DEVICE_STATUS)
    fun occupied(version: Long): Boolean =
      version in 1..TrustLimits.MAX_VERSION && share.read(NasLayout.statusFile(deviceId, version), maxBytes) !is NasRead.NotFound
    var low = when {
      occupied(from) -> from
      occupied(from + 1) -> from + 1
      else -> return from - 1
    }
    var step = 1L
    var high = low + step // primo candidato libero
    var probes = 0
    while (occupied(high) && probes < MAX_PROBES) {
      low = high
      step *= 2
      high = minOf(low + step, TrustLimits.MAX_VERSION + 1)
      probes++
    }
    while (high - low > 1 && probes < MAX_PROBES * 2) {
      val mid = low + (high - low) / 2
      if (occupied(mid)) low = mid else high = mid
      probes++
    }
    return low
  }

  /**
   * L'ultimo stato valido di [deviceId] piu' nuovo di [previous] (o uguale con lo stesso contenuto). Se l'ultimo
   * nome occupato e' spazzatura si torna indietro di al massimo [TrustLimits.MAX_JUNK_RUN] nomi.
   */
  fun readLatest(share: NasFileSystem, registry: RegistryState, deviceId: String, previous: HeadMark?, nasNow: Long): StatusScan {
    val tally = RejectionTally()
    if (!TrustIds.isValid(deviceId)) return StatusScan(null, tally)
    val start = previous?.version ?: 1L
    val last = lastOccupied(share, deviceId, start)
    val maxBytes = Envelope.maxFileBytes(DocType.DEVICE_STATUS)
    var version = last
    var tries = 0
    while (version >= start && tries <= TrustLimits.MAX_JUNK_RUN) {
      tries++
      when (val read = share.read(NasLayout.statusFile(deviceId, version), maxBytes)) {
        NasRead.NotFound -> Unit
        NasRead.TooLarge -> tally.add(RejectReason.TOO_LARGE)
        is NasRead.Data -> when (val verdict = DeviceStatusVerifier.accept(read.bytes, registry, deviceId, version, previous, nasNow)) {
          is Verdict.Accepted -> return StatusScan(verdict.value, tally)
          is Verdict.Rejected -> tally.add(verdict.reason)
        }
      }
      version--
    }
    return StatusScan(null, tally)
  }

  /**
   * Pubblica lo stato di questo dispositivo al primo nome libero dopo [after] (l'ultima versione propria nota):
   * [sign] riceve il numero scelto e restituisce i byte firmati. Null se non si trova posto.
   */
  fun publish(share: NasFileSystem, deviceId: String, after: Long, sign: (Long) -> ByteArray): Long? {
    var version = lastOccupied(share, deviceId, maxOf(1L, after)) + 1
    if (version <= after) version = after + 1
    repeat(TrustLimits.MAX_JUNK_RUN + 1) {
      if (version > TrustLimits.MAX_VERSION) return null
      when (share.publishExclusive(NasLayout.statusFile(deviceId, version), sign(version))) {
        PublishResult.CREATED -> return version
        PublishResult.ALREADY_EXISTS -> version++
      }
    }
    return null
  }
}
