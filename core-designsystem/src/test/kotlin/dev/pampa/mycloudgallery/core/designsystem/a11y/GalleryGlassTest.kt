package dev.pampa.mycloudgallery.core.designsystem.a11y

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.GlassTint
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeCapsule
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeStyles
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTextButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import java.io.File
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, P8 (D26, E-AC5): il vetro con l'uscita sicura. Con "piu' contrasto" o "meno trasparenza" ogni pezzo di vetro e' una superficie
 * **piena**: sopra un fondo a scacchi bianchi e neri (il caso peggiore di una foto) i pixel della superficie sono tutti uguali e senza
 * alfa; e un test sui sorgenti tiene `glassSurface` dell'engine fuori da tutta la galleria (solo `galleryGlass` lo chiama).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class GalleryGlassTest {

  val composeRule = createComposeRule()

  /** L'activity di prova con la finestra accelerata (come nel manifest vero): senza, `captureToImage` non trova niente da copiare. */
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

  @Composable
  private fun Checker(content: @Composable () -> Unit) {
    // Il caso peggiore sotto il vetro: strisce bianche e nere strette.
    Box(Modifier.fillMaxSize().background(GalleryOnPhoto.Content)) {
      Row(Modifier.fillMaxSize()) {
        repeat(40) { i ->
          Box(Modifier.width(5.dp).fillMaxHeight().background(if (i % 2 == 0) MaterialTheme.colorScheme.scrim else GalleryOnPhoto.Content))
        }
      }
      content()
    }
  }

  private enum class Piece { SURFACE, CAPSULE }

  // `setContent` si puo' chiamare una volta per prova: i casi (richieste, tema, pezzo) sono stati osservabili.
  private val pieceState = mutableStateOf(Piece.SURFACE)
  private val prefsState = mutableStateOf(GalleryAccessibilityPrefs.Standard)
  private val darkState = mutableStateOf(false)
  private var host: View? = null
  private var started = false

  private fun stage(piece: Piece, prefs: GalleryAccessibilityPrefs, dark: Boolean = false) {
    pieceState.value = piece
    prefsState.value = prefs
    darkState.value = dark
    if (!started) {
      started = true
      composeRule.setContent { Content() }
    }
    composeRule.waitForIdle()
  }

  @Composable
  private fun Content() {
    MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (darkState.value) ThemeMode.DARK else ThemeMode.LIGHT), accessibility = prefsState.value) {
      host = LocalView.current
      val backdrop = rememberGlassBackdrop()
      val scheme = MaterialTheme.colorScheme
      Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
        Checker {
          when (pieceState.value) {
            Piece.SURFACE -> Box(
              Modifier.testTag("pezzo").size(160.dp, 60.dp)
                .galleryGlass(state = backdrop, tint = GlassDefaults.floatingTint(), shape = FluidCapsuleShape, role = GlassRole.Floating),
            )
            Piece.CAPSULE -> {
              val choice = GalleryLegibility.adapt(ContentRange(listOf(GalleryOnPhoto.Content, scheme.scrim)), GalleryChromeStyles.adaptivePalette())
              GalleryChromeCapsule(GalleryChromeStyles.adaptive(choice), backdrop, Modifier.testTag("pezzo")) {
                GalleryChromeTextButton(label = "Seleziona", onClick = {})
              }
            }
          }
        }
      }
    }
  }

  /** I pixel della finestra dentro al pezzo [tag], senza i bordi (curve dei lati e linea): la vista si disegna su un `Bitmap`. */
  private fun pixelsOf(tag: String): List<Int> {
    val view = host!!
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    val b = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    val out = ArrayList<Int>()
    val x0 = (b.left + b.width / 6f).toInt()
    val x1 = (b.right - b.width / 6f).toInt()
    val y0 = (b.top + b.height / 4f).toInt()
    val y1 = (b.bottom - b.height / 4f).toInt()
    for (y in y0 until y1) for (x in x0 until x1) out += bitmap.getPixel(x, y)
    return out
  }

  @Test
  fun conLeSuperficiPieneIlPezzoEUnColoreSoloEOpaco() {
    for (prefs in listOf(GalleryAccessibilityPrefs(highContrast = true), GalleryAccessibilityPrefs(reduceTransparency = true))) {
      stage(Piece.SURFACE, prefs)
      val pixels = pixelsOf("pezzo").toSet()
      assertWithMessage("$prefs: i pixel della superficie sono ${pixels.size} colori").that(pixels).hasSize(1)
      assertThat((pixels.first() ushr 24) and 0xFF).isEqualTo(0xFF)
    }
  }

  /** La capsula di chrome con un tasto di testo, posata sul caso peggiore (strisce bianche e nere), misurata sui **pixel**. */
  private fun capsuleContrast(prefs: GalleryAccessibilityPrefs, dark: Boolean): Float {
    stage(Piece.CAPSULE, prefs, dark)
    var darkest = Color.White
    var lightest = Color.Black
    for (argb in pixelsOf("pezzo")) {
      val c = Color(argb)
      if (c.alpha < 1f) continue
      if (c.luminance() < darkest.luminance()) darkest = c
      if (c.luminance() > lightest.luminance()) lightest = c
    }
    return GalleryLegibility.contrast(darkest, lightest)
  }

  @Test
  fun conIlContrastoAltoLaScrittaSullaCapsulaHaAlmeno4e5SuiPixel() {
    for (dark in listOf(false, true)) for (prefs in listOf(GalleryAccessibilityPrefs(highContrast = true), GalleryAccessibilityPrefs(reduceTransparency = true))) {
      val ratio = capsuleContrast(prefs, dark)
      assertWithMessage("$prefs scuro=$dark: contrasto misurato sui pixel $ratio").that(ratio).isAtLeast(4.5f)
    }
  }

  @Test
  fun laPellicolaPienaEIlFondoDelCasoPeggiore() {
    // L'inchiostro che il vetro adattivo sceglie (quasi nero o bianco) contro la pellicola piena ha il massimo del contrasto, a prescindere dalla foto.
    val palette = GalleryChromeStyles.adaptivePaletteFor(Color(0xFF000000))
    for ((ink, film) in listOf(palette.darkInk to palette.lightFilm, palette.lightInk to palette.darkFilm)) {
      val opaque = film.copy(alpha = 1f)
      assertThat(GalleryLegibility.contrast(ink, opaque)).isAtLeast(7f)
      assertThat(GalleryLegibility.worstContrast(ink, opaque, ContentRange(listOf(GalleryOnPhoto.Content, Color(0xFF000000))))).isAtLeast(7f)
    }
  }

  @Test
  fun soloGalleryGlassChiamaGlassSurface() {
    // Dal modulo `core-designsystem`: la cartella dei moduli e' la madre.
    val root = File("..").canonicalFile
    val sources = listOf("core-designsystem", "app") + root.listFiles { f -> f.isDirectory && f.name.startsWith("feature-") }.orEmpty().map { it.name }
    val offenders = sources.flatMap { module ->
      File(root, "$module/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }.filter { it.name != "GalleryGlass.kt" }.flatMap { file ->
      file.readLines().mapIndexedNotNull { i, line ->
        val code = line.substringBefore("//")
        if (Regex("""(?<![A-Za-z])glassSurface\(""").containsMatchIn(code) && !code.trimStart().startsWith("*")) "${file.name}:${i + 1}" else null
      }
    }
    assertWithMessage("`glassSurface` fuori da GalleryGlass.kt (usare `galleryGlass`, che rispetta contrasto alto e meno trasparenza):\n" + offenders.joinToString("\n"))
      .that(offenders).isEmpty()
  }

  @Test
  fun leSuperficiPieneNonHannoAlfa() {
    val scheme = androidx.compose.material3.lightColorScheme()
    val tints = listOf(
      GlassTint(overlay = Color(0x80FFFFFF), fallback = Color(0xF0FFFFFF), hairline = Color(0x20000000)),
      GlassTint(overlay = Color(0x00000000), fallback = Color(0x00000000), hairline = Color(0x00000000)),
      GlassTint(overlay = Color(0x80000000), fallback = Color(0xF0000000), hairline = Color(0x20FFFFFF)),
    )
    for (tint in tints) assertThat(solidFill(tint, scheme).alpha).isEqualTo(1f)
  }
}
