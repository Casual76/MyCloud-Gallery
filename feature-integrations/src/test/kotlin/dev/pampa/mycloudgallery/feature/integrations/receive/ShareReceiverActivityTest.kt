package dev.pampa.mycloudgallery.feature.integrations.receive

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.FakeIncomingDestinations
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.FakeIncomingQueue
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.share.ReceiveCounter
import dev.pampa.mycloudgallery.feature.integrations.testing.FakeEntryGate
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 5B, P11 (D24, C17, C18, E-S11, E-X2, E-X7, E-X8): `ShareReceiverActivity` come la apre un'altra app, con un provider vero che conta le aperture.
 * Un intento che non e' per noi chiude subito; con l'app bloccata non si apre **nessun** flusso; `file://` e i provider dell'app non si aprono mai;
 * i file si copiano da soli ma il caricamento parte **solo** dal tocco su "Salva sul NAS"; indietro toglie le copie.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class ShareReceiverActivityTest {

  @get:Rule val compose = createEmptyComposeRule()

  @get:Rule val folder = TemporaryFolder()

  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val gate = FakeEntryGate(EntryDecision.ALLOWED)
  private val queue = FakeIncomingQueue()
  private val destinations = FakeIncomingDestinations()
  private val clock = FakeBackupClock()
  private val unlocks = AtomicInteger()
  private lateinit var uploads: DefaultIncomingUploads
  private val incoming: File get() = File(folder.root, "incoming")

  @Before
  fun setUp() {
    FlowCounters.reset()
    SharingProvider.opens.set(0)
    SharingProvider.files.clear()
    IntegrationPorts.reset()
    ReceivePorts.reset()
    Robolectric.setupContentProvider(SharingProvider::class.java, AUTHORITY)
    uploads = DefaultIncomingUploads(IncomingStager(incoming, freeBytes = { 100L shl 30 }, bounds = ImageBoundsReader { null }), queue, clock)
  }

  @After
  fun tearDown() {
    IntegrationPorts.reset()
    ReceivePorts.reset()
    FlowCounters.reset()
  }

  private fun install() {
    IntegrationPorts.install(gate, null)
    ReceivePorts.install(uploads, destinations, unlockThatWorks())
  }

  /** Lo sblocco con la credenziale di sistema che riesce: il cancello si apre. */
  private fun unlockThatWorks(): ReceiveUnlock = object : ReceiveUnlock {
    override suspend fun unlock(activity: androidx.fragment.app.FragmentActivity): Boolean {
      unlocks.incrementAndGet()
      gate.decision = EntryDecision.ALLOWED
      return true
    }
  }

  /** Lo sblocco che non riesce mai (l'utente annulla la schermata di sistema). */
  private fun unlockThatFails(): ReceiveUnlock = object : ReceiveUnlock {
    override suspend fun unlock(activity: androidx.fragment.app.FragmentActivity): Boolean {
      unlocks.incrementAndGet()
      return false
    }
  }

  private fun shared(name: String, bytes: ByteArray): Uri {
    SharingProvider.files[name] = File(folder.root, name).apply { writeBytes(bytes) }
    return Uri.parse("content://$AUTHORITY/$name")
  }

  private fun send(vararg uris: Uri, multiple: Boolean = false): Intent {
    val intent = Intent(context, ShareReceiverActivity::class.java).setType("image/*")
    if (multiple || uris.size > 1) {
      intent.action = Intent.ACTION_SEND_MULTIPLE
      intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
    } else {
      intent.action = Intent.ACTION_SEND
      intent.putExtra(Intent.EXTRA_STREAM, uris.single())
    }
    return intent
  }

  private fun launch(intent: Intent): ActivityScenario<ShareReceiverActivity> = ActivityScenario.launchActivityForResult<ShareReceiverActivity>(intent).also { compose.waitForIdle() }

  private fun waitForText(text: String, partial: Boolean = false) {
    compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = partial).fetchSemanticsNodes().isNotEmpty() }
  }

  // --- Intenti che non sono per noi ----------------------------------------------------------------------------------------------------------

  @Test
  fun unIntentoCheNonEUnaCondivisioneDiMediaChiudeSubitoAnnullatoSenzaAprireNiente() {
    install()
    val uri = shared("a.jpg", ReceiveSamples.jpeg())
    for (hostile in listOf(
      Intent(Intent.ACTION_VIEW).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri),
      Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri),
      Intent(Intent.ACTION_SEND).setType("image/jpeg"), // nessun flusso
      Intent("azione.sconosciuta"),
    )) {
      hostile.setClass(context, ShareReceiverActivity::class.java)
      val activity = Robolectric.buildActivity(ShareReceiverActivity::class.java, hostile).create().get()
      assertThat(activity.isFinishing).isTrue()
      assertThat(shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
    }
    assertThat(SharingProvider.opens.get()).isEqualTo(0)
    assertThat(FlowCounters.receive.get(ReceiveCounter.REFUSED_REQUEST)).isEqualTo(4L)
    assertThat(FlowCounters.receive.get(ReceiveCounter.OPENED)).isEqualTo(0L)
  }

  /**
   * Campo, gruppo A (D1, `IMG_mcg_0106`; CA-2): una condivisione nuova che trova davanti la schermata di una vecchia arriva in `onNewIntent` e **prende il
   * posto della vecchia in quella stessa schermata**: nessuna activity nuova, nessun task, nessun `startActivity` (che Android 14 e 15 possono bloccare in
   * silenzio, perdendo la nuova e chiudendo la vecchia). Il file nuovo si legge con il permesso che porta `onNewIntent`; il vecchio, non salvato, si toglie.
   */
  @Test
  fun d1_unaCondivisioneNuovaDavantiAUnaVecchiaPrendeIlPostoNellaStessaSchermata() {
    install()
    val first = shared("a.jpg", ReceiveSamples.jpeg(3_000, 1))
    val second = shared("b.jpg", ReceiveSamples.jpeg(2_000, 2))
    val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, send(first)).setup()
    val activity = controller.get()
    awaitState(activity) { it.screen == ReceiveScreenState.READY && it.bytes == 3_000L }
    val opensBefore = SharingProvider.opens.get()

    controller.newIntent(send(second))
    awaitState(activity) { it.screen == ReceiveScreenState.READY && it.bytes == 2_000L }

    // Nessuna schermata nuova, nessun task: la schermata e' sempre la stessa e non si e' chiusa.
    assertThat(shadowOf(context as Application).nextStartedActivity).isNull()
    assertThat(activity.isFinishing).isFalse()
    assertThat(activity.intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)).isEqualTo(second)
    // Il flusso nuovo si e' letto, il vecchio (non salvato) e' stato tolto dopo.
    assertThat(SharingProvider.opens.get()).isGreaterThan(opensBefore)
    awaitState(activity) { incoming.listFiles().orEmpty().size == 1 }
  }

  @Suppress("DEPRECATION")
  private fun <T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String): T? = getParcelableExtra(name)

  /** Aspetta (con i thread di lavoro veri e il ciclo principale a mano) che lo stato della schermata soddisfi [condition]. */
  private fun awaitState(activity: ShareReceiverActivity, condition: (ReceiveUi) -> Boolean) {
    val deadline = System.nanoTime() + 15_000_000_000L
    while (System.nanoTime() < deadline) {
      shadowOf(android.os.Looper.getMainLooper()).idle()
      val ui = activity.receiveStateForTest()
      if (ui != null && condition(ui)) return
      Thread.sleep(20)
    }
    throw AssertionError("Lo stato atteso non e' arrivato: ${activity.receiveStateForTest()}")
  }

  // --- Porte non installate e app bloccata -------------------------------------------------------------------------------------------------------

  @Test
  fun senzaLePorteInstallateNonSiCopiaNienteENonSiSalvaNiente() {
    val scenario = launch(send(shared("a.jpg", ReceiveSamples.jpeg())))

    waitForText("Prima configura MyCloud Gallery")

    assertThat(SharingProvider.opens.get()).isEqualTo(0)
    assertThat(incoming.listFiles().orEmpty().toList()).isEmpty()
    compose.onNodeWithText("Chiudi").performClick()
    compose.waitForIdle()
    assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_CANCELED)
  }

  @Test
  fun conLAppBloccataPrimaLoSbloccoEFinoAlloraNessunFlussoSiApre_E_X7() {
    gate.decision = EntryDecision.NEEDS_UNLOCK
    IntegrationPorts.install(gate, null)
    // L'utente annulla la schermata delle credenziali: resta lo sblocco, e basta.
    ReceivePorts.install(uploads, destinations, unlockThatFails())

    val scenario = launch(send(shared("a.jpg", ReceiveSamples.jpeg())))
    waitForText("MyCloud Gallery è bloccata")

    // Bloccata: nessun flusso aperto, niente copiato, nessun riepilogo in vista.
    assertThat(SharingProvider.opens.get()).isEqualTo(0)
    assertThat(incoming.listFiles().orEmpty().toList()).isEmpty()
    assertThat(compose.onAllNodesWithText("Salva sul NAS", substring = false).fetchSemanticsNodes().size).isEqualTo(1) // solo il titolo in alto
    scenario.close()
  }

  /**
   * IN-3 della verifica funzionale 5B: l'app e' chiusa, WhatsApp condivide, il processo nasce e sta ancora leggendo il suo stato. La schermata non
   * dice "Prima configura MyCloud Gallery" (era una corsa) e non apre lo sblocco: aspetta, e appena lo stato c'e' copia da sola.
   */
  @Test
  fun in3_aFreddoAspettaLoStatoENonDiceMaiPrimaConfigura() {
    gate.decision = EntryDecision.LOADING
    install()
    val scenario = launch(send(shared("a.jpg", ReceiveSamples.jpeg(3_000, 1))))

    assertThat(compose.onAllNodesWithText("Prima configura MyCloud Gallery").fetchSemanticsNodes()).isEmpty()
    assertThat(compose.onAllNodesWithText("MyCloud Gallery è bloccata").fetchSemanticsNodes()).isEmpty()
    assertThat(SharingProvider.opens.get()).isEqualTo(0) // niente si apre finche' non si sa

    gate.decision = EntryDecision.ALLOWED
    waitForText("1 foto", partial = true)
    assertThat(SharingProvider.opens.get()).isEqualTo(1)
    scenario.close()
  }

  /** IN-10: annullata la credenziale di sistema non si riapre da sola in ciclo (parte una volta, poi solo con il tasto). */
  @Test
  fun in10_annullareLaCredenzialeNonLaRiapreInCiclo() {
    gate.decision = EntryDecision.NEEDS_UNLOCK
    IntegrationPorts.install(gate, null)
    ReceivePorts.install(uploads, destinations, unlockThatFails())

    val scenario = launch(send(shared("a.jpg", ReceiveSamples.jpeg())))
    waitForText("MyCloud Gallery è bloccata")
    assertThat(unlocks.get()).isEqualTo(1)
    scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    compose.waitForIdle()
    assertThat(unlocks.get()).isEqualTo(1)
    // Il tasto riprova.
    compose.onNodeWithText("Sblocca").performClick()
    compose.waitForIdle()
    assertThat(unlocks.get()).isEqualTo(2)
    scenario.close()
  }

  /** IN-6: col backup in prova (D12, R2) la schermata lo dice **prima di copiare** e non apre il flusso dell'altra app. */
  @Test
  fun in6_ConIlBackupInProvaDiceInProvaENonCopiaNiente() {
    destinations.trialOnly = true
    install()
    val scenario = launch(send(shared("a.jpg", ReceiveSamples.jpeg(3_000, 1))))

    waitForText("Backup in prova")
    compose.onNodeWithText("Il backup è ancora in prova", substring = true).assertExists()
    assertThat(SharingProvider.opens.get()).isEqualTo(0)
    assertThat(incoming.listFiles().orEmpty().toList()).isEmpty()
    assertThat(compose.onAllNodesWithText("In quella cartella non si può scrivere", substring = true).fetchSemanticsNodes()).isEmpty()
    compose.onNodeWithText("Chiudi").performClick()
    compose.waitForIdle()
    assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_CANCELED)
  }

  /** IN-11: con i file spariti "Riprova" chiudeva la schermata: adesso il tasto dice "Chiudi". */
  @Test
  fun in11_ConIFileSpariti_IlTastoDiceChiudiNonRiprova() {
    install()
    val scenario = launch(send(shared("a.jpg", ReceiveSamples.jpeg(3_000, 1))))
    waitForText("Salva sul NAS", partial = false)
    // I4: la frase «Non carico niente finche'...» non c'e' piu': la pagina e' pronta quando c'e' il tasto «Salva sul NAS» sotto il titolo.
    compose.waitUntil(10_000) { compose.onAllNodesWithText("Salva sul NAS").fetchSemanticsNodes().size >= 2 }
    incoming.deleteRecursively()
    compose.onAllNodesWithText("Salva sul NAS")[1].performClick()
    waitForText("I file copiati non ci sono più", partial = true)
    assertThat(compose.onAllNodesWithText("Riprova").fetchSemanticsNodes()).isEmpty()
    compose.onNodeWithText("Chiudi", useUnmergedTree = false).performClick()
    compose.waitForIdle()
    assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_CANCELED)
  }

  @Test
  fun dopoLoSbloccoLaCopiaPartedaSolaEIlCaricamentoNo() {
    gate.decision = EntryDecision.NEEDS_UNLOCK
    install()
    val scenario = launch(send(shared("a.jpg", ReceiveSamples.jpeg(3_000, 1)), shared("b.jpg", ReceiveSamples.jpeg(4_000, 2))))

    waitForText("2 foto · ${android.text.format.Formatter.formatShortFileSize(context, 7_000)}")

    assertThat(unlocks.get()).isEqualTo(1)
    assertThat(SharingProvider.opens.get()).isEqualTo(2) // aperti solo dopo lo sblocco
    assertThat(queue.enqueued).isEmpty() // il caricamento aspetta il tocco
    scenario.close()
  }

  // --- Il tocco, "Salva sul NAS" ---------------------------------------------------------------------------------------------------------------

  @Test
  fun iFileSiCopianoDaSoliIlCaricamentoParteSoloDalTocco_E_S11() {
    install()
    val scenario = launch(send(shared("a.jpg", ReceiveSamples.jpeg(3_000, 1)), shared("v.mp4", ReceiveSamples.mp4(5_000))))

    waitForText("1 foto e 1 video · ${android.text.format.Formatter.formatShortFileSize(context, 8_000)}")
    assertThat(queue.enqueued).isEmpty()
    assertThat(incoming.listFiles().orEmpty()).hasLength(1)

    compose.onNodeWithText("Ricevute").assertExists()
    // Il bottone: il testo "Salva sul NAS" compare due volte (titolo e bottone); si tocca l'ultimo.
    val nodes = compose.onAllNodesWithText("Salva sul NAS")
    nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
    waitForText("2 file in coda")

    assertThat(queue.enqueued).hasSize(1)
    assertThat(queue.enqueued.single().second).hasSize(2)
    compose.onNodeWithText("Chiudi").performClick()
    compose.waitForIdle()
    assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_OK)
    // Le copie restano al diario.
    assertThat(incoming.listFiles().orEmpty()).hasLength(1)
  }

  @Test
  fun fileSchemeEProviderNostriNonSiAprononoEPartitoSoloIlBuono_E_X2() {
    install()
    val good = shared("a.jpg", ReceiveSamples.jpeg())
    val intent = send(Uri.parse("file:///data/data/${context.packageName}/databases/x.db"), Uri.parse("content://${context.packageName}.integrations.files/share/x.jpg"), good)

    val scenario = launch(intent)
    waitForText("1 indicati come percorso del telefono, non come file")
    compose.onNodeWithText("1 sono file di MyCloud Gallery stessa").assertExists()

    assertThat(SharingProvider.opens.get()).isEqualTo(1) // solo il file buono
    scenario.close()
  }

  @Test
  fun tuttoRifiutatoMostraIMotiviENonLasciaNiente() {
    install()
    val html = shared("falso.jpg", ReceiveSamples.fakeJpegThatIsHtml())

    val scenario = launch(send(html))
    waitForText("Niente da salvare")

    compose.onNodeWithText("1 non sono foto né video").assertExists()
    assertThat(incoming.listFiles().orEmpty().toList()).isEmpty()
    scenario.close()
  }

  @Test
  fun indietroTogliLeCopieEChiudeSenzaCaricare() {
    install()
    val scenario = launch(send(shared("a.jpg", ReceiveSamples.jpeg())))
    waitForText("Dove li salvo")
    assertThat(incoming.listFiles().orEmpty()).hasLength(1)

    compose.onNodeWithText("Annulla").performClick()
    compose.waitForIdle()

    assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_CANCELED)
    val deadline = System.nanoTime() + 5_000_000_000L
    while (incoming.listFiles().orEmpty().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
    assertThat(incoming.listFiles().orEmpty().toList()).isEmpty()
    assertThat(queue.enqueued).isEmpty()
  }

  /** Un provider di un'altra app: serve i file del test e conta quante volte un flusso viene aperto. */
  class SharingProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
      opens.incrementAndGet()
      val file = files[uri.lastPathSegment] ?: return null
      return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
      val opens = AtomicInteger()
      val files = java.util.concurrent.ConcurrentHashMap<String, File>()
    }
  }

  private companion object {
    const val AUTHORITY = "com.example.share"
  }
}
