package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTagPrefix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.FakeViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.FakeTimelineRepository
import dev.pampa.mycloudgallery.core.library.testing.InMemoryLibraryPreferences
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.feature.library.grid.MediaGrid
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.grid.showThemed
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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

/** La scheda Libreria (§2.4): stati, filtro e stile ricordati, selezione e barra delle azioni, livelli, tablet, E-P18. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class LibraryTabTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RecordingCommands : LibraryCommands {
    val calls = mutableListOf<String>()
    override fun requestMediaAccess() {
      calls += "access"
    }

    override fun requestMediaLocation() {
      calls += "location"
    }

    override fun share(ids: Set<MediaId>): Boolean {
      calls += "share:${ids.size}"
      return true
    }

    override val canDeleteFromPhone: Boolean get() = true
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

  /** Una Libreria vuota con un repository vero (finto) e lo stato del telefono scelto dal test. */
  private fun emptyState(
    phone: LibraryPhoneStatus,
    filter: LibraryFilter = LibraryFilter.ALL,
    commands: LibraryCommands = RecordingCommands(),
  ): LibraryTabState {
    val repository = FakeTimelineRepository(EmptyTimelineSnapshot)
    val preferences = InMemoryLibraryPreferences(filter = filter)
    val grid = MediaGridState(
      view = repository.view(filter).value,
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
      preferences = preferences,
      phone = MutableStateFlow(phone),
      commands = commands,
    )
  }

  @Test
  fun senzaPermessoSpiegaEChiedeSoloDaUnTocco() {
    val commands = RecordingCommands()
    show(emptyState(LibraryPhoneStatus(access = MediaAccess.NONE), commands = commands))
    composeRule.onNodeWithText("accesso alle foto", substring = true).assertExists()
    assertThat(commands.calls).isEmpty()
    composeRule.onNodeWithText("Consenti accesso").performClick()
    assertThat(commands.calls).containsExactly("access")
  }

  @Test
  fun accessoParzialeEFotoInAttesaHannoIlLoroAvviso() {
    val state = syntheticLibraryState(count = 400, phone = LibraryPhoneStatus(access = MediaAccess.PARTIAL, pendingLocation = 3))
    val commands = RecordingCommands()
    val withCommands = LibraryTabState(
      grid = state.grid, viewerItems = state.viewerItems, viewerImages = state.viewerImages, photoCount = 0, videoCount = 0,
      timeline = state.timeline, preferences = state.preferences, phone = state.phone, commands = commands,
    )
    show(withCommands)
    composeRule.onNodeWithText("Vedi solo le foto che hai scelto").assertExists()
    composeRule.onNodeWithText("3 foto nuove in attesa").assertExists()
    composeRule.onNodeWithText("Consenti tutto").performClick()
    composeRule.onNodeWithText("Consenti").performClick()
    assertThat(commands.calls).containsExactly("access", "location").inOrder()
  }

  @Test
  fun laPrimaLetturaDiceAChePuntoE() {
    show(emptyState(LibraryPhoneStatus(sync = SyncStatus.Scanning(done = 120, total = 6_700, full = true))))
    composeRule.onNodeWithText("Sto leggendo la Libreria").assertExists()
    composeRule.onNodeWithText("120 di 6.700").assertExists()
  }

  @Test
  fun unFiltroSenzaRisultatiOffreMostraTutto() {
    val state = emptyState(LibraryPhoneStatus(), filter = LibraryFilter.VIDEOS)
    show(state)
    composeRule.onNodeWithText("Nessun video").assertExists()
    composeRule.onNodeWithText("Mostra tutto").performClick()
    composeRule.waitForIdle()
    assertThat(state.preferences.filter.value).isEqualTo(LibraryFilter.ALL)
    composeRule.onNodeWithText("La Libreria è vuota").assertExists()
  }

  @Test
  fun filtroEStileSiScelgonoDalMenuESiRicordano() {
    val state = syntheticLibraryState(count = 400)
    show(state)
    composeRule.onNodeWithContentDescription("Vista").performClick()
    composeRule.onNodeWithText("Video").performClick()
    composeRule.waitForIdle()
    assertThat(state.preferences.filter.value).isEqualTo(LibraryFilter.VIDEOS)
    assertThat(state.grid.view.filter).isEqualTo(LibraryFilter.VIDEOS)
    // Il filtro attivo si dice anche a menu chiuso, nell'etichetta in alto ("Video · Oslo").
    composeRule.waitForIdle()
    assertThat(composeRule.onAllNodesWithText("Video", substring = true).fetchSemanticsNodes()).isNotEmpty()

    composeRule.onNodeWithContentDescription("Vista").performClick()
    composeRule.onNodeWithText("Proporzioni originali").performClick()
    composeRule.waitForIdle()
    assertThat(state.preferences.style.value).isEqualTo(GridStyle.JUSTIFIED)
    assertThat(state.grid.layout!!.spec.style).isEqualTo(GridStyle.JUSTIFIED)
  }

  @Test
  fun lePreferenzeRicordateEntranoNellaGriglia() {
    val first = syntheticLibraryState(count = 400)
    val preferences = InMemoryLibraryPreferences(filter = LibraryFilter.VIDEOS, level = GridLevel.MONTHS, style = GridStyle.JUSTIFIED)
    val state = LibraryTabState(
      grid = first.grid, viewerItems = first.viewerItems, viewerImages = first.viewerImages, photoCount = 0, videoCount = 0,
      timeline = first.timeline, preferences = preferences,
    )
    show(state)
    assertThat(state.grid.view.filter).isEqualTo(LibraryFilter.VIDEOS)
    assertThat(state.grid.level).isEqualTo(GridLevel.MONTHS)
    assertThat(state.grid.layout!!.spec.style).isEqualTo(GridStyle.JUSTIFIED)
  }

  @Test
  fun selezionareMostraLaBarraDelleAzioniEIPreferitiCambianoLaCella() {
    val state = syntheticLibraryState(count = 400)
    show(state)
    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    composeRule.onNodeWithText("Seleziona elementi").assertExists()
    composeRule.onAllNodesWithContentDescription("Foto, ", substring = true)[0].performClick()
    composeRule.onNodeWithText("1 selezionato").assertExists()
    composeRule.onNodeWithContentDescription("Condividi").assertExists()
    composeRule.onNodeWithContentDescription("Nascondi").assertExists()
    composeRule.onNodeWithContentDescription("Elimina dal telefono").assertExists()
    val id = state.grid.selection.selected.value.ids.single()

    val wasFavorite = state.grid.isFavorite(id)
    composeRule.onNodeWithContentDescription(if (wasFavorite) "Togli dai preferiti" else "Aggiungi ai preferiti").performClick()
    composeRule.waitForIdle()
    val snapshot = state.grid.view.snapshot
    assertThat(CellFlags.has(snapshot.flagsAt(snapshot.indexOf(id)), CellFlags.FAVORITE)).isEqualTo(!wasFavorite)
    composeRule.onNodeWithContentDescription(if (wasFavorite) "Aggiungi ai preferiti" else "Togli dai preferiti").assertExists()

    composeRule.onNodeWithText("Fine").performClick()
    assertThat(state.grid.selecting).isFalse()
    assertThat(state.grid.selection.selected.value.isEmpty).isTrue()
  }

  /**
   * GA-2: in selezione "Indietro" chiude la selezione, non l'app. Prima la Libreria non aveva un gestore del tasto e il sistema
   * chiudeva l'activity; le Raccolte e gli Eliminati di recente ce l'avevano gia'. Fuori dalla selezione il tasto resta del sistema.
   */
  @Test
  fun ga2_inSelezioneIndietroEsceDallaSelezioneNonDallApp() {
    val state = syntheticLibraryState(count = 400)
    var activity: ComponentActivity? = null
    var dispatcher: OnBackPressedDispatcher? = null
    composeRule.setContent {
      activity = LocalActivity.current as ComponentActivity
      dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        LibraryTab(
          contentPadding = PaddingValues(bottom = 96.dp),
          backdropOut = {},
          onOpenViewer = {},
          onOpenSettings = {},
          statusFooter = {},
          state = state,
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    composeRule.onAllNodesWithContentDescription("Foto, ", substring = true)[0].performClick()
    assertThat(state.grid.selecting).isTrue()

    composeRule.runOnUiThread { dispatcher!!.onBackPressed() }
    composeRule.waitForIdle()

    assertThat(state.grid.selecting).isFalse()
    assertThat(state.grid.selection.selected.value.isEmpty).isTrue()
    assertWithMessage("l'app non si chiude").that(activity!!.isFinishing).isFalse()

    // Fuori dalla selezione il tasto e' del sistema, come sempre.
    composeRule.runOnUiThread { dispatcher!!.onBackPressed() }
    composeRule.waitForIdle()
    assertThat(activity!!.isFinishing).isTrue()
  }

  /**
   * P11 (nota di P5): nascondere la copertina di un gruppo nasconde tutto il gruppo, e il foglio "Nascondi" lo dice prima
   * (quanti elementi spariscono davvero). Un elemento da solo si nasconde subito, senza foglio.
   */
  @Test
  fun nascondereLaCopertinaDiUnGruppoLoDicePrima() {
    val first = syntheticLibraryState(count = 400)
    val hidden = mutableListOf<Set<MediaId>>()
    val state = LibraryTabState(
      grid = first.grid, viewerItems = first.viewerItems, viewerImages = first.viewerImages, photoCount = 0, videoCount = 0,
      timeline = first.timeline, preferences = first.preferences, phone = first.phone, commands = first.commands,
      actions = object : LocalMediaActions {
        override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = Unit

        override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
          if (on) hidden += ids
        }
      },
    )
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
    val snapshot = state.grid.view.snapshot
    val cover = (0 until snapshot.size).first { snapshot.groupSizeAt(it) > 1 }
    val coverId = snapshot.idAt(cover)
    val groupSize = snapshot.groupSizeAt(cover)
    val single = snapshot.idAt((0 until snapshot.size).first { snapshot.groupSizeAt(it) <= 1 })

    // Da solo: nessun foglio, si nasconde subito.
    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    composeRule.runOnIdle { state.grid.selection.toggle(single) }
    composeRule.onNodeWithContentDescription("Nascondi").performClick()
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Nascondere", substring = true).assertCountEquals(0)
    assertThat(hidden).containsExactly(setOf(single))

    // La copertina: prima il foglio, con il numero vero.
    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    composeRule.runOnIdle { state.grid.selection.toggle(coverId) }
    composeRule.onNodeWithContentDescription("Nascondi").performClick()
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Nascondere $groupSize elementi?").onFirst().assertExists()
    composeRule.onAllNodesWithText("tutto il gruppo", substring = true).onFirst().assertExists()
    assertThat(hidden).hasSize(1)
    composeRule.onAllNodesWithText("Nascondi").onFirst().performClick()
    composeRule.waitForIdle()
    assertThat(hidden).containsExactly(setOf(single), setOf(coverId)).inOrder()
    assertThat(state.grid.selecting).isFalse()
  }

  /** I livelli stanno nel menu della vista (scelta 4 dell'utente, G2): niente segmenti sopra la pillola. */
  @Test
  fun iLivelliSiScelgonoDalMenuESiRicordano() {
    val state = syntheticLibraryState(count = 600)
    show(state)
    composeRule.onAllNodesWithText("Anni").assertCountEquals(0)
    composeRule.onNodeWithContentDescription("Vista").performClick()
    composeRule.onNodeWithText("Anni").performClick()
    composeRule.waitForIdle()
    assertThat(state.grid.level).isEqualTo(GridLevel.YEARS)
    assertThat(state.grid.layout!!.spec.columns).isEqualTo(7)
    assertThat(state.preferences.level.value).isEqualTo(GridLevel.YEARS)
  }

  @Test
  fun oggiCompareLontanoDaiPiuRecentiERiportaInFondo() {
    val state = syntheticLibraryState(count = 2_000)
    show(state)
    composeRule.onNodeWithContentDescription("Torna ai più recenti").assertDoesNotExist()
    composeRule.runOnIdle {
      state.grid.listState.requestScrollToItem(state.grid.layout!!.rowCount / 3, 0)
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Torna ai più recenti").performClick()
    composeRule.waitForIdle()
    assertThat(state.grid.listState.canScrollForward).isFalse()
  }

  @Test
  @Config(qualifiers = "w1280dp-h800dp-xhdpi")
  fun sulTabletLeColonneSonoPerLaLarghezza() {
    val state = syntheticLibraryState(count = 600)
    show(state)
    assertThat(state.grid.layout!!.spec.columns).isEqualTo(10)
    composeRule.onNodeWithContentDescription("Vista").performClick()
    composeRule.onNodeWithText("Anni").performClick()
    composeRule.waitForIdle()
    assertThat(state.grid.layout!!.spec.columns).isEqualTo(16)
  }

  /** E-P18: una finestra che cambia larghezza (DeX, split screen, rotazione) resta sullo stesso elemento. */
  @Test
  fun ridimensionareResteAncoratoAlloStessoElemento() {
    val state = syntheticLibraryState(count = 2_000)
    var width by mutableStateOf(412.dp)
    composeRule.showThemed {
      Box(Modifier.width(width).fillMaxHeight()) {
        MediaGrid(state = state.grid, contentPadding = PaddingValues(top = 100.dp, bottom = 120.dp))
      }
    }
    composeRule.runOnIdle { state.grid.listState.requestScrollToItem(state.grid.layout!!.rowCount / 2, 0) }
    composeRule.waitForIdle()
    // Un trascinamento simulato: da qui la griglia non torna piu' in fondo da sola.
    composeRule.runOnIdle { state.grid.savePosition() }
    val before = composeRule.runOnIdle { forceUserScrolled(state.grid); state.grid.savePosition()!! }
    width = 300.dp
    composeRule.waitForIdle()
    val after = composeRule.runOnIdle { state.grid.savePosition()!! }
    assertThat(after.atNewest).isFalse()
    assertThat(after.mediaId).isEqualTo(before.mediaId)
  }

  /** E-P18: la posizione salvata (elemento + scostamento) rimette una griglia nuova sullo stesso elemento. */
  @Test
  fun laPosizioneSalvataSiRimetteDopoUnaRicreazione() {
    val first = syntheticLibraryState(count = 2_000)
    current = first
    composeRule.showThemed {
      LibraryTab(
        contentPadding = PaddingValues(bottom = 96.dp),
        backdropOut = {},
        onOpenViewer = {},
        onOpenSettings = {},
        statusFooter = {},
        state = current!!,
      )
    }
    composeRule.runOnIdle { first.grid.listState.requestScrollToItem(first.grid.layout!!.rowCount / 2, 0) }
    composeRule.waitForIdle()
    val saved = composeRule.runOnIdle { forceUserScrolled(first.grid); first.grid.savePosition()!! }
    assertThat(saved.atNewest).isFalse()

    // Una griglia nuova (come dopo la ricreazione dell'activity) riceve la posizione salvata e torna sullo stesso elemento.
    val second = syntheticLibraryState(count = 2_000)
    second.grid.restorePosition(saved)
    current = second
    composeRule.waitForIdle()
    val restored = composeRule.runOnIdle { second.grid.savePosition()!! }
    assertThat(restored.mediaId).isEqualTo(saved.mediaId)
  }

  /**
   * L'etichetta in alto e' l'intestazione agganciata (G2): scorrendo a piccoli passi attraverso piu' cambi di sezione dice
   * sempre una sezione della Libreria, sta tutta dentro la riga di chrome **misurata** (anche con il testo a 1,3x, dove la
   * riga cresce) e la griglia comincia sotto. In selezione dice quanti sono gli scelti.
   */
  @Test
  fun lEtichettaInAltoDiceLaSezioneEStaNellaRigaMisurata() {
    val state = syntheticLibraryState(count = 2_000)
    var fontScale by mutableStateOf(1f)
    composeRule.showThemed {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        LibraryTab(
          contentPadding = PaddingValues(bottom = 96.dp),
          backdropOut = {},
          onOpenViewer = {},
          onOpenSettings = {},
          statusFooter = {},
          state = state,
        )
      }
    }

    val normal = scanLabel(state, "testo normale")

    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    composeRule.runOnIdle { (0 until 5).forEach { position -> state.grid.selection.toggle(state.grid.idAt(position)!!) } }
    composeRule.onNodeWithText("5 selezionati").assertExists()
    composeRule.onNodeWithText("Fine").performClick()

    fontScale = LargeText
    composeRule.waitForIdle()
    val large = scanLabel(state, "testo grande")
    // Con il testo grande l'etichetta e' piu' alta, e la riga misurata la segue.
    assertThat(large).isGreaterThan(normal)
  }

  /** Scorre verso i meno recenti a passi piccoli; a ogni passo l'etichetta e' dentro la riga; restituisce il suo fondo. */
  private fun scanLabel(state: LibraryTabState, case: String): Float {
    val titles = mutableSetOf<String>()
    var bottom = 0f
    repeat(ScanSteps) { step ->
      composeRule.runOnIdle { state.grid.listState.dispatchRawDelta(-ScanStepPx) }
      composeRule.waitForIdle()
      val label = composeRule.onAllNodesWithTag("${GalleryChromeTagPrefix}titolo").fetchSemanticsNodes().single()
      bottom = label.boundsInRoot.bottom
      val line = state.grid.viewport.padTop.toFloat()
      assertWithMessage("$case, passo $step: l'etichetta sta sopra la linea della griglia").that(bottom).isAtMost(line + Tolerance)
      // B1 della beta: fra l'etichetta e il suo testo ci sono i contenitori dell'animazione (la molla e la dissolvenza): si cerca nei
      // discendenti, non solo nei figli.
      label.texts().firstOrNull()?.let { titles += it }
    }
    // Piu' titoli = almeno un cambio di sezione attraversato.
    assertWithMessage("$case: sezioni dette dall'etichetta $titles").that(titles.size).isAtLeast(2)
    return bottom
  }

  /** I testi di un nodo e dei suoi discendenti, in ordine. */
  private fun SemanticsNode.texts(): List<String> =
    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.texts() }

  private var current: LibraryTabState? by mutableStateOf(null)

  private fun forceUserScrolled(grid: MediaGridState) {
    grid.userScrolled = true
  }

  @Test
  fun laSchedaVuotaDicePercheEVuotaEMostraLoStato() {
    show(emptyLibraryTabState())
    composeRule.onNodeWithText("La Libreria è vuota").assertExists()
    // B2 della beta: lo stato non sta piu' sotto lo stato vuoto (lo dice la nuvola in alto); il vecchio slot non si mostra.
    assertThat(composeRule.onAllNodesWithText("stato").fetchSemanticsNodes()).isEmpty()
    assertThat(SyntheticLibrary.DEFAULT_COUNT).isGreaterThan(0)
  }

  private companion object {
    const val ScanSteps = 240
    const val ScanStepPx = 8f
    const val Tolerance = 1f
    const val LargeText = 1.3f
  }
}
