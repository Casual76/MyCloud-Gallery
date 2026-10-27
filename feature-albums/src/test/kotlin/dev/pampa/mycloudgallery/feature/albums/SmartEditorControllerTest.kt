package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PlaceAlternative
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.ProposalNote
import dev.pampa.mycloudgallery.core.albums.contract.RuleProposal
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartPreview
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.albums.testing.FakeSmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.testing.FakeSmartNameInterpreter
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.feature.albums.contract.BroadHint
import dev.pampa.mycloudgallery.feature.albums.contract.SmartEditorState
import dev.pampa.mycloudgallery.feature.albums.editor.EditorProblem
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorController
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorMode
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * L'editor delle regole (Fase 7, P10; D13-D16, §2.2, E-AL2, E-AL6, E-AL9, E-AL12): il nome diventa regole che si vedono e si cambiano tutte prima di creare;
 * ad ogni cambio l'anteprima si rifa'; "troppo larghe" e "senza motore" si dicono; il salvataggio scrive solo nei dati personali.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartEditorControllerTest {

  private val first = LocalDayRange(LocalDate.of(2026, 3, 13), LocalDate.of(2026, 3, 19))
  private val second = LocalDayRange(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 6))
  private val oslo = PlaceRule(listOf(3143244), GeoPoint(59.91, 10.75), 25, "Oslo", PlaceScope.AROUND)
  private val osloUs = PlaceRule(listOf(5000001), GeoPoint(41.9, -85.5), 15, "Oslo (Michigan)", PlaceScope.AROUND)

  private val proposal = RuleProposal(
    rules = SmartRules(SmartPeriod.Ranges(listOf(first, second)), oslo, null),
    alternatives = listOf(PlaceAlternative(oslo, 220), PlaceAlternative(osloUs, 3)),
    periodSeries = listOf(first, second),
    notes = setOf(ProposalNote.SEVERAL_SERIES),
  )

  private val preview = SmartPreview(confident = 230, suggested = 12, tooBroad = false, sample = listOf(ShareFixtures.media(1), ShareFixtures.media(2)), searchUsed = true)

  private fun TestScope.editor(
    world: AlbumsTestWorld = AlbumsTestWorld(),
    mode: SmartEditorMode = SmartEditorMode.Create("Oslo 2026"),
    engine: FakeSmartAlbumEngine = FakeSmartAlbumEngine(nextPreview = preview),
    interpreter: FakeSmartNameInterpreter = FakeSmartNameInterpreter(proposal),
  ) = SmartEditorController(mode, interpreter, engine, world.albums, world.screens, backgroundScope).also { it.start() }

  @Test
  fun ilNomeDiventaRegoleConAlternativeSerieEAnteprima() = runTest(UnconfinedTestDispatcher()) {
    val interpreter = FakeSmartNameInterpreter(proposal)
    val controller = editor(interpreter = interpreter)
    val ui = controller.ui.value
    assertThat(interpreter.asked).containsExactly("Oslo 2026")
    val state = ui.state as SmartEditorState.Proposal
    assertThat(state.rules.place).isEqualTo(oslo)
    assertThat(state.alternatives.map { it.mediaCount }).containsExactly(220, 3).inOrder()
    assertThat(state.series).containsExactly(first, second).inOrder()
    assertThat(state.preview.confident).isEqualTo(230)
    assertThat(ui.canSave).isTrue()
    assertThat(ui.creating).isTrue()
  }

  @Test
  fun leSerieDiGiorniSiTengonoUnaAUnaMaNonSiTogliesLUltima_E_AL12() = runTest(UnconfinedTestDispatcher()) {
    val controller = editor()
    controller.setSeries(setOf(first))
    assertThat((controller.ui.value.rules?.period as SmartPeriod.Ranges).ranges).containsExactly(first)
    controller.setSeries(emptySet())
    assertThat((controller.ui.value.rules?.period as SmartPeriod.Ranges).ranges).containsExactly(first)
    controller.setSeries(setOf(first, second))
    assertThat((controller.ui.value.rules?.period as SmartPeriod.Ranges).ranges).containsExactly(first, second).inOrder()
  }

  @Test
  fun ilPeriodoSiScriveAManoESiToglie() = runTest(UnconfinedTestDispatcher()) {
    val controller = editor()
    val custom = LocalDayRange(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3))
    controller.setPeriod(custom)
    assertThat(controller.ui.value.series).containsExactly(custom)
    assertThat((controller.ui.value.rules?.period as SmartPeriod.Ranges).ranges).containsExactly(custom)
    controller.removePeriod()
    assertThat(controller.ui.value.rules?.period).isNull()
    assertThat(controller.ui.value.series).isEmpty()
  }

  @Test
  fun ilLuogoSiScegliFraGliOmonimiEIlRaggioStaNeiLimiti_E_AL2() = runTest(UnconfinedTestDispatcher()) {
    val controller = editor()
    controller.choosePlace(PlaceAlternative(osloUs, 3))
    assertThat(controller.ui.value.rules?.place).isEqualTo(osloUs)
    controller.setRadius(1)
    assertThat(controller.ui.value.rules?.place?.radiusKm).isEqualTo(SmartLimits.MIN_RADIUS_KM)
    controller.setRadius(10_000)
    assertThat(controller.ui.value.rules?.place?.radiusKm).isEqualTo(SmartLimits.MAX_RADIUS_KM)
    controller.setRadius(40)
    assertThat(controller.ui.value.rules?.place?.radiusKm).isEqualTo(40)
    controller.removePlace()
    assertThat(controller.ui.value.rules?.place).isNull()
    // Con un luogo di regione il raggio non c'e': non si cambia.
    val region = PlaceRule(listOf(1), GeoPoint(60.0, 10.0), 25, "Puglia", PlaceScope.REGION)
    controller.choosePlace(PlaceAlternative(region, 5))
    controller.setRadius(100)
    assertThat(controller.ui.value.rules?.place?.radiusKm).isEqualTo(25)
  }

  @Test
  fun leRegoleTroppoLargheSiDiconoEProponganoDiRestringere_E_AL6() = runTest(UnconfinedTestDispatcher()) {
    val engine = FakeSmartAlbumEngine(nextPreview = preview.copy(confident = 5_000, tooBroad = true))
    val controller = editor(engine = engine)
    val state = controller.ui.value.state as SmartEditorState.TooBroad
    assertThat(state.hints).containsExactly(BroadHint.NARROW_PERIOD, BroadHint.SMALLER_RADIUS)
    // Il creatore decide: troppo larghe non impedisce di salvare.
    assertThat(controller.ui.value.canSave).isTrue()
    controller.removePlace()
    controller.removePeriod()
    assertThat((controller.ui.value.state as? SmartEditorState.TooBroad)?.hints).contains(BroadHint.ADD_PLACE)
  }

  @Test
  fun laDescrizioneSenzaMotoreSiDiceELeAltreRegoleLavoranoLoStesso_E_AL9() = runTest(UnconfinedTestDispatcher()) {
    val engine = FakeSmartAlbumEngine(nextPreview = preview.copy(searchUsed = false))
    val controller = editor(engine = engine)
    assertThat(controller.ui.value.state).isInstanceOf(SmartEditorState.Proposal::class.java)
    controller.setText("neve")
    assertThat(controller.ui.value.state).isInstanceOf(SmartEditorState.NoSearch::class.java)
    assertThat(controller.ui.value.canSave).isTrue()
    // Con il motore pronto la stessa descrizione e' una proposta normale.
    engine.nextPreview = preview.copy(searchUsed = true)
    controller.setText("neve ")
    assertThat(controller.ui.value.state).isInstanceOf(SmartEditorState.Proposal::class.java)
  }

  @Test
  fun leFotoDEsempioAlPiuSeiLeSorgentiEITipiNeHannoSempreAlmenoUno() = runTest(UnconfinedTestDispatcher()) {
    val controller = editor()
    controller.addExamples((1..10).map { ShareFixtures.media(it) })
    assertThat(controller.ui.value.rules?.examples).hasSize(SmartLimits.MAX_EXAMPLES)
    controller.removeExample(ShareFixtures.media(1))
    assertThat(controller.ui.value.rules?.examples).hasSize(SmartLimits.MAX_EXAMPLES - 1)
    SmartSource.entries.forEach { controller.toggleSource(it) }
    assertThat(controller.ui.value.rules?.sources).hasSize(1)
    SmartMediaType.entries.forEach { controller.toggleType(it) }
    assertThat(controller.ui.value.rules?.types).hasSize(1)
  }

  @Test
  fun senzaNessunaRegolaNonSiPuoCreare() = runTest(UnconfinedTestDispatcher()) {
    val controller = editor(interpreter = FakeSmartNameInterpreter(null))
    assertThat(controller.ui.value.rules?.period).isNull()
    assertThat(controller.ui.value.canSave).isFalse()
    controller.save()
    assertThat(controller.ui.value.savedAlbum).isNull()
  }

  @Test
  fun creareScriveUnoSmartConLeRegoleVisteESoloNeiDatiPersonali() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val controller = editor(world)
    controller.setRadius(60)
    controller.save()
    val id = controller.ui.value.savedAlbum!!
    val album = world.albums.all.value.byId(id) as dev.pampa.mycloudgallery.core.albums.contract.Album.Smart
    assertThat(album.title).isEqualTo("Oslo 2026")
    assertThat(album.kind).isEqualTo(AlbumKind.SMART)
    assertThat(album.rules.place?.radiusKm).isEqualTo(60)
    assertThat(world.diary.openOps()).isEmpty()
  }

  @Test
  fun leRegoleTroppoLungheNonSiScrivonoELEditorLoDice() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val controller = editor(world)
    controller.setText("a".repeat(2_000))
    controller.save()
    assertThat(controller.ui.value.problem).isEqualTo(EditorProblem.RULES_TOO_LONG)
    assertThat(controller.ui.value.savedAlbum).isNull()
    assertThat(world.albums.all.value.albums).isEmpty()
  }

  @Test
  fun cambiareLeRegoleDiUnoSmartCheCeGiaLeSalvaESenzaTitolo() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = (world.albums.create("Oslo 2026", AlbumKind.SMART, SmartRules(SmartPeriod.Ranges(listOf(first)), oslo, null)) as dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome.Created).id
    val controller = editor(world, SmartEditorMode.Edit(id))
    assertThat(controller.ui.value.creating).isFalse()
    assertThat(controller.ui.value.title).isEqualTo("Oslo 2026")
    assertThat(controller.ui.value.rules?.place).isEqualTo(oslo)
    controller.setText("neve")
    controller.save()
    assertThat(controller.ui.value.savedAlbum).isEqualTo(id)
    assertThat((world.albums.all.value.byId(id) as dev.pampa.mycloudgallery.core.albums.contract.Album.Smart).rules.text).isEqualTo("neve")
  }

  /** Il motore che sbaglia: l'anteprima e (poi) la proposta lanciano. */
  private class BrokenEngine : dev.pampa.mycloudgallery.core.albums.contract.SmartAlbumEngine {
    var previews = 0

    override suspend fun evaluate(id: dev.pampa.mycloudgallery.core.model.AlbumId, budget: kotlin.time.Duration) = error("non qui")

    override suspend fun preview(rules: SmartRules): SmartPreview {
      previews++
      error("il catalogo non risponde")
    }
  }

  private class BrokenInterpreter : dev.pampa.mycloudgallery.core.albums.contract.SmartNameInterpreter {
    override suspend fun propose(title: String, today: LocalDate): RuleProposal = error("la ricerca e' caduta")
  }

  @Test
  fun seLAnteprimaSbagliaLEditorLoDiceELasciaSalvareLeRegole_AS7() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val engine = BrokenEngine()
    // Prima l'eccezione usciva dalla coroutine dell'editor e arrivava al gestore dell'ambito: l'app si chiudeva.
    val controller = SmartEditorController(SmartEditorMode.Create("Oslo 2026"), FakeSmartNameInterpreter(proposal), engine, world.albums, world.screens, backgroundScope)
      .also { it.start() }
    assertThat(engine.previews).isEqualTo(1)
    assertThat(controller.ui.value.problem).isEqualTo(EditorProblem.PREVIEW_FAILED)
    assertThat(controller.ui.value.previewing).isFalse()
    assertThat(controller.ui.value.canSave).isTrue()
    controller.save()
    val id = controller.ui.value.savedAlbum!!
    assertThat(world.albums.all.value.byId(id)).isNotNull()
  }

  @Test
  fun seLaPropostaDelNomeSbagliaLEditorParteConRegoleVuote_AS7() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val controller = SmartEditorController(SmartEditorMode.Create("Oslo 2026"), BrokenInterpreter(), FakeSmartAlbumEngine(nextPreview = preview), world.albums, world.screens, backgroundScope)
      .also { it.start() }
    assertThat(controller.ui.value.problem).isEqualTo(EditorProblem.PREVIEW_FAILED)
    assertThat(controller.ui.value.rules).isEqualTo(SmartRules(null, null, null))
    assertThat(controller.ui.value.canSave).isFalse()
    // Si possono comunque scrivere le regole a mano.
    controller.setText("neve")
    assertThat(controller.ui.value.rules?.text).isEqualTo("neve")
    assertThat(controller.ui.value.problem).isNull()
    assertThat(controller.ui.value.canSave).isTrue()
  }

  @Test
  fun leRegoleDiUnAlbumCheNonCePiuODiUnManualeNonSiCambiano() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val missing = editor(world, SmartEditorMode.Edit(ShareFixtures.ALBUM))
    assertThat(missing.ui.value.problem).isEqualTo(EditorProblem.NOT_FOUND)
    assertThat(missing.ui.value.canSave).isFalse()
  }
}
