package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.ProposalNote
import dev.pampa.mycloudgallery.core.albums.contract.RuleProposal
import dev.pampa.mycloudgallery.core.albums.contract.SmartPreview
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.testing.FakeSmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.testing.FakeSmartNameInterpreter
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.contract.PersonChoice
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorController
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorMode
import dev.pampa.mycloudgallery.feature.albums.testing.FakePeopleChoices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La regola "Persone" nell'editor (Fase 8, P10; docs/fase8-progetto.md §2.7, D27; E-F23, E-F27, E-F28, E-F37): si scelgono dalla porta, "tutte" o "una qualunque", si vedono
 * per nome, una persona perduta si dice e una nascosta non mostra il nome; dove i volti non ci sono la sezione lo dice e la regola resta com'e'; dalla pagina persona
 * l'editor parte gia' con la persona. Solo nomi inventati.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleEditorControllerTest {
  private fun person(n: Int) = PersonId.fromBytes(ByteArray(PersonId.BYTES) { (n * 11 + it).toByte() })

  private val marco = person(1)
  private val anna = person(2)
  private val giulia = person(3)

  private val choices = listOf(PersonChoice(marco, "Marco", 120), PersonChoice(anna, "Anna", 80), PersonChoice(giulia, "Giulia", 12))
  private val preview = SmartPreview(confident = 30, suggested = 4, tooBroad = false, sample = listOf(ShareFixtures.media(1)), searchUsed = true)

  private fun TestScope.editor(
    people: FakePeopleChoices = FakePeopleChoices(choices),
    mode: SmartEditorMode = SmartEditorMode.Create("Marco 2025"),
    interpreter: FakeSmartNameInterpreter = FakeSmartNameInterpreter(RuleProposal(SmartRules(null, null, null), emptyList(), emptyList(), setOf(ProposalNote.NO_DATE, ProposalNote.NO_PLACE))),
    engine: FakeSmartAlbumEngine = FakeSmartAlbumEngine(nextPreview = preview),
    world: AlbumsTestWorld = AlbumsTestWorld(),
  ) = SmartEditorController(mode, interpreter, engine, world.albums, world.screens, backgroundScope, people).also { it.start() }

  @Test
  fun leRegoleDelNomeConLePersoneArrivanoConIlNome() = runTest(UnconfinedTestDispatcher()) {
    val proposal = RuleProposal(SmartRules(null, null, null, people = PeopleRule(listOf(marco), PeopleMode.ANY)), emptyList(), emptyList(), emptySet())
    val controller = editor(interpreter = FakeSmartNameInterpreter(proposal))
    val ui = controller.ui.value
    assertThat(ui.rules?.people).isEqualTo(PeopleRule(listOf(marco), PeopleMode.ANY))
    assertThat(ui.peopleInfo.names).containsExactly(marco, "Marco")
    assertThat(ui.peopleOn).isTrue()
    assertThat(ui.peopleChoices.map { it.label }).containsExactly("Marco", "Anna", "Giulia").inOrder()
    assertThat(ui.canSave).isTrue()
  }

  @Test
  fun dallaPaginaPersonaLEditorParteConLaPersonaESenzaInterprete() = runTest(UnconfinedTestDispatcher()) {
    val interpreter = FakeSmartNameInterpreter()
    val start = PeopleRule(listOf(anna), PeopleMode.ANY)
    val controller = editor(mode = SmartEditorMode.Create("Anna", startWith = start), interpreter = interpreter)
    // Il titolo e' il nome della persona, non un'idea di regole: l'interprete non si chiede e il nome non diventa una descrizione.
    assertThat(interpreter.asked).isEmpty()
    assertThat(controller.ui.value.rules).isEqualTo(SmartRules(null, null, null, people = start))
    assertThat(controller.ui.value.rules?.text).isNull()
    assertThat(controller.ui.value.peopleInfo.names).containsExactly(anna, "Anna")
    assertThat(controller.ui.value.preview?.confident).isEqualTo(30)
    // Una regola di sole persone e' una regola: si crea (le altre regole da sole no, E-AL9).
    assertThat(controller.ui.value.canSave).isTrue()
  }

  @Test
  fun leDuePersoneStannoInTutteESiCambiaIlModo() = runTest(UnconfinedTestDispatcher()) {
    val controller = editor()
    controller.addPeople(listOf(marco))
    assertThat(controller.ui.value.rules?.people).isEqualTo(PeopleRule(listOf(marco), PeopleMode.ALL))
    // Con una persona sola il modo non conta e non si cambia.
    controller.setPeopleMode(PeopleMode.ANY)
    assertThat(controller.ui.value.rules?.people?.mode).isEqualTo(PeopleMode.ALL)
    controller.addPeople(listOf(anna))
    assertThat(controller.ui.value.rules?.people).isEqualTo(PeopleRule(listOf(marco, anna), PeopleMode.ALL))
    controller.setPeopleMode(PeopleMode.ANY)
    assertThat(controller.ui.value.rules?.people?.mode).isEqualTo(PeopleMode.ANY)
    // Una terza persona non rimette "tutte": il modo scelto resta.
    controller.addPeople(listOf(giulia))
    assertThat(controller.ui.value.rules?.people).isEqualTo(PeopleRule(listOf(marco, anna, giulia), PeopleMode.ANY))
    assertThat(controller.ui.value.peopleInfo.names.keys).containsExactly(marco, anna, giulia)
  }

  @Test
  fun unaPersonaSiMetteEFaTornareLAnteprima() = runTest(UnconfinedTestDispatcher()) {
    val engine = FakeSmartAlbumEngine(nextPreview = preview)
    val controller = editor(engine = engine)
    controller.togglePerson(marco)
    assertThat(controller.ui.value.rules?.people?.ids).containsExactly(marco)
    assertThat(controller.ui.value.preview).isEqualTo(preview)
    engine.nextPreview = preview.copy(confident = 7, suggested = 9)
    controller.togglePerson(anna)
    assertThat(controller.ui.value.preview?.confident).isEqualTo(7)
    controller.togglePerson(marco)
    assertThat(controller.ui.value.rules?.people?.ids).containsExactly(anna)
  }

  @Test
  fun togliendoLUltimaPersonaLaRegolaSparisce() = runTest(UnconfinedTestDispatcher()) {
    val controller = editor()
    controller.addPeople(listOf(marco, anna))
    controller.removePerson(marco)
    assertThat(controller.ui.value.rules?.people?.ids).containsExactly(anna)
    controller.removePerson(anna)
    assertThat(controller.ui.value.rules?.people).isNull()
    assertThat(controller.ui.value.peopleInfo.names).isEmpty()
    // Senza altre regole non si puo' creare (E-AL9).
    assertThat(controller.ui.value.canSave).isFalse()
    controller.addPeople(listOf(marco, anna))
    controller.removePeople()
    assertThat(controller.ui.value.rules?.people).isNull()
  }

  @Test
  fun alPiuTrentaDuePersoneESenzaRipetizioni() = runTest(UnconfinedTestDispatcher()) {
    val many = (1..40).map { PersonChoice(person(100 + it), "Persona $it", it) }
    val controller = editor(people = FakePeopleChoices(many))
    controller.addPeople(many.map { it.id })
    controller.addPeople(many.take(5).map { it.id })
    assertThat(controller.ui.value.rules?.people?.ids).hasSize(PeopleRule.MAX_PEOPLE)
    assertThat(controller.ui.value.rules?.people?.ids?.toSet()).hasSize(PeopleRule.MAX_PEOPLE)
  }

  @Test
  fun senzaIVoltiLaSezioneLoDiceELaRegolaRestaCosiComeE_F37() = runTest(UnconfinedTestDispatcher()) {
    val people = FakePeopleChoices(choices, available = false)
    val proposal = RuleProposal(SmartRules(null, null, null, people = PeopleRule(listOf(marco, anna), PeopleMode.ALL)), emptyList(), emptyList(), emptySet())
    val controller = editor(people = people, interpreter = FakeSmartNameInterpreter(proposal))
    val ui = controller.ui.value
    assertThat(ui.peopleOn).isFalse()
    assertThat(ui.peopleChoices).isEmpty()
    // La regola resta: un dispositivo senza volti non la toglie ne' la svuota.
    assertThat(ui.rules?.people).isEqualTo(PeopleRule(listOf(marco, anna), PeopleMode.ALL))
    assertThat(ui.canSave).isTrue()
    // Quando i volti si accendono la sezione si rifa' da sola.
    people.setAvailable(true)
    assertThat(controller.ui.value.peopleOn).isTrue()
    assertThat(controller.ui.value.peopleChoices).hasSize(3)
    assertThat(controller.ui.value.peopleInfo.names.keys).containsExactly(marco, anna)
  }

  @Test
  fun senzaLaPortaDelleNessunaPersonaSiOffre() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val controller = SmartEditorController(SmartEditorMode.Create("Oslo"), FakeSmartNameInterpreter(), FakeSmartAlbumEngine(nextPreview = preview), world.albums, world.screens, backgroundScope)
      .also { it.start() }
    assertThat(controller.ui.value.peopleOn).isFalse()
    assertThat(controller.ui.value.peopleChoices).isEmpty()
  }

  @Test
  fun unaPersonaPerdutaSiDiceUnaNascostaNonHaIlNome_E_F27_E_F28() = runTest(UnconfinedTestDispatcher()) {
    val lost = person(50)
    val hidden = person(51)
    val people = FakePeopleChoices(choices, lost = setOf(lost), hidden = setOf(hidden))
    val rule = PeopleRule(listOf(marco, lost, hidden), PeopleMode.ALL)
    val controller = editor(people = people, interpreter = FakeSmartNameInterpreter(RuleProposal(SmartRules(null, null, null, people = rule), emptyList(), emptyList(), emptySet())))
    val info = controller.ui.value.peopleInfo
    assertThat(info.names).containsExactly(marco, "Marco")
    assertThat(info.lost).containsExactly(lost)
    // La nascosta non e' ne' fra i nomi ne' fra le perse: vale ancora e il nome non si mostra; fuori dalle scelte.
    assertThat(hidden in info.names.keys).isFalse()
    assertThat(hidden in info.lost).isFalse()
    assertThat(controller.ui.value.peopleChoices.map { it.id }).doesNotContain(hidden)
    // Si puo' togliere la perduta e la regola ha il resto.
    controller.removePerson(lost)
    assertThat(controller.ui.value.rules?.people?.ids).containsExactly(marco, hidden).inOrder()
    assertThat(controller.ui.value.peopleInfo.lost).isEmpty()
  }

  @Test
  fun seLaPortaSbagliaLEditorNonSiRompe_AS7() = runTest(UnconfinedTestDispatcher()) {
    val people = FakePeopleChoices(choices).also { it.failing = true }
    val controller = editor(people = people)
    assertThat(controller.ui.value.peopleChoices).isEmpty()
    controller.addPeople(listOf(marco))
    assertThat(controller.ui.value.rules?.people?.ids).containsExactly(marco)
    assertThat(controller.ui.value.peopleInfo.names).isEmpty()
    assertThat(controller.ui.value.canSave).isTrue()
  }

  @Test
  fun creareScriveLaRegolaDellePersoneConLeAltreRegole() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val controller = editor(world = world)
    controller.setText("mare")
    controller.addPeople(listOf(marco, anna))
    controller.setPeopleMode(PeopleMode.ANY)
    controller.save()
    val id = controller.ui.value.savedAlbum!!
    val album = world.albums.all.value.byId(id) as Album.Smart
    assertThat(album.kind).isEqualTo(AlbumKind.SMART)
    assertThat(album.rules.people).isEqualTo(PeopleRule(listOf(marco, anna), PeopleMode.ANY))
    assertThat(album.rules.text).isEqualTo("mare")
  }

  @Test
  fun cambiareLeRegoleDiUnoSmartConLePersoneLeMostraELeSalva() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val created = world.albums.create("Marco e Anna", AlbumKind.SMART, SmartRules(null, null, null, people = PeopleRule(listOf(marco, anna), PeopleMode.ALL))) as CreateOutcome.Created
    val controller = editor(world = world, mode = SmartEditorMode.Edit(created.id))
    assertThat(controller.ui.value.rules?.people?.ids).containsExactly(marco, anna).inOrder()
    assertThat(controller.ui.value.peopleInfo.names.values).containsExactly("Marco", "Anna")
    controller.togglePerson(giulia)
    controller.removePerson(marco)
    controller.save()
    val saved = (world.albums.all.value.byId(created.id) as Album.Smart).rules.people
    assertThat(saved).isEqualTo(PeopleRule(listOf(anna, giulia), PeopleMode.ALL))
  }
}
