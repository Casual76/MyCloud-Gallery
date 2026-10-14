package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.FakeViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.FakeTimelineRepository
import dev.pampa.mycloudgallery.core.library.testing.InMemoryLibraryPreferences
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.countText
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.grid.showThemed
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import dev.pampa.mycloudgallery.feature.library.photoVideoSummary
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Revisione di sicurezza della Fase 3: R8 (nascondere e preferiti che non riescono lo dicono, e la selezione resta aperta),
 * I8 (condividere o eliminare troppi elementi avvisa invece di fallire in silenzio), R5 (accesso parziale: finche' la prima
 * lettura non risponde la Libreria dice che sta leggendo, non che e' vuota) e i conteggi all'italiana, **con il telefono in
 * inglese** (`en-rUS`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "en-rUS-w412dp-h915dp-xhdpi")
class LibraryActionNoticeTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  /** Un catalogo che non scrive (disco pieno, database in errore). */
  private class FailingActions : LocalMediaActions {
    var calls = 0

    override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) {
      calls++
      throw IllegalStateException("disco pieno")
    }

    override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
      calls++
      throw IllegalStateException("disco pieno")
    }
  }

  private class Commands(
    override val shareLimit: Int = Int.MAX_VALUE,
    override val deleteLimit: Int = Int.MAX_VALUE,
    private val shareStarts: Boolean = true,
    private val deleteStarts: Boolean = true,
  ) : LibraryCommands {
    val shared = mutableListOf<Int>()
    val deleted = mutableListOf<Int>()

    override val canDeleteFromPhone: Boolean get() = true

    override fun share(ids: Set<MediaId>): Boolean {
      shared += ids.size
      return shareStarts
    }

    override fun deleteFromPhone(ids: Set<MediaId>): Boolean {
      deleted += ids.size
      return deleteStarts
    }
  }

  private fun show(state: LibraryTabState) {
    composeRule.showThemed {
      LibraryTab(
        contentPadding = PaddingValues(bottom = 96.dp),
        backdropOut = {},
        onOpenViewer = {},
        onOpenSettings = {},
        statusFooter = { Text("stato") },
        state = state,
      )
    }
  }

  /** Una Libreria di [count] elementi con le azioni e i comandi scelti dal test. */
  private fun state(
    count: Int = 60,
    actions: LocalMediaActions? = null,
    commands: LibraryCommands = Commands(),
  ): LibraryTabState {
    val first = syntheticLibraryState(count = count)
    return LibraryTabState(
      grid = first.grid, viewerItems = first.viewerItems, viewerImages = first.viewerImages, photoCount = 0, videoCount = 0,
      timeline = first.timeline, preferences = first.preferences, phone = first.phone, commands = commands,
      actions = actions ?: first.actions,
    )
  }

  /** Entra in selezione e sceglie i primi [howMany] elementi (con un colpo solo: la prova non e' sul gesto). */
  private fun select(state: LibraryTabState, howMany: Int) {
    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    composeRule.waitForIdle()
    composeRule.runOnIdle {
      val snapshot = state.grid.view.snapshot
      val view = state.grid.view
      state.grid.selection.selectAll((0 until howMany).map { snapshot.idAt(view.snapshotIndex(it)) })
    }
    composeRule.waitForIdle()
  }

  // --- R8 -----------------------------------------------------------------------------------------------------------

  @Test
  fun nascondiCheNonRiesceLoDiceELaSelezioneResta() {
    val failing = FailingActions()
    val state = state(actions = failing)
    show(state)
    select(state, 3)

    composeRule.onNodeWithContentDescription("Nascondi").performClick()
    composeRule.waitForIdle()

    assertThat(failing.calls).isEqualTo(1)
    composeRule.onNodeWithText("Non è stato possibile nascondere").assertExists()
    composeRule.onNodeWithText("Gli elementi sono ancora visibili nella Libreria. Riprova fra poco.").assertExists()
    // La selezione resta aperta: le foto private non sono nascoste e l'utente puo' riprovare.
    assertThat(state.grid.selecting).isTrue()
    assertThat(state.grid.selection.selected.value.size).isEqualTo(3)
    composeRule.onNodeWithText("OK").performClick()
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Non è stato possibile nascondere").assertCountEquals(0)
  }

  @Test
  fun nascondiCheRiesceChiudeLaSelezioneENonDiceNiente() {
    val hidden = mutableListOf<Set<MediaId>>()
    val state = state(
      actions = object : LocalMediaActions {
        override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = Unit

        override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
          if (on) hidden += ids
        }
      },
    )
    show(state)
    select(state, 2)

    composeRule.onNodeWithContentDescription("Nascondi").performClick()
    composeRule.waitForIdle()

    assertThat(hidden).hasSize(1)
    assertThat(state.grid.selecting).isFalse()
    composeRule.onAllNodesWithText("Non è stato possibile nascondere").assertCountEquals(0)
  }

  @Test
  fun unPreferitoNonSalvatoLoDice() {
    val failing = FailingActions()
    val state = state(actions = failing)
    show(state)
    select(state, 1)

    composeRule.onNodeWithContentDescription("Aggiungi ai preferiti").performClick()
    composeRule.waitForIdle()

    assertThat(failing.calls).isEqualTo(1)
    composeRule.onNodeWithText("Preferiti non aggiornati").assertExists()
  }

  // --- I8 -----------------------------------------------------------------------------------------------------------

  @Test
  fun condividereTroppiElementiAvvisaEDiceIlMassimo() {
    val commands = Commands(shareLimit = 1_500)
    val state = state(count = 1_700, commands = commands)
    show(state)
    select(state, 1_501)

    composeRule.onNodeWithContentDescription("Condividi").performClick()
    composeRule.waitForIdle()

    // Il numero all'italiana e il comando mai chiamato: niente transazione troppo grande.
    composeRule.onNodeWithText("Puoi condividere al massimo 1.500 elementi alla volta").assertExists()
    composeRule.onNodeWithText("Togli qualche elemento dalla selezione e riprova.").assertExists()
    assertThat(commands.shared).isEmpty()
    assertThat(state.grid.selecting).isTrue()
  }

  @Test
  fun condividereDentroIlTettoParte() {
    val commands = Commands(shareLimit = 500)
    val state = state(commands = commands)
    show(state)
    select(state, 3)

    composeRule.onNodeWithContentDescription("Condividi").performClick()
    composeRule.waitForIdle()

    assertThat(commands.shared).containsExactly(3)
    composeRule.onAllNodesWithText("Puoi condividere", substring = true).assertCountEquals(0)
  }

  @Test
  fun seAndroidNonApreLaCondivisioneLaLibreriaLoDice() {
    val commands = Commands(shareStarts = false)
    val state = state(commands = commands)
    show(state)
    select(state, 3)

    composeRule.onNodeWithContentDescription("Condividi").performClick()
    composeRule.waitForIdle()

    assertThat(commands.shared).containsExactly(3)
    composeRule.onNodeWithText("Non è stato possibile condividere").assertExists()
  }

  @Test
  fun eliminareTroppiElementiAvvisaSenzaAprireIlFoglio() {
    val commands = Commands(deleteLimit = 2)
    val state = state(commands = commands)
    show(state)
    select(state, 3)

    composeRule.onNodeWithContentDescription("Elimina dal telefono").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Puoi eliminare al massimo 2 elementi alla volta").assertExists()
    composeRule.onAllNodesWithText("Eliminare 3 foto?").assertCountEquals(0)
    assertThat(commands.deleted).isEmpty()
  }

  // --- R5 -----------------------------------------------------------------------------------------------------------

  private fun emptyPartial(selectionPending: Boolean): LibraryTabState {
    val repository = FakeTimelineRepository(EmptyTimelineSnapshot)
    val grid = MediaGridState(
      view = repository.view(LibraryFilter.ALL).value,
      loader = FakeThumbnailLoader(),
      placeholders = FakePlaceholderCache(),
      placeNames = FakePlaces(),
      engine = TimelineLayoutEngine(),
      refOf = { error("vuota") },
    )
    return LibraryTabState(
      grid = grid,
      viewerItems = grid.viewerItems,
      viewerImages = FakeViewerImageSource(),
      photoCount = 0,
      videoCount = 0,
      timeline = repository,
      preferences = InMemoryLibraryPreferences(),
      phone = MutableStateFlow(LibraryPhoneStatus(access = MediaAccess.PARTIAL, selectionPending = selectionPending)),
    )
  }

  @Test
  fun conAccessoParzialeEPrimaLetturaInCorsoLaLibreriaDiceCheStaLeggendo() {
    show(emptyPartial(selectionPending = true))

    composeRule.onNodeWithText("Sto leggendo la Libreria").assertExists()
    composeRule.onAllNodesWithText("La Libreria è vuota").assertCountEquals(0)
    // L'avviso dell'accesso parziale c'e' lo stesso.
    composeRule.onNodeWithText("Vedi solo le foto che hai scelto").assertExists()
  }

  @Test
  fun conAccessoParzialeEUnaSelezioneVuotaLaLibreriaEVuota() {
    show(emptyPartial(selectionPending = false))

    composeRule.onNodeWithText("La Libreria è vuota").assertExists()
    composeRule.onAllNodesWithText("Sto leggendo la Libreria").assertCountEquals(0)
  }

  // --- Conteggi all'italiana, con il telefono in inglese ---------------------------------------------------------

  @Test
  fun iConteggiHannoIlPuntoDelleMigliaiaAncheConIlTelefonoInInglese() {
    composeRule.showThemed {
      Text(photoVideoSummary(92_019, 7_981))
      Text(countText(R.plurals.selection_count, 1_500))
      Text(countText(R.plurals.collection_count, 12_345))
    }

    composeRule.onNodeWithText("92.019 foto, 7.981 video").assertExists()
    composeRule.onNodeWithText("1.500 selezionati").assertExists()
    composeRule.onNodeWithText("12.345 elementi").assertExists()
  }

  @Test
  fun alSingolareEZeroLeFormeSonoGiuste() {
    composeRule.showThemed {
      Text(photoVideoSummary(1, 1))
      Text(photoVideoSummary(0, 2))
      Text(countText(R.plurals.selection_count, 1))
      Text(countText(R.plurals.collection_count, 1))
      Text(countText(R.plurals.library_first_read_progress_unknown, 1))
      Text(countText(R.plurals.library_first_read_progress_unknown, 1_000))
    }

    composeRule.onNodeWithText("1 foto, 1 video").assertExists()
    composeRule.onNodeWithText("0 foto, 2 video").assertExists()
    composeRule.onNodeWithText("1 selezionato").assertExists()
    composeRule.onNodeWithText("1 elemento").assertExists()
    composeRule.onNodeWithText("1 letto").assertExists()
    composeRule.onNodeWithText("1.000 letti").assertExists()
  }

  @Test
  fun ilRiepilogoInFondoAllaLibreriaUsaGliStessiNumeri() {
    val state = state()
    state.photoCount = 92_019
    state.videoCount = 7_981
    composeRule.showThemed { LibraryFooter(state, LibraryPhoneStatus(), status = {}) }

    composeRule.onNodeWithText("92.019 foto, 7.981 video").assertExists()
  }
}
