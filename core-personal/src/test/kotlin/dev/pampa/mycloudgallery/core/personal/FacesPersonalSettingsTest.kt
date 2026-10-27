package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.noop.NoFacesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedFacesSettings
import dev.pampa.mycloudgallery.core.personal.testing.FacesRegister
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryFacesPersonalSettings
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** La voce `faces` sul motore vero: due dispositivi dell'account con lo store, il ledger e la sincronizzazione dei dati personali veri. */
class SyncedFacesSettingsTest : FacesPersonalSettingsContract() {

  override fun rig(): Rig = RealRig()

  private class RealRig : Rig {
    private val world = PersonalWorld()
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val deviceA = world.device()
    private val deviceB = world.device()
    private var remoteTime = world.clock.now + 60_000L
    private val thirdDevice = TrustIds.random()

    init {
      kotlinx.coroutines.runBlocking {
        deviceA.store.open()
        deviceB.store.open()
      }
    }

    override val a: FacesPersonalSettings = SyncedFacesSettings(deviceA.store, scope)
    override val b: FacesPersonalSettings = SyncedFacesSettings(deviceB.store, scope)

    override fun aOnline(online: Boolean) {
      deviceA.anchored = online
    }

    override fun bOnline(online: Boolean) {
      deviceB.anchored = online
    }

    override suspend fun syncAll() {
      for (device in listOf(deviceA, deviceB, deviceA)) {
        world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
        device.syncOnce()
      }
    }

    override suspend fun remoteWrites(text: String?) {
      // Un terzo dispositivo dell'account (un id che non e' ne' A ne' B), con un'ora piu' recente di tutto cio' che si sa.
      remoteTime += 60_000L
      val value = if (text == null) PersonalValue.Removed else PersonalValue.Text(text)
      deviceA.store.applyRemote(listOf(PersonalEntry(FacesSettingCodec.KEY, remoteTime, thirdDevice, value)))
    }
  }

  /** L'ora piu' alta e' il segno di "da datare" (lo legge `syncPending`): lo stato effettivo dello store lo promette. */
  @Test
  fun laScelteDaDatareHannoLOraPiuAltaDelloStore() = runTest {
    val world = PersonalWorld()
    val device = world.device().also { it.anchored = false }
    device.store.open()
    val settings = SyncedFacesSettings(device.store, CoroutineScope(Dispatchers.Unconfined + Job()))
    settings.set(FacesSetting.On(g1, 1))
    assertThat(device.store.undated()).containsExactly(FacesSettingCodec.KEY)
    assertThat(device.store.effective().getValue(FacesSettingCodec.KEY).atNas).isEqualTo(Long.MAX_VALUE)
    assertThat(device.store.entries.value).isEmpty()
    assertThat(settings.syncPending.value).isTrue()
    assertThat(settings.current()).isEqualTo(FacesSetting.On(g1, 1))
  }

  /** Un nuovo processo (stesso ledger, ancora senza ora del NAS): l'attivazione fatta fuori casa c'e' ancora. */
  @Test
  fun lAttivazioneFattaSenzaOraSopravvivealRiavvio() = runTest {
    val world = PersonalWorld()
    val first = world.device().also { it.anchored = false }
    first.store.open()
    SyncedFacesSettings(first.store, CoroutineScope(Dispatchers.Unconfined + Job())).set(FacesSetting.On(g1, 3))

    val restarted = world.device(ledger = first.ledger, signer = first.signer).also { it.anchored = false }
    restarted.store.open()
    val settings = SyncedFacesSettings(restarted.store, CoroutineScope(Dispatchers.Unconfined + Job()))
    assertThat(settings.current()).isEqualTo(FacesSetting.On(g1, 3))
    assertThat(settings.syncPending.value).isTrue()
  }

  /** La lettura del campo da uno stato (cio' che passa il gancio prima della pubblicazione). */
  @Test
  fun ilCodecLeggeLaVoceDaUnoStato() {
    val g = g1
    val key = FacesSettingCodec.KEY
    fun state(value: PersonalValue) = mapOf(key to PersonalEntry(key, 1L, TrustIds.random(), value))
    assertThat(FacesSettingCodec.read(emptyMap())).isNull()
    assertThat(FacesSettingCodec.read(state(FacesSettingCodec.on(g, 2)))).isEqualTo(FacesSetting.On(g, 2))
    assertThat(FacesSettingCodec.read(state(FacesSettingCodec.off(g)))).isEqualTo(FacesSetting.Off(g))
    assertThat(FacesSettingCodec.read(state(PersonalValue.Removed))).isNull()
    assertThat(FacesSettingCodec.read(state(PersonalValue.Text("boh")))).isEqualTo(FacesSetting.Unknown)
    // Un'altra voce con un campo diverso non e' la voce `faces`.
    val other = PersonalKey(PersonalKind.SETTING, key.subject, "text_reading")
    assertThat(FacesSettingCodec.read(mapOf(other to PersonalEntry(other, 1L, TrustIds.random(), FacesSettingCodec.on(g, 1))))).isNull()
  }

}

/** Lo stesso contratto sul fake: perche' l'interruttore e le schermate che lo usano per provare si fidino. */
class InMemoryFacesPersonalSettingsTest : FacesPersonalSettingsContract() {

  override fun rig(): Rig = FakeRig()

  private class FakeRig : Rig {
    private val register = FacesRegister()
    private val first = InMemoryFacesPersonalSettings(register, "A")
    private val second = InMemoryFacesPersonalSettings(register, "B")

    override val a: FacesPersonalSettings = first
    override val b: FacesPersonalSettings = second

    override fun aOnline(online: Boolean) {
      first.online = online
    }

    override fun bOnline(online: Boolean) {
      second.online = online
    }

    override suspend fun syncAll() {
      first.sync()
      second.sync()
      first.sync()
    }

    override suspend fun remoteWrites(text: String?) {
      first.injectRemote(text?.let { PersonalValue.Text(it) })
      second.pull()
    }
  }

  @Test
  fun ilNullObjectNonScriveENonAccendeNiente() = runTest {
    assertThat(NoFacesPersonalSettings.current()).isNull()
    assertThat(NoFacesPersonalSettings.set(FacesSetting.On(FaceGeneration.random(), 1))).isFalse()
    assertThat(NoFacesPersonalSettings.setting.value).isNull()
    assertThat(NoFacesPersonalSettings.syncPending.value).isFalse()
  }
}
