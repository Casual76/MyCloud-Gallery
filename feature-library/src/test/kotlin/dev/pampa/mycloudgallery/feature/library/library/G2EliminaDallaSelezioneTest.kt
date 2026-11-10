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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 5B I3 (decisione dell'utente, fase grafica G2): "Elimina dal telefono" dalla selezione della Libreria.
 *  - **backup attivo** ([LibraryCommands.deletesThroughSafeSheet]): passa dal foglio "Prima mettilo al sicuro" della 5B (lo mostra `:app` alla
 *    radice) con i soli media che hanno una copia sul telefono, anche con una selezione mista; il foglio di sempre non si apre;
 *  - **backup spento**: il foglio di sempre (D14, D-B1), come oggi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class G2EliminaDallaSelezioneTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  /** I comandi della Libreria: il backup attivo o no, e cosa chiede la Libreria. [nasOnly] sono i media senza una copia sul telefono. */
  private class Commands(private val safeSheet: Boolean, private val nasOnly: Set<MediaId> = emptySet()) : LibraryCommands {
    val safely = ArrayList<Set<MediaId>>()
    val trashed = ArrayList<Set<MediaId>>()

    /** FAB-7: la risposta positiva del cestino di sistema, o l'utente che va avanti dal foglio della 5B: lo emette il test, come farebbe `:app`. */
    val settled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val selectionSettled: Flow<Unit> get() = settled

    override val canDeleteFromPhone: Boolean get() = true

    override fun onPhone(ids: Set<MediaId>): Set<MediaId> = ids - nasOnly

    override fun deleteFromPhone(ids: Set<MediaId>): Boolean {
      trashed += ids
      return true
    }

    override val deletesThroughSafeSheet: Boolean get() = safeSheet

    override fun deleteFromPhoneSafely(ids: Set<MediaId>) {
      safely += ids
    }
  }

  /** Una Libreria in cui le prime [safe] celle sono "al sicuro sul NAS" e le altre sono solo sul telefono. */
  private fun state(safe: Int, commands: LibraryCommands): LibraryTabState = syntheticLibraryState(
    count = 60,
    edit = { snapshot -> markFirst(snapshot, safe) },
  ).let { first ->
    LibraryTabState(
      grid = first.grid, viewerItems = first.viewerItems, viewerImages = first.viewerImages, photoCount = 0, videoCount = 0,
      timeline = first.timeline, preferences = first.preferences, phone = first.phone, commands = commands, actions = first.actions,
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

  private fun idsOf(state: LibraryTabState, howMany: Int): List<MediaId> {
    val snapshot = state.grid.view.snapshot
    val view = state.grid.view
    return (0 until howMany).map { snapshot.idAt(view.snapshotIndex(it)) }
  }

  private fun selectAndDelete(state: LibraryTabState, ids: List<MediaId>) {
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
    composeRule.runOnIdle { state.grid.selection.selectAll(ids) }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Elimina dal telefono").performClick()
    composeRule.waitForIdle()
  }

  private fun assertNotShown(text: String) = composeRule.onAllNodesWithText(text, substring = true).assertCountEquals(0)

  /** Selezione mista (tre al sicuro, due no), backup attivo: tutti e cinque al foglio della 5B, che dice quanti non sono al sicuro. */
  @Test
  fun i3_backupAttivo_selezioneMista_vaAlFoglioPrimaMettiloAlSicuro() {
    val commands = Commands(safeSheet = true)
    val state = state(safe = 3, commands = commands)
    val ids = idsOf(state, 5)
    selectAndDelete(state, ids)

    assertThat(commands.safely).containsExactly(ids.toSet())
    assertThat(commands.trashed).isEmpty()
    // Il foglio della 4B non si apre: il seguito e' del foglio della 5B, alla radice.
    assertNotShown("Eliminare 5 foto?")
    // FAB-7: la selezione resta finche' l'utente non va avanti dal foglio. Se preme "Chiudi" la ritrova com'era (prima si chiudeva subito).
    composeRule.runOnIdle {
      assertThat(state.grid.selecting).isTrue()
      assertThat(state.grid.selection.selected.value.size).isEqualTo(5)
    }
    // "Continua" (o il cestino dopo l'avviso): `:app` lo dice e la selezione si chiude.
    composeRule.runOnIdle { assertThat(commands.settled.tryEmit(Unit)).isTrue() }
    composeRule.waitForIdle()
    composeRule.runOnIdle { assertThat(state.grid.selecting).isFalse() }
  }

  /**
   * FAB-7: confermato il foglio di sempre, la selezione resta finche' il dialogo di sistema del cestino non ha la risposta positiva. Se l'utente annulla
   * li' (nessuna risposta) la scelta non e' persa; prima la selezione si chiudeva appena il dialogo si apriva.
   */
  @Test
  fun fab7_confermatoIlFoglioLaSelezioneRestaFinoAllaRispostaPositivaDelDialogo() {
    val commands = Commands(safeSheet = false)
    val state = state(safe = 3, commands = commands)
    selectAndDelete(state, idsOf(state, 2))

    composeRule.onAllNodesWithText("Elimina dal telefono").onFirst().performClick()
    composeRule.waitForIdle()

    assertThat(commands.trashed).hasSize(1)
    // Il dialogo di sistema e' aperto (l'utente puo' annullare): la selezione e' ancora tutta li'.
    composeRule.runOnIdle {
      assertThat(state.grid.selecting).isTrue()
      assertThat(state.grid.selection.selected.value.size).isEqualTo(2)
    }
    // Risposta positiva: ora la selezione si chiude.
    composeRule.runOnIdle { assertThat(commands.settled.tryEmit(Unit)).isTrue() }
    composeRule.waitForIdle()
    composeRule.runOnIdle { assertThat(state.grid.selecting).isFalse() }
  }

  /** I media solo sul NAS non vanno al foglio (non c'e' niente da togliere dal telefono), come nel foglio di sempre (GA-8). */
  @Test
  fun i3_backupAttivo_soloIMediaSulTelefono() {
    val probe = state(safe = 3, commands = Commands(safeSheet = true))
    val ids = idsOf(probe, 5)
    val commands = Commands(safeSheet = true, nasOnly = setOf(ids[0]))
    val state = state(safe = 3, commands = commands)
    selectAndDelete(state, ids)

    assertThat(commands.safely).containsExactly(ids.drop(1).toSet())
  }

  /** Backup spento: il foglio di sempre (D14, D-B1), con i numeri della selezione mista; niente foglio della 5B. */
  @Test
  fun i3_backupSpento_restaIlFoglioDiSempre() {
    val commands = Commands(safeSheet = false)
    val state = state(safe = 3, commands = commands)
    selectAndDelete(state, idsOf(state, 5))

    composeRule.onAllNodesWithText("Eliminare 5 foto?", substring = true).onFirst().assertExists()
    composeRule.onAllNodesWithText("2 di 5 non hanno ancora una copia sul NAS", substring = true).onFirst().assertExists()
    assertThat(commands.safely).isEmpty()
    composeRule.runOnIdle { assertThat(state.grid.selecting).isTrue() }
  }

  private companion object {
    const val PRESENCE: Int = CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE or CellFlags.TO_VERIFY
  }
}
