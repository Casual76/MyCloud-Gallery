package dev.pampa.mycloudgallery.core.qr

import android.app.Application
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.qr.codec.ZxingQrCodec
import dev.pampa.mycloudgallery.core.qr.render.QUIET_ZONE_MODULES
import dev.pampa.mycloudgallery.core.qr.render.QrGeometry
import java.util.Base64
import kotlin.random.Random
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `QrCode` sullo schermo (Robolectric con grafica vera): `FLAG_SECURE` quando serve, il QR che si legge
 * davvero **dai pixel disegnati** (chiaro e scuro, piu' misure), margini e nitidezza, e il caso in cui il
 * testo non si puo' codificare.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class QrCodeTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val invite: String = "MCG1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(Random(5).nextBytes(180))
  private val max600: String = Base64.getUrlEncoder().withoutPadding().encodeToString(Random(6).nextBytes(450)).take(600)

  @Before
  fun grantNormalPermission() {
    // HIDE_OVERLAY_WINDOWS e' normale: sul telefono vero e' concesso all'installazione (qui serve al solo scanner).
    shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions("android.permission.HIDE_OVERLAY_WINDOWS")
  }

  private val window: Window get() = rule.activity.window

  private fun Window.secure() = (attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

  private var dark by mutableStateOf(false)

  /** Mostra [content] una sola volta (un test Compose chiama `setContent` una volta sola); il resto si cambia con lo stato. */
  private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
    rule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        content()
      }
    }
    rule.waitForIdle()
  }

  private fun update(change: () -> Unit) {
    rule.runOnUiThread(change)
    rule.waitForIdle()
  }

  // --- FLAG_SECURE -------------------------------------------------------------------------------------------

  @Test
  fun secureTrue_impostaFlagSecure_efinitoIlQrLoToglie() {
    var shown by mutableStateOf(true)
    show { if (shown) QrCode(content = invite, contentDescription = "QR d'invito", secure = true) }
    assertThat(window.secure()).isTrue()

    rule.runOnUiThread { shown = false }
    rule.waitForIdle()
    assertThat(window.secure()).isFalse()
  }

  @Test
  fun secureFalse_nonToccaLaFinestra() {
    show { QrCode(content = invite, contentDescription = "QR", secure = false) }
    assertThat(window.secure()).isFalse()
    rule.onNodeWithContentDescription("QR").assertIsDisplayed()
  }

  @Test
  fun secureSiPuoAccendereEspegnereAlVolo() {
    var secure by mutableStateOf(false)
    show { QrCode(content = invite, contentDescription = "QR", secure = secure) }
    assertThat(window.secure()).isFalse()

    rule.runOnUiThread { secure = true }
    rule.waitForIdle()
    assertThat(window.secure()).isTrue()

    rule.runOnUiThread { secure = false }
    rule.waitForIdle()
    assertThat(window.secure()).isFalse()
  }

  @Test
  fun dueQrSicuri_laProtezioneRestaFinoAlRilascioDellUltimo() {
    var first by mutableStateOf(true)
    var second by mutableStateOf(true)
    show {
      if (first) QrCode(content = invite, contentDescription = "uno", secure = true, modifier = Modifier.size(120.dp))
      if (second) QrCode(content = invite, contentDescription = "due", secure = true, modifier = Modifier.size(120.dp))
    }
    rule.runOnUiThread { first = false }
    rule.waitForIdle()
    assertThat(window.secure()).isTrue()
    rule.runOnUiThread { second = false }
    rule.waitForIdle()
    assertThat(window.secure()).isFalse()
  }

  @Test
  fun ilQrNonPortaIlContenutoNelleDescrizioniAccessibili() {
    show { QrCode(content = invite, contentDescription = "QR d'invito per Marta", secure = true) }
    val tree = rule.onRoot().printToString()
    assertThat(tree).doesNotContain(invite)
    assertThat(tree).doesNotContain(invite.removePrefix("MCG1:").take(24))
    rule.onNodeWithContentDescription("QR d'invito per Marta").assertIsDisplayed()
  }

  @Test
  fun ilQrHaRuoloImmagine_eLaDescrizioneDelChiamante() {
    show { QrCode(content = invite, contentDescription = "QR d'invito per Marta", secure = false) }
    val node = rule.onNodeWithContentDescription("QR d'invito per Marta").fetchSemanticsNode()
    assertThat(node.config[SemanticsProperties.Role]).isEqualTo(Role.Image)
  }

  // --- il QR disegnato si legge --------------------------------------------------------------------------------

  /**
   * I pixel del nodo [description] cosi' come sono disegnati, in scala di grigi. La finestra si disegna in un
   * `Bitmap` (con grafica nativa di Robolectric) e se ne ritaglia il rettangolo del nodo: e' lo stesso disegno
   * che vedrebbe una fotocamera puntata sullo schermo, senza il resto della catena.
   */
  private fun capture(description: String): Luma {
    val bounds = rule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInWindow
    val window = rule.activity.window.decorView.drawToBitmap()
    val w = bounds.width.toInt()
    val h = bounds.height.toInt()
    val pixels = IntArray(w * h)
    window.getPixels(pixels, 0, w, bounds.left.toInt(), bounds.top.toInt(), w, h)
    val data = ByteArray(w * h) { i ->
      val p = pixels[i]
      val r = (p shr 16) and 0xFF
      val g = (p shr 8) and 0xFF
      val b = p and 0xFF
      ((r * 299 + g * 587 + b * 114) / 1000).toByte()
    }
    return Luma(w, h, data)
  }

  @Test
  fun ilQrDisegnatoSiLegge_inChiaroEInScuro_aPiuMisure() {
    var content by mutableStateOf(invite)
    var side by mutableStateOf(180)
    show { QrCode(content = content, contentDescription = "QR", secure = false, modifier = Modifier.size(side.dp)) }
    for (isDark in listOf(false, true)) {
      update { dark = isDark }
      for (dp in listOf(180, 260, 340, 400)) {
        for (text in listOf(invite, max600)) {
          update {
            side = dp
            content = text
          }
          val image = capture("QR")
          assertWithMessage("scuro=$isDark lato=${dp}dp ${text.length} caratteri (${image.width}px)")
            .that(ZxingQrCodec.decode(image.data, image.width, image.height))
            .isEqualTo(text)
        }
      }
    }
  }

  @Test
  fun ilQrDisegnatoSiLeggeAnche_ruotatoDiNovanta() {
    dark = true
    show { QrCode(content = invite, contentDescription = "QR", secure = false, modifier = Modifier.size(300.dp)) }
    val image = capture("QR")
    with(QrRaster) {
      assertThat(ZxingQrCodec.decode(image.rotate90().data, image.height, image.width)).isEqualTo(invite)
      assertThat(ZxingQrCodec.decode(image.rotate180().data, image.width, image.height)).isEqualTo(invite)
      assertThat(ZxingQrCodec.decode(image.rotate270().data, image.height, image.width)).isEqualTo(invite)
    }
  }

  @Test
  fun ilFondoEBiancoAnche_inTemaScuro_eIlMargineEdiAlmenoQuattroModuli() {
    show { QrCode(content = max600, contentDescription = "QR", secure = false, modifier = Modifier.size(400.dp)) }
    for (isDark in listOf(false, true)) {
      update { dark = isDark }
      val image = capture("QR")
      val bits = ZxingQrCodec.encode(max600)
      val g = QrGeometry.of(image.width.toFloat(), image.height.toFloat(), bits.size)
      val firstDark = firstDarkPixel(image)
      val minMargin = (QUIET_ZONE_MODULES * g.module).toInt()
      assertWithMessage("scuro=$isDark: primo pixel scuro a $firstDark px dal bordo, margine minimo $minMargin px")
        .that(firstDark).isAtLeast(minMargin)
      // il fondo: un punto della zona di rispetto, vicino al bordo ma dentro l'angolo arrotondato
      val inset = (QUIET_ZONE_MODULES * g.module / 2).toInt().coerceAtLeast(1)
      assertThat(image[image.width / 2, inset]).isAtLeast(250)
      assertThat(image[inset, image.height / 2]).isAtLeast(250)
    }
  }

  /** La distanza dal bordo (in pixel) del primo pixel scuro, cercando riga per riga e colonna per colonna. */
  private fun firstDarkPixel(image: Luma): Int {
    var best = Int.MAX_VALUE
    for (y in 0 until image.height) for (x in 0 until image.width) {
      if (image[x, y] < 128) {
        best = minOf(best, x, y, image.width - 1 - x, image.height - 1 - y)
      }
    }
    return best
  }

  @Test
  fun ilDisegnoENitido_soloNeroEBiancoDentroIlQr() {
    show { QrCode(content = max600, contentDescription = "QR", secure = false, modifier = Modifier.size(400.dp)) }
    val image = capture("QR")
    val bits = ZxingQrCodec.encode(max600)
    val g = QrGeometry.of(image.width.toFloat(), image.height.toFloat(), bits.size)
    // un modulo e' di pixel interi, e fra i moduli non ci sono grigi: nell'area del QR ogni pixel e' ~0 o ~255
    assertThat(g.module).isEqualTo(g.module.toInt().toFloat())
    var grey = 0
    val x0 = g.originX.toInt()
    val y0 = g.originY.toInt()
    val extent = (bits.size * g.module).toInt()
    for (y in y0 until y0 + extent) for (x in x0 until x0 + extent) {
      val v = image[x, y]
      if (v in 8..247) grey++
    }
    assertWithMessage("pixel grigi dentro il QR").that(grey).isEqualTo(0)
  }

  @Test
  fun inUnTabletIlQrNonSuperaIlTetto() {
    show { QrCode(content = invite, contentDescription = "QR", secure = false) }
    val node = rule.onNodeWithContentDescription("QR").fetchSemanticsNode()
    // 412 dp di larghezza a 2x: il QR riempie la larghezza (824 px) ma non oltre i 400 dp = 800 px
    assertThat(node.size.width).isAtMost(800)
    assertThat(node.size.width).isEqualTo(node.size.height)
  }

  // --- testo non codificabile -------------------------------------------------------------------------------------

  @Test
  fun testoOltreIlTetto_nonDisegnaUnQrFinto() {
    show { QrCode(content = "x".repeat(QrLimits.MAX_CHARS + 1), contentDescription = "QR", secure = false) }
    rule.onNodeWithContentDescription(rule.activity.getString(R.string.qr_code_unavailable)).assertIsDisplayed()
    assertThat(rule.onAllNodesWithContentDescription("QR").fetchSemanticsNodes().size).isEqualTo(0)
  }

  @Test
  fun testoVuoto_nonDisegnaUnQrFinto() {
    show { QrCode(content = "", contentDescription = "QR", secure = true) }
    rule.onNodeWithContentDescription(rule.activity.getString(R.string.qr_code_unavailable)).assertIsDisplayed()
    assertThat(rule.onAllNodesWithContentDescription("QR").fetchSemanticsNodes().size).isEqualTo(0)
  }

  @Test
  fun alTettoEsatto_siDisegna() {
    show { QrCode(content = max600, contentDescription = "QR", secure = false, modifier = Modifier.size(400.dp)) }
    rule.onNodeWithContentDescription("QR").assertIsDisplayed()
  }
}
