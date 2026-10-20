package dev.pampa.mycloudgallery.core.family.p3

import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.lifecycle.CredentialWiper
import dev.pampa.mycloudgallery.core.family.lifecycle.DeviceSelfWipe
import dev.pampa.mycloudgallery.core.family.lifecycle.InMemoryWipeJournal
import dev.pampa.mycloudgallery.core.family.lifecycle.KitAdminRecovery
import dev.pampa.mycloudgallery.core.family.lifecycle.LostReportWatcher
import dev.pampa.mycloudgallery.core.family.lifecycle.NasFamilyReset
import dev.pampa.mycloudgallery.core.family.lifecycle.NasLostReporting
import dev.pampa.mycloudgallery.core.family.lifecycle.WipeJournal
import dev.pampa.mycloudgallery.core.family.lifecycle.WipeRecord
import dev.pampa.mycloudgallery.core.family.lifecycle.WorkCanceller
import dev.pampa.mycloudgallery.core.family.registry.ApprovalSightings
import dev.pampa.mycloudgallery.core.family.registry.DeviceStatusBook
import dev.pampa.mycloudgallery.core.family.registry.InMemoryApprovalSightings
import dev.pampa.mycloudgallery.core.family.registry.NasRegistryService
import dev.pampa.mycloudgallery.core.family.registry.RegistryWriter
import dev.pampa.mycloudgallery.core.family.testing.FakeTrustStateStore
import dev.pampa.mycloudgallery.core.family.testing.FakeTrustSyncScheduler
import dev.pampa.mycloudgallery.core.family.testing.OperationLog
import dev.pampa.mycloudgallery.core.family.testing.RecordingLocalDataEraser
import dev.pampa.mycloudgallery.core.family.trust.DefaultTrustGuard
import dev.pampa.mycloudgallery.core.family.trust.LocalDeviceProfile
import dev.pampa.mycloudgallery.core.family.trust.TrustDiagnostics
import dev.pampa.mycloudgallery.core.family.trust.TrustEngine
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryCode
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKit
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.FamilyConfig
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor

/** 12/10/2026 12:00 UTC: l'ora "del NAS" dei test. */
const val NAS_NOON: Long = 1_791_806_400_000L
const val COMMON = "test-comune"

class MutableNasClock(var now: Long = NAS_NOON) : NasClock {
  override fun nowMillis(): Long = now

  fun advance(millis: Long) {
    now += millis
  }
}

/**
 * Chiave del dispositivo in software (stessa curva del Keystore), con gli stessi comportamenti di `AndroidKeystoreKeyProvider`:
 * `deviceSigner` crea la chiave se manca, `hasDeviceKey` non la crea mai, la distruzione di una chiave che non c'e' non fa niente.
 * [broken] e' un Keystore che non risponde (R7), come `SoftwareKeyProvider.broken` di `support`: ogni domanda lancia e niente cambia,
 * e quando torna la chiave e' la stessa di prima (`SoftwareKeysParityTest`).
 */
class SoftwareKeys : KeyProvider {
  private var key: FamilyKey? = null
  var failDestroy: Boolean = false

  /** Il Keystore non risponde: `hasDeviceKey`, `deviceSigner` e la distruzione lanciano. */
  var broken: Boolean = false

  override fun deviceSigner(): Signer {
    check(!broken) { "Keystore non disponibile" }
    return key ?: FamilyKey.generate().also { key = it }
  }

  override fun hasDeviceKey(): Boolean {
    check(!broken) { "Keystore non disponibile" }
    return key != null
  }

  override fun destroyDeviceKey() {
    check(!broken) { "Keystore non disponibile" }
    if (failDestroy) throw IllegalStateException("Keystore occupato")
    key = null
  }
}

/** La cassaforte in memoria; senza blocco schermo sicuro rifiuta come quella vera (M3). */
class MemoryFamilyKeyVault(private val log: OperationLog? = null) : FamilyKeyVault {
  var key: FamilyKey? = null
  var deviceSecure: Boolean = true

  override fun store(key: FamilyKey) {
    check(deviceSecure) { "Il dispositivo admin deve avere un blocco schermo sicuro." }
    this.key = key
  }

  override fun load(expected: EcPublicKey): FamilyKey? {
    check(deviceSecure) { "Il dispositivo admin deve avere un blocco schermo sicuro." }
    return key?.takeIf { it.publicKey == expected }
  }

  override fun destroy() {
    log?.record("vault:destroy")
    key = null
  }
}

/** Il diario dell'azzeramento che registra ogni scrittura (fase) in [log]. */
class RecordingJournal(private val log: OperationLog, private val inner: WipeJournal = InMemoryWipeJournal()) : WipeJournal {
  override suspend fun read(): WipeRecord? = inner.read()

  override suspend fun write(record: WipeRecord) {
    log.record("journal:${record.phase.name}")
    inner.write(record)
  }

  override suspend fun clear() = inner.clear()
}

/**
 * Un dispositivo (o profilo) di prova con tutte le parti di P3 collegate come le colleghera' `:app`: chiavi,
 * cassaforte, stato fidato, motore, guardia, registro, azzeramento con fake che registrano l'ordine.
 */
class Device(
  val family: FamilyWorld,
  val name: String,
  val nasUser: String,
  val privateShare: String?,
  val keys: SoftwareKeys = SoftwareKeys(),
) {
  val log = OperationLog()
  val sessions = HostileSessions(family.server, log)
  val store = FakeTrustStateStore()
  val vault = MemoryFamilyKeyVault(log)
  val journal = RecordingJournal(log)
  val eraser = RecordingLocalDataEraser(log)
  var credentialsWiped = true
  val sightings: ApprovalSightings = InMemoryApprovalSightings()
  var monotonic = 0L
  var profile = LocalDeviceProfile(nasUser, COMMON, privateShare, name, "SM-S931B", setOf(DeviceRole.INDEXER), "2.0.0")

  val selfWipe = DeviceSelfWipe(
    journal = journal,
    keys = LoggingKeys(keys, log),
    familyKeys = vault,
    credentials = CredentialWiper {
      log.record("credentials:wipeAll")
      credentialsWiped
    },
    sessions = sessions,
    trustState = store,
    eraser = eraser,
    work = WorkCanceller { log.record("work:cancelAll") },
    deviceClock = { 42L },
    localState = listOf({ sightings.clear() }),
  )
  val engine = TrustEngine(sessions, store, keys, { profile }, family.clock, selfWipe)
  val writer = RegistryWriter(engine, sessions, vault, family.clock)
  val statuses = DeviceStatusBook(engine, sessions, keys)
  val scheduler = FakeTrustSyncScheduler()
  val service = NasRegistryService(engine, sessions, writer, sightings, statuses, scheduler)
  val lostWatcher = LostReportWatcher(engine, sessions)
  val guard = DefaultTrustGuard(engine, selfWipe, lostWatcher, statuses, { monotonic })
  val lostReporting = NasLostReporting(engine, sessions, keys, writer)
  val recovery = KitAdminRecovery(engine, sessions, keys, vault, family.clock)
  val reset = NasFamilyReset(engine, sessions, keys, vault, family.clock)
  val diagnostics = TrustDiagnostics(engine, selfWipe, statuses, lostWatcher, writer)

  val id: String get() = TrustIds.deviceIdOf(keys.deviceSigner().publicKey)

  /** Il codice che il dispositivo mostra mentre aspetta (quello che l'admin legge e digita). */
  val shownCode: String get() = (engine.status.value as TrustStatus.Pending).code

  /** Il tempo monotono passa (la guardia rilegge al massimo ogni 15 minuti). */
  fun later(millis: Long) {
    monotonic += millis
  }
}

/** Le chiavi del dispositivo viste dall'azzeramento: la distruzione si registra (se riesce). */
private class LoggingKeys(private val inner: SoftwareKeys, private val log: OperationLog) : KeyProvider by inner {
  override fun destroyDeviceKey() {
    inner.destroyDeviceKey()
    log.record("keys:destroyDeviceKey")
  }
}

/**
 * Una famiglia di prova sul NAS ostile: chiave di famiglia, kit dell'admin nella sua area privata, telefono
 * dell'admin fondatore (gia' nella cassaforte e con lo stato fidato salvato, come dopo il passo 12 di P4).
 */
class FamilyWorld {
  val server = HostileNasServer()
  val clock = MutableNasClock()
  val common: HostileNas get() = server.share(COMMON)
  var familyKey: FamilyKey = FamilyKey.generate()
  val familyId: String = TrustIds.random()
  val recoveryCode: RecoveryCode = RecoveryCode.generate()
  val config = FamilyConfig(
    nasHost = "192.168.178.58",
    commonShare = COMMON,
    nasName = "MyCloudEX2Ultra.fritz.box",
    nasGuid = "0f8fad5b-d9cb-469f-a165-70867728950e",
    homeUla = listOf("fd12:3456:789a::/48"),
  )
  val admin: Device = Device(this, "Telefono di Admin", "admin2", privateShare = "admin2")

  init {
    val founder = request(admin, admin.name, admin.nasUser)
    val signed = FamilyAuthority(familyKey, clock).found(familyId, config, founder)
    check(FamilyAuthority.publishFounding(common, signed) == PublishResult.CREATED)
    server.share("admin2").publishExclusive(
      NasLayout.recoveryKitFile(familyId, familyKey.publicKey.fingerprint),
      RecoveryKit.seal(familyKey, familyId, recoveryCode, clock.now),
    )
    admin.vault.key = familyKey
    val state = (RegistryVerifier.accept(signed.bytes, null, anchorAt(1)) as Verdict.Accepted).value
    admin.store.snapshot = snapshot(state, minVersion = 1)
  }

  fun anchorAt(minVersion: Long): TrustAnchor = TrustAnchor.create(familyId, familyKey.publicKey.fingerprint, minVersion)!!

  fun snapshot(state: RegistryState, minVersion: Long): TrustSnapshot =
    TrustSnapshot(state.signedBytes, state.signerKey.fingerprint, familyId, familyKey.publicKey.fingerprint, minVersion, emptyMap())

  fun request(device: Device, name: String, nasUser: String): RegistrationRequest {
    val bytes = RegistrationRequest.sign(device.keys.deviceSigner(), familyId, name, "SM-X200", nasUser, clock.now)
    return (RegistrationRequest.verify(bytes, familyId, device.id) as Verdict.Accepted).value
  }

  /**
   * L'ingresso di P4 (§2.2), ridotto all'osso: il dispositivo nuovo pubblica la sua richiesta e salva lo
   * stato fidato con l'ancora del QR dell'admin. Da qui e' "in attesa".
   */
  suspend fun join(name: String, nasUser: String, privateShare: String? = nasUser): Device {
    val device = Device(this, name, nasUser, privateShare)
    val bytes = RegistrationRequest.sign(device.keys.deviceSigner(), familyId, name, "SM-X200", nasUser, clock.now)
    check(common.publishExclusive(NasLayout.requestFile(device.id), bytes) == PublishResult.CREATED)
    val adminState = admin.engine.state ?: run {
      admin.engine.ensureLoaded()
      admin.engine.state!!
    }
    device.store.snapshot = snapshot(adminState, minVersion = adminState.registry.version)
    device.engine.ensureLoaded()
    device.engine.sync(dev.pampa.mycloudgallery.core.nas.contract.Purpose.ONBOARDING)
    return device
  }

  /** Ingresso + approvazione dell'admin con il codice digitato. */
  suspend fun joinApproved(name: String, nasUser: String, privateShare: String? = nasUser): Device {
    val device = join(name, nasUser, privateShare)
    admin.service.refresh(dev.pampa.mycloudgallery.core.nas.contract.Purpose.INTERACTIVE)
    val result = admin.service.approve(device.id, dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof.TypedCode(device.shownCode))
    check(result == dev.pampa.mycloudgallery.core.family.contract.AdminActionResult.Done) { "approvazione non riuscita: $result" }
    device.service.refresh(dev.pampa.mycloudgallery.core.nas.contract.Purpose.BACKGROUND)
    return device
  }
}
