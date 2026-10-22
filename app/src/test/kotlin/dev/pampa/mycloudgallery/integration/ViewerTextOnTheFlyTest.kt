package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.ai.ViewerOcrImages
import dev.pampa.mycloudgallery.core.family.contract.RolesChange
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepControl
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 6, P11b (docs/fase6-progetto.md §2.10 "Testo dal vivo", D41, D42, E-V9): la **lettura al volo** dell'immagine mostrata nel viewer.
 * Prima rispondeva solo dall'anteprima gia' nel buffer (cioe' solo sugli indicizzatori); adesso legge dal telefono (anteprima preparata dai
 * byte della foto) e dall'HQ della replica (un media del NAS), **solo con la lettura attiva qui**, e il risultato segue il percorso normale:
 * salvato nell'indice locale e pubblicato come il testo letto dal calcolo.
 *
 * Il lettore finto fallisce se qualcuno lo chiama con la lettura spenta (D41, regola di P4): e' la prova che da spenti non si legge mai.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class ViewerTextOnTheFlyTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private lateinit var rig: AiRig

  @Before
  fun setUp() {
    rig = AiRig(app, temp.root)
  }

  @After
  fun tearDown() = rig.close()

  private fun phonePhoto(): dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore {
    val store = NasGalleries.newStore()
    val bytes = AiRig.jpeg(90)
    store.put(AiRig.photoRow(1, "DCIM/mcg-prova/", "IMG_0001.jpg", bytes, rig.time), bytes)
    return store
  }

  /** Il media e il riferimento ai suoi byte come li darebbe la Libreria al viewer. */
  private suspend fun shown(phone: AiRig.Mounted): Pair<MediaId, MediaRef> {
    val catalog = phone.gallery.core().catalog
    val id = catalog.cellContentKeys().cells.single().mediaId
    val ref: MediaRef = catalog.phoneRefs(listOf(id))[id] ?: catalog.nasRefs(listOf(id)).getValue(id)
    return id to ref
  }

  @Test
  fun conLaLetturaSpentaIlViewerNonChiamaMaiIlLettoreNeMLKit() = runBlocking<Unit> {
    val phone = rig.founder(phonePhoto())
    val hooks = phone.stack.viewerHooks(phone.test.graph, phone.gallery)
    val reader = checkNotNull(hooks.textReader)
    val (id, ref) = shown(phone)

    assertThat(phone.stack.textSwitch.state.value.activeHere).isFalse()
    assertThat(reader.available).isFalse()
    assertThat(reader.readNow(id, ref)).isNull()
    // Il lettore finto avrebbe lanciato un errore se fosse stato chiamato; e non ha letto niente.
    assertThat(phone.reader.reads).isEmpty()
    assertThat(phone.stack.search.index.sizes).isEqualTo(0 to 0)
  }

  @Test
  fun unaFotoDelTelefonoSiLeggeAlVoloEIlTestoSiSalva() = runBlocking<Unit> {
    val phone = rig.founder(phonePhoto())
    rig.enableReading(phone)
    assertThat(phone.stack.search.open()).isTrue()
    val hooks = phone.stack.viewerHooks(phone.test.graph, phone.gallery)
    val reader = checkNotNull(hooks.textReader)
    val (id, ref) = shown(phone)
    assertWithMessage("prima non c'e' testo letto").that(checkNotNull(hooks.textSource).layout(id)).isNull()

    val layout = checkNotNull(reader.readNow(id, ref))

    // L'anteprima e' stata preparata ora dai byte del telefono (come il calcolo) e il lettore e' stato chiamato una volta.
    assertThat(phone.reader.reads).containsExactly(OcrSource.PHONE_ORIGINAL)
    assertThat(layout.lines.map { it.text }).containsExactly("Pizzeria Mario")
    // Salvato: la prossima volta il testo dal vivo e la ricerca lo trovano senza rileggere (D43).
    assertThat(checkNotNull(hooks.textSource).layout(id)?.lines?.map { it.text }).containsExactly("Pizzeria Mario")
    assertThat(phone.stack.search.index.sizes).isEqualTo(0 to 1)
  }

  @Test
  fun seIlTempoDelViewerScadeNienteSiSalvaENienteSiMostra() = runBlocking<Unit> {
    val phone = rig.founder(phonePhoto(), readerLatencyMs = 10_000)
    rig.enableReading(phone)
    assertThat(phone.stack.search.open()).isTrue()
    // Un lettore lento e un tetto di 50 ms (in produzione sono 2 secondi).
    val hooks = phone.stack.viewerHooks(phone.test.graph, phone.gallery, textTimeoutMs = 50)
    val (id, ref) = shown(phone)

    assertThat(checkNotNull(hooks.textReader).readNow(id, ref)).isNull()

    assertThat(phone.stack.search.index.sizes).isEqualTo(0 to 0)
    assertThat(checkNotNull(hooks.textSource).layout(id)).isNull()
  }

  @Test
  fun loOriginaleGiaSulDispositivoSiPreparaSoloSeEQuelloDellaChiave() = runBlocking<Unit> {
    val phone = rig.founder()
    val original = AiRig.jpeg(150)
    rig.backend.put(TestNas.COMMON, "Foto/2024/IMG_0001.jpg", original, lastWriteMs = rig.time.now() - 2 * AiRig.DAY)
    checkNotNull(checkNotNull(phone.gallery.nas).scan(ScanMode.Full, 5.minutes))
    val (id, ref) = shown(phone)
    val nasRef = ref as MediaRef.Nas
    val catalog = phone.gallery.core().catalog
    val key = catalog.cellContentKeys().cells.single().own.first()
    val quick = checkNotNull(catalog.media(id)).currentVersion.fingerprint.quickHash
    val file = File(temp.root, "originale.jpg").also { it.writeBytes(original) }
    val analysis = AiRig.jpeg(33)

    // Un preparatore finto che dice l'impronta dei byte che ha letto: ogni sorgente e' il file dell'originale gia' qui.
    fun preparer(quickHash: QuickHash?) = object : Preparer {
      val sources = mutableListOf<PrepareSource>()

      override suspend fun prepare(source: PrepareSource, spec: PrepSpec): PrepResult {
        sources += source
        return PrepResult.Done(listOf(PrepOutput.Image(SegmentClass.ANALYSIS, analysis, 640, 480)), null, null, null, quickHash)
      }
    }
    val honest = preparer(quick)
    val found = checkNotNull(
      ViewerOcrImages(
        { catalog }, phone.stack.ai.buffer, null, { honest }, { null },
        { file },
      ).image(id, nasRef),
    )

    assertThat(found.source).isEqualTo(OcrSource.ANALYSIS)
    assertThat(found.bytes).isEqualTo(analysis)
    assertThat(found.key).isEqualTo(key)
    assertThat(honest.sources.single()).isInstanceOf(PrepareSource.Local::class.java)

    // D8: se i byte letti non hanno l'impronta della chiave (il file e' cambiato dopo l'elenco) non si legge niente.
    val other = preparer(QuickHash("ab".repeat(32)))
    assertThat(ViewerOcrImages({ catalog }, phone.stack.ai.buffer, null, { other }, { null }, { file }).image(id, nasRef)).isNull()
    // Senza l'originale sul dispositivo (e senza HQ) non si scarica niente: nessuna immagine.
    assertThat(ViewerOcrImages({ catalog }, phone.stack.ai.buffer, null, { honest }, { null }, { null }).image(id, nasRef)).isNull()
  }

  @Test
  fun unaFotoDelNasSiLeggeDallHqEIlTestoArrivaAgliAltriDispositivi() = runBlocking<Unit> {
    val phoneDevice = rig.foundedDevice()
    val tabletDevice = rig.joinTablet(phoneDevice)
    val phone = rig.startMounted(rig.mount(phoneDevice, TestNas.ADMIN_USER))
    rig.backend.put(TestNas.COMMON, "Foto/2024/IMG_0001.jpg", AiRig.jpeg(120), lastWriteMs = rig.time.now() - 2 * AiRig.DAY)

    // Il telefono: scansione e preparazione (l'HQ finisce nella replica); poi si svuota il buffer, come su un dispositivo che non l'ha.
    val phoneNas = checkNotNull(phone.gallery.nas)
    // L'HQ resta nella replica solo con la qualita' "Alta" (D16).
    phoneNas.setQuality(QualityLevel.HIGH)
    rig.enableReading(phone)
    checkNotNull(phoneNas.scan(ScanMode.Full, 5.minutes))
    phone.test.graph.trustGuard.preflight(Purpose.BACKGROUND)
    assertThat(checkNotNull(phoneNas.prepare(5.minutes, PrepControl.ALWAYS)).prepared).isEqualTo(1)
    phoneNas.flushOutbox()
    phone.stack.ai.buffer.clear()
    assertThat(phone.stack.search.open()).isTrue()

    val hooks = phone.stack.viewerHooks(phone.test.graph, phone.gallery)
    val (id, ref) = shown(phone)
    assertThat(ref).isInstanceOf(MediaRef.Nas::class.java)
    val layout = checkNotNull(checkNotNull(hooks.textReader).readNow(id, ref))

    assertThat(phone.reader.reads).containsExactly(OcrSource.HQ)
    assertThat(layout.lines.map { it.text }).containsExactly("Pizzeria Mario")
    // Il file e' nell'area comune di prova: il testo e' nell'outbox `ai` e si pubblica (F4-D31 lo consente su una share di prova).
    phoneNas.flushOutbox()
    assertThat(phone.stack.ai.publishStore.pending().waitingForShare).isEqualTo(0)

    // Il tablet (non indicizzatore, lettura spenta) riceve il testo con la sincronizzazione: stesso testo dal vivo, nessuna lettura (D42).
    val tablet = rig.startMounted(rig.mount(tabletDevice, TestNas.MEMBER_USER))
    val tabletNas = checkNotNull(tablet.gallery.nas)
    assertThat(tablet.stack.roles.setRoles(indexer = false, preparer = false)).isInstanceOf(RolesChange.Saved::class.java)
    checkNotNull(tabletNas.syncIndex(5.minutes))
    checkNotNull(tabletNas.scan(ScanMode.Full, 5.minutes))
    assertThat(tablet.stack.search.open()).isTrue()
    val tabletId = tablet.gallery.core().catalog.cellContentKeys().cells.single().mediaId
    val received = checkNotNull(tablet.stack.search.mediaText.layout(tabletId))
    assertThat(received.lines.map { it.text }).containsExactly("Pizzeria Mario")
    assertThat(tablet.reader.reads).isEmpty()
  }
}
