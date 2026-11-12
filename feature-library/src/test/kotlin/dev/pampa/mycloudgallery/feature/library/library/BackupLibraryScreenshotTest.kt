package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.NasMix
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di P5 della Libreria (Fase 5A, `docs/fase5-progetto.md` §1.5, §2.12, D15, D16; G2): il **piede con la terza voce**
 * (backup, sotto rete e NAS) nei suoi casi (dalla beta, B2: il pannello del pop-up della nuvola, che ne ha preso il posto), e le **celle con il segno del problema** (disco intero, vince su "al sicuro") **e quello
 * delle copie** (il numero). Chiaro e scuro, telefono (360 dp) e tablet (1280 dp), piu' il testo grande (1,3x).
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests "*BackupLibraryScreenshot*"` scrive i PNG in `docs/screenshots/5-P5/`; nei
 * normali `testDebugUnitTest` si verifica solo che le schermate compongano.
 */
internal abstract class BackupLibraryScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun capture(name: String, fontScale: Float?) {
    composeRule.waitForIdle()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  // --- Il piede da solo: le tre voci una sotto l'altra ----------------------------------------------------------------------------

  private fun shootFooter(name: String, backup: LibraryBackupStatus, nas: LibraryNasStatus = NasUpToDate, fontScale: Float? = null) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: 1f)) {
          Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
              // Dalla beta (B2) le voci del piede stanno nel pop-up della nuvola: qui il suo pannello, con le stesse frasi.
              val cloud = libraryCloud(null, nas, backup)
              if (cloud != null) {
                LibraryCloudPanel(cloud = cloud, lastSyncNasMillis = null, commands = NoLibraryCommands, actions = LibraryCloudActions({}, {}, {}), close = {})
              }
            }
          }
        }
      }
    }
    // Nomi nuovi: i PNG del piede di prima (G2, Fase 5A) restano com'erano nella cartella.
    capture("pannello-nuvola-$name", fontScale)
  }

  @Test
  fun piedeTuttoAlSicuro() = shootFooter("piede-tutto-al-sicuro", LibraryBackupStatus.AllSafe(atNasMillis = null))

  @Test
  fun piedeInCoda() = shootFooter("piede-in-coda", LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING))

  @Test
  fun piedePrimoBackup() = shootFooter("piede-primo-backup", LibraryBackupStatus.FirstBackup(done = 1_240, total = 6_658, etaMillis = 80 * 60_000L))

  @Test
  fun piedePrimoBackupTestoGrande() =
    shootFooter("piede-primo-backup", LibraryBackupStatus.FirstBackup(done = 1_240, total = 6_658, etaMillis = 80 * 60_000L), fontScale = LARGE_TEXT)

  @Test
  fun piedeRicerca() = shootFooter("piede-ricerca", LibraryBackupStatus.Comparing(seen = 1_234, total = 5_559))

  /** Fase I (BF-6): il primo backup pronto, da avviare (il segno «tocca a te»). */
  @Test
  fun piedePrimoBackupDaAvviare() = shootFooter("piede-primo-backup-da-avviare", LibraryBackupStatus.Queued(items = 1_546, wait = LibraryBackupWait.FIRST_BACKUP))

  /** Fase I (punto 5): la ricerca sul NAS ferma per il caldo dice il motivo vero, con la clessidra. */
  @Test
  fun piedeRicercaInPausa() = shootFooter("piede-ricerca-in-pausa", LibraryBackupStatus.Comparing(wait = LibraryBackupWait.THERMAL))

  @Test
  fun piedeProblemaQuota() = shootFooter("piede-problema-quota", LibraryBackupStatus.Problem(code = "quota", count = 0))

  @Test
  fun piedeProblemaQuotaTestoGrande() = shootFooter("piede-problema-quota", LibraryBackupStatus.Problem(code = "quota", count = 0), fontScale = LARGE_TEXT)

  @Test
  fun piedeProblemaDiUnMedia() = shootFooter("piede-problema-copie-non-integre", LibraryBackupStatus.Problem(code = "verify_failed", count = 12))

  @Test
  fun piedeInProva() = shootFooter("piede-in-prova", LibraryBackupStatus.TestOnly)

  @Test
  fun piedeConIlNasFermoEIlBackupInProblema() = shootFooter(
    "piede-nas-fermo-e-backup-fermo",
    LibraryBackupStatus.Problem(code = "frozen", count = 0),
    nas = LibraryNasStatus.Frozen(dev.pampa.mycloudgallery.core.model.FreezeReason.CHANGED, sinceNasMillis = 0L),
  )

  // --- La Libreria: le celle con i segni nuovi e il piede in fondo --------------------------------------------------------------

  /** Una Libreria mista con qualche cella con il problema del backup e qualche pila di copie simili, e il piede in fondo. */
  private fun shootLibrary(name: String, fontScale: Float? = null, scrollFraction: Float? = null) {
    val state = syntheticLibraryState(
      count = 2_000,
      mix = NasMix(nasOnlyPercent = 10, bothPercentOfPhone = 90, safePercentOfBoth = 70, toVerifyPercentOfVideoBoth = 0),
      nas = NasUpToDate,
      backup = LibraryBackupStatus.Problem(code = "verify_failed", count = 4),
      edit = { it.withBackupMarks() },
    )
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: 1f)) {
          var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
          Box(modifier = Modifier.fillMaxSize()) {
            LibraryTab(
              contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
              backdropOut = { backdrop = it },
              onOpenViewer = {},
              onOpenSettings = {},
              state = state,
            )
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
      }
    }
    composeRule.waitForIdle()
    if (scrollFraction != null) {
      // Un po' sopra il fondo: dove le celle dei segni sono piu' fitte (su un tablet il fondo ne mostra poche).
      composeRule.runOnIdle {
        val grid = state.grid
        grid.userScrolled = true
        grid.listState.requestScrollToItem((grid.layout!!.rowCount * scrollFraction).toInt(), 0)
      }
      composeRule.waitForIdle()
    }
    capture(name, fontScale)
  }

  @Test
  fun libreriaConISegniDelBackupScorsa() = shootLibrary("libreria-segni-backup-scorsa", scrollFraction = 0.985f)

  @Test
  fun libreriaConISegniDelBackup() = shootLibrary("libreria-segni-backup")

  @Test
  fun libreriaConISegniDelBackupTestoGrande() = shootLibrary("libreria-segni-backup", fontScale = LARGE_TEXT)

  companion object {
    const val DIR = "../docs/screenshots/5-P5"
    const val LARGE_TEXT = 1.3f

    /** Il NAS aggiornato: le tre voci del piede sono tre davvero. */
    val NasUpToDate: LibraryNasStatus = LibraryNasStatus.UpToDate(atNasMillis = 1_792_119_120_000)

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

/**
 * La stessa Libreria con dei segni in piu': le celle piu' recenti (quelle che si vedono a Libreria aperta) con il problema del backup
 * (anche sopra "al sicuro"), con le copie simili (con il numero, anche con il segno di preferito) e le altre come stavano.
 */
internal fun InMemoryTimelineSnapshot.withBackupMarks(): InMemoryTimelineSnapshot {
  val n = size
  val idMsb = LongArray(n)
  val idLsb = LongArray(n)
  val flags = IntArray(n)
  val groups = IntArray(n)
  for (i in 0 until n) {
    val uuid = UUID.fromString(idAt(i).value)
    idMsb[i] = uuid.mostSignificantBits
    idLsb[i] = uuid.leastSignificantBits
    flags[i] = flagsAt(i)
    groups[i] = groupSizeAt(i)
  }
  val onNas = CellFlags.ON_PHONE or CellFlags.ON_NAS
  for (i in 0 until minOf(n, 90)) {
    when (i % 7) {
      2 -> flags[i] = (flags[i] and PRESENCE.inv()) or onNas or CellFlags.SAFE or CellFlags.BACKUP_PROBLEM
      4 -> flags[i] = (flags[i] and PRESENCE.inv()) or onNas or CellFlags.BACKUP_PROBLEM
      5 -> {
        flags[i] = (flags[i] and PRESENCE.inv()) or onNas or CellFlags.SAFE or CellFlags.SIMILAR_COPIES or CellFlags.GROUP_COVER
        groups[i] = if (i % 3 == 0) 2 else 3 + i % 5
      }
    }
  }
  return InMemoryTimelineSnapshot(
    seq = seq,
    idMsb = idMsb,
    idLsb = idLsb,
    sortTime = LongArray(n) { sortTimeAt(it) },
    localDay = IntArray(n) { localDayAt(it) },
    kinds = ByteArray(n) { kindAt(it).ordinal.toByte() },
    sources = ByteArray(n) { sourceAt(it).ordinal.toByte() },
    flags = flags,
    aspectMilli = IntArray(n) { aspectMilliAt(it) },
    durationMs = LongArray(n) { durationMsAt(it) },
    place = IntArray(n) { placeAt(it) },
    groupSize = groups,
    thumbHashOf = { thumbHashAt(it) },
    nasRefOf = { nasRefAt(it) },
  )
}

private const val PRESENCE: Int = CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE or CellFlags.TO_VERIFY

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xhdpi")
internal class BackupLibraryPhoneScreenshotTest(dark: Boolean) : BackupLibraryScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-xhdpi")
internal class BackupLibraryTabletScreenshotTest(dark: Boolean) : BackupLibraryScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
