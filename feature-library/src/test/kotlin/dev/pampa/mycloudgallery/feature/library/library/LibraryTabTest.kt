package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridStickyHeaderTag
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
    // Il filtro attivo si dice anche a menu chiuso, nella barra.
    composeRule.onNodeWithText("Video").assertExists()

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
    composeRule.onNodeWithText("1 elemento selezionato").assertExists()
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

    composeRule.onNodeWithContentDescription("Chiudi").performClick()
    assertThat(state.grid.selecting).isFalse()
    assertThat(state.grid.selection.selected.value.isEmpty).isTrue()
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

  @Test
  fun iSegmentiCambianoLivelloELoRicordano() {
    val state = syntheticLibraryState(count = 600)
    show(state)
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
   * L'intestazione agganciata sta sempre sotto la barra superiore **misurata**, qualunque sia la sua altezza: barra
   * normale, di selezione, e con il testo grande (piu' alta: titolo e sottotitolo a 1,3x). Si scorre a piccoli passi
   * attraverso piu' cambi di sezione (dove l'intestazione successiva spinge quella agganciata): a ogni passo
   * l'intestazione e il suo testo cominciano sotto l'ultimo controllo della barra e non sono tagliati.
   */
  @Test
  fun lIntestazioneAgganciataStaSempreSottoLaBarraMisurata() {
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

    val normal = scanStuckHeaderUnderBar(state, "barra normale")

    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    // Cinque elementi qualsiasi (dal modello, non toccando celle che possono stare sotto la barra o sotto i segmenti).
    composeRule.runOnIdle { (0 until 5).forEach { position -> state.grid.selection.toggle(state.grid.idAt(position)!!) } }
    composeRule.onNodeWithText("5 elementi selezionati").assertExists()
    scanStuckHeaderUnderBar(state, "barra di selezione")

    composeRule.onNodeWithContentDescription("Chiudi").performClick()
    composeRule.onNodeWithContentDescription("Vista").performClick()
    composeRule.onNodeWithText("Video").performClick()
    fontScale = LargeText
    composeRule.waitForIdle()
    val large = scanStuckHeaderUnderBar(state, "testo grande")
    // La barra col testo grande e' davvero piu' alta: la linea di aggancio la segue perche' e' misurata.
    assertThat(large).isGreaterThan(normal)
  }

  /** Scorre verso i meno recenti a passi piccoli e verifica a ogni passo; restituisce il fondo dei controlli della barra. */
  private fun scanStuckHeaderUnderBar(state: LibraryTabState, case: String): Float {
    var shown = 0
    var barBottom = 0f
    val titles = mutableSetOf<String>()
    repeat(ScanSteps) { step ->
      composeRule.runOnIdle { state.grid.listState.dispatchRawDelta(-ScanStepPx) }
      composeRule.waitForIdle()
      // La barra misurata: il fondo del suo ultimo controllo (titolo, sottotitolo, pulsanti), non un'altezza attesa.
      barBottom = composeRule.onAllNodes(hasAnyAncestor(hasTestTag(LibraryTopBarTag)), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .maxOf { it.boundsInRoot.bottom }
      val header = composeRule.onAllNodesWithTag(MediaGridStickyHeaderTag).fetchSemanticsNodes()
      if (header.isEmpty()) return@repeat
      shown++
      val parts = composeRule.onAllNodes(hasAnyAncestor(hasTestTag(MediaGridStickyHeaderTag)), useUnmergedTree = true)
        .fetchSemanticsNodes()
      for (node in header + parts) {
        val bounds = node.boundsInRoot
        assertWithMessage("$case, passo $step: l'intestazione agganciata comincia sotto la barra")
          .that(bounds.top).isAtLeast(barBottom - Tolerance)
        assertWithMessage("$case, passo $step: l'intestazione agganciata non e' tagliata")
          .that(bounds.height).isWithin(Tolerance).of(node.size.height.toFloat())
      }
      parts.forEach { node -> node.config.getOrNull(SemanticsProperties.Text)?.forEach { titles += it.text } }
    }
    assertWithMessage("$case: l'intestazione agganciata si e' vista").that(shown).isAtLeast(ScanSteps / 2)
    // Piu' titoli = almeno un cambio di sezione attraversato: la spinta dell'intestazione successiva e' nel giro.
    assertWithMessage("$case: cambi di sezione attraversati").that(titles.size).isAtLeast(2)
    return barBottom
  }

  private var current: LibraryTabState? by mutableStateOf(null)

  private fun forceUserScrolled(grid: MediaGridState) {
    grid.userScrolled = true
  }

  @Test
  fun laSchedaVuotaDicePercheEVuotaEMostraLoStato() {
    show(emptyLibraryTabState())
    composeRule.onNodeWithText("La Libreria è vuota").assertExists()
    composeRule.onNodeWithText("stato").assertExists()
    assertThat(SyntheticLibrary.DEFAULT_COUNT).isGreaterThan(0)
  }

  private companion object {
    const val ScanSteps = 240
    const val ScanStepPx = 8f
    const val Tolerance = 1f
    const val LargeText = 1.3f
  }
}
