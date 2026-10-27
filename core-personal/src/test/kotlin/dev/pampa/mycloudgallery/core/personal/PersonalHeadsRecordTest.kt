package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.noop.NoPrePublishHook
import dev.pampa.mycloudgallery.core.personal.sync.NasPersonalSync
import dev.pampa.mycloudgallery.core.personal.sync.PersonalHeadsRecord
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Fase 8, P8 (D30, §2.8 passo 10): la `PersonalHeadsView` scritta dal motore vero dei dati personali. Per ogni dispositivo dell'account dice cosa
 * contiene la sua ultima testa letta per intero (e la propria appena pubblicata), con l'ora del NAS; sopravvive alla morte del processo (il motore
 * non rilegge le teste che conosce); se il ricordo non si scrive il segno anti-rollback non si salva e la testa si rilegge; un file rovinato
 * vale "nessuna testa nota"; una chiave non guardata risponde vuoto.
 */
class PersonalHeadsRecordTest {

  private val world = PersonalWorld()
  private val g = FaceGeneration.fromBytes(ByteArray(16) { 8 })
  private val dir: File = Files.createTempDirectory("teste-personali").toFile()

  @After
  fun clean() {
    dir.deleteRecursively()
  }

  private fun PersonalDevice.engine(heads: PersonalHeadsRecord): NasPersonalSync {
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
      prePublish = NoPrePublishHook,
      heads = heads,
    )
  }

  private suspend fun PersonalDevice.faces(setting: FacesSetting) = store.set(FacesSettingCodec.KEY, FacesSettingCodec.valueOf(setting))

  @Test
  fun ogniTestaLettaPerInteroEQuellaPropriaDiconoLaVoce() = runTest {
    val a = world.device()
    val b = world.device()
    val aHeads = PersonalHeadsRecord()
    val bHeads = PersonalHeadsRecord()
    a.faces(FacesSetting.On(g, 1))
    a.engine(aHeads).sync(5.minutes)
    // La propria testa, appena pubblicata.
    assertThat(aHeads.latestValueOf(FacesSettingCodec.KEY).getValue(a.id).entry?.value).isEqualTo(FacesSettingCodec.on(g, 1))
    val syncB = b.engine(bHeads)
    syncB.sync(5.minutes)
    val seen = bHeads.latestValueOf(FacesSettingCodec.KEY)
    assertThat(seen.getValue(a.id).entry?.value).isEqualTo(FacesSettingCodec.on(g, 1))
    // B ha unito la voce di A e ha pubblicato: anche la sua testa la contiene.
    assertThat(seen.getValue(b.id).entry?.value).isEqualTo(FacesSettingCodec.on(g, 1))
    world.clock.advance(6 * 60_000L)
    a.faces(FacesSetting.Off(g))
    a.engine(aHeads).sync(5.minutes)
    syncB.sync(5.minutes)
    val after = bHeads.latestValueOf(FacesSettingCodec.KEY)
    assertThat(after.getValue(a.id).entry?.value).isEqualTo(FacesSettingCodec.off(g))
    assertThat(after.getValue(a.id).headAtNas).isEqualTo(world.clock.now)
    // Una chiave non guardata: nessuna testa nota.
    assertThat(bHeads.latestValueOf(PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, "altro"))).isEmpty()
  }

  @Test
  fun ilRicordoSopravviveAllaMorteDelProcesso() = runTest {
    val a = world.device()
    val b = world.device()
    a.faces(FacesSetting.Off(g))
    a.engine(PersonalHeadsRecord()).sync(5.minutes)
    val file = File(dir, PersonalHeadsRecord.FILE)
    b.engine(PersonalHeadsRecord(file)).sync(5.minutes)
    // Un processo nuovo: il motore riconosce la testa di A dal segno e non la rilegge, ma il ricordo c'e'.
    val restarted = PersonalHeadsRecord(file)
    val reads = world.fs(b.share).reads
    b.engine(restarted).sync(5.minutes)
    assertThat(restarted.latestValueOf(FacesSettingCodec.KEY).getValue(a.id).entry?.value).isEqualTo(FacesSettingCodec.off(g))
    assertThat(world.fs(b.share).reads - reads).isLessThan(5)
  }

  @Test
  fun seIlRicordoNonSiScriveLaTestaSiRilegge() = runTest {
    val a = world.device()
    val b = world.device()
    a.faces(FacesSetting.Off(g))
    a.engine(PersonalHeadsRecord()).sync(5.minutes)
    // La cartella del file e' un file: la scrittura non riesce.
    val blocker = File(dir, "non-una-cartella").apply { writeText("x") }
    val broken = PersonalHeadsRecord(File(blocker, PersonalHeadsRecord.FILE))
    b.engine(broken).sync(5.minutes)
    assertThat(b.store.marks()[a.id]).isNull()
    // Con un file che si scrive la testa si rilegge e il segno si salva.
    val good = PersonalHeadsRecord(File(dir, PersonalHeadsRecord.FILE))
    b.engine(good).sync(5.minutes)
    assertThat(b.store.marks()[a.id]).isNotNull()
    assertThat(good.latestValueOf(FacesSettingCodec.KEY).getValue(a.id).entry?.value).isEqualTo(FacesSettingCodec.off(g))
  }

  @Test
  fun unFileRovinatoValeNessunaTestaNota() = runTest {
    val file = File(dir, PersonalHeadsRecord.FILE)
    for (text in listOf("", "altro formato\n", "mcg-personal-heads 1\nD\tx\n", "mcg-personal-heads 1\nE\tsetting\tYQ==\tYQ==\t1\tx\tR\n", "mcg-personal-heads 9\n")) {
      file.writeText(text)
      assertThat(PersonalHeadsRecord(file).latestValueOf(FacesSettingCodec.KEY)).isEmpty()
    }
  }
}
