package dev.pampa.mycloudgallery.feature.integrations.receive

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStageId
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.FakeIncomingDestinations
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.FakeIncomingQueue
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.incomingStream
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.testing.FakeEntryGate
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * "Aggiungi ad album" dalla condivisione in arrivo (Fase 7, P10, D35, F5-D24, E-S11): la scelta dell'album sta al posto di quella della cartella; il
 * salvataggio e' sempre "Salva sul NAS" nella cartella scelta, col tocco che emette la conferma; la promessa dell'album si registra solo dopo che i file sono
 * in coda; una porta che non risponde non toglie niente (i file restano in coda e la schermata lo dice).
 */
class ReceiveAlbumTest {

  @get:Rule val folder = TemporaryFolder()

  private val gate = FakeEntryGate(EntryDecision.ALLOWED)
  private val queue = FakeIncomingQueue()
  private val destinations = FakeIncomingDestinations()
  private val clock = FakeBackupClock()
  private val scope = CoroutineScope(Job() + Dispatchers.Default)
  private lateinit var uploads: DefaultIncomingUploads

  private val mine = IncomingAlbumChoice("album-1", "Compleanno di Anna", shared = false)
  private val family = IncomingAlbumChoice("album-2", "Natale in famiglia", shared = true)

  /** Una porta degli album che ricorda cosa le si e' chiesto e in che ordine rispetto alla coda. */
  private inner class RecordingAlbums(
    private val offered: List<IncomingAlbumChoice> = listOf(mine, family),
    private val promise: Boolean = true,
    private val failOnOffer: Boolean = false,
    private val failOnPromise: Boolean = false,
  ) : IncomingAlbums {
    var offers = 0
    val promises = mutableListOf<Pair<IncomingStageId, IncomingAlbumChoice>>()

    /** Quanti file erano gia' in coda quando e' arrivata la promessa. */
    var queuedWhenPromised = -1

    override suspend fun albums(): List<IncomingAlbumChoice> {
      offers++
      if (failOnOffer) error("porta guasta")
      return offered
    }

    override suspend fun willAdd(stage: IncomingStageId, album: IncomingAlbumChoice): Boolean {
      queuedWhenPromised = queue.enqueued.size
      if (failOnPromise) error("porta guasta")
      promises += stage to album
      return promise
    }
  }

  @Before
  fun setUp() {
    FlowCounters.reset()
    uploads = DefaultIncomingUploads(IncomingStager(File(folder.root, "incoming"), freeBytes = { 100L shl 30 }, bounds = ImageBoundsReader { null }), queue, clock)
  }

  @After
  fun tearDown() {
    scope.cancel()
  }

  private fun streams(): List<IncomingStream> = listOf(incomingStream(ReceiveSamples.jpeg(3_000, 1)), incomingStream(ReceiveSamples.png(3_500, 2)))

  private fun model(albums: IncomingAlbums) = ReceiveViewModel(
    streams = { streams() }, overflow = 0, gate = gate, uploads = uploads, destinations = destinations, monotonicMs = { clock.monotonicMs() }, scope = scope, albums = albums,
  )

  private fun waitFor(model: ReceiveViewModel, screen: ReceiveScreenState) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (model.ui.value.screen != screen && System.nanoTime() < deadline) Thread.sleep(10)
    assertThat(model.ui.value.screen).isEqualTo(screen)
  }

  private fun ready(albums: IncomingAlbums): ReceiveViewModel = model(albums).also {
    it.refreshGate()
    waitFor(it, ReceiveScreenState.READY)
  }

  @Test
  fun senzaLaPortaNonCeLaVoce() {
    val model = ready(NoIncomingAlbums)
    assertThat(model.ui.value.albums).isEmpty()
    model.openAlbumPicker()
    assertThat(model.ui.value.albumPick).isFalse()
  }

  @Test
  fun glialbumOffertiSonoQuelliDellaPortaESiChiedonoSoloAFileCopiati() {
    gate.decision = EntryDecision.NEEDS_UNLOCK
    val albums = RecordingAlbums()
    val locked = model(albums)
    locked.refreshGate()
    assertThat(locked.ui.value.screen).isEqualTo(ReceiveScreenState.LOCKED)
    // Con l'app bloccata non si chiede nemmeno quali album ci siano (i titoli sono dati dell'utente).
    assertThat(albums.offers).isEqualTo(0)

    gate.decision = EntryDecision.ALLOWED
    val model = ready(RecordingAlbums())
    assertThat(model.ui.value.albums).containsExactly(mine, family).inOrder()
    assertThat(model.ui.value.albumPick).isFalse()
  }

  @Test
  fun laScelta_SostituisceQuellaDellaCartellaEPreselezionaIlPrimoAlbum() {
    val model = ready(RecordingAlbums())
    model.openAlbumPicker()
    assertThat(model.ui.value.albumPick).isTrue()
    assertThat(model.ui.value.selectedAlbum).isEqualTo(mine)
    model.selectAlbum(family)
    assertThat(model.ui.value.selectedAlbum).isEqualTo(family)
    model.closeAlbumPicker()
    assertThat(model.ui.value.albumPick).isFalse()
    // La cartella scelta non e' cambiata: e' dove si salva comunque.
    assertThat(model.ui.value.selected).isEqualTo(FakeIncomingDestinations.defaultRoots().first())
  }

  @Test
  fun salvaEAggiungiMetteInCodaEPoiRegistraLaPromessa() {
    val albums = RecordingAlbums()
    val model = ready(albums)
    model.openAlbumPicker()
    model.selectAlbum(family)

    model.saveToAlbum()
    waitFor(model, ReceiveScreenState.DONE)

    assertThat(queue.enqueued).hasSize(1)
    assertThat(albums.promises).hasSize(1)
    assertThat(albums.promises.single().second).isEqualTo(family)
    // La promessa viene dopo la coda: prima i file, poi l'album.
    assertThat(albums.queuedWhenPromised).isEqualTo(1)
    assertThat(model.ui.value.addedToAlbum).isEqualTo(family)
    assertThat(model.ui.value.albumNotRegistered).isFalse()
    // L'album condiviso non riceve niente da qui: la promessa e' tutto, la conferma col conteggio la chiede :app quando i file sono sul NAS.
  }

  @Test
  fun ilSalvaEAggiungiVaSoloConIlSelettoreAperto() {
    val albums = RecordingAlbums()
    val model = ready(albums)
    model.saveToAlbum() // il selettore non e' aperto
    model.openAlbumPicker()
    model.saveToAlbum() // un album e' preselezionato: questo salva
    waitFor(model, ReceiveScreenState.DONE)
    assertThat(queue.enqueued).hasSize(1)
    assertThat(albums.promises.single().second).isEqualTo(mine)
  }

  @Test
  fun unSalvataggioNormaleNonRegistraNessunaPromessa() {
    val albums = RecordingAlbums()
    val model = ready(albums)
    model.openAlbumPicker()
    model.closeAlbumPicker()
    model.save()
    waitFor(model, ReceiveScreenState.DONE)
    assertThat(queue.enqueued).hasSize(1)
    assertThat(albums.promises).isEmpty()
    assertThat(model.ui.value.addedToAlbum).isNull()
  }

  @Test
  fun annullareOChiudereSenzaSalvareNonRegistraNiente() {
    val albums = RecordingAlbums()
    val model = ready(albums)
    model.openAlbumPicker()
    model.selectAlbum(family)
    model.cancel()
    assertThat(albums.promises).isEmpty()
    assertThat(queue.enqueued).isEmpty()
  }

  @Test
  fun seLaPromessaNonSiRegistraIFileSonoComunqueInCodaELaSchermataLoDice() {
    val albums = RecordingAlbums(promise = false)
    val model = ready(albums)
    model.openAlbumPicker()
    model.saveToAlbum()
    waitFor(model, ReceiveScreenState.DONE)
    assertThat(queue.enqueued).hasSize(1)
    assertThat(model.ui.value.addedToAlbum).isNull()
    assertThat(model.ui.value.albumNotRegistered).isTrue()
  }

  @Test
  fun unaPortaCheLanciaUnEccezioneNonFaPerdereIFile() {
    val albums = RecordingAlbums(failOnPromise = true)
    val model = ready(albums)
    model.openAlbumPicker()
    model.saveToAlbum()
    waitFor(model, ReceiveScreenState.DONE)
    assertThat(queue.enqueued).hasSize(1)
    assertThat(model.ui.value.albumNotRegistered).isTrue()
  }

  @Test
  fun unaPortaCheNonRispondeSulleScelteVaraleNessunAlbumESiSalvaComeSempre() {
    val model = ready(RecordingAlbums(failOnOffer = true))
    assertThat(model.ui.value.albums).isEmpty()
    model.openAlbumPicker()
    assertThat(model.ui.value.albumPick).isFalse()
    model.save()
    waitFor(model, ReceiveScreenState.DONE)
    assertThat(queue.enqueued).hasSize(1)
  }

  @Test
  fun ilNomeDiUnAlbumNonStaNelTestoDiDiagnosi() {
    // Il nome e' un dato dell'utente: la sua rappresentazione per un log non lo contiene.
    assertThat(mine.toString()).doesNotContain("Anna")
    assertThat(family.toString()).doesNotContain("Natale")
  }
}
