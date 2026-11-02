package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.PaddingValues
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
import dev.pampa.mycloudgallery.core.library.noop.EmptyViewerItems
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenDenial
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenScreen
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenUiState
import kotlinx.coroutines.runBlocking
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test

/**
 * Fase 10, P8 (D23, D25-D27, §2.7 "Raccolte, album, Persone", D28): le Raccolte, le griglie filtrate e i Nascosti letti come li legge TalkBack, a testo
 * 1,0 e 2,0: nome e ruolo per ogni elemento attivo, bersagli da 48 dp misurati, nessun id o nome di file, testo non tagliato; le sezioni sono
 * intestazioni. I Nascosti sono dati sintetici: la prova non apre mai niente di vero.
 */
class CollectionsAccessibilityTest : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val environment = fakeGridEnvironment(loader = fakeThumbnailLoader())

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  private fun config() = A11yConfig(density = composeRule.density)

  private fun grid(kind: CollectionKind, items: dev.pampa.mycloudgallery.core.library.contract.ViewerItems = world.source.items(kind.filter!!)): @Composable () -> Unit = {
    CollectionGridScreen(kind, items, environment, { _, _ -> }, {}, {}, onBack = {})
  }

  private fun hidden(state: HiddenUiState): @Composable () -> Unit = {
    HiddenScreen(state, environment, onUnlock = {}, onRetry = {}, onOpenSecuritySettings = {}, onShow = {}, onOpenViewer = {}, onBack = {})
  }

  private fun list(): @Composable () -> Unit = {
    CollectionsScreen(
      state = layoutOf(world),
      contentPadding = PaddingValues(),
      backdropOut = {},
      onOpenCollection = {},
      onOpenSettings = {},
      statusHeader = {},
      initialSelection = null,
      detail = { kind -> grid(kind)() },
    )
  }

  /** La scala del testo di adesso: si cambia senza rimostrare la scena (`setContent` si puo' chiamare una volta per prova). */
  private val scale = mutableStateOf(1f)

  /** Mostra [content] e controlla le regole a testo 1,0 e poi a 2,0 sulla stessa scena. */
  private fun clean(name: String, content: @Composable () -> Unit) {
    show {
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

  @Test fun raccolte() = clean("raccolte", list())

  @Test fun preferiti() = clean("preferiti", grid(CollectionKind.FAVORITES))

  @Test fun video() = clean("video", grid(CollectionKind.VIDEOS))

  @Test fun preferitiVuoti() = clean("preferiti vuoti", grid(CollectionKind.FAVORITES, EmptyViewerItems))

  @Test fun nascostiChiusi() = clean("nascosti chiusi", hidden(HiddenUiState.Locked))

  @Test fun nascostiAnnullato() = clean("nascosti annullato", hidden(HiddenUiState.Denied(HiddenDenial.CANCELLED)))

  @Test fun nascostiAperti() = clean("nascosti aperti", hidden(HiddenUiState.Content(runBlocking { world.hiddenLibrary.read() })))

  @Test
  fun leSezioniDelleRaccolteSonoIntestazioni() {
    show(content = list())
    assertThat(A11yAssertions.headingNames(root())).isNotEmpty()
  }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atfRaccolte() {
    show(content = list())
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfPreferiti() {
    show(content = grid(CollectionKind.FAVORITES))
    composeRule.onRoot().checkRoboAccessibility()
  }
}

