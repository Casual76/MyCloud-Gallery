package dev.pampa.mycloudgallery.feature.people

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListUi
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageUi
import dev.pampa.mycloudgallery.feature.people.list.NoOthersHandlers
import dev.pampa.mycloudgallery.feature.people.list.NoPeopleListHandlers
import dev.pampa.mycloudgallery.feature.people.list.OthersScreen
import dev.pampa.mycloudgallery.feature.people.list.PeopleListScreen
import dev.pampa.mycloudgallery.feature.people.page.NoPersonPageHandlers
import dev.pampa.mycloudgallery.feature.people.page.PersonPageScreen
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, P8 (D23, D25-D27, E-AC2, §2.7 "Raccolte, album, Persone", D28): le persone lette come le legge TalkBack. Gli stati del catalogo (elenco,
 * selezione, vuoto, in calcolo, "Altri volti", la pagina di una persona con e senza nome), a testo 1,0 e 2,0: nome e ruolo per ogni elemento
 * attivo, bersagli da 48 dp, nessun id o nome di file, testo non tagliato. I volti sono sagome astratte e i nomi inventati.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class PeopleAccessibilityTest : PeopleScreenTest() {

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  private fun config() = A11yConfig(density = composeRule.density)

  private fun page(ui: PersonPageUi): @Composable () -> Unit = { PersonPageScreen(ui, scenes.thumbs.thumbnails, scenes.crops, NoPersonPageHandlers) }

  /** La scala del testo di adesso: si cambia senza rimostrare la scena (`setContent` si puo' chiamare una volta per prova). */
  private val scale = mutableStateOf(1f)

  /** Mostra [content] e controlla le regole a testo 1,0 e poi a 2,0 sulla stessa scena. */
  private fun clean(name: String, content: @Composable () -> Unit) {
    showAll {
      val d = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) { content() }
    }
    for (value in listOf(1f, 2f)) {
      scale.value = value
      composeRule.waitForIdle()
      val found = A11yAssertions.check(root(), config())
      assertWithMessage("$name a testo $value: " + found.joinToString(" | ")).that(found).isEmpty()
    }
  }

  // --- Le liste -----------------------------------------------------------------------------------------------------------------

  @Test fun elenco() = clean("elenco") { PeopleListScreen(scenes.list(), scenes.crops, NoPeopleListHandlers) }

  @Test fun elencoInSelezione() = clean("elenco in selezione") { PeopleListScreen(scenes.listSelecting(), scenes.crops, NoPeopleListHandlers) }

  @Test fun elencoVuoto() = clean("elenco vuoto") { PeopleListScreen(scenes.listEmpty(), scenes.crops, NoPeopleListHandlers) }

  @Test fun elencoInCalcolo() = clean("elenco in calcolo") { PeopleListScreen(PeopleListUi(PeopleListState.Working(35)), scenes.crops, NoPeopleListHandlers) }

  @Test
  fun unioneConDueNomi() = clean("unione con due nomi") { PeopleListScreen(scenes.listMergeChoosePickName(), scenes.crops, NoPeopleListHandlers) }

  @Test fun altriVolti() = clean("altri volti") { OthersScreen(scenes.others(), scenes.crops, NoOthersHandlers) }

  @Test fun altriVoltiInSelezione() = clean("altri volti in selezione") { OthersScreen(scenes.othersSelecting(), scenes.crops, NoOthersHandlers) }

  @Test fun altriVoltiVuoto() = clean("altri volti vuoto") { OthersScreen(scenes.othersEmpty(), scenes.crops, NoOthersHandlers) }

  // --- La pagina di una persona -----------------------------------------------------------------------------------------------

  @Test fun paginaPersona() = clean("persona", page(scenes.pageUi(scenes.world.anna)))

  @Test fun paginaSenzaNome() = clean("senza nome", page(scenes.pageUnnamed()))

  @Test fun paginaNascosta() = clean("nascosta", page(scenes.pageHidden()))

  @Test fun paginaDimenticata() = clean("dimenticata", page(scenes.pageForgotten()))

  @Test fun paginaNonTrovata() = clean("non trovata", page(scenes.pageNotFound()))

  @Test fun paginaInCaricamento() = clean("in caricamento", page(scenes.pageLoading()))

  @Test fun paginaConMessaggio() = clean("con messaggio", page(scenes.pageWithMessage()))

  @Test fun foglioDelNome() = clean("foglio del nome", page(scenes.pageNaming()))

  // --- La semantica attesa del §2.7 -------------------------------------------------------------------------------------------

  @Test
  fun inSelezioneLeRigheDiconoSeSonoScelte() {
    showAll(content = { PeopleListScreen(scenes.listSelecting(), scenes.crops, NoPeopleListHandlers) })
    val toggles = A11yAssertions.nodes(root()).filter {
      androidx.compose.ui.semantics.SemanticsProperties.ToggleableState in it.config || androidx.compose.ui.semantics.SemanticsProperties.Selected in it.config
    }
    assertThat(toggles).isNotEmpty()
  }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atfElenco() {
    showAll(content = { PeopleListScreen(scenes.list(), scenes.crops, NoPeopleListHandlers) })
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfPaginaDiUnaPersona() {
    showAll(content = page(scenes.pageUi(scenes.world.anna)))
    composeRule.onRoot().checkRoboAccessibility()
  }
}

