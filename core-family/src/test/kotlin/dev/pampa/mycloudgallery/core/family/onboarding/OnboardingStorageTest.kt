package dev.pampa.mycloudgallery.core.family.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.family.setup.DataStoreNasAccountStore
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.rules.Preset
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Dove l'avanzamento si salva (DataStore su file), com'e' fatto (JSON senza segreti) e cosa succede quando il
 * file e' danneggiato, illeggibile (BFU) o arriva da un altro telefono (E-O8).
 */
class OnboardingStorageTest {

  @get:Rule
  val folder = TemporaryFolder()

  private lateinit var scope: CoroutineScope
  private lateinit var file: File

  @Before
  fun setUp() {
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    file = File(folder.root, "onboarding.preferences_pb")
  }

  @After
  fun tearDown() {
    scope.cancel()
  }

  /** Il processo muore e ne nasce un altro: il vecchio DataStore rilascia il file (ce n'e' uno solo per file) prima del nuovo. */
  private suspend fun restartProcess() {
    scope.coroutineContext[Job]!!.cancelAndJoin()
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  }

  private fun dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }

  private suspend fun DataStoreOnboardingStateStore.ready(): OnboardingSnapshot =
    withTimeout(10_000) { (state.first { it is StoreState.Ready } as StoreState.Ready).snapshot }

  private fun full(): OnboardingSnapshot = OnboardingSnapshot(
    progress = OnboardingProgress(
      path = OnboardingPath.FOUND_FAMILY,
      plan = OnboardingStep.entries.toList(),
      current = OnboardingStep.FOLDERS,
      completed = setOf(OnboardingStep.WELCOME, OnboardingStep.NAS_ADDRESS, OnboardingStep.ACCOUNT, OnboardingStep.AREAS),
      skipped = setOf(OnboardingStep.BATTERY),
      startedAt = 1_791_806_400_123L,
    ),
    finished = false,
    finishedAt = null,
    resuming = true,
    deferrable = true,
    homeConfirmed = HomeCandidate("wifi-7", setOf("fd12:3456:789a::/48", "fdd1:2345:6789::/48"), "192.168.178.1", "fritz.box", "192.168.178.0/24"),
    nas = NasFacts("192.168.178.58", "MyCloudEX2Ultra.fritz.box", 445, "6f1c0a52-3a4d-4c1e-9d2b-0123456789ab"),
    nasUser = "alessio",
    commonShare = "NAS",
    privateShare = "alessio",
    excluded = setOf("Musica", "Film/Serie TV"),
    choices = OnboardingChoices(
      camera = false, screenshots = true, indexer = true, preparer = true, preset = Preset.WIFI_ALWAYS, quality = PreviewQuality.HIGH,
      remoteControl = true, tunnelName = "casa_vpn", externalBackupDone = true, routerChecklistDone = true,
    ),
    foundation = FoundationRecord(FamilyKeyStage.KIT_VERIFIED, "abcdefghjkmnpqrstvwxyz0123", "chiave-pubblica"),
    join = JoinRecord("abcdefghjkmnpqrstvwxyz0123", manual = true, minRegistryVersion = 0, registered = true, requestedAt = 1_791_806_400_000L),
  )

  // --- Codifica ---------------------------------------------------------------------------------------------

  @Test
  fun loSnapshotFaAndataERitornoInOgniCampo() {
    val snapshot = full()
    assertThat(OnboardingSnapshotCodec.decode(OnboardingSnapshotCodec.encode(snapshot))).isEqualTo(snapshot)
    assertThat(OnboardingSnapshotCodec.decode(OnboardingSnapshotCodec.encode(OnboardingSnapshot.EMPTY))).isEqualTo(OnboardingSnapshot.EMPTY)
  }

  @Test
  fun loSnapshotNonContieneSegretiNeIndirizziNeiLog() {
    val text = OnboardingSnapshotCodec.encode(full())
    assertThat(text.lowercase()).doesNotContain("password")
    assertThat(text.lowercase()).doesNotContain("code")
    // E i dati del NAS non vanno in un toString (che finisce nei log).
    assertThat(full().nas.toString()).doesNotContain("192.168")
    assertThat(full().nas.toString()).doesNotContain("6f1c0a52")
  }

  @Test
  fun unTestoDanneggiatoODiUnaVersioneSconosciutaVaScartato() {
    val good = OnboardingSnapshotCodec.encode(full())
    assertThat(OnboardingSnapshotCodec.decode("")).isNull()
    assertThat(OnboardingSnapshotCodec.decode("non e' json")).isNull()
    assertThat(OnboardingSnapshotCodec.decode("[]")).isNull()
    assertThat(OnboardingSnapshotCodec.decode(good.dropLast(5))).isNull()
    assertThat(OnboardingSnapshotCodec.decode(good.replace("\"v\":1", "\"v\":2"))).isNull()
    assertThat(OnboardingSnapshotCodec.decode("{\"v\":1}")).isNull() // niente piano
    // Il passo corrente deve stare nel piano.
    assertThat(OnboardingSnapshotCodec.decode("{\"cur\":\"VPN\",\"plan\":[\"WELCOME\"],\"v\":1}")).isNull()
    assertThat(OnboardingSnapshotCodec.decode("{\"cur\":\"WELCOME\",\"plan\":[],\"v\":1}")).isNull()
  }

  @Test
  fun iCampiInPiuSiIgnoranoEQuelliCheManCanoPrendonoIlValoreDiPartenza() {
    val minimal = "{\"cur\":\"WELCOME\",\"plan\":[\"WELCOME\",\"NAS_ADDRESS\"],\"v\":1,\"futuro\":{\"x\":1}}"
    val decoded = OnboardingSnapshotCodec.decode(minimal)!!
    assertThat(decoded.progress.current).isEqualTo(OnboardingStep.WELCOME)
    assertThat(decoded.progress.path).isNull()
    assertThat(decoded.choices).isEqualTo(OnboardingChoices())
    assertThat(decoded.finished).isFalse()
    assertThat(decoded.nas).isNull()
    // Un passo o un preset che non esistono piu' si scartano, non fanno cadere la lettura.
    val odd = "{\"cur\":\"WELCOME\",\"plan\":[\"WELCOME\",\"PASSO_FUTURO\"],\"v\":1,\"ch\":{\"pre\":\"INVENTATO\"}}"
    val tolerant = OnboardingSnapshotCodec.decode(odd)!!
    assertThat(tolerant.progress.plan).containsExactly(OnboardingStep.WELCOME)
    assertThat(tolerant.choices.preset).isEqualTo(OnboardingChoices().preset)
  }

  // --- DataStore -----------------------------------------------------------------------------------------------

  /** P9: chi legge lo stato subito dopo una scrittura (il coordinatore al passo dopo) vede gia' l'avanzamento nuovo. */
  @Test
  fun dopoUnaScritturaLoStatoELoSnapshotNuovoSubito() = runBlocking {
    val store = DataStoreOnboardingStateStore(dataStore(), scope)
    store.ready()
    store.update { full() }
    assertThat((store.state.value as StoreState.Ready).snapshot).isEqualTo(full())
    store.update { it.copy(finished = true) }
    assertThat((store.state.value as StoreState.Ready).snapshot.finished).isTrue()
  }

  @Test
  fun lAvanzamentoSopravvivePerUnNuovoProcesso() = runBlocking {
    val first = DataStoreOnboardingStateStore(dataStore(), scope)
    assertThat(first.ready()).isEqualTo(OnboardingSnapshot.EMPTY)
    assertThat(first.update { full() }).isEqualTo(full())
    // Un altro DataStore sullo stesso file: il processo e' morto e ne e' nato un altro.
    restartProcess()
    val second = DataStoreOnboardingStateStore(dataStore(), scope)
    assertThat(second.ready()).isEqualTo(full())
    val updated = second.update { it.copy(finished = true) }
    assertThat(updated!!.finished).isTrue()
    assertThat(updated.nas).isEqualTo(full().nas)
  }

  @Test
  fun unFileDanneggiatoValeMaiIniziato() = runBlocking {
    val raw = dataStore()
    raw.edit { it[stringPreferencesKey("onboarding.snapshot")] = "{ questo non e' un avanzamento" }
    val store = DataStoreOnboardingStateStore(raw, scope)
    assertThat(store.ready()).isEqualTo(OnboardingSnapshot.EMPTY)
    // E la prima scrittura lo rimette in ordine.
    assertThat(store.update { it.copy(deferrable = true) }!!.deferrable).isTrue()
  }

  @Test
  fun ilTrasferimentoDaUnAltroTelefonoSiRiconoscePercheMancaLaChiave_E_O8() = runBlocking {
    val finished = full().copy(finished = true, finishedAt = 1L)
    DataStoreOnboardingStateStore(dataStore(), scope).also { it.ready() }.update { finished }
    restartProcess()

    // Smart Switch ha copiato il file ma non le chiavi del Keystore.
    val raw = dataStore()
    val transferred = DataStoreOnboardingStateStore(raw, scope, deviceKeyPresent = { false })
    assertThat(transferred.ready()).isEqualTo(OnboardingSnapshot.EMPTY)
    // Il file copiato si cancella: nemmeno un riavvio lo resuscita.
    withTimeout(10_000) {
      while (raw.data.first()[stringPreferencesKey("onboarding.snapshot")] != null) delay(20)
    }
  }

  @Test
  fun conLaChiaveIlFileSiTieneEQuandoIlKeystoreNonRispondeNonSiButtaNiente() = runBlocking {
    val finished = full().copy(finished = true, finishedAt = 1L)
    DataStoreOnboardingStateStore(dataStore(), scope).also { it.ready() }.update { finished }
    restartProcess()
    assertThat(DataStoreOnboardingStateStore(dataStore(), scope, deviceKeyPresent = { true }).ready()).isEqualTo(finished)
    restartProcess()
    val flaky = DataStoreOnboardingStateStore(dataStore(), scope, deviceKeyPresent = { throw IllegalStateException("Keystore occupato") })
    assertThat(flaky.ready()).isEqualTo(finished)
    // Un avanzamento NON finito non dipende dalla chiave: il dispositivo non ne ha ancora una.
    restartProcess()
    DataStoreOnboardingStateStore(dataStore(), scope).also { it.ready() }.update { full() }
    restartProcess()
    val unfinished = DataStoreOnboardingStateStore(dataStore(), scope, deviceKeyPresent = { false })
    assertThat(unfinished.ready()).isEqualTo(full())
  }

  /** Un DataStore che per le prime [failures] letture dice IOException (telefono non ancora sbloccato). */
  private class FlakyDataStore(private val delegate: DataStore<Preferences>, private var failures: Int, private val failWrites: Boolean = false) :
    DataStore<Preferences> {
    override val data: Flow<Preferences> = flow {
      if (failures-- > 0) throw IOException("archiviazione non ancora sbloccata")
      delegate.data.collect { emit(it) }
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      if (failWrites) throw IOException("disco pieno")
      return delegate.updateData(transform)
    }
  }

  @Test
  fun conLArchivioNonLeggibileSiDiceNonDisponibileESiRiprovaDaSolo_BFU() = runBlocking {
    val store = DataStoreOnboardingStateStore(FlakyDataStore(dataStore(), failures = 2), scope, retryBaseMillis = 20)
    withTimeout(10_000) { store.state.first { it is StoreState.Unavailable } }
    assertThat(store.ready()).isEqualTo(OnboardingSnapshot.EMPTY)
  }

  @Test
  fun unaScritturaCheFallisceNonLanciaEDaNull() = runBlocking {
    val store = DataStoreOnboardingStateStore(FlakyDataStore(dataStore(), failures = 0, failWrites = true), scope)
    store.ready()
    assertThat(store.update { full() }).isNull()
    store.clear() // non lancia
  }

  // --- Account ---------------------------------------------------------------------------------------------------

  @Test
  fun l_accountDelNasSiSalvaESiRilegge() = runBlocking {
    val accounts = DataStoreNasAccountStore(dataStore())
    assertThat(accounts.current()).isNull()
    val account = NasAccount(NasAddress("192.168.178.58", "MyCloudEX2Ultra.fritz.box"), "alessio", "nas-primary", "guid-1")
    accounts.save(account)
    assertThat(accounts.current()).isEqualTo(account)
    assertThat(accounts.observe().first()).isEqualTo(account)
    val v6 = NasAccount(NasAddress("fd12:3456:789a::58", null, 4450), "mamma", "nas-primary", null)
    accounts.save(v6)
    assertThat(accounts.current()).isEqualTo(v6)
    accounts.clear()
    assertThat(accounts.current()).isNull()
    assertThat(accounts.observe().first()).isNull()
  }
}
