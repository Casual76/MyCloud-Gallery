package dev.pampa.mycloudgallery.integration

import android.content.Context
import android.content.ContextWrapper
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsLog
import dev.pampa.mycloudgallery.core.common.diagnostics.NoDiagnosticsLog
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.SyncReason
import dev.pampa.mycloudgallery.core.family.contract.TrustSyncScheduler
import dev.pampa.mycloudgallery.core.family.lifecycle.WorkCanceller
import dev.pampa.mycloudgallery.core.family.onboarding.DeviceDescription
import dev.pampa.mycloudgallery.core.family.onboarding.DeviceInfoSource
import dev.pampa.mycloudgallery.core.family.onboarding.ScreenLockSource
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.contract.NasDiscovery
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import dev.pampa.mycloudgallery.core.network.env.NetworkObservationSource
import dev.pampa.mycloudgallery.core.network.env.NetworkPathSource
import dev.pampa.mycloudgallery.core.network.gate.BindingFactory
import dev.pampa.mycloudgallery.core.network.testing.FakeLocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.testing.FakeNasDiscovery
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkObservationSource
import dev.pampa.mycloudgallery.core.network.testing.FakeReachabilityProbe
import dev.pampa.mycloudgallery.core.network.testing.FakeWireGuardPlatform
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import dev.pampa.mycloudgallery.core.network.testing.RecordingMagicPacketSender
import dev.pampa.mycloudgallery.core.network.vpn.WireGuardPlatform
import dev.pampa.mycloudgallery.core.network.wol.MagicPacketSender
import dev.pampa.mycloudgallery.core.rules.DeviceReading
import dev.pampa.mycloudgallery.core.rules.DeviceStateSource
import dev.pampa.mycloudgallery.core.rules.FakeDeviceStateSource
import dev.pampa.mycloudgallery.core.rules.NetworkState
import dev.pampa.mycloudgallery.core.rules.NetworkTransport
import dev.pampa.mycloudgallery.core.rules.PowerState
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealthReader
import dev.pampa.mycloudgallery.core.rules.platform.health.testing.FakeBackgroundHealthReader
import dev.pampa.mycloudgallery.core.security.credentials.KeyProtectionLevel
import dev.pampa.mycloudgallery.core.security.credentials.LoadResult
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.core.security.faces.FacesKeys
import dev.pampa.mycloudgallery.core.security.testing.FakeFacesKeys
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyUse
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.VaultCopy
import dev.pampa.mycloudgallery.core.trust.crypto.VaultRefusal
import dev.pampa.mycloudgallery.core.trust.crypto.VaultResult
import dev.pampa.mycloudgallery.graph.AppCredentialStore
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.AppPlatform
import dev.pampa.mycloudgallery.root.ProcessRestart
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

// Il banco dei test d'integrazione dell'app (P9): il grafo VERO (`AppGraph`, lo stesso di produzione) con le sole parti
// del telefono sostituite: il NAS e' il simulatore SMB di core-nas (semantica di Samba, nessun socket), la rete e' una
// rete finta di core-network (Wi-Fi di casa con l'ULA del FRITZ!Box), le chiavi sono in software.

/** 12/10/2026 08:00 UTC: dentro l'intervallo plausibile dei campi firmati (come l'ora del simulatore). */
const val NAS_START: Long = 1_791_763_200_000L

/** Il tempo di tutti: l'ora del NAS e del telefono partono dallo stesso istante e avanzano insieme. */
class TestTime(private val start: Long = NAS_START) {
  private val elapsed = AtomicLong(0)

  val monotonic: MonotonicClock = MonotonicClock { elapsed.get() }

  fun now(): Long = start + elapsed.get()

  fun advance(millis: Long) {
    elapsed.addAndGet(millis)
  }
}

/** Il NAS di prova di §5.4: utenti `test` e `test2`, area comune `test-comune`, aree private `test` e `test2`. */
object TestNas {
  const val IP = "192.168.178.58"
  const val ADMIN_USER = "test"
  const val ADMIN_PASSWORD = "Chiave-Di-Prova-1"
  const val MEMBER_USER = "test2"
  const val MEMBER_PASSWORD = "Chiave-Di-Prova-2"
  const val COMMON = "test-comune"

  fun backend(time: TestTime): InMemorySmbBackend = InMemorySmbBackend(nasTime = time::now).apply {
    addUser(ADMIN_USER, ADMIN_PASSWORD)
    addUser(MEMBER_USER, MEMBER_PASSWORD)
    addShare(COMMON, mapOf(ADMIN_USER to SimAccess.READ_WRITE, MEMBER_USER to SimAccess.READ_WRITE))
    addShare(ADMIN_USER, mapOf(ADMIN_USER to SimAccess.READ_WRITE))
    addShare(MEMBER_USER, mapOf(MEMBER_USER to SimAccess.READ_WRITE))
  }
}

/**
 * La chiave del dispositivo in software (stessa curva del Keystore), con gli stessi comportamenti di `AndroidKeystoreKeyProvider`:
 * `deviceSigner` crea la chiave se manca, `hasDeviceKey` non la crea mai, la distruzione di una chiave che non c'e' non fa niente.
 * [broken] e' un Keystore che non risponde (R7), come quello dei test di core-family: ogni domanda lancia e niente cambia, e quando
 * torna la chiave e' la stessa di prima (`SoftwareKeysParityTest`). Prima qui non c'era: il grafo vero non si provava con un
 * Keystore guasto.
 */
class SoftwareKeys : KeyProvider {
  @Volatile private var key: FamilyKey? = null

  /** Il Keystore non risponde: `hasDeviceKey`, `deviceSigner` e la distruzione lanciano. */
  @Volatile var broken: Boolean = false

  override fun deviceSigner(): Signer = synchronized(this) {
    check(!broken) { "Keystore non disponibile" }
    key ?: FamilyKey.generate().also { key = it }
  }

  override fun hasDeviceKey(): Boolean {
    check(!broken) { "Keystore non disponibile" }
    return key != null
  }

  override fun destroyDeviceKey() {
    check(!broken) { "Keystore non disponibile" }
    key = null
  }
}

/**
 * La cassaforte della chiave di famiglia in memoria (telefono con blocco schermo). Fase 10, M3: ogni custodia e ogni apertura passano dal
 * foglio finto ([sheet]: null = l'admin conferma); [touches] conta ogni chiamata, che il lavoro in background deve lasciare ferma.
 */
class MemoryFamilyVault : FamilyKeyVault {
  @Volatile var stored: FamilyKey? = null
  @Volatile var sheet: VaultRefusal? = null
  val touches = java.util.concurrent.atomic.AtomicInteger()

  override suspend fun store(key: FamilyKey, use: FamilyKeyUse): VaultResult<Unit> {
    touches.incrementAndGet()
    sheet?.let { return VaultResult.Refused(it) }
    stored = key
    return VaultResult.Done(Unit)
  }

  override suspend fun load(expected: EcPublicKey, use: FamilyKeyUse): VaultResult<FamilyKey> {
    touches.incrementAndGet()
    val key = stored?.takeIf { it.publicKey == expected } ?: return VaultResult.Absent
    sheet?.let { return VaultResult.Refused(it) }
    return VaultResult.Done(key)
  }

  override fun keep(): VaultCopy? {
    val kept = stored ?: return null
    return VaultCopy { stored = kept }
  }

  override fun destroy() {
    stored = null
  }
}

/** Le credenziali in memoria al posto del Keystore: [load] da' ogni volta una copia (chi la riceve la chiude). */
class MemoryCredentials : AppCredentialStore {
  private val slots = HashMap<String, Pair<String, CharArray>>()
  var wiped: Boolean = false
    private set

  val slotNames: Set<String> get() = synchronized(slots) { slots.keys.toSet() }

  override suspend fun save(slot: String, credentials: NasCredentials): SaveResult {
    synchronized(slots) { slots[slot] = credentials.username to credentials.password.value.copyOf() }
    return SaveResult.Saved(KeyProtectionLevel.TRUSTED_ENVIRONMENT)
  }

  override suspend fun load(slot: String): LoadResult {
    val saved = synchronized(slots) { slots[slot] } ?: return LoadResult.Absent
    return LoadResult.Found(NasCredentials(saved.first, saved.second))
  }

  override suspend fun remove(slot: String) {
    synchronized(slots) { slots.remove(slot) }
  }

  override suspend fun wipeAll(): Boolean {
    synchronized(slots) { slots.clear() }
    wiped = true
    return true
  }
}

/** Un dispositivo di prova: lo stesso `AppPlatform` del telefono, con le parti fisiche sostituite. */
class TestPlatform(
  val time: TestTime,
  backend: InMemorySmbBackend,
  private val description: (String) -> DeviceDescription = { user -> DeviceDescription("Pixel di prova", "Telefono di $user") },
) : AppPlatform {
  val network = FakeNetworkObservationSource(LinkFactsSamples.observation(LinkFactsSamples.homeWifi()))
  /** I socket "legati" alla rete: quelli del simulatore, che rispondono come il NAS (TCP rifiutata, muta...). */
  val bindingFactory = BindingFactory { networkId -> backend.binding(networkId) }
  val probe = FakeReachabilityProbe(ProbeResult.Open(1.seconds))
  val wireGuardPlatform = FakeWireGuardPlatform(installed = false)
  val keys = SoftwareKeys()
  val vault = MemoryFamilyVault()
  val memoryCredentials = MemoryCredentials()

  /** Fase 8 (P13): le chiavi dei volti in memoria (AES-GCM vero della JVM, una per generazione): i test guardano gli alias e la distruzione. */
  val faceKeys = FakeFacesKeys()
  val health = FakeBackgroundHealthReader()
  val power = FakeDeviceStateSource(
    DeviceReading(PowerState(pluggedIn = true, levelPercent = 80), NetworkState(transport = NetworkTransport.WIFI)),
  )

  override val networkObservation: NetworkObservationSource = network
  override val bindings: BindingFactory = bindingFactory
  override val localNetworkPermission: LocalNetworkPermission = FakeLocalNetworkPermission()
  override val smbTransport: SmbTransport = backend
  override val reachabilityProbe: ReachabilityProbe = probe
  override val wireGuard: WireGuardPlatform = wireGuardPlatform
  override val deviceKeys: KeyProvider = keys
  override val familyKeys: FamilyKeyVault = vault
  override val credentials: AppCredentialStore = memoryCredentials
  override val facesKeys: FacesKeys = faceKeys
  override val healthReader: BackgroundHealthReader = health
  override val deviceState: DeviceStateSource = power
  override val screenLock: ScreenLockSource = ScreenLockSource { ScreenLockStatus.SECURE }
  override val deviceInfo: DeviceInfoSource = DeviceInfoSource { description(it) }
  override val monotonicClock: MonotonicClock = time.monotonic
  override val deviceNow: () -> Long = time::now

  override fun discovery(paths: NetworkPathSource): NasDiscovery = FakeNasDiscovery()

  override fun magicPacketSender(paths: NetworkPathSource): MagicPacketSender = RecordingMagicPacketSender()

  override fun isUserForeground(): Boolean = true
}

/** La pianificazione registrata (WorkManager non c'e'): i test la guardano. */
class RecordingScheduler : TrustSyncScheduler {
  /** Lo scrive la produzione da un thread del grafo e lo guardano i test da un altro (`until`): una lista che si puo' leggere mentre si scrive. */
  val soon: MutableList<SyncReason> = CopyOnWriteArrayList()
  var inviteWindowUntil: Long? = null

  override fun requestSoon(reason: SyncReason) {
    soon += reason
  }

  override fun setInviteWindow(untilNasMs: Long) {
    inviteWindowUntil = untilNasMs
  }
}

/**
 * Un `Context` con le cartelle di un dispositivo solo (due dispositivi nello stesso test non condividono DataStore,
 * stato fidato, diario dell'azzeramento).
 */
class DeviceContext(base: Context, private val root: File) : ContextWrapper(base) {
  private val noBackup = File(root, "no_backup").apply { mkdirs() }
  private val files = File(root, "files").apply { mkdirs() }
  private val cache = File(root, "cache").apply { mkdirs() }

  override fun getApplicationContext(): Context = this

  override fun getNoBackupFilesDir(): File = noBackup

  override fun getFilesDir(): File = files

  override fun getCacheDir(): File = cache

  override fun databaseList(): Array<String> = emptyArray()
}

/**
 * Un dispositivo della famiglia nei test: piattaforma finta, grafo vero.
 *
 * [flags]: di serie quelle di una build **di debug** (le funzioni della release, ma senza R2): il NAS di prova ha l'account `test`, e in
 * release un account di prova non fa scrivere il backup in nessuna share (R2 della revisione 5A, dove D13 non c'e'). Per provare la release
 * vera si passa `FeatureFlags.RELEASE`.
 */
class TestDevice(
  base: Context,
  root: File,
  val platform: TestPlatform,
  flags: FeatureFlags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true),
  /** L'anello "Ultimi esiti ed errori" (RE-4): di solito nessuno; i test che guardano gli eventi ne danno uno vero. */
  diagnostics: DiagnosticsLog = NoDiagnosticsLog,
  /** Il riavvio del processo che "Configura di nuovo" chiede se la galleria era partita (R6): di solito niente; un test lo conta. */
  restart: ProcessRestart = ProcessRestart {},
  /** Dove vive il grafo: di solito i thread di `Dispatchers.Default`; D-7 lo mette su un thread solo per rendere la corsa ripetibile. */
  dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
  private val tracked = TrackedDispatcher(dispatcher)
  val scope = CoroutineScope(SupervisorJob() + tracked)
  val scheduler = RecordingScheduler()
  val workCancelled = AtomicLong(0)
  val context = DeviceContext(base, root)
  val graph = AppGraph(
    context = context,
    scope = scope,
    platform = platform,
    flags = flags,
    appVersion = "0.1.0",
    schedulerFactory = { scheduler },
    work = WorkCanceller { workCancelled.incrementAndGet() },
    diagnostics = diagnostics,
    restart = restart,
  )

  private val strayAtStart = StrayExceptions.count()

  init {
    openDevices += this
  }

  /**
   * Ferma il dispositivo **e aspetta** che tutti i suoi lavori (DataStore, raccolte dei flussi) siano finiti: subito dopo
   * la `TemporaryFolder` cancella i suoi file. Solo `cancel()` lasciava letture di DataStore ancora in corso su un
   * dispositivo gia' chiuso, che trovavano il file sparito (`FileNotFoundException` su `nas_auth_breaker.preferences_pb`)
   * e lo segnalavano come eccezione non gestita **nel test successivo** (P11).
   *
   * **La rete (secondo giro, 31/10)**: l'eccezione di un lavoro sfuggito arriva a chi apre il primo `runTest` dopo di lui (`UncaughtExceptionsBeforeTest`),
   * cioe' a una classe che non c'entra. Qui cade invece **la classe che il dispositivo l'ha lasciato**: dopo aver aspettato lo scope del dispositivo, controlla
   *  1. che nessuna coroutine nata sul dispositivo (lo stesso dispatcher, anche da uno scope **non figlio** del suo: uno creato al volo con `scope.coroutineContext + SupervisorJob()`)
   *     sia ancora attiva (la annulla e la aspetta, poi fa fallire il test) e che nessun lavoro sia ancora in corso sui suoi thread (lo aspetta);
   *  2. che da quando il dispositivo e' nato nessuna coroutine del processo abbia perso un'eccezione ([StrayExceptions]).
   * Prima di far fallire chiude gli altri dispositivi ancora aperti: un `devices.forEach { it.close() }` interrotto da un'eccezione ne lascerebbe di aperti, che e'
   * esattamente il lavoro sfuggito che si vuole evitare.
   */
  fun close() {
    if (!closed.compareAndSet(false, true)) return
    try {
      closeScope(scope)
      val problems = tracked.settle() + StrayExceptions.since(strayAtStart)
      if (problems.isNotEmpty()) {
        throw AssertionError("Il dispositivo ${context.noBackupFilesDir.parentFile?.name} lascia lavoro sfuggito alla chiusura:\n  " + problems.joinToString("\n  "))
      }
    } catch (e: Throwable) {
      closeOthers()
      throw e
    } finally {
      openDevices -= this
    }
  }

  private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

  private companion object {
    /** I dispositivi non ancora chiusi del processo dei test (in ordine di nascita). */
    val openDevices: MutableSet<TestDevice> = java.util.Collections.synchronizedSet(LinkedHashSet())
    val closingOthers = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Chiude quelli rimasti aperti, senza far fallire per loro (il guasto da dire e' quello del dispositivo che ha chiamato). */
    fun closeOthers() {
      if (!closingOthers.compareAndSet(false, true)) return
      try {
        synchronized(openDevices) { openDevices.toList() }.forEach { runCatching { it.close() } }
      } finally {
        closingOthers.set(false)
      }
    }
  }
}

/**
 * Le eccezioni che nessuna coroutine ha preso (un `launch` senza gestore: finiscono a tutti i `CoroutineExceptionHandler` del processo, e questo e' registrato come
 * servizio in `META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler`, accanto a quello di kotlinx-coroutines-test). Le ricorda con la loro traccia, cosi' che
 * [TestDevice.close] possa dire **chi** le ha perse invece di lasciarle a chi apre dopo il primo `runTest`. Non cambia il comportamento degli altri gestori.
 */
class StrayExceptions : kotlin.coroutines.AbstractCoroutineContextElement(kotlinx.coroutines.CoroutineExceptionHandler), kotlinx.coroutines.CoroutineExceptionHandler {
  override fun handleException(context: kotlin.coroutines.CoroutineContext, exception: Throwable) {
    // Una cancellazione normale non arriva mai qui; il resto si tiene (con la traccia, che dice il percorso e il thread) e si scrive.
    val text = "${Thread.currentThread().name}: ${exception.javaClass.simpleName}: ${exception.message}\n    " +
      exception.stackTrace.take(MAX_FRAMES).joinToString("\n    ") + (exception.suppressed.firstOrNull()?.let { "\n    soppressa: $it" } ?: "")
    synchronized(lost) { lost += text }
    System.err.println("ECCEZIONE NON GESTITA di una coroutine: $text")
  }

  companion object {
    private const val MAX_FRAMES = 12
    private val lost = ArrayList<String>()

    fun count(): Int = synchronized(lost) { lost.size }

    fun since(start: Int): List<String> = synchronized(lost) { lost.drop(start).map { "eccezione non gestita di una coroutine: $it" } }
  }
}

/**
 * [inner] che si ricorda ogni `Job` che gli ha dato un lavoro e quanti lavori ha in corso ORA: serve a [TestDevice.close] per vedere il lavoro che lo scope del
 * dispositivo non ha aspettato (un `Job` attivo che non e' un suo discendente, un lavoro in volo).
 */
internal class TrackedDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
  private val running = java.util.concurrent.atomic.AtomicInteger(0)
  private val seen: MutableSet<Job> = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Job, Boolean>())

  override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext): Boolean = inner.isDispatchNeeded(context)

  override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
    context[Job]?.let { job -> synchronized(seen) { seen += job } }
    running.incrementAndGet()
    inner.dispatch(context, Runnable { try { block.run() } finally { running.decrementAndGet() } })
  }

  /**
   * Dopo la chiusura dello scope del dispositivo: annulla e aspetta le coroutine ancora attive che hanno usato questo dispatcher (orfane: non figlie dello scope), poi
   * aspetta che nessun lavoro sia in corso sui thread. Ritorna cio' che ha trovato (vuoto = niente era sfuggito).
   */
  fun settle(): List<String> {
    val problems = ArrayList<String>()
    val orphans = synchronized(seen) { seen.filter { it.isActive } }
    if (orphans.isNotEmpty()) {
      problems += "${orphans.size} coroutine ancora attive dopo la chiusura, nate da uno scope che non e' figlio di quello del dispositivo: ${orphans.groupingBy { it.toString().substringBefore('{') }.eachCount()}"
      runBlocking { withTimeout(Attese.TETTO) { orphans.forEach { it.cancelAndJoin() } } }
    }
    // Un lavoro appena finito decrementa il contatore un attimo dopo aver chiuso il suo `Job` (e dopo il `join` dello scope): si aspetta che arrivi a zero, con il tetto comune.
    try {
      runBlocking { withTimeout(Attese.TETTO) { while (running.get() > 0) yield() } }
    } catch (e: TimeoutCancellationException) {
      problems += "${running.get()} lavori ancora in corso sui thread del dispositivo dopo ${Attese.TETTO}"
    }
    return problems
  }
}
/** Annulla [scope] e ne aspetta la fine (con un tetto: un lavoro che non si ferma fa fallire il test, non lo blocca). */
fun closeScope(scope: CoroutineScope) {
  val job = scope.coroutineContext[Job] ?: return scope.cancel()
  runBlocking { withTimeout(Attese.TETTO) { job.cancelAndJoin() } }
}


/** Aspetta (con un tetto) che un flusso arrivi a un valore che soddisfa [predicate]. */
suspend fun <T> Flow<T>.await(timeout: Duration = Attese.TETTO, predicate: (T) -> Boolean): T =
  withTimeout(timeout) { first(predicate) }

/** Aspetta lo stato del dispositivo dato da [predicate]. */
suspend fun AppGraph.awaitSetup(timeout: Duration = Attese.TETTO, predicate: (SetupState) -> Boolean): SetupState =
  setup.state.await(timeout, predicate)
