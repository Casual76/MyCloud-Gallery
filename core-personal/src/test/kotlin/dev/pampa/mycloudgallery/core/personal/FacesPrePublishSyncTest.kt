package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PrePublish
import dev.pampa.mycloudgallery.core.personal.contract.PrePublishHook
import dev.pampa.mycloudgallery.core.personal.sync.NasPersonalSync
import dev.pampa.mycloudgallery.core.personal.sync.PersonalHeadsRecord
import dev.pampa.mycloudgallery.core.personal.testing.RecordingPrePublishHook
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import java.util.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8, P8 (D30, §2.8 "Gli altri dispositivi" punto 2): il gancio `PrePublishHook` dentro il motore vero dei dati personali. Si chiama **prima di
 * ogni pubblicazione**, con lo stato esatto che sta per uscire; con `Hold` quel giro legge soltanto e nessuna testa esce; un gancio che lancia
 * ferma come `Hold`; niente gancio quando non si pubblica (stato uguale, M3, cancello chiuso). Proprieta': ogni testa pubblicata ha uno stato
 * che il gancio ha appena approvato.
 */
class FacesPrePublishSyncTest {

  private val world = PersonalWorld()
  private val g = FaceGeneration.fromBytes(ByteArray(16) { 5 })

  /** Il motore vero di [this], con il gancio e il ricordo delle teste. Il tempo monotono non avanza: i budget non scadono. */
  private fun PersonalDevice.engine(hook: PrePublishHook, heads: PersonalHeadsRecord = PersonalHeadsRecord()): NasPersonalSync {
    val rules = IndexWriteRules(flags, gate)
    return NasPersonalSync(
      share = share,
      account = account,
      remote = remote,
      store = store,
      identity = identity,
      clock = NasClock { nasNow() },
      gate = IndexWriteGate { rules.blockFor(it) },
      monotonicMillis = { 0L },
      limits = limits,
      prePublish = hook,
      heads = heads,
    )
  }

  private suspend fun PersonalDevice.faces(setting: FacesSetting) = store.set(FacesSettingCodec.KEY, FacesSettingCodec.valueOf(setting))

  @Test
  fun ilGancioVedeLoStatoCheEsceEPrimaDiOgniPubblicazione() = runTest {
    val a = world.device()
    val hook = RecordingPrePublishHook()
    val sync = a.engine(hook)
    a.faces(FacesSetting.On(g, 1))
    a.favorite(mediaId(1))
    sync.sync(5.minutes)
    assertThat(hook.seen).hasSize(1)
    assertThat(hook.seen.single()).isEqualTo(a.store.state())
    assertThat(a.headFiles()).hasSize(1)
    // Niente di nuovo: niente pubblicazione, niente gancio.
    sync.sync(5.minutes)
    assertThat(hook.seen).hasSize(1)
    // Qualcosa di nuovo ma prima dei 5 minuti (M3): niente gancio.
    a.favorite(mediaId(2))
    sync.sync(5.minutes)
    assertThat(hook.seen).hasSize(1)
    world.clock.advance(6 * 60_000L)
    sync.sync(5.minutes)
    assertThat(hook.seen).hasSize(2)
    assertThat(a.headFiles()).hasSize(2)
  }

  @Test
  fun conHoldNessunaTestaEsceEIlGiroDopoRiprova() = runTest {
    val a = world.device()
    val hook = RecordingPrePublishHook(PrePublish.Hold("FACES_ERASE_PENDING"))
    val sync = a.engine(hook)
    a.faces(FacesSetting.Off(g))
    val report = sync.sync(5.minutes)
    assertThat(report.published).isEqualTo(0)
    assertThat(a.headFiles()).isEmpty()
    assertThat(sync.lastHold.value).isEqualTo("FACES_ERASE_PENDING")
    hook.answer = PrePublish.Proceed
    assertThat(sync.sync(5.minutes).published).isGreaterThan(0)
    assertThat(a.headFiles()).hasSize(1)
    assertThat(sync.lastHold.value).isNull()
  }

  /**
   * I7 (correzioni della Fase 8): con le persone non montate per un guasto il gancio chiede di pubblicare **senza** la voce `faces`. La testa esce (i
   * dati personali non restano fermi per tutto il processo) ma non contiene la voce: chi la legge, e il ricordo della propria testa, non sanno niente
   * dei volti di questo dispositivo (nessuna conferma di una cancellazione, nessuna generazione trattata). Quando il gancio torna a dire `Proceed`,
   * la voce torna.
   */
  @Test
  fun i7_senzaLaVoceFacesLaTestaEsceENonDiceNienteDeiVolti() = runTest {
    val a = world.device()
    val b = world.device()
    val aView = PersonalHeadsRecord()
    val bView = PersonalHeadsRecord()
    val syncA = a.engine(RecordingPrePublishHook(), aView)
    val hook = RecordingPrePublishHook(PrePublish.ProceedWithout(setOf(FacesSettingCodec.KEY), "FACES_MOUNT_FAILED"))
    val syncB = b.engine(hook, bView)
    b.faces(FacesSetting.On(g, 1))
    b.favorite(mediaId(1))
    assertThat(syncB.sync(5.minutes).published).isGreaterThan(0)
    assertThat(b.headFiles()).hasSize(1)
    assertThat(syncB.lastHold.value).isEqualTo("FACES_MOUNT_FAILED")
    syncA.sync(5.minutes)
    val seen = aView.latestValueOf(FacesSettingCodec.KEY)[b.id]
    assertThat(seen).isNotNull()
    assertThat(seen!!.entry).isNull()
    assertThat(bView.latestValueOf(FacesSettingCodec.KEY)[b.id]!!.entry).isNull()
    // Lo stato di B non cambia: la voce resta nel suo stato, solo la testa non la porta. Con il gancio che torna a dire si', la voce esce.
    assertThat(FacesSettingCodec.read(b.store.state())).isEqualTo(FacesSetting.On(g, 1))
    hook.answer = PrePublish.Proceed
    world.clock.advance(6 * 60_000L)
    syncB.sync(5.minutes)
    syncA.sync(5.minutes)
    assertThat(FacesSettingCodec.parse(aView.latestValueOf(FacesSettingCodec.KEY)[b.id]!!.entry!!.value)).isEqualTo(FacesSetting.On(g, 1))
  }

  @Test
  fun unGancioCheLanciaFermaComeHold() = runTest {
    val a = world.device()
    val sync = a.engine(PrePublishHook { throw IllegalStateException("guasto") })
    a.faces(FacesSetting.Off(g))
    sync.sync(5.minutes)
    assertThat(a.headFiles()).isEmpty()
    assertThat(sync.lastHold.value).isEqualTo("PRE_PUBLISH_FAILED")
  }

  @Test
  fun colCancelloChiusoNonSiPubblicaENonSiChiamaIlGancio() = runTest {
    val a = world.device()
    val hook = RecordingPrePublishHook()
    val sync = a.engine(hook)
    a.faces(FacesSetting.Off(g))
    for (block in listOf(WriteBlock.FROZEN, WriteBlock.EMERGENCY_STOP, WriteBlock.READ_ONLY_AREA, WriteBlock.LOCAL_ONLY)) {
      a.block(block)
      sync.sync(5.minutes)
    }
    assertThat(hook.seen).isEmpty()
    assertThat(a.headFiles()).isEmpty()
  }

  /**
   * Proprieta' (D30, invariante 2 del §2.8 dal lato del motore): su storie a caso di due dispositivi, con la voce `faces` che cambia e il gancio
   * che risponde a caso, ogni testa che B pubblica dice per `faces` esattamente cio' che il gancio aveva appena approvato.
   */
  @Test
  fun proprietaOgniTestaHaUnoStatoApprovatoDalGancio() = runTest {
    val random = Random(30)
    repeat(20) {
      val world = PersonalWorld()
      val a = world.device()
      val b = world.device()
      val aView = PersonalHeadsRecord()
      val syncA = a.engine(RecordingPrePublishHook(), aView)
      var approved: PersonalEntry? = null
      var approvedAny = false
      val syncB = b.engine(
        PrePublishHook { state ->
          if (random.nextInt(3) == 0) {
            PrePublish.Hold("FACES_ERASE_PENDING")
          } else {
            approved = state[FacesSettingCodec.KEY]
            approvedAny = true
            PrePublish.Proceed
          }
        },
      )
      val generations = listOf(g, FaceGeneration.fromBytes(ByteArray(16) { 6 }))
      repeat(30) { step ->
        when (random.nextInt(5)) {
          0 -> a.faces(if (random.nextBoolean()) FacesSetting.On(generations[random.nextInt(2)], 1) else FacesSetting.Off(generations[random.nextInt(2)]))
          1 -> b.favorite(mediaId(step))
          2 -> syncA.sync(5.minutes)
          3 -> syncB.sync(5.minutes)
          else -> world.clock.advance(6 * 60_000L)
        }
        syncA.sync(5.minutes)
        val seen = aView.latestValueOf(FacesSettingCodec.KEY)[b.id]
        if (seen != null && approvedAny) {
          assertWithMessage("una testa di B dice per faces cio' che il gancio non ha approvato").that(seen.entry?.value).isEqualTo(approved?.value)
        }
      }
    }
  }
}
