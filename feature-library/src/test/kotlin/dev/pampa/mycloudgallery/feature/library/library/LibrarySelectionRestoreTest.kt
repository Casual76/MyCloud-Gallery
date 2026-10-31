package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.grid.SavedSelection
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La selezione sopravvive a una **ricreazione vera** dell'activity (densita', lingua, tema, processo morto in secondo piano: Fase 10, P4, D14, E-T8). Una griglia nuova (come
 * quella dell'activity nuova) riceve cio' che la vecchia ha salvato nel suo stato (`rememberSaveable`, il `Bundle` dell'activity), con `StateRestorationTester`.
 * Fino a 500 elementi si salvano gli id; oltre, o con tutta la vista scelta, il solo "tutti": **mai una selezione troncata in silenzio**.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class LibrarySelectionRestoreTest {

  private val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private fun idsOf(grid: MediaGridState, count: Int): List<MediaId> {
    val view = grid.view
    return (0 until count).map { view.snapshot.idAt(view.snapshotIndex(it)) }
  }

  /**
   * Mostra la scheda Libreria sullo stato [first] con un registro dello stato salvato nostro; poi, come una ricreazione dell'activity, **salva** quel registro (il `Bundle`), ne fa
   * uno nuovo con quei valori e ricompone da zero la scheda su [second]: cio' che `rememberSaveable` ritrova e' tutto cio' che l'activity nuova sa della vecchia.
   */
  private fun recreate(first: LibraryTabState, second: LibraryTabState, before: (LibraryTabState) -> Unit) {
    var shown by mutableStateOf(first)
    var registry by mutableStateOf(SaveableStateRegistry(restoredValues = null, canBeSaved = { true }))
    // La composizione si toglie e si rimette (stessa posizione, quindi le stesse chiavi dello stato salvato): e' la ricreazione dell'activity.
    var alive by mutableStateOf(true)
    composeRule.setContent {
      CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
        if (alive) {
          MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
            LibraryTab(
              contentPadding = PaddingValues(bottom = 96.dp),
              backdropOut = {},
              onOpenViewer = {},
              onOpenSettings = {},
              statusFooter = {},
              state = shown,
            )
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.runOnIdle { before(first) }
    composeRule.waitForIdle()
    // L'activity nuova: lo stato salvato della vecchia, una griglia nuova, una composizione da zero.
    val saved = composeRule.runOnIdle { registry.performSave() }
    composeRule.runOnIdle { alive = false }
    composeRule.waitForIdle()
    composeRule.runOnIdle {
      shown = second
      registry = SaveableStateRegistry(restoredValues = saved, canBeSaved = { true })
      alive = true
    }
    composeRule.waitForIdle()
  }

  @Test
  fun unaSelezioneDiPochiElementiSiRitrovaDopoLaRicreazione() {
    val first = syntheticLibraryState(count = 2_000)
    val second = syntheticLibraryState(count = 2_000)
    val chosen = idsOf(first.grid, 12).filterIndexed { i, _ -> i % 2 == 0 }

    recreate(first, second) { state ->
      state.grid.startSelecting()
      state.grid.selection.selectAll(chosen)
    }

    assertThat(second.grid.selecting).isTrue()
    assertThat(second.grid.selection.selected.value.ids).containsExactlyElementsIn(chosen)
  }

  @Test
  fun laModalitaSelezioneSenzaElementiScelti_siRitrova() {
    val first = syntheticLibraryState(count = 500)
    val second = syntheticLibraryState(count = 500)

    recreate(first, second) { it.grid.startSelecting() }

    assertThat(second.grid.selecting).isTrue()
    assertThat(second.grid.selection.selected.value.ids).isEmpty()
  }

  @Test
  fun senzaSelezioneNonSiAccendeNiente() {
    val first = syntheticLibraryState(count = 500)
    val second = syntheticLibraryState(count = 500)

    recreate(first, second) { }

    assertThat(second.grid.selecting).isFalse()
    assertThat(second.grid.selection.selected.value.ids).isEmpty()
  }

  @Test
  fun unaSelezioneGrandeSiSalvaFinoA500_eAl500unoSiRitrovaPerIntero() {
    val first = syntheticLibraryState(count = 2_000)
    val second = syntheticLibraryState(count = 2_000)
    val chosen = idsOf(first.grid, SavedSelection.MAX_IDS)

    recreate(first, second) { state ->
      state.grid.startSelecting()
      state.grid.selection.selectAll(chosen)
      assertThat(state.grid.saveSelection()!!.ids).hasSize(SavedSelection.MAX_IDS)
    }

    assertThat(second.grid.selection.selected.value.ids).containsExactlyElementsIn(chosen)
  }

  @Test
  fun oltre500ElementiNonSiSalvaUnaSelezioneTroncata_siRestaInModalitaSelezioneSenzaScelte() {
    val first = syntheticLibraryState(count = 2_000)
    val second = syntheticLibraryState(count = 2_000)
    val chosen = idsOf(first.grid, SavedSelection.MAX_IDS + 1)

    recreate(first, second) { state ->
      state.grid.startSelecting()
      state.grid.selection.selectAll(chosen)
      val saved = state.grid.saveSelection()!!
      assertThat(saved.all).isFalse()
      assertThat(saved.ids).isEmpty()
    }

    // Niente di mezzo: o tutti gli scelti o nessuno. Chi riprende non agisce su un pezzo della selezione che aveva.
    assertThat(second.grid.selecting).isTrue()
    assertThat(second.grid.selection.selected.value.ids).isEmpty()
  }

  @Test
  fun conTuttaLaVistaSelezionata_siSalvaIlSoloTutti_eSiRiselezionaLaVistaDiAdesso() {
    val first = syntheticLibraryState(count = 1_200)
    val second = syntheticLibraryState(count = 1_200)

    recreate(first, second) { state ->
      state.grid.startSelecting()
      state.grid.selection.selectAll(idsOf(state.grid, 1_200))
      val saved = state.grid.saveSelection()!!
      assertThat(saved.all).isTrue()
      assertThat(saved.ids).isEmpty()
    }

    assertThat(second.grid.selecting).isTrue()
    assertThat(second.grid.selection.selected.value.ids).hasSize(1_200)
  }

  @Test
  fun unElementoCheNonCePiuNellaVistaNonRitorna() {
    val first = syntheticLibraryState(count = 600)
    // L'activity nuova trova una Libreria piu' corta (nel frattempo sono spariti degli elementi): chi non c'e' piu' non torna selezionato.
    val second = syntheticLibraryState(count = 300)
    val firstIds = idsOf(first.grid, 600)
    val inBoth = firstIds.take(5)
    val onlyInFirst = firstIds.takeLast(5).filter { it !in idsOf(second.grid, 300) }

    recreate(first, second) { state ->
      state.grid.startSelecting()
      state.grid.selection.selectAll(inBoth + onlyInFirst)
    }

    val restored = second.grid.selection.selected.value.ids
    assertThat(restored).containsAtLeastElementsIn(inBoth.filter { it in idsOf(second.grid, 300) })
    for (gone in onlyInFirst) assertThat(restored).doesNotContain(gone)
  }

  @Test
  fun ilSalvataggioNonSiFidaDiUnoStatoRovinato() {
    val grid = syntheticLibraryState(count = 100).grid
    val saver = selectionSaver(grid)
    // Con una lista di tipi sbagliati, o vuota, o un valore qualunque: nessuna selezione, nessuna eccezione.
    for (garbage in listOf<Any>(emptyList<Any>(), listOf("x"), listOf(true, 5, null), "testo", 42)) {
      val keeper = with(saver) { restore(garbage) }
      assertThat(keeper).isNotNull()
    }
  }
}
