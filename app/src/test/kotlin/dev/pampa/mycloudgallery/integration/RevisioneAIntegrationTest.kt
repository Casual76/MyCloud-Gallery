package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dagger.multibindings.IntoSet
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.ai.AppCoverage
import dev.pampa.mycloudgallery.ai.ViewerOcrImages
import dev.pampa.mycloudgallery.core.ai.compute.AiInputOrigin
import dev.pampa.mycloudgallery.core.ai.compute.AiOriginBuffer
import dev.pampa.mycloudgallery.core.ai.compute.AreaView
import dev.pampa.mycloudgallery.core.ai.compute.DeviceRoleView
import dev.pampa.mycloudgallery.core.ai.compute.OcrBodies
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.family.contract.RolesChange
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.index.ai.AiIndexDiag
import dev.pampa.mycloudgallery.core.index.ai.AiKey
import dev.pampa.mycloudgallery.core.index.ai.AiRowState
import dev.pampa.mycloudgallery.core.index.ai.AiStaged
import dev.pampa.mycloudgallery.core.index.ai.AiWinner
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.testing.FakeUnitPriority
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.search.contract.CoverageReason
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import dev.pampa.mycloudgallery.di.AiModule
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Revisione A della Fase 6 sul grafo vero dell'app (catalogo, indice locale, adattatori, pubblicatore, Diagnostica), con i motori finti:
 * - VT-1, I12, R4: il testo dal vivo dei Nascosti, e la lettura propria prima di quella arrivata dopo da un altro dispositivo;
 * - R5: la lettura al volo usa dal buffer solo un'anteprima propria;
 * - PR-2: il testo in attesa di una share si pubblica quando la share si apre anche su un dispositivo che non indicizza;
 * - PR-3: un'unita' del calcolo con risultati nell'outbox non si rilascia;
 * - CE-1: il filtro "Documenti" e "documenti" in Cerca trovano lo scontrino;
 * - PR-1: i motivi della copertura vengono dal catalogo;
 * - DG-1: i dati AI dell'indice arrivano in Diagnostica.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class RevisioneAIntegrationTest {

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

  private fun phonePhoto(): FakeMediaStore {
    val store = NasGalleries.newStore()
    val bytes = AiRig.jpeg(90)
    store.put(AiRig.photoRow(1, "DCIM/mcg-prova/", "IMG_0001.jpg", bytes, rig.time), bytes)
    return store
  }

  /** Un corpo `ocr` di [lines] righe, ognuna "[text] n". */
  private fun body(text: String, lines: Int = 1): OcrBody = checkNotNull(
    OcrBodies.of(
      OcrSource.ANALYSIS,
      (0 until lines).map { i ->
        val line = "$text $i"
        val n = line.split(' ').size
        val spans = (0 until n).map { k -> WordSpan(k * 65535 / n, (k + 1) * 65535 / n - if (k < n - 1) 100 else 0) }
        OcrLine(ShortBox(1000, 1000 + i * 3000, 60000, 3000 + i * 3000), 0, spans, line)
      },
      AiRecipe.CURRENT,
    ),
  )

  private suspend fun onlyCell(phone: AiRig.Mounted): Pair<MediaId, ContentKey> {
    val cell = phone.gallery.core().catalog.cellContentKeys().cells.single()
    return cell.mediaId to cell.own.first()
  }

  private suspend fun linesOf(stack: AiStack, id: MediaId): List<String>? = stack.search.mediaText.layout(id)?.lines?.map { it.text }

  @Test
  fun r4_laLetturaPropriaVinceSuQuellaArrivataDopoDaUnAltroDispositivo() = runBlocking<Unit> {
    val phone = rig.founder(phonePhoto())
    val stack = phone.stack
    assertThat(stack.search.open()).isTrue()
    val (id, key) = onlyCell(phone)
    stack.ai.results.saveText(key, body("mio"))
    stack.ai.replicaSink.apply(TestNas.COMMON, listOf(AiWinner(TestNas.COMMON, AiEntry.Text(key, body("altrui")), AiRecipe.CURRENT.ocrVersion, "dev-altro")))
    assertThat(linesOf(stack, id)).containsExactly("mio 0")
  }

  @Test
  fun vt1_nelViewerDeiNascostiIlTestoDalVivoCe() = runBlocking<Unit> {
    val phone = rig.founder(phonePhoto())
    val stack = phone.stack
    assertThat(stack.search.open()).isTrue()
    val (id, key) = onlyCell(phone)
    stack.ai.results.saveText(key, body("mio"))
    // D29: nascosto, il testo dal vivo c'e' ancora (il viewer dei Nascosti).
    phone.gallery.core().catalog.setPersonalFlags(id, PersonalFlags(hidden = true))
    assertThat(linesOf(stack, id)).containsExactly("mio 0")
    // La lettura al volo nel viewer dei Nascosti trova l'anteprima propria nel buffer; quella di un altro dispositivo no (R5).
    val ref = MediaRef.Synthetic(seed = 1, width = 640, height = 480)
    val buffer = stack.ai.buffer
    (buffer as AiOriginBuffer).putFrom(key, SegmentClass.ANALYSIS, AiRig.jpeg(10), AiInputOrigin.Preview(TestNas.COMMON, "dev-altro"))
    val images = ViewerOcrImages({ phone.gallery.core().catalog }, buffer, null, { null }, { null })
    assertThat(images.image(id, ref)).isNull()
    buffer.put(key, SegmentClass.ANALYSIS, AiRig.jpeg(11))
    assertThat(checkNotNull(images.image(id, ref)).from).isEqualTo(AiInputOrigin.Own)
  }

  @Test
  fun r4_laRevocaTogliIlTestoDiUnAltroDispositivoDalViewer() = runBlocking<Unit> {
    val phone = rig.founder(phonePhoto())
    val stack = phone.stack
    assertThat(stack.search.open()).isTrue()
    val (id, key) = onlyCell(phone)
    stack.ai.replicaSink.apply(TestNas.COMMON, listOf(AiWinner(TestNas.COMMON, AiEntry.Text(key, body("altrui")), AiRecipe.CURRENT.ocrVersion, "dev-altro")))
    assertThat(linesOf(stack, id)).containsExactly("altrui 0")
    stack.ai.replicaSink.forgetDevice("dev-altro")
    assertThat(linesOf(stack, id)).isNull()
  }

  @Test
  fun dg1_iDatiAiDellIndiceArrivanoInDiagnostica() = runBlocking<Unit> {
    val phone = rig.founder()
    val stack = phone.stack
    stack.ai.indexCounters.reject(RejectReason.SIGNER_NOT_APPROVED)
    stack.ai.indexCounters.disagree.incrementAndGet()
    stack.search.index.counters.overCap.incrementAndGet()
    // Cio' che Hilt mette nell'insieme dei contributori: i metodi `@IntoSet` di AiModule.
    val contributors = AiModule::class.java.methods
      .filter { it.isAnnotationPresent(IntoSet::class.java) && it.parameterTypes.contentEquals(arrayOf(AiStack::class.java)) }
      .map { it.invoke(AiModule, stack) as DiagnosticsContributor }
    val items = contributors.flatMap { it.collect() }
    val codes = items.map { it.code.id }
    assertThat(codes).containsAtLeast(AiIndexDiag.AI_SEGMENT_REJECTED.id, AiIndexDiag.AI_VECTOR_DISAGREE.id, AiIndexDiag.AI_INDEX_STATE.id)
    val state = items.single { it.code.id == AiIndexDiag.AI_INDEX_STATE.id }
    assertThat(state.export["localOverCap"]).isEqualTo(ExportValue.Num(1))
    val ids = AiIndexDiag.ALL.map { it.id }
    assertThat(items.filter { it.code.id in ids }.all { it.titleRes != 0 }).isTrue()
  }

  /** Il telefono fondatore con una foto nell'area comune di prova, elencata. */
  private suspend fun withNasPhoto(store: FakeMediaStore = NasGalleries.newStore()): AiRig.Mounted {
    val phone = rig.founder(store)
    rig.backend.put(TestNas.COMMON, "Foto/2024/IMG_0001.jpg", AiRig.jpeg(120), lastWriteMs = rig.time.now() - 2 * AiRig.DAY)
    val nas = checkNotNull(phone.gallery.nas)
    checkNotNull(nas.scan(ScanMode.Full, 5.minutes))
    phone.test.graph.trustGuard.preflight(Purpose.BACKGROUND)
    return phone
  }

  private suspend fun nasCell(phone: AiRig.Mounted): Pair<MediaId, ContentKey> {
    val catalog = phone.gallery.core().catalog
    val cells = catalog.cellContentKeys().cells
    val shares = catalog.nasSharesOf(cells.map { it.mediaId })
    val cell = cells.single { shares[it.mediaId].orEmpty().isNotEmpty() }
    return cell.mediaId to cell.own.first()
  }

  @Test
  fun pr2_ilTestoInAttesaSiPubblicaQuandoLaShareSiApreAncheSenzaIndicizzare() = runBlocking<Unit> {
    val phone = withNasPhoto()
    assertThat(phone.stack.roles.setRoles(indexer = false, preparer = false)).isInstanceOf(RolesChange.Saved::class.java)
    val (_, key) = nasCell(phone)
    val store = phone.stack.ai.publishStore
    // Il testo letto al volo mentre la share era "solo locale" (F4-D31): in attesa, con il corpo gia' calcolato.
    val bytes = checkNotNull(AiSegments.bodyOrNull(body("letto al volo")))
    store.stage(TestNas.COMMON, AiRowState.HELD, listOf(AiStaged(AiClass.OCR, key, AiRecipe.CURRENT.ocrVersion, bytes)))
    assertThat(store.pending().waitingForShare).isEqualTo(1)

    // La share ora si puo' pubblicare: il giro periodico (nessuna tappa del calcolo su chi non indicizza) la rilascia e la pubblica.
    checkNotNull(phone.gallery.nas).flushOutbox()
    assertThat(store.pending().waitingForShare).isEqualTo(0)
    assertThat(store.pending().staged).isEqualTo(0)
    assertThat(store.live(TestNas.COMMON, listOf(AiKey(key, AiClass.OCR)))).isNotEmpty()
  }

  @Test
  fun pr3_unUnitaConRisultatiNellOutboxNonSiRilascia() = runBlocking<Unit> {
    val phone = withNasPhoto()
    val rt = checkNotNull(checkNotNull(phone.gallery.nas).runtime?.ai)
    val claim = checkNotNull(rt.units.next(TestNas.COMMON, WorkPass.AI, FakeUnitPriority()))
    val item = claim.items.first()
    rt.publisher.stage(TestNas.COMMON, listOf(AiEntry.Text(item.contentKey, body("pronto"))))
    rt.units.finish(claim, UnitOutcome.Completed(prepared = 1, failed = 0))
    assertThat(rt.units.stats.kept.get()).isEqualTo(1)
  }

  @Test
  fun ce1_ilFiltroDocumentiEDocumentiInCercaTrovanoLoScontrino() = runBlocking<Unit> {
    val phone = rig.founder(phonePhoto())
    val stack = phone.stack
    assertThat(stack.search.open()).isTrue()
    val (id, key) = onlyCell(phone)
    val library = phone.gallery.repository.snapshot
    withTimeout(AiRig.TIMEOUT) { library.first { s -> s != null && s.size == 1 } }
    assertThat(checkNotNull(library.value).hasFlag(0, CellFlags.DOCUMENT)).isFalse()

    stack.ai.results.saveText(key, body("riga dello scontrino", lines = 10))
    stack.search.documents.refresh()
    // Il bit arriva all'istantanea della Libreria: lo legge il filtro "Documenti".
    val snapshot = withTimeout(AiRig.TIMEOUT) { library.first { s -> s != null && s.size == 1 && s.hasFlag(0, CellFlags.DOCUMENT) } }
    assertThat(checkNotNull(snapshot).idAt(0)).isEqualTo(id)
    val found = stack.search.engine.search(SearchRequest("documenti"))
    val view = found.confident
    assertThat((0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }).containsExactly(id)

    // Il testo cambia (qui: un testo corto sostituisce il primo): il bit si spegne.
    stack.ai.results.saveText(key, body("uscita"))
    stack.search.documents.refresh()
    withTimeout(AiRig.TIMEOUT) { library.first { s -> s != null && s.size == 1 && !s.hasFlag(0, CellFlags.DOCUMENT) } }
  }

  @Test
  fun pr1_iMotiviDellaCoperturaVengonoDalCatalogo() = runBlocking<Unit> {
    val phone = withNasPhoto(phonePhoto())
    val catalog = phone.gallery.core().catalog
    val ids = catalog.cellContentKeys().cells.map { it.mediaId }
    assertThat(ids).hasSize(2)
    val now = rig.time.now()
    fun coverage(indexer: Boolean, devices: List<DeviceRoleView>) = AppCoverage(
      areas = { listOf(AreaView(TestNas.COMMON, null)) },
      devices = { devices },
      nasNow = { now },
      catalog = { catalog },
      indexerHere = { indexer },
      modelReadyHere = { false },
      unsupported = { phone.stack.ai.hooks.unsupported },
      readingOff = { false },
    )
    // Nessun indicizzatore: la foto del NAS aspetta per l'area, quella del solo telefono perche' qui non si analizza.
    assertThat(coverage(false, listOf(DeviceRoleView(TestNas.ADMIN_USER, false, now))).reasons(ids))
      .containsExactly(CoverageReason.NO_INDEXER_FOR_AREA, 1, CoverageReason.PHONE_ONLY_NOT_INDEXER, 1)
    // Indicizzatore qui senza il modello: l'area e' coperta (preparazione) e la foto del telefono aspetta il modello.
    assertThat(coverage(true, listOf(DeviceRoleView(TestNas.ADMIN_USER, true, now))).reasons(ids))
      .containsExactly(CoverageReason.PREPARATION, 1, CoverageReason.MODEL_MISSING, 1)
    // Un segno "non supportato" dai propri byte vale per la cella.
    val (nasId, nasKey) = nasCell(phone)
    phone.stack.ai.hooks.unsupported.mark(nasKey, AiClass.VEC, AiInputOrigin.Own)
    assertThat(coverage(true, listOf(DeviceRoleView(TestNas.ADMIN_USER, true, now))).reasons(listOf(nasId)))
      .containsExactly(CoverageReason.UNSUPPORTED, 1)
  }
}
