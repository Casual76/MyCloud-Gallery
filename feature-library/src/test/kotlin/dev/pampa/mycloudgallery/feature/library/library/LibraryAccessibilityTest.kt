package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.a11y.GalleryAccessibilityPrefs
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStatus
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStatusIndicator
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yRule
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Rule
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, P8 (D23, D25-D27, §2.7 "Libreria", D28): la Libreria letta come la legge TalkBack. Gli stati del catalogo (livelli, selezione,
 * permesso, prima lettura, vuota, filtro senza risultati), a testo 1,0 e 2,0, e con "piu' contrasto" e "meno trasparenza": ogni elemento
 * attivo ha un nome e un ruolo, i bersagli sono da 48 dp misurati, nessuna descrizione contiene un id o un nome di file, il testo non e'
 * tagliato. In piu' la griglia e' una raccolta, le intestazioni dei giorni sono intestazioni, la cella ha le sue azioni e il pizzico le sue.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class LibraryAccessibilityTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  // Una prova monta il contenuto **una volta** (`setContent` non si puo' richiamare): stato, scala del testo, richieste del sistema e tema sono
  // stati osservabili, e `show` li cambia e aspetta che si assesti.
  private val current = mutableStateOf<LibraryTabState?>(null)
  private val scale = mutableStateOf<Float?>(null)
  private val prefsState = mutableStateOf(GalleryAccessibilityPrefs.Standard)
  private val darkState = mutableStateOf(false)
  private var started = false

  private fun show(state: LibraryTabState, fontScale: Float? = null, prefs: GalleryAccessibilityPrefs = GalleryAccessibilityPrefs.Standard, dark: Boolean = false) {
    current.value = state
    scale.value = fontScale
    prefsState.value = prefs
    darkState.value = dark
    if (!started) {
      started = true
      composeRule.setContent { Stage() }
    }
    composeRule.waitForIdle()
  }

  @Composable
  private fun Stage() {
    val state = current.value ?: return
    MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (darkState.value) ThemeMode.DARK else ThemeMode.LIGHT), accessibility = prefsState.value) {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value ?: density.fontScale)) {
        key(state) {
          Box(modifier = Modifier.fillMaxSize()) {
            LibraryTab(
              contentPadding = PaddingValues(),
              backdropOut = {},
              onOpenViewer = {},
              onOpenSettings = {},
              statusFooter = { GalleryStatusIndicator(status = GalleryStatus.AllSafe, onClick = {}) },
              state = state,
            )
          }
        }
      }
    }
  }

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  /**
   * [overview]: i livelli **Anni** e **Mesi** sono una panoramica della timeline (centinaia di miniature in poche righe): le celle sono piu' piccole
   * di 48 dp **per scelta**. Li' la regola TARGET si esenta per le sole celle (che hanno la pressione lunga), con questo motivo scritto: la foto si apre
   * dal livello Giorni o Tutte, e TalkBack ha "Livello successivo" e "Livello precedente" al posto del pizzico. Lo dice anche `docs/decisioni.md`.
   */
  private fun config(overview: Boolean = false) = A11yConfig(
    density = composeRule.density,
    exempt = { rule, node -> overview && rule == A11yRule.TARGET && SemanticsActions.OnLongClick in node.config },
  )

  private fun cells(): List<SemanticsNode> = A11yAssertions.nodes(root()).filter {
    SemanticsActions.OnClick in it.config && A11yAssertions.name(it).let { n -> n.startsWith("Foto") || n.startsWith("Video") }
  }

  // --- Gli stati del catalogo: nessuna violazione ---------------------------------------------------------------------------------

  @Test
  fun giorniPulita() {
    show(syntheticLibraryState())
    A11yAssertions.assertClean(root(), config())
  }

  @Test
  fun anniMesiTuttePulite() {
    for (level in listOf(GridLevel.YEARS, GridLevel.MONTHS, GridLevel.ALL)) {
      show(syntheticLibraryState(level = level))
      A11yAssertions.assertClean(root(), config(overview = level != GridLevel.ALL))
    }
  }

  @Test
  fun selezionePulita() {
    val state = syntheticLibraryState()
    show(state)
    composeRule.runOnIdle {
      val grid = state.grid
      grid.startSelecting()
      var n = 0
      var p = 0
      while (n < 3 && p < 200) grid.idAt(p++)?.let { grid.selection.toggle(it); n++ }
    }
    composeRule.waitForIdle()
    A11yAssertions.assertClean(root(), config())
    // "3 selezionati": annunciato una volta, educato.
    val announced = A11yAssertions.nodes(root()).filter { A11yAssertions.liveRegion(it) != null && A11yAssertions.name(it).contains("3") }
    assertThat(announced).isNotEmpty()
  }

  @Test
  fun statiSenzaGrigliaPuliti() {
    val states = listOf(
      syntheticLibraryState(phone = LibraryPhoneStatus(access = MediaAccess.NONE)),
      syntheticLibraryState(phone = LibraryPhoneStatus(access = MediaAccess.PARTIAL, pendingLocation = 12)),
      syntheticLibraryState(count = 0, phone = LibraryPhoneStatus(sync = SyncStatus.Scanning(done = 1_240, total = 6_700, full = true))),
      syntheticLibraryState(count = 0),
      syntheticLibraryState(count = 0, filter = LibraryFilter.FAVORITES),
    )
    for (state in states) {
      show(state)
      A11yAssertions.assertClean(root(), config())
    }
  }

  @Test
  fun testoDoppioSenzaTagli() {
    for (level in listOf(GridLevel.DAYS, GridLevel.MONTHS)) {
      show(syntheticLibraryState(level = level), fontScale = TEXT_2X)
      val found = A11yAssertions.check(root(), config(overview = level == GridLevel.MONTHS))
      assertWithMessage("livello $level a testo 2,0: " + found.joinToString(" | ")).that(found).isEmpty()
    }
    show(syntheticLibraryState(count = 0), fontScale = TEXT_2X)
    A11yAssertions.assertClean(root(), config())
  }

  @Test
  fun contrastoAltoEMenoTrasparenzaPuliti() {
    val prefs = listOf(GalleryAccessibilityPrefs(highContrast = true), GalleryAccessibilityPrefs(reduceTransparency = true))
    for (p in prefs) for (dark in listOf(false, true)) {
      show(syntheticLibraryState(), prefs = p, dark = dark)
      A11yAssertions.assertClean(root(), config())
    }
  }

  // --- La semantica attesa del §2.7 -----------------------------------------------------------------------------------------------

  @Test
  fun leIntestazioniDeiGiorniSonoIntestazioni() {
    show(syntheticLibraryState())
    val headings = A11yAssertions.headingNames(root())
    assertThat(headings).isNotEmpty()
    assertThat(headings.all { it.isNotBlank() }).isTrue()
  }

  @Test
  fun laGrigliaERaccoltaEOgniCellaDiceIlSuoPosto() {
    val state = syntheticLibraryState()
    show(state)
    val collections = A11yAssertions.collections(root())
    // La raccolta dei media: tanti elementi quanti quelli della vista (non le righe della lista).
    assertThat(collections.map { it.second.first }).contains(state.grid.view.size)
    val cell = cells().first()
    val item = cell.config.getOrNull(SemanticsProperties.CollectionItemInfo)
    assertThat(item).isNotNull()
    assertThat(item!!.rowIndex).isAtLeast(0)
  }

  @Test
  fun laCellaHaLeAzioniAlPostoDeiGesti() {
    show(syntheticLibraryState())
    val labels = A11yAssertions.customActionLabels(cells().first())
    assertThat(labels).containsAtLeast("Aggiungi ai preferiti", "Condividi", "Nascondi")
    // Apri (tocco) e Seleziona (pressione lunga) sono le azioni standard della cella.
    val click = cells().first().config.getOrNull(SemanticsActions.OnClick)
    assertThat(click?.label).isEqualTo("Apri")
    assertThat(cells().first().config.getOrNull(SemanticsActions.OnLongClick)?.label).isEqualTo("Seleziona")
  }

  @Test
  fun ilPizzicoHaLeSueAzioni() {
    show(syntheticLibraryState(level = GridLevel.DAYS))
    val list = A11yAssertions.collections(root()).first { it.second.first > 1 }.first
    assertThat(A11yAssertions.customActionLabels(list)).containsExactly("Livello successivo", "Livello precedente")
  }

  @Test
  fun leBarreDiVetroSiLeggonoPrimaDelContenuto() {
    show(syntheticLibraryState())
    val cellIds = cells().map { it.id }.toSet()
    val order = A11yAssertions.readingOrder(root())
    val firstCell = order.indexOfFirst { it.id in cellIds }
    val firstControl = order.indexOfFirst { A11yAssertions.isActionable(it) && it.id !in cellIds }
    assertThat(firstCell).isAtLeast(0)
    assertThat(firstControl).isAtLeast(0)
    assertWithMessage("il primo comando (%s) deve venire prima della prima cella (%s)", firstControl, firstCell).that(firstControl).isLessThan(firstCell)
  }

  @Test
  fun neiNomiDelleCelleNonCeNienteDiTecnico() {
    show(syntheticLibraryState())
    val names = cells().map(A11yAssertions::name)
    assertThat(names).isNotEmpty()
    names.forEach { assertThat(A11yAssertions.leakIn(it)).isNull() }
  }

  // --- Gli screenshot di P8 (docs/screenshots/10-P8): con `recordRoborazziDebug`; nei test normali verificano solo che compongano ----------

  private fun shot(name: String) {
    composeRule.onRoot().captureRoboImage("../docs/screenshots/10-P8/$name.png")
  }

  @Test
  fun screenshotTesto2xContrastoAltoEMenoTrasparenza() {
    show(syntheticLibraryState(), fontScale = TEXT_2X)
    shot("libreria-testo-2x-chiaro-telefono")
    show(syntheticLibraryState(), fontScale = TEXT_2X, dark = true)
    shot("libreria-testo-2x-scuro-telefono")
    show(syntheticLibraryState(count = 0), fontScale = TEXT_2X)
    shot("libreria-vuota-testo-2x-chiaro-telefono")
    show(syntheticLibraryState(), prefs = GalleryAccessibilityPrefs(highContrast = true))
    shot("libreria-contrasto-alto-chiaro-telefono")
    show(syntheticLibraryState(), prefs = GalleryAccessibilityPrefs(highContrast = true), dark = true)
    shot("libreria-contrasto-alto-scuro-telefono")
    show(syntheticLibraryState(), prefs = GalleryAccessibilityPrefs(reduceTransparency = true))
    shot("libreria-meno-trasparenza-chiaro-telefono")
    show(syntheticLibraryState())
    shot("libreria-standard-chiaro-telefono")
  }

  private companion object {
    const val TEXT_2X = 2.0f
  }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atfGiorni() {
    show(syntheticLibraryState())
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfPermessoNegato() {
    show(syntheticLibraryState(phone = LibraryPhoneStatus(access = MediaAccess.NONE)))
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfContrastoAlto() {
    show(syntheticLibraryState(), prefs = GalleryAccessibilityPrefs(highContrast = true))
    composeRule.onRoot().checkRoboAccessibility()
  }
}

