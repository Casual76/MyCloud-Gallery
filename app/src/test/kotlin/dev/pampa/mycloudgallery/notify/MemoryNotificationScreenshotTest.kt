package dev.pampa.mycloudgallery.notify

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.NotifyContent
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyPolicy
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot della notifica dei ricordi (Fase 9, P10, F115-F117): le parole della notifica **nelle due modalita'**, "Un ricordo per te" (generica, di serie) e "Titolo e foto", chiaro e
 * scuro, e la versione che si vede a **telefono bloccato** (sempre generica). Android disegna le notifiche nel suo processo (SystemUI): qui si disegna **una scheda che dice quello che la
 * notifica dice**, con gli stessi testi (`NotifyTextResolver` sulle risorse vere, il contenuto deciso da `MemoryNotifyPolicy`) e una miniatura sintetica; la tendina vera si fotografa sul
 * telefono (F115-F117). Nessun dato personale: un luogo sintetico ("Oslo") e una "foto" disegnata.
 *
 * `gradlew :app:recordRoborazziDebug --tests "*MemoryNotificationScreenshotTest*"` scrive i PNG in `docs/screenshots/9-P10/`. Nei normali `testDebugUnitTest` si verifica solo che le schede
 * compongano e che il testo sia quello giusto.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
internal class MemoryNotificationScreenshotTest(private val dark: Boolean) {

  val composeRule = createComposeRule()

  /** La `ComponentActivity` di Compose non e' nel manifest dell'app: la si registra a mano (come per gli altri screenshot dell'app). */
  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
    }
  }

  private val look: String get() = if (dark) "scuro" else "chiaro"
  private val today = LocalDate.of(2026, 11, 3)

  /** Un "un anno fa" sintetico con un luogo, e un viaggio con il suo titolo: gli unici testi che "Titolo e foto" puo' dire. */
  private fun memory(): Memory {
    val key = MemoryKey.OnThisDay(today.minusYears(1))
    val members = List(12) { MediaId(UUID(0x7100_0000L, it.toLong()).toString()) }
    return Memory(MemoryIds.of(key), key, MemoryTitle(1, DayRange.of(today.minusYears(1)), listOf(PLACE), null, null, null), members, members.first(), 20261103)
  }

  /** Una "foto" sintetica (un cielo, un sole): nessuna immagine vera. */
  private fun syntheticPhoto(): Bitmap {
    val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, 0f, 192f, 0xFF4F7FC8.toInt(), 0xFFF2C079.toInt(), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, 192f, 192f, paint)
    paint.shader = null
    paint.color = 0xFFFFF3D0.toInt()
    canvas.drawCircle(128f, 70f, 26f, paint)
    paint.color = 0xFF2D4A3A.toInt()
    canvas.drawRect(0f, 150f, 192f, 192f, paint)
    return bitmap
  }

  private fun show(fontScale: Float? = null, content: @Composable () -> Unit) {
    composeRule.setContent {
      val settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
      MyCloudGalleryTheme(settings = settings) {
        val density = LocalDensity.current
        val wrapped: @Composable () -> Unit = { Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp)) { content() } }
        if (fontScale == null) wrapped() else CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { wrapped() }
      }
    }
    composeRule.waitForIdle()
  }

  /** Il testo che la notifica porta con [mode] (titolo, testo) e quello della versione pubblica, dalle risorse vere. */
  private fun texts(mode: LockScreenMode): List<String> {
    val resolver = NotifyTextResolver(ApplicationProvider.getApplicationContext<Application>().resources) { if (it == PLACE) "Oslo" else null }
    val content: NotifyContent = MemoryNotifyPolicy().content(memory(), mode)
    return listOf(resolver.resolve(content.title), resolver.resolve(content.text), resolver.resolve(content.publicTitle), resolver.resolve(content.publicText))
  }

  private fun shoot(name: String, mode: LockScreenMode, lockScreen: Boolean, fontScale: Float? = null) {
    val (title, text, publicTitle, publicText) = texts(mode)
    val photo = if (mode == LockScreenMode.TITLE_AND_PHOTO && !lockScreen) syntheticPhoto() else null
    show(fontScale) {
      Column(Modifier.testTag("scheda"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NotificationCard(if (lockScreen) publicTitle else title, if (lockScreen) publicText else text, photo)
      }
    }
    // Quello che si legge e' quello che la notifica dice: la generica e la versione pubblica non hanno ne' anni ne' luoghi.
    composeRule.onNodeWithTag("scheda").assertExists()
    if (mode == LockScreenMode.GENERIC || lockScreen) {
      assertThat(listOf(if (lockScreen) publicTitle else title, if (lockScreen) publicText else text).joinToString(" ")).isEqualTo("Un ricordo per te Tocca per vederlo")
    } else {
      assertThat(title).isEqualTo("Un anno fa · Oslo")
      assertThat(text).isEqualTo("Tocca per rivederlo")
    }
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onNodeWithTag("scheda").captureRoboImage("$DIR/$name-$look-telefono$suffix.png")
  }

  @Test
  fun generica() = shoot("generica", LockScreenMode.GENERIC, lockScreen = false)

  @Test
  fun genericaTestoGrande() = shoot("generica", LockScreenMode.GENERIC, lockScreen = false, fontScale = LARGE_TEXT)

  @Test
  fun titoloEFoto() = shoot("titolo-e-foto", LockScreenMode.TITLE_AND_PHOTO, lockScreen = false)

  @Test
  fun titoloEFotoTestoGrande() = shoot("titolo-e-foto", LockScreenMode.TITLE_AND_PHOTO, lockScreen = false, fontScale = LARGE_TEXT)

  /** A telefono bloccato, anche con "Titolo e foto" scelto: la versione pubblica, quella generica, senza foto. */
  @Test
  fun titoloEFotoATelefonoBloccato() = shoot("titolo-e-foto-schermata-di-blocco", LockScreenMode.TITLE_AND_PHOTO, lockScreen = true)

  @Test
  fun genericaATelefonoBloccato() = shoot("generica-schermata-di-blocco", LockScreenMode.GENERIC, lockScreen = true)

  companion object {
    const val DIR = "../docs/screenshots/9-P10"
    const val LARGE_TEXT = 1.3f
    private const val PLACE = 7_654_321

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

/** La scheda di una notifica come la mostra la tendina: icona piccola, nome dell'app, titolo e testo, e la miniatura a destra se c'e'. Solo per lo screenshot. */
@Composable
private fun NotificationCard(title: String, text: String, photo: Bitmap?) {
  val appName = LocalContext.current.getString(R.string.app_name)
  Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
          painterResource(R.drawable.ic_stat_gallery), contentDescription = null, colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(appName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(" · adesso", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Spacer(Modifier.height(10.dp))
      Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
          Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
          Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (photo != null) {
          Spacer(Modifier.width(12.dp))
          val image = remember(photo) { photo.asImageBitmap() }
          Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
        }
      }
    }
  }
}
