package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeContent
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTagPrefix
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryCloudState
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeCloudButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryTopChrome
import dev.pampa.mycloudgallery.core.designsystem.design.galleryTopChromeHeight
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.contract.SectionInfo
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.feature.library.grid.GridText
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.time.LocalDate
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot del pacchetto G1 della beta (B1, B2, B7): la Libreria in fondo senza le tre scritte, a meta' con "torna in fondo" e
 * in cima; la nuvola in ogni stato; il pop-up senza problemi e con un problema; l'ingresso in selezione (prima e dopo); la chrome su
 * una foto molto chiara. Chiaro e scuro, telefono e tablet (1280 dp). La pillola e' disegnata come in `MainScaffold`.
 *
 * **Il telefono e' a 360 x 780 dp**, il Galaxy S25 dell'utente (pacchetto K2, GF-2): a 412 dp entravano piu' cose che sul suo schermo,
 * e la data lunga che a 360 dp finiva con i puntini negli screenshot non si vedeva.
 *
 * Con il pacchetto K2 (le correzioni della review) si aggiungono, in `docs/screenshots/beta-grafica/K2/`: la data in alto con il testo
 * a 1,3x e al livello Mesi (GF-2), la nuvola e il pop-up con il backup in pausa e con il backup fermato da Android (OA-10), il pop-up
 * senza NAS (il filo divisorio, GF-12).
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests "dev.pampa.mycloudgallery.feature.library.library.LibraryCloud*"` scrive i PNG
 * in `docs/screenshots/beta-grafica/G1/` e `K2/`; nei normali `testDebugUnitTest` si verifica solo che le schermate compongano.
 * Robolectric registra un fotogramma a riposo e non sfoca: il movimento (B1) e il vetro vero si giudicano sul telefono.
 */
internal abstract class LibraryCloudScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val look: String get() = if (dark) "scuro" else "chiaro"

  /** Lo spazio del NAS per il pop-up e "Libera spazio" disponibile. */
  private object CloudCommands : LibraryCommands {
    override val canDeleteFromPhone: Boolean get() = true
    override val canFreeSpace: Boolean get() = true
    override suspend fun nasSpace(): LibraryNasSpace = LibraryNasSpace.Ready(usedBytes = 1_250_000_000_000L, totalBytes = 3_600_000_000_000L)
  }

  private val homeNetwork = LibraryNetworkStatus(severity = LibraryNetworkSeverity.Fine, label = "In casa", detail = "NAS collegato")

  private fun library(
    nas: LibraryNasStatus = LibraryNasStatus.UpToDate(atNasMillis = System.currentTimeMillis()),
    backup: LibraryBackupStatus = LibraryBackupStatus.AllSafe(atNasMillis = System.currentTimeMillis()),
    level: GridLevel = GridLevel.DAYS,
    commands: LibraryCommands = CloudCommands,
  ): LibraryTabState = syntheticLibraryState(level = level, nas = nas, backup = backup, commands = commands)

  private fun show(state: LibraryTabState, network: LibraryNetworkStatus? = homeNetwork, fontScale: Float = 1f, onBackup: () -> Unit = {}) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
          Scaffolded(state, network, onBackup)
        }
      }
    }
    settle()
  }

  /** Il tempo che serve alla molla e alla soglia dell'etichetta (600 ms) per posarsi: lo screenshot e' il fotogramma a riposo. */
  private fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SettleMillis)
    composeRule.waitForIdle()
  }

  /**
   * La scena come in produzione (`GalleryRoot`): il host dei pop-up sta **alla radice**, sopra la pagina e sopra la pillola (GF-23 b: prima il host stava dentro la
   * Libreria e la pillola, che e' un fratello, restava nitida sopra la pagina sfocata del pop-up), e il fondale che il vetro dei pop-up campiona e' quello di tutta la
   * scena, che c'e' solo finche' un pop-up e' sullo schermo.
   */
  @Composable
  private fun Scaffolded(state: LibraryTabState, network: LibraryNetworkStatus?, onBackup: () -> Unit) {
    var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
    val modal = rememberFluidGlassModalHostState()
    val rootBackdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalFluidGlassModalHostState provides modal) {
      Box(modifier = Modifier.fillMaxSize()) {
        val rootSource = if (modal.isOnScreen) Modifier.glassBackdropSource(rootBackdrop) else Modifier
        Box(modifier = Modifier.fillMaxSize().then(rootSource).fluidGlassModalObscured()) {
          LibraryTab(
            contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
            backdropOut = { backdrop = it },
            onOpenViewer = {},
            onOpenSettings = {},
            state = state,
            network = network,
            onBackupStatusClick = onBackup,
          )
          if (!state.selectionActive) {
            GalleryTabBar(
              selected = GalleryTab.Library,
              onSelect = {},
              backdrop = backdrop,
              modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = FluidFloatingTabBarDefaults.HorizontalMargin)
                .padding(bottom = FluidFloatingTabBarDefaults.BottomMargin),
            )
          }
        }
        FluidGlassModalHost(state = modal, backdrop = rootBackdrop)
      }
    }
  }

  private fun shootRoot(name: String, dir: String = DIR) = composeRule.onRoot().captureRoboImage("$dir/$name-$look-$device.png")

  private fun texts(text: String): Int = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

  private fun scrollTo(state: LibraryTabState, fraction: Float) {
    composeRule.runOnIdle {
      val grid = state.grid
      grid.userScrolled = true
      grid.listState.requestScrollToItem((grid.layout!!.rowCount * fraction).toInt(), 0)
    }
    settle()
  }

  // --- La Libreria (B1, B2) ----------------------------------------------------------------------------------------------

  /** In fondo, sui piu' recenti: sotto la griglia solo il riepilogo, niente piu' rete, NAS e "tutto al sicuro". */
  @Test
  fun inFondoSenzaLeTreScritte() {
    show(library())
    shootRoot("libreria-in-fondo-senza-scritte")
  }

  @Test
  fun aMetaConTornaInFondo() {
    val state = library()
    show(state)
    scrollTo(state, 0.5f)
    shootRoot("libreria-a-meta-con-torna-in-fondo")
  }

  @Test
  fun inCima() {
    val state = library()
    show(state)
    scrollTo(state, 0f)
    shootRoot("libreria-in-cima")
  }

  @Test
  fun selezionePrimaEDopo() {
    val state = library()
    show(state)
    shootRoot("selezione-prima")
    composeRule.runOnIdle {
      val grid = state.grid
      grid.startSelecting()
      val l = grid.layout!!
      val center = grid.rowAtCenter(l)
      var picked = 0
      for (row in center - 2..center + 2) {
        if (row !in 0 until l.rowCount) continue
        for (p in l.itemsOf(row)) if (picked < 3) grid.idAt(p)?.let { grid.selection.toggle(it); picked++ }
      }
    }
    settle()
    shootRoot("selezione-dopo")
  }

  // --- La nuvola (B2) ----------------------------------------------------------------------------------------------------

  private fun shootCloud(name: String, state: LibraryTabState, network: LibraryNetworkStatus? = homeNetwork) {
    show(state, network)
    composeRule.onNodeWithTag("${GalleryChromeTagPrefix}comandi").captureRoboImage("$DIR/nuvola-$name-$look-$device.png")
  }

  @Test
  fun nuvolaTuttoSalvato() = shootCloud("salvato", library())

  @Test
  fun nuvolaInCorso() = shootCloud("in-corso", library(backup = LibraryBackupStatus.Running(done = 12, total = 40, etaMillis = 120_000L)))

  @Test
  fun nuvolaInAttesa() = shootCloud("in-attesa", library(backup = LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING)))

  @Test
  fun nuvolaSenzaRete() = shootCloud(
    "senza-rete",
    library(nas = LibraryNasStatus.AreasUnavailable(setOf(NasArea.PRIVATE, NasArea.COMMON))),
    LibraryNetworkStatus(severity = LibraryNetworkSeverity.Offline, label = "Offline", detail = "Libreria sul telefono"),
  )

  @Test
  fun nuvolaDaGuardare() = shootCloud("da-guardare", library(nas = LibraryNasStatus.AreasUnavailable(setOf(NasArea.COMMON))))

  @Test
  fun nuvolaServeIntervento() = shootCloud("serve-intervento", library(nas = LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = 0L)))

  // --- Il pop-up (B2) ----------------------------------------------------------------------------------------------------

  private fun openCloud() {
    composeRule.onAllNodesWithContentDescription("Stato della sincronizzazione", substring = true)[0].performClick()
    settle()
  }

  @Test
  fun popUpSenzaProblemi() {
    show(library())
    openCloud()
    shootRoot("pop-up-senza-problemi", I4)
  }

  @Test
  fun popUpSenzaProblemiConUnBackupInCorso() {
    show(library(backup = LibraryBackupStatus.Running(done = 1_240, total = 6_658, etaMillis = 80 * 60_000L)))
    openCloud()
    shootRoot("pop-up-backup-in-corso", I4)
  }

  /** I4: l'anello e il conto a testo grande (1,3x). */
  @Test
  fun popUpConUnBackupInCorsoTestoGrande() {
    show(library(backup = LibraryBackupStatus.Running(done = 1_240, total = 6_658, etaMillis = 80 * 60_000L)), fontScale = LargeText)
    openCloud()
    shootRoot("pop-up-backup-in-corso-testo-grande", I4)
  }

  /** I4: la lettura del NAS con il suo anello, e il backup in coda accanto (senza totale: il segno dell'attesa). */
  @Test
  fun popUpConLaLetturaDelNasEIlBackupInCoda() {
    show(library(nas = LibraryNasStatus.FirstScan(done = 1_200, total = 12_000, etaMillis = 4 * 60_000L), backup = LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING)))
    openCloud()
    shootRoot("pop-up-lettura-nas-e-coda", I4)
  }

  @Test
  fun popUpConUnProblema() {
    show(library(nas = LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = 0L), backup = LibraryBackupStatus.Problem(code = "nas_full", count = 0)))
    openCloud()
    shootRoot("pop-up-con-problema", I4)
  }

  @Test
  fun popUpConLaReteDaSistemare() {
    show(
      library(),
      LibraryNetworkStatus(
        severity = LibraryNetworkSeverity.Attention,
        label = "VPN spenta",
        detail = "Apri WireGuard",
        action = LibraryNetworkAction.WIREGUARD,
      ),
    )
    openCloud()
    shootRoot("pop-up-rete", I4)
  }

  // --- Pacchetto K2: le correzioni della review ------------------------------------------------------------------------------

  /** GF-2: a meta' Libreria con "torna in fondo" e il testo a 1,3x: la data si legge intera, in una forma piu' corta. */
  @Test
  fun k2_aMetaConIlTestoGrande() {
    val state = library()
    show(state, fontScale = LargeText)
    scrollTo(state, 0.5f)
    shootRoot("libreria-a-meta-testo-grande", K2)
  }

  /** GF-2: al livello Mesi, a meta' Libreria. */
  @Test
  fun k2_aMetaAlLivelloMesi() {
    val state = library(level = GridLevel.MONTHS)
    show(state)
    scrollTo(state, 0.5f)
    shootRoot("libreria-a-meta-mesi", K2)
  }

  /**
   * OA-10: con il backup in pausa ("Ferma", fino a "Riprendi") e il NAS aggiornato la nuvola porta il segno della pausa, TalkBack lo
   * dice, e il pop-up dice «Backup in pausa» con il tasto che porta alla pagina Backup. Niente "tutto salvato", "NAS aggiornato" o
   * "Libera spazio".
   */
  @Test
  fun k2_ilBackupInPausaSiDiceEIlTastoPortaAllaPaginaBackup() {
    var opened = 0
    show(library(backup = LibraryBackupStatus.Paused(byUser = true)), onBackup = { opened++ })
    composeRule.onNodeWithContentDescription("Stato della sincronizzazione: In pausa. Backup in pausa").assertExists()
    composeRule.onNodeWithTag("${GalleryChromeTagPrefix}comandi").captureRoboImage("$K2/nuvola-in-pausa-$look-$device.png")

    openCloud()
    assertThat(texts("In pausa")).isAtLeast(1)
    assertThat(texts("Backup in pausa")).isAtLeast(1)
    // I4: la frase sta dietro il tocco della capsula.
    assertThat(texts("Le foto nuove non si salvano finché non lo riprendi dalla pagina Backup.")).isEqualTo(0)
    for (gone in listOf("Tutto al sicuro", "NAS aggiornato", "Libera spazio")) {
      assertWithMessage("«$gone» con il backup in pausa").that(texts(gone)).isEqualTo(0)
    }
    assertThat(composeRule.onAllNodesWithTag(SpaceRowTag).fetchSemanticsNodes()).isEmpty()
    shootRoot("pop-up-backup-in-pausa", I4)
    composeRule.onAllNodesWithText("Backup in pausa", substring = true)[0].performClick()
    settle()
    assertThat(texts("Le foto nuove non si salvano finché non lo riprendi dalla pagina Backup.")).isEqualTo(1)
    shootRoot("pop-up-backup-in-pausa-frase-aperta", I4)

    composeRule.onNodeWithText("Apri Backup").performClick()
    settle()
    assertThat(opened).isEqualTo(1)
  }

  /** OA-10: il lavoro fermato da Android non e' "tutto salvato" nemmeno lui: e' un'attesa, che riprende da sola. */
  @Test
  fun k2_ilBackupFermatoDaAndroidEUnAttesa() {
    show(library(backup = LibraryBackupStatus.Paused(byUser = false)))
    composeRule.onNodeWithContentDescription("Stato della sincronizzazione: In attesa").assertExists()
    openCloud()
    assertThat(texts("Backup interrotto")).isAtLeast(1)
    assertThat(texts("Android ha fermato il lavoro: riprende da solo.")).isEqualTo(1)
    assertThat(texts("Tutto al sicuro")).isEqualTo(0)
    shootRoot("pop-up-backup-interrotto", I4)
  }

  /** GF-12: «VPN spenta» ha sotto il tasto «Apri WireGuard»: la frase non lo ripete. */
  @Test
  fun k2_sottoLaVpnSpentaApriWireGuardCeUnaVoltaSola() {
    show(
      library(),
      LibraryNetworkStatus(
        severity = LibraryNetworkSeverity.Attention,
        label = "VPN spenta",
        detail = "Apri WireGuard",
        action = LibraryNetworkAction.WIREGUARD,
        description = "VPN spenta · Apri WireGuard",
      ),
    )
    openCloud()
    assertThat(texts("VPN spenta")).isAtLeast(1)
    assertThat(composeRule.onAllNodesWithText("Apri WireGuard").fetchSemanticsNodes()).hasSize(1)
  }

  /** GF-12: sotto un problema del backup il tasto e' "Apri Backup", e la frase rimanda li', non alla Diagnostica. */
  @Test
  fun k2_laFraseDiUnProblemaDelBackupDiceLaStessaCosaDelTasto() {
    show(library(backup = LibraryBackupStatus.Problem(code = "nas_unreachable", count = 0)))
    openCloud()
    assertThat(texts("Il NAS non risponde")).isAtLeast(1)
    assertThat(texts("Riprova dalla pagina Backup.")).isEqualTo(0)
    composeRule.onAllNodesWithText("Il NAS non risponde", substring = true)[0].performClick()
    settle()
    assertThat(texts("Riprova dalla pagina Backup.")).isEqualTo(1)
    assertThat(composeRule.onAllNodesWithText("Apri Backup").fetchSemanticsNodes()).hasSize(1)
    assertThat(texts("Diagnostica")).isEqualTo(0)
  }

  /**
   * GF-12: senza lo spazio del NAS, senza un'ultima sincronizzazione e senza "Libera spazio" il filo divisorio non resta da solo in
   * fondo al pop-up; con qualcosa sotto c'e'.
   */
  @Test
  fun k2_ilFiloDivisorioNonRestaDaSolo() {
    val queued = LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING)
    show(library(nas = LibraryNasStatus.Idle, backup = queued, commands = NoNasCommands))
    openCloud()
    assertThat(texts("312 in coda")).isAtLeast(1)
    assertThat(composeRule.onAllNodesWithTag(CloudDividerTag).fetchSemanticsNodes()).isEmpty()
    shootRoot("pop-up-senza-nas", I4)
  }

  @Test
  fun k2_ilFiloDivisorioCeSeSottoCeQualcosa() {
    show(library(backup = LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING)))
    openCloud()
    assertThat(composeRule.onAllNodesWithTag(CloudDividerTag).fetchSemanticsNodes()).hasSize(1)
  }

  /** Senza NAS e senza "Libera spazio": la Libreria del solo telefono con il backup in coda. */
  private object NoNasCommands : LibraryCommands {
    override val canDeleteFromPhone: Boolean get() = true
  }

  // --- La chrome su una foto molto chiara (B7) ---------------------------------------------------------------------------

  /** Sotto la chrome una foto quasi bianca (cielo, neve, un foglio): inchiostro scuro, pellicola minima, il testo si legge. */
  @Test
  fun chromeSuUnaFotoMoltoChiara() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val backdrop = rememberGlassBackdrop()
        val content = remember { LightPhoto }
        Box(modifier = Modifier.fillMaxSize()) {
          Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop).background(LightPhoto.color))
          GalleryTopChrome(
            content = content,
            backdrop = backdrop,
            title = TitleOfTheDay,
            subtitle = "Passo dello Stelvio",
            topChrome = galleryTopChromeHeight(),
            // GF-23 a: le forme piu' corte della data come le passa la produzione (`GridText.sectionShorterTitles`): a 360 dp l'etichetta mostra la prima che entra.
            shorterTitles = ShorterTitlesOfTheDay,
          ) {
            GalleryChromeCloudButton(state = GalleryCloudState.Attention, contentDescription = "Nuvola", onClick = {})
            GalleryChromeIconButton(icon = Icons.Rounded.CheckCircleOutline, contentDescription = "Seleziona", onClick = {})
            GalleryChromeIconButton(icon = Icons.Rounded.MoreHoriz, contentDescription = "Vista", onClick = {})
          }
        }
      }
    }
    settle()
    shootRoot("chrome-su-foto-chiara")
  }

  /** Una foto uniforme quasi bianca come contenuto sotto la chrome (quello che i ThumbHash della griglia direbbero di una neve). */
  private object LightPhoto : GalleryChromeContent {
    val color = Color(0xFFF4F6F8)

    override fun chromeRowsUnder(bounds: Rect): Long = 1L

    override fun chromeContentUnder(bounds: Rect, page: Color, text: Color): ContentRange = ContentRange(listOf(color))

    override val chromeOriginInWindow: Offset get() = Offset.Zero

    override val chromeContentVersion: Any? get() = null
  }

  companion object {
    /** Il giorno della foto chiara: un sabato di un anno passato, con il titolo e le forme corte che la produzione scrive per lui. */
    private val Today: LocalDate = LocalDate.of(2026, 11, 10)
    private val DayOfThePhoto = SectionInfo(level = GridLevel.DAYS, firstDay = 20_241_012, lastDay = 20_241_012, count = 1, topPlaces = IntArray(0), distinctPlaces = 0)
    val TitleOfTheDay: String = GalleryDates.weekdayTitle(GridText.date(DayOfThePhoto.firstDay), withYear = true)
    val ShorterTitlesOfTheDay: List<String> = GridText.sectionShorterTitles(DayOfThePhoto, Today)

    const val DIR = "../docs/screenshots/beta-grafica/G1"
    const val K2 = "../docs/screenshots/beta-grafica/K2"
    const val I4 = "../docs/screenshots/beta-grafica/I4"
    const val SettleMillis = 1_500L
    const val LargeText = 1.3f

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xhdpi")
internal class LibraryCloudPhoneScreenshotTest(dark: Boolean) : LibraryCloudScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-xhdpi")
internal class LibraryCloudTabletScreenshotTest(dark: Boolean) : LibraryCloudScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
