package dev.pampa.mycloudgallery.core.faces.switch

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.PrePublish
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Il gancio prima della pubblicazione legato all'interruttore (D30): la voce si legge dallo stato che sta per uscire, e un giro pubblica solo se i dati di
 * una generazione non piu' corrente sono tolti. Con la cancellazione che fallisce il giro non pubblica e il codice e' quello della Diagnostica.
 */
class FacesPrePublishHookTest {

  private val rigs = mutableListOf<SwitchRig>()

  @After
  fun chiudi() = rigs.forEach { it.close() }

  private val g1: FaceGeneration = SwitchRig.generation(1)
  private val g2: FaceGeneration = SwitchRig.generation(2)

  private fun state(value: PersonalValue?): Map<PersonalKey, PersonalEntry> =
    if (value == null) emptyMap() else mapOf(FacesSettingCodec.KEY to PersonalEntry(FacesSettingCodec.KEY, 1_000L, TrustIds.random(), value))

  private suspend fun withData(): SwitchRig = SwitchRig().also {
    rigs += it
    it.account.set(FacesSetting.On(g1, 1))
    it.localStore.update { l -> l.copy(noticedGeneration = g1, noticedTextVersion = 1, dataGeneration = g1) }
    it.eraser.data += g1
  }

  @Test
  fun conLaVoceSpentaPrimaSiCancellaPoiSiPubblica() = runTest {
    val rig = withData()
    rig.eraser.succeeds = false
    rig.account.injectRemote(FacesSettingCodec.off(g1)) // l'allineamento da solo non riesce
    rig.eraser.succeeds = true
    val hook = FacesPrePublishHook(rig.switch)
    assertThat(hook.beforePublish(state(FacesSettingCodec.off(g1)))).isEqualTo(PrePublish.Proceed)
    // Quando il gancio risponde "si'", i dati di G1 su questo dispositivo non ci sono piu'.
    assertThat(rig.eraser.data).isEmpty()
    assertThat(rig.local.dataGeneration).isNull()
  }

  @Test
  fun seLaCancellazioneNonRiesceIlGiroNonPubblica() = runTest {
    val rig = withData()
    rig.eraser.succeeds = false
    rig.account.injectRemote(FacesSettingCodec.off(g1))
    val hook = FacesPrePublishHook(rig.switch)
    val answer = hook.beforePublish(state(FacesSettingCodec.off(g1)))
    assertThat(answer).isEqualTo(PrePublish.Hold("FACES_ERASE_PENDING"))
    assertThat(rig.eraser.data).containsExactly(g1)
  }

  @Test
  fun conUnaGenerazioneNuovaLaVecchiaSiToglieDaSola() = runTest {
    val rig = withData()
    rig.eraser.succeeds = false
    rig.account.injectRemote(FacesSettingCodec.on(g2, 1))
    rig.eraser.succeeds = true
    assertThat(FacesPrePublishHook(rig.switch).beforePublish(state(FacesSettingCodec.on(g2, 1)))).isEqualTo(PrePublish.Proceed)
    assertThat(rig.eraser.data).isEmpty()
  }

  @Test
  fun lAssenzaDellaVoceEUnaVoceSconosciutaPubblicanoSenzaCancellare() = runTest {
    val rig = withData()
    val hook = FacesPrePublishHook(rig.switch)
    assertThat(hook.beforePublish(state(null))).isEqualTo(PrePublish.Proceed)
    assertThat(hook.beforePublish(state(PersonalValue.Text("9|forma|nuova")))).isEqualTo(PrePublish.Proceed)
    assertThat(hook.beforePublish(state(PersonalValue.Removed))).isEqualTo(PrePublish.Proceed)
    assertThat(rig.eraser.calls).isEmpty()
    assertThat(rig.eraser.data).containsExactly(g1)
  }

  @Test
  fun laStessaVoceAccesaNonCambiaNiente() = runTest {
    val rig = withData()
    assertThat(FacesPrePublishHook(rig.switch).beforePublish(state(FacesSettingCodec.on(g1, 1)))).isEqualTo(PrePublish.Proceed)
    assertThat(rig.eraser.calls).isEmpty()
  }
}
