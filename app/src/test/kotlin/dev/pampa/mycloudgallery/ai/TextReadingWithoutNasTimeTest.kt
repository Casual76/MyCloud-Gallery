package dev.pampa.mycloudgallery.ai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingConsent
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.ocr.GatedTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.ReleasableTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.TextReadingShutdown
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.SearchOrderSetting
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.integration.until
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La lettura del testo e le altre scelte personali **senza l'ora del NAS** (R6, R7, R8 della revisione di sicurezza della Fase 6; LT-1, LT-2,
 * CE-3, VM-2 e AZ-2 della verifica funzionale), sul montaggio vero: registro dei dati personali (`LedgerPersonalStore` senza ora), il suo
 * `PersonalSettingsHub`, l'interruttore `AppTextReadingSwitch`, la memoria su file, il cancello `GatedTextReader` con un motore testimone e
 * la pulizia di avvio. Prima di queste correzioni "Spegni" fuori casa tornava "Attiva" e alla lettura dopo ML Kit ripartiva.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TextReadingWithoutNasTimeTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

  @After
  fun tearDown() {
    scope.cancel()
  }

  /** Il motore OCR testimone: se qualcuno lo chiama da spento, il test lo vede. */
  private class Witness : ReleasableTextReader {
    var reads = 0
    var released = 0

    override suspend fun read(image: dev.pampa.mycloudgallery.core.ai.contract.OcrImage): OcrResult {
      reads++
      return OcrResult(emptyList(), dense = false)
    }

    override fun release() {
      released++
    }
  }

  private inner class Rig(private val dir: File = temp.newFolder()) {
    val ledger = InMemoryPersonalLedger()
    val stores = MutableStateFlow<LedgerPersonalStore?>(null)
    val localFile = File(dir, PersonalLocalSettings.FILE)
    val hub = PersonalSettingsHub(stores, scope, PersonalLocalSettings(localFile))
    val file = File(dir, TextReadingMemory.FILE)
    val memory = TextReadingMemory(file)
    val witness = Witness()
    lateinit var switch: AppTextReadingSwitch
    val gated = GatedTextReader({ switch.currentState() }) { witness }
    private val shutdown = TextReadingShutdown.create(app, gated)

    init {
      switch = AppTextReadingSwitch(
        scope = scope,
        choice = hub.textReading,
        setChoice = { choice ->
          val settings = hub.search.value
          if (settings == null) false else { settings.setTextReading(choice); true }
        },
        memory = memory,
        shutdown = { shutdown },
      )
    }

    /** Il registro dei dati personali c'e' ma il telefono non ha l'ora del NAS (fuori casa, VPN spenta, nessun accesso SMB nel processo). */
    fun attachStoreWithoutNasTime(): LedgerPersonalStore =
      LedgerPersonalStore(thisDevice, { null }, ledger).also { store ->
        runBlocking { store.open() }
        stores.value = store
      }

    suspend fun read(): Result<OcrResult> = runCatching { gated.read(AiTestInputs.ocr("scontrino")) }
  }

  private val consent = TextReadingConsent(1, 0L)

  /** Due id di dispositivo veri (26 cifre, l'ultima con i bit giusti): quello di questo telefono e quello dell'altro. */
  private val thisDevice = Base32.encode(ByteArray(16) { 0x22 })
  private val otherDevice = Base32.encode(ByteArray(16) { 0x11 })

  private val reading = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_TEXT_READING)

  private fun remoteOn(version: Int) = PersonalEntry(reading, 5_000L, otherDevice, TextReadingChoice.toValue(TextReadingChoice.OnForAccount(version)), null)

  // --- LT-1 / R6 / R7 -------------------------------------------------------------------------------------------------------------------

  @Test
  fun spegniSenzaLOraDelNasSpegneSubitoELaLetturaNonRiparte() = runBlocking {
    val rig = Rig()
    val store = rig.attachStoreWithoutNasTime()
    rig.switch.enableForAccount(consent)
    assertThat(rig.switch.currentState().activeHere).isTrue()
    assertThat(rig.read().isSuccess).isTrue()
    assertThat(rig.witness.reads).isEqualTo(1)

    rig.switch.disable()

    // Subito, in modo sincrono: il cancello di ML Kit non lascia piu' passare niente.
    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.Off)
    val after = rig.read().exceptionOrNull() as AiEngineException
    assertThat(after.error).isEqualTo(AiEngineError.TEXT_READING_OFF)
    assertThat(rig.witness.reads).isEqualTo(1)
    assertThat(rig.witness.released).isEqualTo(1)
    // La voce non ha l'ora, quindi non e' nello stato che si pubblica: lo stato di prima di R6 lasciava "accesa" tutto il resto.
    assertThat(store.entries.value).isEmpty()
    assertThat(store.undated()).containsExactly(reading)
    assertThat(rig.hub.textReading.value).isEqualTo(TextReadingChoice.Off)
    until("lo stato e' spento") { rig.switch.state.value == TextReadingState.Off }
    // E si ricorda: un nuovo processo parte da spento (nessuna pulizia mancata, "la memoria dice accesa" non e' piu' vero).
    assertThat(TextReadingMemory(rig.file).state()).isEqualTo(TextReadingState.Off)
    assertThat(rig.switch.syncPending.value).isFalse() // consegnata al registro, "da datare"
  }

  /**
   * K5 (regressione di K3b): con la memoria del telefono piena il registro non si scrive. Lo spegnimento vale subito qui, ma **non e' consegnato**: resta in sospeso e
   * viaggia quando si puo'. Con la scrittura silenziosa di K3b l'interruttore lo credeva consegnato, la memoria lo segnava sincronizzato e lo spegnimento non arrivava
   * mai al registro (ne' agli altri dispositivi dell'account).
   */
  @Test
  fun spegniConLaMemoriaPienaRestaDaConsegnareEViaggiaQuandoSiPuo() = runBlocking {
    val dir = temp.newFolder()
    val rig = Rig(dir)
    rig.attachStoreWithoutNasTime()
    rig.switch.enableForAccount(consent)
    assertThat(rig.switch.syncPending.value).isFalse()

    rig.ledger.full = true
    rig.switch.disable()

    // Qui e' spenta subito, e ML Kit non riparte.
    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.Off)
    assertThat(rig.read().exceptionOrNull()).isInstanceOf(AiEngineException::class.java)
    // Ma il registro dice ancora "accesa": la scelta non e' consegnata, e si ricorda come tale.
    assertThat(rig.hub.textReading.value).isEqualTo(TextReadingChoice.OnForAccount(1))
    assertThat(rig.switch.syncPending.value).isTrue()
    assertThat(TextReadingMemory(rig.file).state()).isEqualTo(TextReadingState.Off)
    assertThat(TextReadingMemory(rig.file).unsynced).isTrue()

    // Al prossimo avvio, con lo spazio libero, lo spegnimento entra nel registro.
    val next = Rig(dir)
    assertThat(next.switch.currentState()).isEqualTo(TextReadingState.Off)
    assertThat(next.switch.syncPending.value).isTrue()
    val store = next.attachStoreWithoutNasTime()
    until("lo spegnimento e' consegnato al registro") { !next.switch.syncPending.value }
    assertThat(store.undated()).containsExactly(reading)
    assertThat(TextReadingMemory(next.file).unsynced).isFalse()
  }

  @Test
  fun spegniQuandoIDatiPersonaliNonCiSonoAncoraSiRicordaEViaggiaAppenaCiSono() = runBlocking {
    val rig = Rig()
    rig.switch.enableForAccount(consent)
    assertThat(rig.switch.currentState().activeHere).isTrue()

    rig.switch.disable() // nessun registro: prima non faceva niente

    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.Off)
    assertThat(rig.switch.syncPending.value).isTrue()
    assertThat(rig.read().exceptionOrNull()).isInstanceOf(AiEngineException::class.java)
    assertThat(TextReadingMemory(rig.file).state()).isEqualTo(TextReadingState.Off)
    assertThat(TextReadingMemory(rig.file).unsynced).isTrue()

    val store = rig.attachStoreWithoutNasTime()
    until("la scelta e' consegnata al registro") { !rig.switch.syncPending.value }
    assertThat(store.undated()).containsExactly(reading)
    assertThat(rig.hub.textReading.value).isEqualTo(TextReadingChoice.Off)
    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.Off)
    assertThat(TextReadingMemory(rig.file).unsynced).isFalse()
  }

  @Test
  fun attivaSenzaOraDelNasVaSubito() = runBlocking {
    val rig = Rig()
    rig.attachStoreWithoutNasTime()
    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.Off)
    rig.switch.enableForAccount(consent)
    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.OnForAccount(thisDevice = true))
    until("la lettura e' accesa qui") { rig.switch.state.value.activeHere }
    assertThat(rig.read().isSuccess).isTrue()
  }

  @Test
  fun lAccensioneAltroveSuUnRegistroSenzaOraNonSovrascriveUnaScelta_EUnaLetturaInVoloDopoLoSpegnimentoNonPassa() = runBlocking {
    val rig = Rig()
    rig.attachStoreWithoutNasTime()
    rig.switch.enableForAccount(consent)
    // Lo spegnimento e' iniziato (stato di spegnimento in corso): una lettura che parte adesso non passa il cancello, anche se il flusso
    // `state` non si e' ancora mosso.
    val gateState = rig.switch.currentState()
    assertThat(gateState.activeHere).isTrue()
    // Il motore esiste (una lettura riuscita) e lo spegnimento lo rilascia.
    assertThat(rig.read().isSuccess).isTrue()
    rig.switch.disable()
    assertThat(rig.read().exceptionOrNull()).isInstanceOf(AiEngineException::class.java)
    assertThat(rig.witness.released).isEqualTo(1)
  }

  // --- LT-2 / R8 --------------------------------------------------------------------------------------------------------------------------

  @Test
  fun conLAccountAccesoDaUnAltroDispositivoQuiMlKitNonParteFinoAllAvviso() = runBlocking {
    val rig = Rig()
    val store = rig.attachStoreWithoutNasTime()
    store.applyRemote(listOf(remoteOn(1)))

    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.AwaitingNotice(1))
    assertThat(rig.switch.currentState().activeHere).isFalse()
    val thrown = rig.read().exceptionOrNull() as AiEngineException
    assertThat(thrown.error).isEqualTo(AiEngineError.TEXT_READING_OFF)
    assertThat(rig.witness.reads).isEqualTo(0)
    // Ci si esclude prima di qualunque lettura: "Non su questo dispositivo".
    rig.switch.excludeThisDevice()
    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.OnForAccount(thisDevice = false))
    assertThat(rig.read().exceptionOrNull()).isInstanceOf(AiEngineException::class.java)
    // O si conferma: l'avviso e' visto qui, la lettura parte, e la voce sincronizzata non cambia.
    rig.switch.enableForAccount(consent)
    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.OnForAccount(thisDevice = true))
    assertThat(rig.read().isSuccess).isTrue()
    assertThat(store.undated()).isEmpty()
    assertThat(TextReadingMemory(rig.file).noticeVersion).isEqualTo(1)
  }

  @Test
  fun lEsclusioneSiDaAncheDaSpenta_ESePoiLAccountLaAccendeQuiNonParte() = runBlocking {
    val rig = Rig()
    val store = rig.attachStoreWithoutNasTime()
    rig.switch.excludeThisDevice()
    assertThat(rig.switch.excludedHere.value).isTrue()
    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.Off)

    store.applyRemote(listOf(remoteOn(1)))
    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.OnForAccount(thisDevice = false))
    assertThat(rig.read().exceptionOrNull()).isInstanceOf(AiEngineException::class.java)
    assertThat(rig.witness.reads).isEqualTo(0)
    // Un nuovo processo la ricorda.
    assertThat(TextReadingMemory(rig.file).excluded).isTrue()
  }

  @Test
  fun unTestoCambiatoRichiedeDiNuovoLAvviso() = runBlocking {
    val rig = Rig()
    val store = rig.attachStoreWithoutNasTime()
    // L'account e' acceso da un altro dispositivo con la versione 1 del testo; qui l'avviso e' stato visto e la lettura parte.
    store.applyRemote(listOf(remoteOn(1)))
    rig.switch.enableForAccount(consent)
    assertThat(rig.switch.currentState().activeHere).isTrue()
    // Poi l'altro dispositivo riaccetta con una versione nuova del testo: qui serve di nuovo l'avviso, e finche' non e' visto non si legge.
    store.applyRemote(listOf(remoteOn(2).copy(atNas = 9_000L)))
    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.AwaitingNotice(2))
    assertThat(rig.read().exceptionOrNull()).isInstanceOf(AiEngineException::class.java)
  }

  // --- AZ-2 / I13 --------------------------------------------------------------------------------------------------------------------------

  @Test
  fun dopoLAzzeramentoLaScelta_LEsclusione_GliAvvisiEIlFileSonoDimenticati_ENienteLiRiscrive() = runBlocking {
    val rig = Rig()
    val store = rig.attachStoreWithoutNasTime()
    rig.switch.enableForAccount(consent)
    rig.switch.excludeThisDevice()
    rig.hub.setSearchOrder(SearchOrderSetting.RELEVANT)
    assertThat(rig.file.exists()).isTrue()

    // L'azzeramento: l'interruttore si ferma per primo (I13), poi si dimentica (AZ-2), poi i file.
    rig.switch.stop()
    rig.switch.forget()
    rig.hub.forget()

    assertThat(rig.file.exists()).isFalse()
    assertThat(rig.localFile.exists()).isFalse()
    assertThat(rig.memory.state()).isEqualTo(TextReadingState.Off)
    assertThat(rig.memory.excluded).isFalse()
    assertThat(rig.memory.noticeVersion).isEqualTo(0)
    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.Off)
    assertThat(rig.switch.excludedHere.value).isFalse()
    // Un'emissione tardiva del registro non fa rinascere il file (I13).
    store.applyRemote(listOf(remoteOn(1)))
    assertThat(rig.file.exists()).isFalse()
    assertThat(rig.switch.currentState()).isEqualTo(TextReadingState.Off)
    // Un nuovo processo, con lo stesso file assente: spenta, nessun avviso, nessuna esclusione.
    assertThat(TextReadingMemory(rig.file).state()).isEqualTo(TextReadingState.Off)
  }

  // --- CE-3 / VM-2: la stessa radice --------------------------------------------------------------------------------------------------------

  @Test
  fun lOrdineEL_animazioneSceltiSenzaRegistroValgonoSubito_SiRicordano_EViaggianoDopo() = runBlocking {
    val rig = Rig()
    assertThat(rig.hub.searchOrder.value).isEqualTo(SearchOrderSetting.RECENT)
    assertThat(rig.hub.motionAnimation.value).isTrue()

    rig.hub.setSearchOrder(SearchOrderSetting.RELEVANT)
    rig.hub.setMotionAnimation(false)
    assertThat(rig.hub.searchOrder.value).isEqualTo(SearchOrderSetting.RELEVANT)
    assertThat(rig.hub.motionAnimation.value).isFalse()
    assertThat(rig.localFile.exists()).isTrue()

    // Dopo un riavvio (il registro non c'e' ancora): le scelte ci sono.
    val restarted = PersonalSettingsHub(MutableStateFlow(null), scope, PersonalLocalSettings(rig.localFile))
    assertThat(restarted.searchOrder.value).isEqualTo(SearchOrderSetting.RELEVANT)
    assertThat(restarted.motionAnimation.value).isFalse()

    // Il registro arriva, senza l'ora del NAS: le scelte vi entrano (da datare) e il file non serve piu'.
    val store = rig.attachStoreWithoutNasTime()
    until("il file locale delle scelte non serve piu'") { !rig.localFile.exists() }
    assertThat(store.undated()).hasSize(2)
    assertThat(rig.hub.searchOrder.value).isEqualTo(SearchOrderSetting.RELEVANT)
    assertThat(rig.hub.motionAnimation.value).isFalse()
  }

  @Test
  fun conIlRegistroSenzaOraLOrdineScelto_VaSubitoENonTornaIndietro() = runBlocking {
    val rig = Rig()
    rig.attachStoreWithoutNasTime()
    rig.hub.setSearchOrder(SearchOrderSetting.RELEVANT)
    assertThat(rig.hub.searchOrder.value).isEqualTo(SearchOrderSetting.RELEVANT)
    rig.hub.setSearchOrder(SearchOrderSetting.RECENT)
    assertThat(rig.hub.searchOrder.value).isEqualTo(SearchOrderSetting.RECENT)
    rig.hub.setMotionAnimation(false)
    assertThat(rig.hub.motionAnimation.value).isFalse()
    rig.hub.setMotionAnimation(true)
    assertThat(rig.hub.motionAnimation.value).isTrue()
  }
}
