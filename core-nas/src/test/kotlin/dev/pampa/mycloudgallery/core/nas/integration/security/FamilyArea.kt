package dev.pampa.mycloudgallery.core.nas.integration.security

import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.fs.FsOps
import dev.pampa.mycloudgallery.core.nas.fs.smb
import dev.pampa.mycloudgallery.core.nas.integration.ItCredentials
import dev.pampa.mycloudgallery.core.nas.integration.SmbAccess
import dev.pampa.mycloudgallery.core.nas.integration.TestAreaTransport
import dev.pampa.mycloudgallery.core.nas.integration.TestAreaViolation
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.NasSessionConfig
import dev.pampa.mycloudgallery.core.nas.session.OpContext
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.EcdsaDer
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustAlgorithms
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// L'AREA DELLA FAMIGLIA DI PROVA vista dal PC (Fase 10 P11, seconda parte; C470): le prove di S2, S3 (b) e (c) e S7 sull'app mettono i loro
// file dove la famiglia di prova li legge davvero, con la guardia [FamilyAreaGuard] su ogni chiamata (TestAreaTransport). Solo l'account
// `test` scrive; lo stato del giro (id del giro, chiavi dei dispositivi di prova, registro di cio' che e' stato creato) sta sul PC, in una
// cartella sotto `build/security/` (mai nel repository): un comando semina, un altro (anche ore dopo) pulisce esattamente quello.

/** Un dispositivo di prova del PC: una chiave P-256 software (solo per le share di prova), l'id derivato come per i dispositivi veri. */
internal class PcDevice private constructor(val role: String, private val privateKey: PrivateKey, override val publicKey: EcPublicKey) : Signer {
  val id: String = TrustIds.deviceIdOf(publicKey)

  override fun sign(message: ByteArray): ByteArray = EcdsaDer.normalize(
    Signature.getInstance(TrustAlgorithms.SIGNATURE).run {
      initSign(privateKey)
      update(message)
      sign()
    },
  )

  /** Una riga dello stato: `ruolo:pkcs8:spki` (base64 e base32). Chiave di prova, solo su disco locale del PC. */
  fun export(): String = role + ":" + Base64.getEncoder().encodeToString(privateKey.encoded) + ":" + publicKey.toText()

  override fun toString(): String = "PcDevice($role)"

  companion object {
    fun generate(role: String): PcDevice {
      val generator = KeyPairGenerator.getInstance(TrustAlgorithms.KEY_ALGORITHM)
      generator.initialize(ECGenParameterSpec(TrustAlgorithms.CURVE))
      val pair = generator.generateKeyPair()
      return PcDevice(role, pair.private, EcPublicKey.fromJca(pair.public))
    }

    fun restore(line: String): PcDevice {
      val (role, pkcs8, spki) = line.split(':', limit = 3)
      val key = KeyFactory.getInstance(TrustAlgorithms.KEY_ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8)))
      return PcDevice(role, key, EcPublicKey.fromText(spki))
    }
  }
}

/**
 * Lo stato di un giro sul PC (`<dir>/stato.txt`, `<dir>/registro.tsv`): l'id del giro, i tre dispositivi di prova (`approvato`, il P di
 * S3 (b), da far approvare dall'admin; `revocato`, il V di S2, da approvare e poi revocare; `canaglia`, l'R di S2, mai approvato) e il
 * registro della guardia.
 */
internal class FamilyRunState private constructor(val dir: File, val runId: String, val devices: Map<String, PcDevice>) {
  private val ledgerFile = File(dir, "registro.tsv")

  fun ledger(): List<String> = if (ledgerFile.isFile) ledgerFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") } else emptyList()

  fun saveLedger(lines: List<String>) {
    ledgerFile.writeText("# registro della guardia: cio' che questo giro ha creato (f=file, d=cartella)\n" + lines.joinToString("\n", postfix = "\n"))
  }

  fun device(role: String): PcDevice = checkNotNull(devices[role]) { "dispositivo di prova $role assente" }

  companion object {
    val ROLES = listOf("approvato", "revocato", "canaglia")

    /** Carica lo stato di [dir], o lo crea (giro e chiavi nuove) se non c'e'. */
    fun loadOrCreate(dir: File, newRunId: () -> String): FamilyRunState {
      dir.mkdirs()
      val file = File(dir, "stato.txt")
      if (!file.isFile) {
        val devices = ROLES.map { PcDevice.generate(it) }
        file.writeText("# stato del giro della famiglia di prova (chiavi SOLO di prova, mai nel repository)\nrun=${newRunId()}\n" + devices.joinToString("") { "device=${it.export()}\n" })
      }
      val lines = file.readLines()
      val run = lines.first { it.startsWith("run=") }.removePrefix("run=")
      val devices = lines.filter { it.startsWith("device=") }.map { PcDevice.restore(it.removePrefix("device=")) }.associateBy { it.role }
      require(devices.keys == ROLES.toSet()) { "stato del giro incompleto" }
      return FamilyRunState(dir, run, devices)
    }
  }
}

/** Una voce della fotografia delle share di prova: solo metadati (nome, dimensione, data). */
internal data class SnapshotEntry(val share: String, val path: String, val size: Long, val modified: Long, val dir: Boolean) {
  fun line(): String = listOf(share, path, size, modified, if (dir) 1 else 0).joinToString("\t")

  companion object {
    fun parse(line: String): SnapshotEntry? {
      val f = line.split('\t')
      if (f.size != 5 || line.startsWith("#")) return null
      return SnapshotEntry(f[0], f[1], f[2].toLong(), f[3].toLong(), f[4] == "1")
    }
  }
}

internal class FamilyArea private constructor(
  val credentials: ItCredentials,
  val guard: FamilyAreaGuard,
  val state: FamilyRunState,
  transport: SmbTransport,
  binding: SocketBinding,
  guid: String?,
  clock: MonotonicClock,
) : AutoCloseable {

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, binding) }

  val manager: SmbNasSessionManager = SmbNasSessionManager(
    account = { NasAccount(NasAddress(credentials.host), credentials.user, "fam", guid) },
    routeGate = gate,
    credentials = { CredentialLoad.Found(NasCredentials(credentials.user, credentials.password.copyOf())) },
    breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope),
    scope = scope,
    transport = transport,
    clock = clock,
    config = NasSessionConfig(),
    deviceId = { "itfam" + state.runId.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT).take(21).padEnd(21, '0') },
    shareGuard = ShareFence,
  )

  /** `test`: l'area privata della famiglia di prova. */
  val privateShare: String get() = credentials.share

  /** `test-comune`: l'area comune. */
  val commonShare: String get() = checkNotNull(credentials.share2)

  /** Una sola verifica, prima di tutto: un `AUTH_FAILED` ferma la prova ([AuthStop]). */
  suspend fun verify() {
    val result = manager.withShare(privateShare, Purpose.INTERACTIVE) { }
    if (result is NasResult.Err) {
      if (result.error is NasError.Auth) throw AuthStop(credentials.user)
      throw NasUnavailable(result.error.toString())
    }
  }

  suspend fun <T> op(shareName: String, block: (OpContext) -> T): T =
    when (val r = manager.runOp(shareName, Purpose.BACKGROUND, idempotent = false, touchesDisk = true, op = block)) {
      is NasResult.Ok -> r.value.also { state.saveLedger(guard.ledger()) }
      is NasResult.Err -> {
        state.saveLedger(guard.ledger())
        if (r.error is NasError.Auth) throw AuthStop(credentials.user)
        throw AssertionError("operazione nell'area della famiglia di prova fallita su $shareName: ${r.error}")
      }
    }

  /** Crea [path] in esclusiva con [bytes] (le cartelle che mancano si creano e finiscono nel registro). */
  suspend fun create(shareName: String, path: SharePath, bytes: ByteArray) = createAll(shareName, listOf(path to bytes))

  suspend fun createAll(shareName: String, files: List<Pair<SharePath, ByteArray>>) {
    for (batch in files.chunked(SecurityArea.BATCH)) op(shareName) { ctx ->
      for ((path, bytes) in batch) {
        FsOps.mkdirs(ctx, path.parent())
        ctx.share.createExclusive(path.smb()).use { file ->
          var offset = 0
          while (offset < bytes.size) offset += file.write(bytes, offset.toLong(), offset, minOf(SecurityArea.MIB, bytes.size - offset))
          file.flush()
        }
      }
    }
  }

  suspend fun read(shareName: String, path: SharePath, max: Long = 4L * SecurityArea.MIB): ByteArray? {
    val file = when (val opened = manager.mediaReader().open(shareName, path, Purpose.BACKGROUND)) {
      is NasResult.Ok -> opened.value
      is NasResult.Err -> return null
    }
    return file.use { if (it.size <= 0) ByteArray(0) else it.readRange(0..minOf(it.size, max) - 1) }
  }

  /** Le voci di [dir] (null = la cartella non c'e' o non si elenca). [dir] null = la radice della share. */
  suspend fun list(shareName: String, dir: SharePath?): List<NasEntry>? {
    val out = ArrayList<NasEntry>()
    return when (manager.mediaReader().list(shareName, dir, Purpose.BACKGROUND) { page -> out += page }) {
      is NasResult.Ok -> out
      is NasResult.Err -> null
    }
  }

  /** La fotografia di [shareName]: ogni voce (cartelle comprese), fino a 16 livelli. `mcg-it` (i giri delle altre prove) si salta. */
  suspend fun snapshot(shareName: String, dir: SharePath? = null, depth: Int = 0): List<SnapshotEntry> {
    val entries = list(shareName, dir) ?: return emptyList()
    val out = ArrayList<SnapshotEntry>()
    for (e in entries) {
      val child = dir?.child(e.name) ?: SharePath.of(e.name)
      if (dir == null && e.name.equals("mcg-it", ignoreCase = true)) continue
      out += SnapshotEntry(shareName.lowercase(Locale.ROOT), child.value, if (e.isDirectory) 0 else e.size, e.modifiedAt, e.isDirectory)
      if (e.isDirectory && depth < 16) out += snapshot(shareName, child, depth + 1)
    }
    return out
  }

  override fun close() {
    state.saveLedger(guard.ledger())
    kotlinx.coroutines.runBlocking { manager.invalidate(InvalidateReason.USER_REQUEST) }
    scope.cancel()
  }

  companion object {
    /**
     * L'area, **prima di qualunque socket**: la guardia valida il file delle credenziali (solo `test`, share `test` e `test-comune`) e
     * deve rifiutare i casi vietati del suo autotest senza nessuna chiamata. [inner] e' il trasporto vero (smbj) o il NAS simulato.
     */
    fun open(credentials: ItCredentials, inner: SmbTransport, binding: SocketBinding, guid: String?, state: FamilyRunState, clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000 }): FamilyArea {
      val guard = FamilyAreaGuard.of(credentials, state.runId, state.devices.values.map { it.id }, state.ledger())
      selfTest(guard)
      return FamilyArea(credentials, guard, state, TestAreaTransport(inner, guard), binding, guid, clock)
    }

    /** L'autotest: ogni caso deve lanciare [TestAreaViolation] (la guardia decide prima di ogni chiamata). */
    fun selfTest(guard: FamilyAreaGuard) {
      val forbidden = listOf(
        Triple("NAS", ".mycloudgallery\\devices", SmbAccess.LIST), Triple("Public", "x.jpg", SmbAccess.STAT),
        Triple("alessio", ".mycloudgallery\\family", SmbAccess.CHANGE), Triple("test2", "mcg-ostile\\x.jpg", SmbAccess.CREATE),
        Triple("test", "mcg-ostile\\x.jpg", SmbAccess.CREATE), Triple("test-comune", "Foto\\x.jpg", SmbAccess.CREATE),
        Triple("test-comune", ".mycloudgallery\\family\\registry\\00000000000000000002", SmbAccess.CREATE),
        Triple("test-comune", ".mycloudgallery\\family", SmbAccess.DELETE), Triple("test-comune", ".mycloudgallery\\..\\x", SmbAccess.CREATE),
        Triple("test", ".mycloudgallery\\recovery\\x\\y", SmbAccess.DELETE), Triple("test-comune", "Foto\\x.jpg", SmbAccess.OPEN_READ),
      )
      for ((share, raw, op) in forbidden) {
        val refused = try {
          guard.checkSmb(share, raw, op)
          false
        } catch (_: TestAreaViolation) {
          true
        }
        check(refused) { "autotest della guardia della famiglia di prova fallito: $share $op" }
      }
    }
  }
}
