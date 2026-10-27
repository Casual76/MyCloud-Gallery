package dev.pampa.mycloudgallery.feature.albums

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.RuleProposal
import dev.pampa.mycloudgallery.core.albums.contract.SmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.contract.SmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.contract.SmartPreview
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposals
import dev.pampa.mycloudgallery.core.albums.contract.TripReport
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.albums.testing.FakeSmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.testing.FakeSmartNameInterpreter
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.choose.AddToAlbumHost
import dev.pampa.mycloudgallery.feature.albums.choose.AddToAlbumViewModel
import dev.pampa.mycloudgallery.feature.albums.contract.AddToAlbumRequest
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumActions
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorRoute
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorViewModel
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListRoute
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListViewModel
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumDependencies
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageExits
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageRoute
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageViewModel
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionsRoute
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionsViewModel
import dev.pampa.mycloudgallery.feature.albums.trips.TripsRoute
import dev.pampa.mycloudgallery.feature.albums.trips.TripsViewModel
import java.util.Optional
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * Le rotte degli album (Fase 7, P10) sul mondo vero di riferimento: dal tocco sulla schermata, passando per il ViewModel e il controllore, fino al contratto
 * (`InMemoryAlbums`, `FakeSharedAlbums`, il motore e l'interprete finti). Provano il **legame** che gli altri test non vedono: che ogni gesto arrivi al controllore
 * giusto e che le uscite verso `:app` (aprire un album, uscire, scegliere i media) siano chiamate.
 */
class AlbumRoutesTest : AlbumsComposeTest() {

  private val scenes = Scenes()

  private class RecordingActions : AlbumActions {
    val viewed = mutableListOf<MediaId>()
    val opened = mutableListOf<AlbumId>()
    var pickFor: AlbumId? = null
    var picks = 0
    var pickCallback: ((List<MediaId>) -> Unit)? = null

    override fun openViewer(view: OrderedTimelineView, start: MediaId) {
      viewed += start
    }

    override fun openAlbum(id: AlbumId) {
      opened += id
    }

    override fun pickMedia(forAlbum: AlbumId?, max: Int, onPicked: (List<MediaId>) -> Unit) {
      picks++
      pickFor = forAlbum
      pickCallback = onPicked
    }

    override fun openDiagnostics() = Unit
  }

  private class RouteTrips(initial: TripProposals, private val created: AlbumId?) : Trips {
    override val proposals = MutableStateFlow(initial)
    val accepted = mutableListOf<TripId>()

    override suspend fun detect(budget: Duration) = TripReport(0, 0, true, false)

    override suspend fun accept(id: TripId): TripAcceptOutcome {
      accepted += id
      return if (created == null) TripAcceptOutcome.NotFound else TripAcceptOutcome.Created(created)
    }

    override suspend fun dismiss(id: TripId) = Unit
  }

  private fun deps(
    trips: Trips? = null,
    interpreter: SmartNameInterpreter? = null,
    engine: SmartAlbumEngine? = null,
    visible: Boolean = true,
  ): AlbumDependencies = AlbumDependencies(
    Optional.of(scenes.world.albums), Optional.of(scenes.world.sharedAlbums), Optional.of(scenes.world.albums), Optional.of(scenes.world.runner),
    Optional.ofNullable(interpreter), Optional.ofNullable(engine), Optional.ofNullable(trips), Optional.of(scenes.people),
    Optional.empty(), Optional.empty(), Optional.of(scenes.world.screens), if (visible) Optional.of(scenes.world.library) else Optional.empty(),
  )

  private fun idHandle(id: AlbumId) = SavedStateHandle(mapOf("id" to id.value, "toShare" to false))

  private val thumbs get() = scenes.thumbnails.thumbnails

  // --- Elenco ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun elencoUnaSchedaApreQuellAlbum() {
    var opened: AlbumId? = null
    val model = AlbumListViewModel(deps())
    show { AlbumListRoute(thumbs, onOpenAlbum = { opened = it }, onNewSmart = {}, viewModel = model) }
    composeRule.onNode(hasContentDescription("Compleanno di Anna", substring = true) and hasClickAction()).performClick()
    assertThat(opened).isEqualTo(scenes.birthday)
  }

  @Test
  fun elencoNuovoAlbumManualeSiCreaESiApre() {
    var opened: AlbumId? = null
    val model = AlbumListViewModel(deps())
    show { AlbumListRoute(thumbs, onOpenAlbum = { opened = it }, onNewSmart = {}, viewModel = model) }
    composeRule.onNode(hasContentDescription("Nuovo album") and hasClickAction()).performClick()
    composeRule.waitForIdle()
    composeRule.onNode(hasSetTextAction()).performTextInput("Mare 2026")
    clickText("Crea")
    composeRule.waitUntil(5_000) { opened != null }
    val created = scenes.world.albums.all.value.albums.single { it.title == "Mare 2026" }
    assertThat(created.kind).isEqualTo(AlbumKind.MANUAL)
    assertThat(opened).isEqualTo(created.id)
  }

  @Test
  fun elencoUnAlbumSmartPortaAllEditorDelleRegoleConIlNome() {
    var smartTitle: String? = null
    val model = AlbumListViewModel(deps())
    show { AlbumListRoute(thumbs, onOpenAlbum = {}, onNewSmart = { smartTitle = it }, viewModel = model) }
    composeRule.onNode(hasContentDescription("Nuovo album") and hasClickAction()).performClick()
    composeRule.waitForIdle()
    composeRule.onNode(hasSetTextAction()).performTextInput("  Oslo 2026 ")
    clickText("Album smart")
    clickText("Avanti")
    assertThat(smartTitle).isEqualTo("Oslo 2026")
    // L'album non e' stato creato: lo crea l'editor, dopo che si sono viste le regole.
    assertThat(scenes.world.albums.all.value.albums.none { it.title == "Oslo 2026" && it.id != scenes.oslo }).isTrue()
  }

  // --- Pagina ---------------------------------------------------------------------------------------------------------------------

  private fun pageRoute(id: AlbumId, actions: AlbumActions = RecordingActions(), exits: AlbumPageExits = exits()): @androidx.compose.runtime.Composable () -> Unit {
    val model = AlbumPageViewModel(idHandle(id), deps())
    return { AlbumPageRoute(actions = actions, thumbnails = thumbs, exits = exits, viewModel = model) }
  }

  private fun exits(onBack: () -> Unit = {}) = AlbumPageExits(onBack, onBack, {}, {}, { _, _ -> })

  @Test
  fun paginaLaStrisciaDeiPermessiApreIlFoglioEIlCreatoreCambiaLivello() {
    show(content = pageRoute(scenes.osloShared))
    clickText("Gli altri aggiungono")
    assertThat(composeRule.onAllNodesWithText("Permessi dell’album", substring = true).fetchSemanticsNodes()).isNotEmpty()
    clickText("Gli altri guardano")
    val level = (scenes.album(scenes.osloShared) as Album.Shared).level
    assertThat(level).isEqualTo(ShareLevel.VIEW)
  }

  @Test
  fun paginaUnaFotoSiApreNelViewerSullaVistaDellAlbum() {
    val actions = RecordingActions()
    show(content = pageRoute(scenes.birthday, actions))
    composeRule.onAllNodes(hasContentDescription("Foto", substring = true) and hasClickAction()).onFirst().performClick()
    composeRule.waitForIdle()
    assertThat(actions.viewed).hasSize(1)
    assertThat(actions.viewed.single() in scenes.birthdayPhotos).isTrue()
  }

  @Test
  fun paginaIlMenuOffreLeAzioniDellaTabella_ESuiPersonaliLeSue() {
    show(content = pageRoute(scenes.grandpa))
    composeRule.onNode(hasContentDescription("Azioni dell’album") and hasClickAction()).performClick()
    composeRule.waitForIdle()
    // "Gli altri guardano", non sono il creatore: niente da aggiungere, rinominare, copertina; si puo' non mostrare piu' (E-SH21).
    assertThat(composeRule.onAllNodesWithText("Rinomina", substring = true).fetchSemanticsNodes()).isEmpty()
    assertThat(composeRule.onAllNodesWithText("Aggiungi foto", substring = true).fetchSemanticsNodes()).isEmpty()
    assertThat(composeRule.onAllNodesWithText("Non mostrare più", substring = true).fetchSemanticsNodes()).isNotEmpty()
  }

  @Test
  fun paginaDiUnoSmartNuovoCheCercaNonDiceZeroDiZero_AS2() {
    // Il motore non ha ancora guardato niente e non sa quante foto guardera': la pagina dice che cerca, senza «0 di 0 guardate».
    scenes.world.smart = dev.pampa.mycloudgallery.core.albums.testing.SmartMembership.EMPTY
    scenes.world.albums.status.value = dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus.Recomputing(0, 0)
    show(content = pageRoute(scenes.oslo))
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Cerco le foto dell", substring = true).fetchSemanticsNodes().isNotEmpty() }
    assertThat(composeRule.onAllNodesWithText("Le foto compaiono man mano", substring = true).fetchSemanticsNodes()).isNotEmpty()
    assertThat(composeRule.onAllNodesWithText("guardate", substring = true).fetchSemanticsNodes()).isEmpty()
  }

  // --- Suggerite, editor, viaggi ---------------------------------------------------------------------------------------------------

  @Test
  fun suggeriteAccettareTutteFaEntrareLeFotoNellAlbum() {
    var back = 0
    val before = (scenes.album(scenes.oslo) as Album.Smart)
    assertThat(before.suggested).isEqualTo(12)
    val model = SuggestionsViewModel(idHandle(scenes.oslo), deps())
    show { SuggestionsRoute(RecordingActions(), thumbs, onBack = { back++ }, viewModel = model) }
    clickText("Accetta tutte (12)")
    val after = scenes.album(scenes.oslo) as Album.Smart
    assertThat(after.suggested).isEqualTo(0)
    assertThat(after.count).isEqualTo(before.count + 12)
  }

  @Test
  fun editorCreaLAlbumSmartConLeRegoleProposte() {
    val rules = SmartRules(null, null, "neve")
    val preview = SmartPreview(confident = 230, suggested = 12, tooBroad = false, sample = emptyList(), searchUsed = true)
    var saved: AlbumId? = null
    val handle = SavedStateHandle(mapOf("title" to "Neve 2026", "album" to null))
    val model = SmartEditorViewModel(
      handle, deps(interpreter = FakeSmartNameInterpreter(RuleProposal(rules, emptyList(), emptyList(), emptySet())), engine = FakeSmartAlbumEngine(nextPreview = preview)),
    )
    show { SmartEditorRoute(RecordingActions(), thumbs, onBack = {}, onSaved = { saved = it }, viewModel = model) }
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Crea l’album", substring = true).fetchSemanticsNodes().isNotEmpty() }
    clickText("Crea l’album")
    composeRule.waitUntil(5_000) { saved != null }
    val album = scenes.album(saved!!) as Album.Smart
    assertThat(album.title).isEqualTo("Neve 2026")
    assertThat(album.rules.text).isEqualTo("neve")
  }

  @Test
  fun editorLeFotoDEsempioSiScelgonoSenzaAlbumDiDestinazioneAncheInModifica_AS8() {
    val preview = SmartPreview(confident = 10, suggested = 0, tooBroad = false, sample = emptyList(), searchUsed = true)
    val actions = RecordingActions()
    // In modifica l'album c'e': prima il selettore riceveva il suo id e si intitolava «Aggiungi foto» (e aggiungeva alle foto dell'album, non agli esempi).
    val handle = SavedStateHandle(mapOf("title" to null, "album" to scenes.oslo.value))
    val model = SmartEditorViewModel(handle, deps(interpreter = FakeSmartNameInterpreter(), engine = FakeSmartAlbumEngine(nextPreview = preview)))
    show { SmartEditorRoute(actions, thumbs, onBack = {}, onSaved = {}, viewModel = model) }
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Aggiungi foto d", substring = true).fetchSemanticsNodes().isNotEmpty() }
    clickText("Aggiungi foto d")
    assertThat(actions.pickCallback).isNotNull()
    assertThat(actions.pickFor).isNull()
    assertThat(actions.picks).isEqualTo(1)
  }

  @Test
  fun viaggiCreareUnAlbumDaUnaPropostaLoApre() {
    val trip = dev.pampa.mycloudgallery.feature.albums.testing.AlbumSamples.trip.copy(cover = ShareFixtures.media(3))
    val trips = RouteTrips(TripProposals(listOf(trip), homeKnown = true), scenes.lake)
    var opened: AlbumId? = null
    val model = TripsViewModel(deps(trips = trips))
    show { TripsRoute(thumbs, onBack = {}, onOpenAlbum = { opened = it }, viewModel = model) }
    clickText("Crea album")
    composeRule.waitUntil(5_000) { opened != null }
    assertThat(trips.accepted).containsExactly(trip.id)
    assertThat(opened).isEqualTo(scenes.lake)
  }

  @Test
  fun viaggiPrimaDellaPrimaRicercaDiceCheCercaENonCheServonoLeFotoConLaPosizione_VG1() {
    val trips = RouteTrips(TripProposals.EMPTY, null)
    val model = TripsViewModel(deps(trips = trips))
    show { TripsRoute(thumbs, onBack = {}, onOpenAlbum = {}, viewModel = model) }
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Sto cercando i viaggi", substring = true).fetchSemanticsNodes().isNotEmpty() }
    assertThat(composeRule.onAllNodesWithText("Servono foto con la posizione", substring = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun viaggiUnaPropostaCheNonCePiuLoDiceInveceDiNonFareNiente_VG2() {
    val trip = dev.pampa.mycloudgallery.feature.albums.testing.AlbumSamples.trip.copy(cover = ShareFixtures.media(3))
    val trips = RouteTrips(TripProposals(listOf(trip), homeKnown = true), null)
    var opened: AlbumId? = null
    val model = TripsViewModel(deps(trips = trips))
    show { TripsRoute(thumbs, onBack = {}, onOpenAlbum = { opened = it }, viewModel = model) }
    clickText("Crea album")
    composeRule.waitUntil(5_000) { trips.accepted.isNotEmpty() }
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("La proposta non c’è più", substring = true).fetchSemanticsNodes().isNotEmpty() }
    assertThat(opened).isNull()
  }

  // --- Aggiungi ad album da fuori --------------------------------------------------------------------------------------------------

  @Test
  fun aggiungiAdAlbumDaFuoriOffreIMieAlbumEAggiungeSoloIVisibili() {
    scenes.world.library.put(scenes.world.photo(900), scenes.world.photo(901, hidden = true))
    var closed = 0
    val request = AddToAlbumRequest(listOf(AlbumsTestWorld.media(900), AlbumsTestWorld.media(901)))
    val model = AddToAlbumViewModel(deps())
    show { AddToAlbumHost(request, onDismiss = { closed++ }, onOpenAlbum = {}, viewModel = model) }
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Gita al lago", substring = true).fetchSemanticsNodes().isNotEmpty() }
    // Una sola foto si offre: la seconda e' nascosta.
    assertThat(composeRule.onAllNodesWithText("1 elemento da aggiungere", substring = true).fetchSemanticsNodes()).isNotEmpty()
    clickText("Gita al lago")
    composeRule.waitUntil(5_000) { (scenes.album(scenes.lake)).count == 7 }
    assertThat(scenes.album(scenes.lake).count).isEqualTo(7)
  }
}
