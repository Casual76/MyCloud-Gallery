package dev.pampa.mycloudgallery.feature.integrations.receive

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStageId
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.drag.DropHint
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
 * Fase 10 (P6, D21, E-T16): da dove comincia il foglio della ricezione quando lo apre un rilascio dentro l'app. La destinazione e' solo la **scelta iniziale**, che l'utente cambia: una cartella
 * dell'albero dove "Salva sul NAS" scrive, o un album dove si puo' aggiungere. Niente si salva e niente si mette in coda finche' non c'e' il tocco; una destinazione che non c'e' (un'altra
 * share, una cartella fuori dall'albero, un album dove "guardano" soltanto) lascia il foglio di sempre.
 */
class DropHintReceiveTest {

  @get:Rule val folder = TemporaryFolder()

  private val gate = FakeEntryGate(EntryDecision.ALLOWED)
  private val queue = FakeIncomingQueue()
  private val clock = FakeBackupClock()
  private val scope = CoroutineScope(Job() + Dispatchers.Default)
  private lateinit var uploads: DefaultIncomingUploads

  private val received = FakeIncomingDestinations.choice("Ricevute", DestinationArea.PRIVATE, "Ricevute")
  private val family = FakeIncomingDestinations.choice("Famiglia", DestinationArea.COMMON, "Famiglia")
  private val holidays = FakeIncomingDestinations.choice("Vacanze", DestinationArea.PRIVATE, "Ricevute/Vacanze")
  private val year = FakeIncomingDestinations.choice("2024", DestinationArea.PRIVATE, "Ricevute/Vacanze/2024")
  private val destinations = FakeIncomingDestinations(listOf(received, family), mapOf(received to listOf(holidays), holidays to listOf(year)))

  private val mine = IncomingAlbumChoice("album-1", "Compleanno", shared = false)
  private val shared = IncomingAlbumChoice("album-2", "Natale", shared = true)

  private class Albums(initial: List<IncomingAlbumChoice>) : IncomingAlbums {
    val offered = MutableStateFlow(initial)
    override suspend fun albums(): List<IncomingAlbumChoice> = offered.value
    override fun choices(): Flow<List<IncomingAlbumChoice>> = offered
    override suspend fun willAdd(stage: IncomingStageId, album: IncomingAlbumChoice): Boolean = true
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

  private fun model(hint: DropHint?, albums: IncomingAlbums = Albums(listOf(mine, shared))) = ReceiveViewModel(
    streams = { streams() }, overflow = 0, gate = gate, uploads = uploads, destinations = destinations, monotonicMs = { clock.monotonicMs() }, scope = scope, albums = albums, hint = hint,
  ).also {
    it.refreshGate()
    waitUntil { it.ui.value.screen == ReceiveScreenState.READY }
  }

  private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
    assertThat(condition()).isTrue()
  }

  @Test
  fun senzaDestinazioneOConLaLibreriaIlFoglioEQuelloDiSempre() {
    for (hint in listOf(null, DropHint.Library)) {
      val ui = model(hint).ui.value
      assertThat(ui.selected).isEqualTo(received)
      assertThat(ui.trail).isEmpty()
      assertThat(ui.albumPick).isFalse()
      assertThat(ui.selectedAlbum).isNull()
    }
  }

  @Test
  fun unaCartellaDellAlberoSiApreSuQuellaCartella() {
    val ui = model(DropHint.Folder("alessio", "Ricevute/Vacanze")).ui.value

    assertThat(ui.selected).isEqualTo(holidays)
    assertThat(ui.trail).containsExactly(received)
    assertThat(ui.choices).containsExactly(holidays)
    assertThat(ui.commonNotice).isFalse()
  }

  @Test
  fun scendeDiPiuLivelli() {
    val ui = model(DropHint.Folder("alessio", "Ricevute/Vacanze/2024")).ui.value

    assertThat(ui.selected).isEqualTo(year)
    assertThat(ui.trail).containsExactly(received, holidays).inOrder()
  }

  @Test
  fun unaCartellaCheNonStaNellAlberoLasciaIlFoglioDiSempre() {
    for (hint in listOf(
      DropHint.Folder("alessio", "Altro/Cartella"),
      DropHint.Folder("un-altra-share", "Ricevute/Vacanze"),
      DropHint.Folder("alessio", "Ricevute/Inesistente"),
      DropHint.Folder("alessio", "Ricevute/Vacanze/2024/Sotto"),
    )) {
      val ui = model(hint).ui.value
      assertThat(ui.selected).isEqualTo(received)
      assertThat(ui.trail).isEmpty()
    }
  }

  @Test
  fun lAreaComuneDiceAncoraCheLaVedonoTutti() {
    val ui = model(DropHint.Folder("alessio", "Famiglia")).ui.value
    assertThat(ui.selected).isEqualTo(family)
    assertThat(ui.commonNotice).isTrue()
  }

  @Test
  fun unAlbumOffertoApreAggiungiAdAlbumConQuellaScelta() {
    val ui = model(DropHint.Album("album-2")).ui.value

    assertThat(ui.albumPick).isTrue()
    assertThat(ui.selectedAlbum).isEqualTo(shared)
    // La cartella dove si salva resta quella di partenza.
    assertThat(ui.selected).isEqualTo(received)
  }

  @Test
  fun unAlbumDoveSiPuoSoloGuardareNonSiOffreEIlFoglioEQuelloDiSempre() {
    val ui = model(DropHint.Album("album-solo-guardano")).ui.value

    assertThat(ui.albumPick).isFalse()
    assertThat(ui.selectedAlbum).isNull()
  }

  @Test
  fun unAlbumCheCompareDopoSiSceglieQuandoCompare() {
    val albums = Albums(emptyList())
    val model = model(DropHint.Album("album-1"), albums)
    assertThat(model.ui.value.albumPick).isFalse()

    albums.offered.value = listOf(mine)
    waitUntil { model.ui.value.albumPick }

    assertThat(model.ui.value.selectedAlbum).isEqualTo(mine)
  }

  @Test
  fun lADestinazioneNonSalvaNienteEFinoAlToccoNullaVaInCoda() {
    val model = model(DropHint.Folder("alessio", "Ricevute/Vacanze"))
    assertThat(queue.enqueued).isEmpty()
    assertThat(model.ui.value.screen).isEqualTo(ReceiveScreenState.READY)

    // Il tocco sceglie dove e salva, e nasce dalla scelta di adesso: la cartella del rilascio.
    model.save()
    waitUntil { model.ui.value.screen == ReceiveScreenState.DONE }
    assertThat(queue.enqueued.single().first).isEqualTo(holidays.destination)
  }

  @Test
  fun unAltroRilascioSulloStessoFoglioPortaLaSuaDestinazione() {
    val model = model(DropHint.Folder("alessio", "Ricevute/Vacanze"))
    assertThat(model.ui.value.selected).isEqualTo(holidays)

    model.hintForNext(DropHint.Folder("alessio", "Ricevute/Vacanze/2024"))
    model.replaceRequest({ streams() }, 0)

    waitUntil { model.ui.value.screen == ReceiveScreenState.READY && model.ui.value.selected == year }
    assertThat(model.ui.value.trail).containsExactly(received, holidays).inOrder()
  }
}
