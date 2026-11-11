package dev.pampa.mycloudgallery.core.designsystem.grid

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I dischi di presenza disegnati dalla riga ([GalleryGridRowPresence]) sono **gli stessi pixel** di quando li disegnava ogni cella: prima i fondi,
 * poi i fili, poi i glifi, ma i dischi non si toccano e stanno sopra la stessa foto. Si confrontano i pixel di una riga di tre celle (un disco intero,
 * uno piccolo, uno piu' intero) disegnata nei due modi, a riposo e con una cella premuta (il disco rimpicciolisce con lei).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class GalleryRowPresenceTest {

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

  private companion object {
    /** La riga: tre celle da 80 dp a xhdpi (2 px per dp). */
    const val RowWidth = 480
    const val RowHeight = 160
  }

  private object NoActions : GalleryGridCellActions {
    override fun describe(key: Any): String = "Foto"
    override fun click(key: Any) = Unit
    override fun longClick(key: Any) = Unit
    override val clickLabel: String = "Apri"
    override val longClickLabel: String = "Seleziona"
  }

  private val kinds = arrayOf(GalleryPresence.NasOnly, GalleryPresence.Both, GalleryPresence.PhoneOnly)
  private val keys = arrayOf<Any>("a", "b", "c")
  private val interaction = GalleryGridInteractionState()
  private val rowDrawn = mutableStateOf(false)
  private val images = List(3) { mutableStateOf<ImageBitmap?>(null) }
  private var host: View? = null

  private fun stage() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) {
        host = LocalView.current
        val style = rememberGalleryGridCellStyle(cornerScale = 0f)
        val byRow = rowDrawn.value
        // Tre celle da 80 dp (160 px a xhdpi) su un fondo pieno: nessuna miniatura, il segnaposto della palette.
        Box(Modifier.testTag("riga").size(240.dp, 80.dp).background(Color.White)) {
          Row {
            for (i in kinds.indices) {
              GalleryGridCell(
                key = keys[i],
                image = images[i],
                badges = if (byRow) GalleryGridCellBadges.None else GalleryGridCellBadges(presence = kinds[i]),
                style = style,
                interaction = interaction,
                actions = NoActions,
                modifier = Modifier.size(80.dp),
              )
            }
          }
          if (byRow) {
            val presence = GalleryRowPresence(
              kinds = Array(kinds.size) { kinds[it] },
              lefts = intArrayOf(0, 160, 320),
              widths = intArrayOf(160, 160, 160),
              height = RowHeight,
              keys = keys,
            )
            GalleryGridRowPresence(presence, style, interaction, Modifier.size(240.dp, 80.dp))
          }
        }
      }
    }
    composeRule.waitForIdle()
  }

  /** I pixel della riga (la vista si disegna su un `Bitmap`, come negli altri test di pixel di questo modulo). */
  private fun capture(byRow: Boolean): IntArray {
    composeRule.runOnIdle { rowDrawn.value = byRow }
    composeRule.waitForIdle()
    val view = host!!
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    val b = composeRule.onNodeWithTag("riga").fetchSemanticsNode().boundsInRoot
    val x0 = b.left.toInt()
    val y0 = b.top.toInt()
    val out = IntArray(RowWidth * RowHeight)
    bitmap.getPixels(out, 0, RowWidth, x0, y0, RowWidth, RowHeight)
    return out
  }

  private fun assertSamePixels(a: IntArray, b: IntArray) {
    var different = 0
    for (i in a.indices) if (a[i] != b[i]) different++
    assertWithMessage("Pixel diversi fra i dischi della cella e quelli della riga").that(different).isEqualTo(0)
  }

  @Test
  fun laCellaNascostaNonSiDisegnaNeLeiNeIlSuoDisco_eLeAltreRestanoUguali() {
    // CU-1: la cella da cui esce (e in cui rientra) la foto del viewer resta vuota, con il suo disco di presenza; le vicine non cambiano.
    stage()
    val before = capture(byRow = true)
    composeRule.runOnIdle { interaction.hiddenKey = "b" }
    val hidden = capture(byRow = true)
    val white = 0xFFFFFFFF.toInt()
    for (y in 0 until RowHeight) {
      for (x in 0 until RowWidth) {
        val i = y * RowWidth + x
        if (x in 160 until 320) {
          assertWithMessage("la cella nascosta disegna in ($x, $y)").that(hidden[i]).isEqualTo(white)
        } else {
          assertWithMessage("una cella vicina e' cambiata in ($x, $y)").that(hidden[i]).isEqualTo(before[i])
        }
      }
    }
    // La foto e' rientrata: la cella torna com'era.
    composeRule.runOnIdle { interaction.hiddenKey = null }
    assertSamePixels(before, capture(byRow = true))
  }

  @Test
  fun aRiposoISegniSonoGliStessiPixelDiQuandoLiDisegnavaLaCella() {
    stage()
    val byCell = capture(byRow = false)
    val byRow = capture(byRow = true)
    // Il confronto vale solo se i dischi si vedono davvero: un punto del fondo del primo disco (intero, 44 px, a 12 px dall'angolo) non e' il segnaposto della cella.
    assertThat(byCell[34 * RowWidth + 110]).isNotEqualTo(byCell[100 * RowWidth + 20])
    assertSamePixels(byCell, byRow)
  }

  @Test
  fun conUnaCellaPremutaIlSuoDiscoRimpicciolisceConLei() {
    stage()
    composeRule.runOnIdle { runBlocking { interaction.press("b", reducedMotion = true) } }
    composeRule.waitForIdle()
    val byCell = capture(byRow = false)
    val byRow = capture(byRow = true)
    assertSamePixels(byCell, byRow)
    composeRule.runOnIdle { interaction.cancelPress() }
  }

  @Test
  fun lePresenzeSiConfrontanoPerContenuto() {
    fun row(kind: GalleryPresence?) = GalleryRowPresence(arrayOf(kind), intArrayOf(0), intArrayOf(160), 160, arrayOf<Any>("a"))
    assertThat(row(GalleryPresence.Safe)).isEqualTo(row(GalleryPresence.Safe))
    assertThat(row(GalleryPresence.Safe)).isNotEqualTo(row(GalleryPresence.Both))
    assertThat(row(null).any).isFalse()
    assertThat(row(GalleryPresence.Both).any).isTrue()
  }
}
