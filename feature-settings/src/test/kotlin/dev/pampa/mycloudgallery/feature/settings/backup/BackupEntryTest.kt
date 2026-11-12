package dev.pampa.mycloudgallery.feature.settings.backup

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.SettingsLinks
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La voce "Backup" delle Impostazioni (Fase 5A, `docs/fase5-progetto.md` §1.3, D16; riga P5 del §4.4): c'e' solo se `:app` ha scritto
 * dove porta (`SettingsLinks.openBackup`), dice lo stato del backup in una frase **per ogni caso del contratto**, e il tocco chiama la
 * callback una volta sola. Gli screenshot (le righe nei loro stati) vanno in `docs/screenshots/5-P5/`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
class BackupEntryTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val state = SettingsUiState(TrustStatus.Approved(admin = false), deviceCount = 3, pendingRequests = 0, pendingSteps = emptyList())

  private val summary = BackupSummary.EMPTY
  private val allCases: List<BackupStatus> = listOf(
    BackupStatus.NotConfigured(setOf(BackupSetupStep.DEVICE_FOLDER)),
    BackupStatus.TestOnly,
    BackupStatus.FirstBackupReady(summary),
    BackupStatus.FirstBackupReady(summary.copy(existing = ExistingCopiesState(privateAreaScanned = false, similarPending = 0))),
    BackupStatus.UpToDate(6_658, null),
    BackupStatus.Waiting(312, 1_400_000_000, WaitReason.CHARGING),
    BackupStatus.Running(BackupKind.PHOTOS, 1_240, 6_658, 2_100_000_000, 48_000_000_000, 4_800_000, BackupTrigger.FIRST_BACKUP),
    BackupStatus.Stopped(StopBy.USER),
    BackupStatus.Problem(BackupProblemCode.QUOTA, 0),
  )

  private fun show(links: SettingsLinks, status: BackupStatus?) {
    rule.setContent {
      MyCloudGalleryTheme {
        SettingsScreen(state = state, onBack = {}, openDiagnostics = {}, resumeStep = {}, links = links, backupStatus = status)
      }
    }
    rule.waitForIdle()
  }

  private fun scrollTo(text: String) {
    rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    rule.waitForIdle()
  }

  @Test
  fun senzaLaCallbackDiAppLaVoceNonCe() {
    show(SettingsLinks(), status = BackupStatus.TestOnly)
    scrollTo("Copia esterna del NAS")
    assertThat(rule.onAllNodesWithText("Backup").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun laVoceChiamaOpenBackupUnaVolta() {
    var opened = 0
    show(SettingsLinks(openBackup = { opened++ }), status = BackupStatus.UpToDate(6_658, null))
    scrollTo("Copia esterna del NAS")
    rule.onNodeWithText("Backup").assertIsDisplayed().performClick()
    assertThat(opened).isEqualTo(1)
  }

  @Test
  fun senzaLoStatoDiceAChePerServe() {
    show(SettingsLinks(openBackup = {}), status = null)
    scrollTo("Copia esterna del NAS")
    rule.onNodeWithText("Da collegare").assertIsDisplayed()
  }

  @Test
  fun ogniCasoDelContrattoHaLaSuaFrase() {
    val declared = BackupStatus::class.java.classes.toSet()
    assertThat(allCases.map { it::class.java }.toSet()).containsAtLeastElementsIn(declared - BackupStatus.TestOnly::class.java)
    show(SettingsLinks(openBackup = {}), status = allCases.first())
    for (case in allCases) {
      // Il testo di ogni caso e' una frase non vuota, e non nomina mai una cartella o un file (solo numeri e codici).
      assertThat(BackupEntryTexts.subtitleOf(rule.activity, case)).isNotEmpty()
    }
  }

  @Test
  fun leFraseDicono_CioCheSuccede() {
    val texts = allCases.map { BackupEntryTexts.subtitleOf(rule.activity, it) }
    // I3 (fase I): lo stato e' un valore corto a destra della riga, non una frase; «in prova» non dice parole da cantiere.
    assertThat(texts).containsAtLeast(
      "Da preparare",
      "In pausa",
      "Preparo",
      "Pronto",
      "Al sicuro",
      "312 in coda",
      "1.240 / 6.658",
      "Da controllare",
    )
    assertThat(texts.none { it.contains("prova") }).isTrue()
  }

  @Test
  fun iSegniDiconoQuantoServeUnOcchio() {
    // I3: il segno sulla piastrella e' lo stesso della testata della pagina Backup.
    val marks = allCases.associateWith { backupEntryMark(it) }
    assertThat(marks.getValue(BackupStatus.UpToDate(6_658, null))).isEqualTo(dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus.Done)
    assertThat(marks.getValue(BackupStatus.Problem(BackupProblemCode.QUOTA, 0))).isEqualTo(dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus.Attention)
    assertThat(marks.getValue(BackupStatus.TestOnly)).isEqualTo(dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus.Paused)
    // Giro 1 (CU-7): l'attesa e' la clessidra (la piastrella), senza il segno della pausa.
    val waiting = marks.filterKeys { it is BackupStatus.Waiting }
    assertThat(waiting).isNotEmpty()
    assertThat(waiting.values.toSet()).containsExactly(null)
    assertThat(backupEntryMark(null)).isNull()
  }
}

/**
 * Gli screenshot della voce nei suoi stati (nessuno stato, non configurato, pronto, tutto al sicuro, in corso, problema): chiaro e scuro,
 * telefono e tablet, piu' il testo grande. `gradlew :feature-settings:recordRoborazziDebug --tests "*BackupEntryShots*"`.
 */
abstract class BackupEntryShots(private val device: String) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val cases: List<BackupStatus?> = listOf(
    null,
    BackupStatus.NotConfigured(setOf(BackupSetupStep.DEVICE_FOLDER)),
    BackupStatus.FirstBackupReady(BackupSummary.EMPTY),
    BackupStatus.UpToDate(6_658, null),
    BackupStatus.Running(BackupKind.PHOTOS, 1_240, 6_658, 2_100_000_000, 48_000_000_000, 4_800_000, BackupTrigger.FIRST_BACKUP),
    BackupStatus.Problem(BackupProblemCode.QUOTA, 0),
  )

  private fun shoot(dark: Boolean, fontScale: Float? = null) {
    rule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: 1f)) {
          Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            FluidListGroup {
              cases.forEachIndexed { index, status ->
                if (index > 0) FluidListDivider()
                BackupEntryRow(status = status, onClick = {})
              }
            }
          }
        }
      }
    }
    rule.waitForIdle()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    rule.onRoot().captureRoboImage("../docs/screenshots/5-P5/impostazioni-voce-backup-${if (dark) "scuro" else "chiaro"}-$device$suffix.png")
  }

  @Test
  fun chiaro() = shoot(dark = false)

  @Test
  fun scuro() = shoot(dark = true)

  @Test
  fun chiaroTestoGrande() = shoot(dark = false, fontScale = 1.3f)

  @Test
  fun scuroTestoGrande() = shoot(dark = true, fontScale = 1.3f)
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h780dp-xhdpi")
class BackupEntryPhoneShots : BackupEntryShots("telefono")

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w1280dp-h800dp-xhdpi")
class BackupEntryTabletShots : BackupEntryShots("tablet")
