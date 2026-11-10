package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.SmartPreview
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumBanner
import dev.pampa.mycloudgallery.feature.albums.contract.PeopleRuleInfo
import dev.pampa.mycloudgallery.feature.albums.contract.PersonChoice
import dev.pampa.mycloudgallery.feature.albums.contract.SmartEditorState
import dev.pampa.mycloudgallery.feature.albums.editor.NoSmartEditorHandlers
import dev.pampa.mycloudgallery.feature.albums.editor.PeoplePickerSheet
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorScreen
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorUi
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageScreen
import dev.pampa.mycloudgallery.feature.albums.page.NoAlbumPageHandlers
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot della regola "Persone" degli album smart (Fase 8, P10; docs/fase8-progetto.md §2.7): la sezione dell'editor in ogni stato (due persone "tutte", una sola
 * dalla pagina persona, una perduta e una nascosta, i volti spenti), il foglio di scelta e le strisce della pagina dell'album. Chiaro e scuro, telefono e tablet, piu' il
 * testo grande sul telefono. Solo nomi inventati. Finiscono in `docs/screenshots/8-P10/` con il prefisso `albums-`.
 */
abstract class PeopleEditorShots(private val dark: Boolean, private val device: String) : AlbumsComposeTest() {

  private val scenes = Scenes()
  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, expect: List<String> = emptyList(), fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale) { AlbumHost { content() } }
    for (text in expect) {
      assertWithMessage("manca «$text» nella scena $name").that(composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()).isNotEmpty()
    }
    val suffix = if (fontScale != null) "-testo-grande" else ""
    // Il testo grande solo sul telefono (le immagini del tablet sono grandi).
    if (fontScale == null || device == "telefono") composeRule.onRoot().captureRoboImage("../docs/screenshots/8-P10/albums-$name-$look-$device$suffix.png")
  }

  private fun person(n: Int) = PersonId.fromBytes(ByteArray(PersonId.BYTES) { (n * 19 + it).toByte() })

  private val marco = person(1)
  private val anna = person(2)
  private val giulia = person(3)
  private val lost = person(9)
  private val hidden = person(8)
  private val choices = listOf(PersonChoice(marco, "Marco", 214), PersonChoice(anna, "Anna", 168), PersonChoice(giulia, "Giulia", 57), PersonChoice(person(4), "Luca", 31))
  private val sample: List<MediaId> = (1..12).map { ShareFixtures.media(it) }
  private val preview = SmartPreview(confident = 38, suggested = 6, tooBroad = false, sample = sample, searchUsed = true)

  private fun editor(
    rule: PeopleRule,
    names: Map<PersonId, String>,
    lostIds: Set<PersonId> = emptySet(),
    on: Boolean = true,
    creating: Boolean = true,
    title: String = "Marco e Anna",
    preview: SmartPreview = this.preview,
    scrolled: Boolean = true,
  ): @Composable () -> Unit {
    val rules = SmartRules(null, null, null, people = rule)
    val ui = SmartEditorUi(
      title = title, state = SmartEditorState.Proposal(rules, emptyList(), emptyList(), preview, emptySet()), rules = rules, preview = preview, canSave = true, creating = creating,
      peopleOn = on, peopleChoices = if (on) choices else emptyList(), peopleInfo = PeopleRuleInfo(names, lostIds),
    )
    // La pagina scorre: di solito si parte dalla sezione "Persone" (e' la terza dopo il testo di apertura), cosi' si vede intera; una scena parte dall'inizio.
    return { SmartEditorScreen(ui, scenes.thumbnails.thumbnails, NoSmartEditorHandlers, listState = rememberLazyListState(initialFirstVisibleItemIndex = if (scrolled) PEOPLE_ITEM else 0)) }
  }

  private val twoNames = mapOf(marco to "Marco", anna to "Anna")

  @Test
  fun editorDuePersoneTutte() =
    shoot("editor-persone", listOf("Marco", "Tutte queste persone"), content = editor(PeopleRule(listOf(marco, anna), PeopleMode.ALL), twoNames))

  @Test
  fun editorDuePersoneTutteTestoGrande() =
    shoot("editor-persone", listOf("Marco", "Tutte queste persone"), LARGE_TEXT, editor(PeopleRule(listOf(marco, anna), PeopleMode.ALL), twoNames))

  @Test
  fun editorUnaPersonaDallaPaginaPersona() =
    shoot("editor-persone-una", listOf("Anna"), content = editor(PeopleRule(listOf(anna), PeopleMode.ANY), mapOf(anna to "Anna"), title = "Anna", scrolled = false))

  @Test
  fun editorUnaQualunque() =
    shoot("editor-persone-una-qualunque", listOf("Almeno una di loro"), content = editor(PeopleRule(listOf(marco, anna, giulia), PeopleMode.ANY), twoNames + (giulia to "Giulia"), creating = false))

  @Test
  fun editorPersonaPerdutaENascosta() = shoot(
    "editor-persone-perdute", listOf("Persona non più riconosciuta", "Una persona nascosta"),
    content = editor(PeopleRule(listOf(marco, lost, hidden), PeopleMode.ALL), mapOf(marco to "Marco"), lostIds = setOf(lost), creating = false),
  )

  @Test
  fun editorVoltiSpenti() = shoot(
    "editor-persone-spente", listOf("Le persone non sono attive su questo dispositivo"),
    content = editor(PeopleRule(listOf(marco, anna), PeopleMode.ALL), twoNames, on = false, creating = false, preview = preview.copy(confident = 0, suggested = 0, sample = emptyList(), peopleAvailable = false)),
  )

  @Test
  fun foglioDiSceltaDellePersone() =
    shoot("foglio-persone", listOf("Scegli le persone", "Fatto"), content = { PeoplePickerSheet(visible = true, choices = choices, chosen = setOf(marco), onToggle = {}, onDismiss = {}) })

  @Test
  fun foglioDiSceltaDellePersoneTestoGrande() =
    shoot("foglio-persone", listOf("Scegli le persone"), LARGE_TEXT, { PeoplePickerSheet(visible = true, choices = choices, chosen = setOf(marco, anna), onToggle = {}, onDismiss = {}) })

  @Test
  fun paginaDellAlbumConLePersoneSpente() = shoot(
    "pagina-persone-spente", listOf("Le persone non sono attive su questo dispositivo"),
    content = { AlbumPageScreen(scenes.pageUi(scenes.oslo, banners = listOf(AlbumBanner.PeopleOff)), "", scenes.thumbnails.thumbnails, NoAlbumPageHandlers) },
  )

  @Test
  fun paginaDellAlbumConUnaPersonaPerduta() = shoot(
    "pagina-persone-perduta", listOf("Una persona della regola non è più riconosciuta"),
    content = { AlbumPageScreen(scenes.pageUi(scenes.oslo, banners = listOf(AlbumBanner.PeopleLost(1))), "", scenes.thumbnails.thumbnails, NoAlbumPageHandlers) },
  )

  companion object {
    /** L'indice della sezione "Persone" nella pagina dell'editor: il testo di apertura, Periodo, Luogo, poi lei. */
    private const val PEOPLE_ITEM = 3

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_360_QUALIFIERS)
class PeopleEditorPhoneScreenshotTest(dark: Boolean) : PeopleEditorShots(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class PeopleEditorTabletScreenshotTest(dark: Boolean) : PeopleEditorShots(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
