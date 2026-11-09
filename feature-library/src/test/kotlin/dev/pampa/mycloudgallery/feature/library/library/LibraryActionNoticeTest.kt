package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
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
    /** GA-8: i media che hanno una copia solo sul NAS (da qui non si condividono ne' si eliminano). */
    private val nasOnly: Set<MediaId> = emptySet(),
  ) : LibraryCommands {
    val shared = mutableListOf<Int>()
    val deleted = mutableListOf<Int>()

    override val canDeleteFromPhone: Boolean get() = true

    override fun onPhone(ids: Set<MediaId>): Set<MediaId> = ids - nasOnly

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

  /** Fase 7 (P9, D23, E-H2): "Nascondi" riesce, ma i familiari vedono ancora [family] elementi (area comune, album condivisi). */
  private class FamilyAware(private val family: Int) : LocalMediaActions {
    val hidden = mutableListOf<Set<MediaId>>()

    override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = Unit

    override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
      if (on) hidden += ids
    }

    override suspend fun familyStillSees(ids: Set<MediaId>): Int = family
  }

  @Test
  fun nascondereCioCheIFamiliariVedonoAncoraDiceChePartecipaSoloDentroLApp() {
    val actions = FamilyAware(family = 2)
    val state = state(actions = actions)
    show(state)
    select(state, 3)

    composeRule.onNodeWithContentDescription("Nascondi").performClick()
    composeRule.waitForIdle()

    assertThat(actions.hidden).hasSize(1)
    // E' un "Nascondi" riuscito: la selezione si chiude. In piu' si dice che i familiari continuano a vedere due di loro.
    assertThat(state.grid.selecting).isFalse()
    composeRule.onNodeWithText("I familiari continuano a vedere 2 di questi elementi").assertExists()
    composeRule.onNodeWithText("Nascosti protegge solo dentro l'app", substring = true).assertExists()
    composeRule.onAllNodesWithText("Non è stato possibile nascondere").assertCountEquals(0)
    composeRule.onNodeWithText("OK").performClick()
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("I familiari continuano a vedere", substring = true).assertCountEquals(0)
  }

  @Test
  fun conUnSoloElementoIlTestoESingolare() {
    val state = state(actions = FamilyAware(family = 1))
    show(state)
    select(state, 1)

    composeRule.onNodeWithContentDescription("Nascondi").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("I familiari continuano a vederlo").assertExists()
  }

  @Test
  fun nascondereCioCheNessunoVedeNonAvvisaDeiFamiliari() {
    val state = state(actions = FamilyAware(family = 0))
    show(state)
    select(state, 2)

    composeRule.onNodeWithContentDescription("Nascondi").performClick()
    composeRule.waitForIdle()

    composeRule.onAllNodesWithText("I familiari continuano a vedere", substring = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("I familiari continuano a vederlo").assertCountEquals(0)
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

  // --- GA-8 ---------------------------------------------------------------------------------------------------------

  /** Gli id dei primi [howMany] elementi della vista, nell'ordine in cui `select` li sceglie. */
  private fun firstIds(state: LibraryTabState, howMany: Int): List<MediaId> {
    val snapshot = state.grid.view.snapshot
    val view = state.grid.view
    return (0 until howMany).map { snapshot.idAt(view.snapshotIndex(it)) }
  }

  @Test
  fun ga8_condividereSoloMediaDelNasLoDiceENonApreIlSelettore() {
    val base = state()
    val commands = Commands(nasOnly = firstIds(base, 3).toSet())
    val state = state(commands = commands)
    show(state)
    select(state, 3)

    composeRule.onNodeWithContentDescription("Condividi").performClick()
    composeRule.waitForIdle()

    // Niente "fatto" finto: il selettore non si apre, la Libreria dice com'e' la situazione e la selezione resta.
    assertThat(commands.shared).isEmpty()
    composeRule.onNodeWithText("Questi elementi sono solo sul NAS").assertExists()
    composeRule.onNodeWithText("Per condividerli aprili e tocca “Scarica sul telefono”, poi condividi la copia scaricata.").assertExists()
    assertThat(state.grid.selecting).isTrue()
  }

  @Test
  fun ga8_condividereUnaSelezioneMistaCondivideIlTelefonoEDiceQuantiNo() {
    val base = state()
    val commands = Commands(nasOnly = setOf(firstIds(base, 3)[0]))
    val state = state(commands = commands)
    show(state)
    select(state, 3)

    composeRule.onNodeWithContentDescription("Condividi").performClick()
    composeRule.waitForIdle()

    assertThat(commands.shared).containsExactly(2)
    composeRule.onNodeWithText("Condiviso senza 1 elemento che è solo sul NAS").assertExists()
  }

  @Test
  fun ga8_eliminareSoloMediaDelNasNonApreIlFoglioEDice() {
    val base = state()
    val commands = Commands(nasOnly = firstIds(base, 3).toSet())
    val state = state(commands = commands)
    show(state)
    select(state, 3)

    composeRule.onNodeWithContentDescription("Elimina dal telefono").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Questi elementi sono solo sul NAS").assertExists()
    composeRule.onNodeWithText("Non c’è niente da eliminare dal telefono. Per toglierli dal NAS aprili uno per uno, o usa Cartelle NAS.").assertExists()
    composeRule.onAllNodesWithText("Eliminare", substring = true).assertCountEquals(0)
    assertThat(commands.deleted).isEmpty()
  }

  @Test
  fun ga8_eliminareUnaSelezioneMistaContaESoloQuelliDelTelefono() {
    val base = state()
    val commands = Commands(nasOnly = setOf(firstIds(base, 3)[0]))
    val state = state(commands = commands)
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
    select(state, 3)

    composeRule.onNodeWithContentDescription("Elimina dal telefono").performClick()
    composeRule.waitForIdle()

    // Il foglio conta i soli media del telefono (2, non 3) e dice che l'altro, solo sul NAS, resta dov'e'.
    composeRule.onAllNodesWithText("Eliminare 2 foto?").onFirst().assertExists()
    composeRule.onAllNodesWithText("1 elemento della selezione è solo sul NAS e resta dov’è.", substring = true).onFirst().assertExists()
    composeRule.onAllNodesWithText("Elimina dal telefono").onFirst().performClick()
    composeRule.waitForIdle()

    assertThat(commands.deleted).containsExactly(2)
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
    composeRule.showThemed { LibraryFooter(state, LibraryPhoneStatus()) }

    composeRule.onNodeWithText("92.019 foto, 7.981 video").assertExists()
  }

  // --- Correzioni F, TS-4: "Trova simili" dalla selezione ---------------------------------------------------------------------------------

  private class SimilarCommands(
    private val outcome: SimilarSelectionOutcome = SimilarSelectionOutcome.STARTED,
    override val findSimilarLimit: Int = 30,
  ) : LibraryCommands {
    val found = mutableListOf<Set<MediaId>>()

    override val canFindSimilar: Boolean get() = true

    override fun findSimilar(ids: Set<MediaId>): SimilarSelectionOutcome {
      found += ids
      return outcome
    }
  }

  @Test
  fun trovaSimiliDallaSelezioneNonCompareSeLaRicercaNonELegata() {
    val state = state()
    show(state)
    select(state, 3)

    composeRule.onAllNodesWithContentDescription("Trova simili").assertCountEquals(0)
  }

  @Test
  fun trovaSimiliDallaSelezioneApreCercaEChiudeLaSelezione() {
    val commands = SimilarCommands()
    val state = state(commands = commands)
    show(state)
    select(state, 3)

    composeRule.onNodeWithContentDescription("Trova simili").performClick()
    composeRule.waitForIdle()

    // La richiesta porta i tre media scelti; la selezione si chiude (Cerca si apre alla radice) e nessun avviso resta.
    assertThat(commands.found).hasSize(1)
    assertThat(commands.found.single()).hasSize(3)
    assertThat(state.grid.selecting).isFalse()
    composeRule.onAllNodesWithText("Foto non ancora analizzate").assertCountEquals(0)
  }

  @Test
  fun trovaSimiliSenzaNessunaFotoAnalizzataLoDiceELaSelezioneResta() {
    val commands = SimilarCommands(outcome = SimilarSelectionOutcome.NOT_ANALYZED)
    val state = state(commands = commands)
    show(state)
    select(state, 2)

    composeRule.onNodeWithContentDescription("Trova simili").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Foto non ancora analizzate").assertExists()
    composeRule.onNodeWithText("Per cercare i simili l’app deve prima analizzare le foto scelte.", substring = true).assertExists()
    assertThat(state.grid.selecting).isTrue()
    assertThat(state.grid.selection.selected.value.size).isEqualTo(2)
  }

  @Test
  fun trovaSimiliConTroppeFotoDiceIlTettoENonParte() {
    val commands = SimilarCommands(findSimilarLimit = 2)
    val state = state(commands = commands)
    show(state)
    select(state, 3)

    composeRule.onNodeWithContentDescription("Trova simili").performClick()
    composeRule.waitForIdle()

    assertThat(commands.found).isEmpty()
    composeRule.onNodeWithText("Per cercare i simili scegline al massimo 2").assertExists()
    assertThat(state.grid.selecting).isTrue()
  }

  @Test
  fun trovaSimiliSenzaNienteDiSelezionatoESpento() {
    val commands = SimilarCommands()
    val state = state(commands = commands)
    show(state)
    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithContentDescription("Trova simili").performClick()
    composeRule.waitForIdle()

    assertThat(commands.found).isEmpty()
  }
}
