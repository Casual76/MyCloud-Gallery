package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D-B1 delle prove sul campo: il foglio "Elimina dal telefono" della Libreria diceva sempre "Non c'è ancora una copia sul NAS", anche per tre foto
 * "al sicuro sul NAS". L'avviso (E-D14) vale solo per i media che non sono al sicuro; con una selezione mista lo dice con i numeri.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class LibraryDeleteSafetyTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class Commands : LibraryCommands {
    override val canDeleteFromPhone: Boolean get() = true
  }

  /** Una Libreria in cui le prime [safe] celle sono "al sicuro sul NAS" e le altre sono solo sul telefono. */
  private fun state(safe: Int): LibraryTabState = syntheticLibraryState(
    count = 60,
    edit = { snapshot -> markFirst(snapshot, safe) },
  ).let { first ->
    LibraryTabState(
      grid = first.grid, viewerItems = first.viewerItems, viewerImages = first.viewerImages, photoCount = 0, videoCount = 0,
      timeline = first.timeline, preferences = first.preferences, phone = first.phone, commands = Commands(), actions = first.actions,
    )
  }

  private fun markFirst(snapshot: InMemoryTimelineSnapshot, safe: Int): InMemoryTimelineSnapshot {
    val onPhoneOnly = CellFlags.ON_PHONE
    val atSafe = CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE
    var out = snapshot
    for (i in 0 until 20) {
      val base = out.flagsAt(i) and PRESENCE.inv()
      out = out.withFlags(i, base or if (i < safe) atSafe else onPhoneOnly)
    }
    return out
  }

  private fun open(state: LibraryTabState, howMany: Int) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        val modalHost = rememberFluidGlassModalHostState()
        val backdrop = rememberGlassBackdrop()
        CompositionLocalProvider(LocalFluidGlassModalHostState provides modalHost) {
          Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
            LibraryTab(
              contentPadding = PaddingValues(bottom = 96.dp),
              backdropOut = {},
              onOpenViewer = {},
              onOpenSettings = {},
              statusFooter = {},
              state = state,
            )
          }
          FluidGlassModalHost(state = modalHost, backdrop = backdrop)
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    composeRule.waitForIdle()
    composeRule.runOnIdle {
      val snapshot = state.grid.view.snapshot
      val view = state.grid.view
      state.grid.selection.selectAll((0 until howMany).map { snapshot.idAt(view.snapshotIndex(it)) })
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Elimina dal telefono").performClick()
    composeRule.waitForIdle()
  }

  private fun assertShown(text: String) = composeRule.onAllNodesWithText(text, substring = true).onFirst().assertExists()

  private fun assertNotShown(text: String) = composeRule.onAllNodesWithText(text, substring = true).assertCountEquals(0)

  @Test
  fun d_b1_conTuttiAlSicuroNonDiceCheNonCeUnaCopia() {
    open(state(safe = 5), howMany = 3)

    assertShown("Eliminare 3 foto?")
    assertShown("Hanno tutti una copia al sicuro sul NAS")
    assertNotShown("Non c’è ancora una copia")
    assertNotShown("Non hanno ancora una copia")
    assertNotShown("non hanno ancora una copia")
  }

  @Test
  fun d_b1_conNessunoAlSicuroDiceCheNonCeUnaCopiaEProponeDiMetterliAlSicuro() {
    open(state(safe = 0), howMany = 3)

    assertShown("Eliminare 3 foto?")
    assertShown("Non hanno ancora una copia sul NAS")
    assertShown("Prima mettili al sicuro")
    assertNotShown("Hanno tutti una copia al sicuro")
    // Tutti e tre senza copia: niente "x di y" (sarebbe un conteggio parziale).
    assertNotShown(" di 3 ")
  }

  @Test
  fun d_b1_conUnSoloElementoNonAlSicuroParlaAlSingolare() {
    open(state(safe = 0), howMany = 1)

    assertShown("Non c’è ancora una copia sul NAS")
    assertShown("Prima mettilo al sicuro")
  }

  @Test
  fun d_b1_conUnaSelezioneMistaLoDiceConINumeri() {
    // Cinque scelte: le prime tre sono al sicuro, le ultime due no.
    open(state(safe = 3), howMany = 5)

    assertShown("Eliminare 5 foto?")
    assertShown("2 di 5 non hanno ancora una copia sul NAS")
    assertShown("Prima mettili al sicuro")
    assertNotShown("Hanno tutti una copia al sicuro")
  }

  @Test
  fun d_b1_conUnoSoloSenzaCopiaFraTantiDiceUnoDiN() {
    open(state(safe = 4), howMany = 5)

    assertShown("1 di 5 non ha ancora una copia sul NAS")
    assertShown("Prima mettilo al sicuro")
  }

  @Test
  fun d_b1_ilConteggioSiLeggeDaiSegniDellaCella() {
    val state = state(safe = 2)
    val snapshot = state.grid.view.snapshot
    val view = state.grid.view
    val ids: Set<MediaId> = (0 until 4).mapTo(LinkedHashSet()) { snapshot.idAt(view.snapshotIndex(it)) }

    assertThat(state.grid.unsafeAmong(ids)).isEqualTo(2)
    // Un id che non e' nell'indice conta come non al sicuro: nel dubbio l'avviso c'e'.
    assertThat(state.grid.unsafeAmong(setOf(MediaId("00000000-0000-0000-0000-000000000000")))).isEqualTo(1)
  }

  private companion object {
    const val PRESENCE: Int = CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE or CellFlags.TO_VERIFY
  }
}
