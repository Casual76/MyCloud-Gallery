package dev.pampa.mycloudgallery.feature.integrations.receive

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

  // --- AA-1: gli album arrivano dopo, con l'app chiusa --------------------------------------------------------------------------

  /** Una porta i cui album si montano dopo la prima lettura (l'app era chiusa: il sistema la apre per la condivisione e gli album si montano dopo qualche secondo). */
  private inner class LateAlbums(initial: List<IncomingAlbumChoice> = emptyList()) : IncomingAlbums {
    val offered = MutableStateFlow(initial)
    val promises = mutableListOf<Pair<IncomingStageId, IncomingAlbumChoice>>()

    override suspend fun albums(): List<IncomingAlbumChoice> = offered.value

    override fun choices(): Flow<List<IncomingAlbumChoice>> = offered

    override suspend fun willAdd(stage: IncomingStageId, album: IncomingAlbumChoice): Boolean {
      promises += stage to album
      return true
    }
  }

  private fun until(what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
    assertWithMessage(what).that(condition()).isTrue()
  }

  @Test
  fun glialbumCheSiMontanoDopoLaPrimaLetturaCompaionoLoStesso_AA1() {
    val albums = LateAlbums()
    val model = ready(albums)
    // La prima lettura era vuota: la voce non c'e' ancora (prima non compariva piu' per tutta la schermata).
    assertThat(model.ui.value.albums).isEmpty()
    model.openAlbumPicker()
    assertThat(model.ui.value.albumPick).isFalse()

    albums.offered.value = listOf(mine, family)
    until("gli album offerti") { model.ui.value.albums == listOf(mine, family) }
    model.openAlbumPicker()
    assertThat(model.ui.value.albumPick).isTrue()
    assertThat(model.ui.value.selectedAlbum).isEqualTo(mine)
  }

  @Test
  fun unAlbumScelto_CheNonCePiuSiLasciaEUnoNuovoSiPuoScegliere_AA1() {
    val albums = LateAlbums(listOf(mine, family))
    val model = ready(albums)
    model.openAlbumPicker()
    model.selectAlbum(family)

    albums.offered.value = listOf(mine)
    until("la scelta lasciata") { model.ui.value.albums == listOf(mine) }
    assertThat(model.ui.value.selectedAlbum).isEqualTo(mine)
  }

  // --- AA-2: «Nuovo album» dalla ricezione --------------------------------------------------------------------------------------

  private inner class CreatingAlbums(
    private val outcome: IncomingAlbumCreate,
    private val offered: List<IncomingAlbumChoice> = emptyList(),
  ) : IncomingAlbums {
    val asked = mutableListOf<String>()
    val promises = mutableListOf<Pair<IncomingStageId, IncomingAlbumChoice>>()
    override val canCreate: Boolean = true

    override suspend fun albums(): List<IncomingAlbumChoice> = offered

    override suspend fun create(title: String): IncomingAlbumCreate {
      asked += title
      return outcome
    }

    override suspend fun willAdd(stage: IncomingStageId, album: IncomingAlbumChoice): Boolean {
      promises += stage to album
      return true
    }
  }

  @Test
  fun senzaAlbumSiPuoCreareUnoNuovoESalvarciDentro_AA2() {
    val created = IncomingAlbumChoice("album-9", "Mare 2026", shared = false)
    val albums = CreatingAlbums(IncomingAlbumCreate.Created(created))
    val model = ready(albums)
    assertThat(model.ui.value.albums).isEmpty()
    assertThat(model.ui.value.canCreateAlbum).isTrue()
    // Prima la voce non compariva senza album; adesso si apre e offre «Nuovo album».
    model.openAlbumPicker()
    assertThat(model.ui.value.albumPick).isTrue()
    assertThat(model.ui.value.selectedAlbum).isNull()

    model.createAlbum("Mare 2026")
    until("l'album creato") { model.ui.value.selectedAlbum == created }
    assertThat(model.ui.value.albums).containsExactly(created)
    assertThat(model.ui.value.creatingAlbum).isFalse()

    model.saveToAlbum()
    waitFor(model, ReceiveScreenState.DONE)
    assertThat(queue.enqueued).hasSize(1)
    assertThat(albums.promises.single().second).isEqualTo(created)
    assertThat(albums.asked).containsExactly("Mare 2026")
  }

  @Test
  fun unNomeNonValidoOGliAlbumNonPronti_LoDiconoESiRiprova_AA2() {
    val invalid = CreatingAlbums(IncomingAlbumCreate.InvalidTitle)
    val model = ready(invalid)
    model.openAlbumPicker()
    model.createAlbum("   ")
    until("il problema") { model.ui.value.newAlbumProblem == NewAlbumProblem.INVALID_TITLE }
    assertThat(model.ui.value.albums).isEmpty()
    assertThat(model.ui.value.creatingAlbum).isFalse()

    val unavailable = ready(CreatingAlbums(IncomingAlbumCreate.Unavailable))
    unavailable.openAlbumPicker()
    unavailable.createAlbum("Mare")
    until("il problema") { unavailable.ui.value.newAlbumProblem == NewAlbumProblem.UNAVAILABLE }
    // Il salvataggio normale non ne dipende.
    unavailable.closeAlbumPicker()
    unavailable.save()
    waitFor(unavailable, ReceiveScreenState.DONE)
  }

  @Test
  fun senzaLaPortaDiCreazioneNonCeNuovoAlbum_AA2() {
    val model = ready(RecordingAlbums(offered = emptyList()))
    assertThat(model.ui.value.canCreateAlbum).isFalse()
    model.openAlbumPicker()
    assertThat(model.ui.value.albumPick).isFalse()
    model.createAlbum("Mare")
    assertThat(model.ui.value.creatingAlbum).isFalse()
  }

  @Test
  fun ilNomeDiUnAlbumNonStaNelTestoDiDiagnosi() {
    // Il nome e' un dato dell'utente: la sua rappresentazione per un log non lo contiene.
    assertThat(mine.toString()).doesNotContain("Anna")
    assertThat(family.toString()).doesNotContain("Natale")
  }
}
