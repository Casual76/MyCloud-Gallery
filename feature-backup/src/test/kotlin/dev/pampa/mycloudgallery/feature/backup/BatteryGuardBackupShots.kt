package dev.pampa.mycloudgallery.feature.backup

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardValue
import dev.pampa.mycloudgallery.core.designsystem.design.BatterySaveResult
import dev.pampa.mycloudgallery.core.designsystem.state.LocalBatteryThreshold
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageActions
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageUi
import dev.pampa.mycloudgallery.feature.backup.settings.BackupSettingsScreen
import dev.pampa.mycloudgallery.feature.backup.settings.FoldersState
import dev.pampa.mycloudgallery.feature.backup.settings.SettingsActions
import dev.pampa.mycloudgallery.feature.backup.settings.SettingsUi
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import dev.pampa.mycloudgallery.feature.backup.ui.LocalBackupCovers
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto B (seconda beta): le immagini di prova del controllo del livello di batteria nelle impostazioni del Backup, per la critica grafica. La sezione «Quando» intera (cosi'
 * si vede com'e' inserito il controllo fra «Proteggi subito le foto nuove» e le note), in tutti gli stati, chiaro e scuro, telefono (360 dp), tablet e testo a 1,3x; e la pagina Backup
 * con il motivo di fermo col numero.
 *
 * `gradlew :feature-backup:recordRoborazziDebug --tests "*BatteryGuardBackup*"` scrive i PNG in `docs/screenshots/beta-grafica/B-soglia-batteria/`. La pagina si compone alta
 * (tutta intera) e il PNG si ritaglia dal titolo «Quando» all'ultima nota: nei normali `testDebugUnitTest` non si scrive niente.
 */
internal enum class GuardShot(val slug: String, val value: BatteryGuardValue?, val failed: Boolean = false) {
  PREDEFINITO("predefinito-20", BatteryGuardValue(20, forBackup = true, forAnalysis = true)),
  SESSANTA("60-entrambi", BatteryGuardValue(60, forBackup = true, forAnalysis = true)),
  SOLO_BACKUP("solo-backup-35", BatteryGuardValue(35, forBackup = true, forAnalysis = false)),
  SOLO_ANALISI("solo-analisi-35", BatteryGuardValue(35, forBackup = false, forAnalysis = true)),
  SPENTI("entrambi-spenti-20", BatteryGuardValue(20, forBackup = false, forAnalysis = false)),
  MINIMO("minimo-5", BatteryGuardValue(5, forBackup = true, forAnalysis = true)),
  MASSIMO("massimo-80", BatteryGuardValue(80, forBackup = true, forAnalysis = true)),

  // Giro 1 (BS-4, BS-5): il livello vero gia' salvato (33, 0, 100), le impostazioni non ancora lette e il salvataggio non riuscito.
  FUORI_GRIGLIA("salvato-33", BatteryGuardValue(33, forBackup = true, forAnalysis = true)),
  ZERO("salvato-0", BatteryGuardValue(0, forBackup = true, forAnalysis = true)),
  CENTO("salvato-100", BatteryGuardValue(100, forBackup = true, forAnalysis = true)),
  CARICAMENTO("sto-caricando", null),
  NON_SALVATO("salvataggio-non-riuscito", BatteryGuardValue(20, forBackup = true, forAnalysis = true), failed = true),

  // Giro 2 (BU-3): chi lavora solo in carica dice «Solo in carica» al posto dell'interruttore. `notte-di-serie` e' la situazione vera del preset di serie: l'analisi solo in carica, il backup solo per «Proteggi subito».
  NOTTE_DI_SERIE("notte-di-serie-20", BatteryGuardValue(20, forBackup = true, forAnalysis = true, backupOnBattery = true, analysisOnBattery = false, backupNeedsPower = true)),
  SOLO_IN_CARICA_BACKUP("backup-solo-in-carica", BatteryGuardValue(20, forBackup = true, forAnalysis = true, backupOnBattery = false, analysisOnBattery = true, backupNeedsPower = true)),
  NESSUNO_ABATTERIA("nessuno-a-batteria", BatteryGuardValue(20, forBackup = true, forAnalysis = true, backupOnBattery = false, analysisOnBattery = false, backupNeedsPower = true)),
}

private const val SHOT_DIR = "../docs/screenshots/beta-grafica/B-soglia-batteria"

/** Ritaglia il PNG [raw] (la pagina intera) fra l'alto di [top] e il fondo di [bottom], con [pad] di margine; non fa niente se la scrittura e' spenta (nessun [raw]). */
internal fun cropShot(density: Density, raw: File, out: File, top: SemanticsNodeInteraction, bottom: SemanticsNodeInteraction, pad: Int = 16) {
  if (!raw.exists()) return
  val image = BitmapFactory.decodeFile(raw.path)
  val margin = with(density) { pad.dp.toPx() }.toInt()
  val y0 = (top.fetchSemanticsNode().boundsInRoot.top.toInt() - margin).coerceIn(0, image.height - 1)
  val y1 = (bottom.fetchSemanticsNode().boundsInRoot.bottom.toInt() + margin).coerceIn(y0 + 1, image.height)
  out.parentFile.mkdirs()
  out.outputStream().use { Bitmap.createBitmap(image, 0, y0, image.width, y1 - y0).compress(Bitmap.CompressFormat.PNG, 100, it) }
}

internal abstract class BatteryGuardBackupShotsBase(
  private val shot: GuardShot,
  private val dark: Boolean,
  private val device: String,
  private val scale: Float?,
) : BackupComposeBase() {

  private fun ui() = SettingsUi(
    folders = FoldersState.Ready(BackupSamples.phoneFolders),
    chosen = BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots, BackupSamples.phoneFolders.last().source)),
    deviceFolder = BackupSamples.deviceFolder,
    // «Proteggi subito» acceso = il backup lavora a batteria (BatteryReach): uno stato con il backup solo in carica ha anche «Proteggi subito» spento.
    protectNewPhotos = shot.value?.backupOnBattery != false,
    rulesAvailable = true,
    editable = true,
    battery = shot.value,
    batteryLoading = shot.value == null,
    batterySaveResult = if (shot.failed) BatterySaveResult(id = 1, level = 60, ok = false) else null,
  )

  @Test
  fun capture() {
    show(dark = dark, fontScale = scale) {
      val covers = remember { I3Covers.covers() }
      CompositionLocalProvider(LocalBackupCovers provides covers) { BackupSettingsScreen(state = ui(), actions = SettingsActions(), onBack = {}) }
    }
    val name = "impostazioni-backup-${shot.slug}-${if (dark) "scuro" else "chiaro"}-$device"
    val raw = File("build/battery-shots-raw/$name.png").also { it.parentFile.mkdirs() }
    composeRule.onRoot().captureRoboImage(raw.path)
    cropShot(
      density = composeRule.density,
      raw = raw,
      out = File("$SHOT_DIR/$name.png"),
      top = composeRule.onAllNodesWithText("Quando", useUnmergedTree = true).onFirst(),
      // Dal titolo «Quando» alla nota «E con il cavo?», in fondo alla sezione «Batteria» (dopo il giro 2 la sezione sta sotto «Che cosa protegge»).
      bottom = composeRule.onAllNodesWithText("E con il cavo?", useUnmergedTree = true).onFirst(),
    )
  }

  companion object {
    fun every(states: List<GuardShot> = GuardShot.entries): List<Array<Any>> = states.flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
  }
}

/** Il telefono (360 dp di larghezza, la pagina composta tutta intera): tutti gli stati, chiaro e scuro. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h2000dp-xhdpi")
internal class BatteryGuardBackupPhoneShots(shot: GuardShot, dark: Boolean) : BatteryGuardBackupShotsBase(shot, dark, "telefono", scale = null) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = every()
  }
}

/** Il tablet: il predefinito e «entrambi spenti». */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h1800dp-xhdpi")
internal class BatteryGuardBackupTabletShots(shot: GuardShot, dark: Boolean) : BatteryGuardBackupShotsBase(shot, dark, "tablet", scale = null) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = every(listOf(GuardShot.PREDEFINITO, GuardShot.SPENTI))
  }
}

/** Il testo a 1,3x sul telefono: il predefinito e «entrambi spenti». */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h2800dp-xhdpi")
internal class BatteryGuardBackupLargeTextShots(shot: GuardShot, dark: Boolean) :
  BatteryGuardBackupShotsBase(shot, dark, "telefono-testo-grande", scale = LARGE_TEXT) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = every(listOf(GuardShot.PREDEFINITO, GuardShot.SPENTI, GuardShot.NOTTE_DI_SERIE))
  }
}

/** Il testo a 2x (il massimo di Samsung): la frase non si taglia (BU-5). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h4400dp-xhdpi")
internal class BatteryGuardBackupDoubleTextShots(shot: GuardShot, dark: Boolean) :
  BatteryGuardBackupShotsBase(shot, dark, "telefono-testo-2x", scale = 2f) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = every(listOf(GuardShot.PREDEFINITO, GuardShot.SPENTI))
  }
}

/** La pagina Backup ferma per la batteria, con il livello scelto al 60%: «Batteria sotto il 60%». */
internal abstract class BatteryWaitBackupShotsBase(private val dark: Boolean) : BackupComposeBase() {
  @Test
  fun capture() {
    show(dark = dark) {
      CompositionLocalProvider(LocalBatteryThreshold provides 60) {
        BackupPageScreen(
          state = BackupPageUi(BackupStatus.Waiting(items = 312, bytes = 1_400_000_000, reason = WaitReason.BATTERY), BackupSamples.sources, BackupSamples.deviceFolder, false),
          actions = BackupPageActions(),
          onBack = {},
        )
      }
    }
    composeRule.onAllNodesWithText("Batteria sotto il 60%", substring = true).onFirst().assertExists()
    composeRule.onRoot().captureRoboImage("$SHOT_DIR/pagina-backup-fermo-batteria-60-${if (dark) "scuro" else "chiaro"}-telefono.png")
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
internal class BatteryWaitBackupPhoneShots(dark: Boolean) : BatteryWaitBackupShotsBase(dark) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}
