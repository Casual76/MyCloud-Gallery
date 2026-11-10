package dev.pampa.mycloudgallery.feature.backup.received

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.feature.backup.BackupComposeBase
import dev.pampa.mycloudgallery.feature.backup.LARGE_TEXT
import dev.pampa.mycloudgallery.feature.backup.SHOT_PHONE_QUALIFIERS
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageActions
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageUi
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import dev.pampa.mycloudgallery.feature.backup.testing.ReceivedSamples
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di "Ricevuti in coda" (correzioni 5B, IN-7): l'elenco con ogni stato, la conferma di "Scarta", la frase dopo un tocco e la scheda nella
 * pagina Backup. Chiaro e scuro, telefono (412 dp) e tablet (1280 dp), piu' il testo grande (1,3x). Sui dati dei test; le miniature sono bitmap di prova.
 *
 * `gradlew :feature-backup:recordRoborazziDebug --tests "*Received*Shots*"` scrive i PNG in `docs/screenshots/5-IN7/`. Nei normali `testDebugUnitTest` si
 * verifica solo che le schermate compongano.
 */
abstract class ReceivedShotBase(private val dark: Boolean, private val device: String) : BackupComposeBase() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  internal fun shoot(name: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale, content = content)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  /** Una miniatura di prova: un riquadro pieno di un colore che dipende dal file (come lo sarebbe una foto). */
  internal fun thumbnails(): suspend (ReceivedItem, Int) -> ImageBitmap? = { item, px ->
    val side = px.coerceAtLeast(8)
    ImageBitmap(side, side).also { bitmap ->
      val hue = (item.id.value * 47 % 360).toFloat()
      val top = Color.hsv(hue, 0.45f, 0.85f)
      val bottom = Color.hsv((hue + 40f) % 360f, 0.55f, 0.55f)
      val canvas = Canvas(bitmap)
      canvas.drawRect(0f, 0f, side.toFloat(), side / 2f, Paint().apply { color = top })
      canvas.drawRect(0f, side / 2f, side.toFloat(), side.toFloat(), Paint().apply { color = bottom })
    }
  }

  @Composable
  internal fun Elenco(items: List<ReceivedItem>, discarding: ReceivedItem? = null, notice: ReceivedNotice? = null) {
    ReceivedScreen(
      state = ReceivedUi(items, discarding, notice),
      actions = ReceivedActions(thumbnail = thumbnails()),
      onBack = {},
    )
  }

  internal val problems: List<ReceivedItem> = listOf(
    ReceivedSamples.item(1, ReceivedStatus.COPY_CHANGED, folder = "Ricevute"),
    ReceivedSamples.item(2, ReceivedStatus.NOT_INTACT, folder = "Viaggi/Estate 2026", video = true, size = 1_240_000_000L),
    ReceivedSamples.item(3, ReceivedStatus.FOLDER_REFUSED, folder = "Versioni"),
    ReceivedSamples.item(4, ReceivedStatus.COPY_MISSING, folder = "Ricevute"),
  )

  internal val waiting: List<ReceivedItem> = listOf(
    ReceivedSamples.item(5, ReceivedStatus.NETWORK, folder = "Ricevute"),
    ReceivedSamples.item(6, ReceivedStatus.TRIAL, folder = "Ricevute", video = true, size = 48_000_000L),
    ReceivedSamples.item(7, ReceivedStatus.FOLDER_LOCKED, folder = "Famiglia/Foto"),
    ReceivedSamples.item(8, ReceivedStatus.NAS_FULL, folder = "Ricevute"),
    ReceivedSamples.item(9, ReceivedStatus.PASSWORD, folder = "Ricevute"),
    ReceivedSamples.item(10, ReceivedStatus.QUOTA, folder = "Ricevute"),
  )

  internal val typical: List<ReceivedItem> = listOf(
    ReceivedSamples.item(11, ReceivedStatus.COPY_CHANGED, folder = "Ricevute"),
    ReceivedSamples.item(12, ReceivedStatus.UPLOADING, folder = "Viaggi/Estate 2026", video = true, size = 612_000_000L),
    ReceivedSamples.item(13, ReceivedStatus.NETWORK, folder = "Ricevute"),
    ReceivedSamples.item(14, ReceivedStatus.QUEUED, folder = "Ricevute", size = 4_100_000L),
  )

  @Composable
  internal fun Page(counts: ReceivedCounts) {
    BackupPageScreen(
      state = BackupPageUi(
        status = BackupStatus.UpToDate(6_658, 1_792_119_120_000),
        sources = BackupSamples.sources,
        deviceFolder = BackupSamples.deviceFolder,
        sheetOpen = false,
        received = counts,
      ),
      actions = BackupPageActions(),
      onBack = {},
    )
  }

  companion object {
    const val DIR = "../docs/screenshots/5-IN7"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h1700dp-xhdpi")
class ReceivedPhoneShots(dark: Boolean) : ReceivedShotBase(dark, "telefono") {

  @Test
  fun elencoTipico() = shoot("elenco") { Elenco(typical) }

  @Test
  fun elencoTipicoTestoGrande() = shoot("elenco", LARGE_TEXT) { Elenco(typical) }

  @Test
  fun elencoProblemi() = shoot("elenco-problemi") { Elenco(problems) }

  @Test
  fun elencoAttese() = shoot("elenco-attese") { Elenco(waiting) }

  @Test
  fun elencoVuoto() = shoot("elenco-vuoto") { Elenco(emptyList()) }

  @Test
  fun fraseDopoRiprova() = shoot("elenco-dopo-riprova") { Elenco(typical, notice = ReceivedNotice.Requeued) }

  @Test
  fun fraseRiprovaBloccata() = shoot("elenco-riprova-bloccata") { Elenco(typical, notice = ReceivedNotice.Blocked(ReceivedStatus.TRIAL)) }

  @Test
  fun paginaBackupConLaScheda() = shoot("pagina-backup-scheda") { Page(ReceivedCounts(waiting = 2, uploading = 1, problems = 1)) }

  @Test
  fun paginaBackupConLaSchedaTestoGrande() = shoot("pagina-backup-scheda", LARGE_TEXT) { Page(ReceivedCounts(waiting = 2, uploading = 1, problems = 1)) }

  @Test
  fun paginaBackupConSoloAttese() = shoot("pagina-backup-scheda-attese") { Page(ReceivedCounts(waiting = 3, uploading = 0, problems = 0)) }

  @Test
  fun paginaBackupSenzaRicevuti() = shoot("pagina-backup-senza-scheda") { Page(ReceivedCounts.NONE) }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class ReceivedSheetShots(dark: Boolean) : ReceivedShotBase(dark, "telefono") {

  @Test
  fun confermaScarta() = shoot("conferma-scarta") { Elenco(typical, discarding = typical.first()) }

  @Test
  fun confermaScartaTestoGrande() = shoot("conferma-scarta", LARGE_TEXT) { Elenco(typical, discarding = typical.first()) }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h1300dp-hdpi")
class ReceivedTabletShots(dark: Boolean) : ReceivedShotBase(dark, "tablet") {

  @Test
  fun elencoTablet() = shoot("elenco") { Elenco(typical) }

  @Test
  fun paginaBackupTablet() = shoot("pagina-backup-scheda") { Page(ReceivedCounts(waiting = 2, uploading = 1, problems = 1)) }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
