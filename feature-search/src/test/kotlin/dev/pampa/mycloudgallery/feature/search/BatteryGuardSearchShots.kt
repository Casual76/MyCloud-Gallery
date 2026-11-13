package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardValue
import dev.pampa.mycloudgallery.core.designsystem.design.BatterySaveResult
import dev.pampa.mycloudgallery.core.designsystem.state.LocalBatteryThreshold
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.feature.search.models.AnalysisScreen
import dev.pampa.mycloudgallery.feature.search.models.NoAnalysisHandlers
import dev.pampa.mycloudgallery.feature.search.models.NoSearchSettingsHandlers
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsScreen
import dev.pampa.mycloudgallery.feature.search.testing.SearchSamples
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto B (seconda beta): le immagini di prova del controllo del livello di batteria nelle impostazioni dell'analisi (la sezione «Batteria» sotto «Analisi delle foto», con
 * il blocco sopra per vedere com'e' inserita) in tutti gli stati, chiaro e scuro, telefono, tablet e testo a 1,3x; e la pagina «Analisi delle foto» e la sua testata nelle
 * impostazioni quando l'analisi e' ferma per la batteria col livello al 60%.
 *
 * `gradlew :feature-search:recordRoborazziDebug --tests "*BatteryGuardSearch*"` scrive i PNG in `docs/screenshots/beta-grafica/B-soglia-batteria/` (stessi nomi dell'altra pagina,
 * `impostazioni-analisi-...`); nei normali `testDebugUnitTest` non si scrive niente.
 */
internal enum class SearchGuardShot(val slug: String, val value: BatteryGuardValue?, val failed: Boolean = false) {
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

  // Giro 3 (BS3-1): un'attivita' spenta dall'utente dice «Spento», non «Solo in carica».
  ANALISI_SPENTA("analisi-spenta", BatteryGuardValue(20, forBackup = true, forAnalysis = true, backupOnBattery = true, analysisOnBattery = false, backupNeedsPower = false, analysisEnabled = false)),
  BACKUP_SPENTO("backup-spento", BatteryGuardValue(20, forBackup = true, forAnalysis = true, backupOnBattery = false, analysisOnBattery = true, backupNeedsPower = true, backupEnabled = false)),
}

private const val SHOT_DIR = "../docs/screenshots/beta-grafica/B-soglia-batteria"
private const val LARGE_TEXT = 1.3f

/** Lo spazio sopra «Questo dispositivo analizza» per avere dentro il titolo della sezione e le due aree. */
private const val INDEXER_HEADER_SPACE = 170

/** Ritaglia il PNG [raw] (la pagina intera) fra l'alto di [top] e il fondo di [bottom], con [pad] di margine; non fa niente se la scrittura e' spenta (nessun [raw]). */
private fun cropShot(density: Density, raw: File, out: File, top: SemanticsNodeInteraction, bottom: SemanticsNodeInteraction, pad: Int = 16, padTop: Int = pad) {
  if (!raw.exists()) return
  val image = BitmapFactory.decodeFile(raw.path)
  val margin = with(density) { pad.dp.toPx() }.toInt()
  val marginTop = with(density) { padTop.dp.toPx() }.toInt()
  val y0 = (top.fetchSemanticsNode().boundsInRoot.top.toInt() - marginTop).coerceIn(0, image.height - 1)
  val y1 = (bottom.fetchSemanticsNode().boundsInRoot.bottom.toInt() + margin).coerceIn(y0 + 1, image.height)
  out.parentFile.mkdirs()
  out.outputStream().use { Bitmap.createBitmap(image, 0, y0, image.width, y1 - y0).compress(Bitmap.CompressFormat.PNG, 100, it) }
}

internal abstract class BatteryGuardSearchShotsBase(
  private val shot: SearchGuardShot,
  private val dark: Boolean,
  private val device: String,
  private val scale: Float?,
) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun capture() {
    rule.setSearchContent(dark = dark, fontScale = scale) {
      SearchSettingsScreen(
        state = SearchSamples.settingsIndexer,
        measuring = false,
        hasIndexer = true,
        hasTextReading = true,
        hasModels = true,
        handlers = NoSearchSettingsHandlers,
        onBack = {},
        battery = shot.value,
        batteryBound = true,
        batterySaveResult = if (shot.failed) BatterySaveResult(id = 1, level = 60, ok = false) else null,
      )
    }
    val name = "impostazioni-analisi-${shot.slug}-${if (dark) "scuro" else "chiaro"}-$device"
    val raw = File("build/battery-shots-raw/$name.png").also { it.parentFile.mkdirs() }
    rule.onRoot().captureRoboImage(raw.path)
    cropShot(
      density = rule.density,
      raw = raw,
      out = File("$SHOT_DIR/$name.png"),
      // Dal blocco «Analisi delle foto» (titolo, aree, «Questo dispositivo analizza») alla nota sotto il controllo: si vede com'e' inserita la sezione «Batteria».
      top = rule.onAllNodesWithText("Questo dispositivo analizza", useUnmergedTree = true).onFirst(),
      bottom = rule.onAllNodesWithText("E con il cavo?", useUnmergedTree = true).onFirst(),
      padTop = INDEXER_HEADER_SPACE,
    )
  }

  companion object {
    fun every(states: List<SearchGuardShot> = SearchGuardShot.entries): List<Array<Any>> = states.flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w360dp-h2800dp-xhdpi")
internal class BatteryGuardSearchPhoneShots(shot: SearchGuardShot, dark: Boolean) : BatteryGuardSearchShotsBase(shot, dark, "telefono", scale = null) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = every()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w1280dp-h2400dp-xhdpi")
internal class BatteryGuardSearchTabletShots(shot: SearchGuardShot, dark: Boolean) : BatteryGuardSearchShotsBase(shot, dark, "tablet", scale = null) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = every(listOf(SearchGuardShot.PREDEFINITO, SearchGuardShot.SPENTI))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w360dp-h3600dp-xhdpi")
internal class BatteryGuardSearchLargeTextShots(shot: SearchGuardShot, dark: Boolean) :
  BatteryGuardSearchShotsBase(shot, dark, "telefono-testo-grande", scale = LARGE_TEXT) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = every(listOf(SearchGuardShot.PREDEFINITO, SearchGuardShot.SPENTI, SearchGuardShot.NOTTE_DI_SERIE))
  }
}

/** Il testo a 2x (il massimo di Samsung): la frase non si taglia (BU-5). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w360dp-h5200dp-xhdpi")
internal class BatteryGuardSearchDoubleTextShots(shot: SearchGuardShot, dark: Boolean) :
  BatteryGuardSearchShotsBase(shot, dark, "telefono-testo-2x", scale = 2f) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = every(listOf(SearchGuardShot.PREDEFINITO, SearchGuardShot.SPENTI, SearchGuardShot.NOTTE_DI_SERIE))
  }
}

/** L'analisi ferma per la batteria col livello al 60%: la pagina «Analisi delle foto» e la testata nelle impostazioni dell'analisi. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w360dp-h800dp-xhdpi")
internal class BatteryWaitSearchPhoneShots(private val dark: Boolean) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val paused = LibraryAnalysisStatus.Paused(LibraryAnalysisWait.BATTERY, remaining = 2_700, ready = 12_300, total = 15_000)
  private val look: String get() = if (dark) "scuro" else "chiaro"

  @Test
  fun laPaginaDellAnalisi() {
    val s = AnalysisScenes
    rule.setSearchContent(dark = dark) {
      CompositionLocalProvider(LocalBatteryThreshold provides 60) { AnalysisScreen(paused, s.restFlow(), s.thumbnails, NoAnalysisHandlers, onBack = {}) }
    }
    rule.onAllNodesWithText("Batteria sotto il 60%", substring = true, useUnmergedTree = true).onFirst().assertExists()
    rule.onRoot().captureRoboImage("$SHOT_DIR/analisi-pagina-fermo-batteria-60-$look-telefono.png")
  }

  @Test
  fun laTestataNelleImpostazioni() {
    rule.setSearchContent(dark = dark) {
      CompositionLocalProvider(LocalBatteryThreshold provides 60) {
        SearchSettingsScreen(
          state = SearchSamples.settingsIndexer.copy(indexer = SearchSamples.settingsIndexer.indexer.copy(analysis = paused)),
          measuring = false,
          hasIndexer = true,
          hasTextReading = true,
          hasModels = true,
          handlers = NoSearchSettingsHandlers,
          onBack = {},
          battery = BatteryGuardValue(60, forBackup = true, forAnalysis = true),
        )
      }
    }
    rule.onAllNodesWithText("Batteria sotto il 60%", substring = true, useUnmergedTree = true).onFirst().assertExists()
    rule.onRoot().captureRoboImage("$SHOT_DIR/analisi-testata-impostazioni-fermo-batteria-60-$look-telefono.png")
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}
