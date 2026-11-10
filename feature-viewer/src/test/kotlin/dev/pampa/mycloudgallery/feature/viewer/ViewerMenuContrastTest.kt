package dev.pampa.mycloudgallery.feature.viewer

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import java.io.File
import org.junit.Test

/**
 * K1 (GF-1, review sul viewer): il menu ⋮ si legge sopra una foto chiara in tema scuro e sopra una foto scura in tema chiaro, **misurato sui pixel** del viewer vero col menu aperto,
 * come l'ha misurato il critico (sulla JVM, senza la sfocatura, che non cambia la luminosita' media). Sul menu a goccia di G3 le voci erano a 2,4:1 e "Elimina dal telefono" a 1,06:1
 * (in chiaro sopra una foto scura il rosso a 1,74:1); con il pop-up e il velo le voci e la voce distruttiva reggono 4,5:1.
 *
 * Il fondo si legge dentro il margine della riga (dove non c'e' testo), l'inchiostro e' il pixel del testo piu' lontano da quel fondo (il cuore delle lettere: i bordi sono sfumati).
 */
class ViewerMenuContrastTest : ViewerTestBase() {

  private fun openMenuOver(photo: Int, dark: Boolean) {
    fixture.items.publish(fullScreenView(fixture.library.view(), portrait = true))
    show(services = services(imageSource = photoSource(flatPhoto(portrait = true, color = photo))), dark = dark)
    composeRule.mainClock.advanceTimeBy(600)
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithText("Informazioni").fetchSemanticsNodes().isNotEmpty() }
    composeRule.mainClock.advanceTimeBy(1_200)
    composeRule.waitForIdle()
  }

  /** Il contrasto misurato della riga [label]: il fondo nel margine sinistro, l'inchiostro fra le prime lettere. */
  private fun measured(label: String): Float {
    // La cattura degli screenshot (Roborazzi, disegno hardware di Robolectric: il vetro dell'engine usa gli shader, che una tela software non disegna), in un file
    // temporaneo. `captureToImage` su Robolectric aspetta un ridisegno della finestra che non arriva.
    val file = File.createTempFile("k1-menu-", ".png").apply { deleteOnExit() }
    composeRule.onRoot().captureRoboImage(file, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    val image = BitmapFactory.decodeFile(file.path)
    val row: Rect = composeRule.onAllNodesWithText(label).onFirst().fetchSemanticsNode().boundsInRoot
    val dp = composeRule.density.density
    val y = row.center.y.toInt()
    val background = Color(image.getPixel((row.left + RowSampleInset * dp).toInt(), y))
    var best = 1f
    val x0 = (row.left + TextInset * dp).toInt()
    val x1 = (x0 + TextScan * dp).toInt().coerceAtMost(row.right.toInt() - 1)
    for (py in (y - (GlyphHalfHeight * dp).toInt())..(y + (GlyphHalfHeight * dp).toInt())) {
      for (px in x0..x1) {
        val c = GalleryLegibility.contrast(Color(image.getPixel(px, py)), background)
        if (c > best) best = c
      }
    }
    return best
  }

  private fun assertLegible(case: String) {
    for (label in listOf("Copia", "Informazioni", "Elimina dal telefono")) {
      val contrast = measured(label)
      // Le misure finiscono nel rapporto della prova (system-out): sono i numeri del report.
      println("K1 contrasto misurato, $case, \"$label\": $contrast")
      assertWithMessage("$case, \"$label\": $contrast").that(contrast).isAtLeast(GalleryLegibility.TextContrast)
    }
  }

  @Test
  fun inTemaScuroSopraUnaFotoBiancaLeVociELEliminaSiLeggono() {
    openMenuOver(photo = android.graphics.Color.WHITE, dark = true)
    assertLegible("tema scuro, foto bianca")
  }

  @Test
  fun inTemaChiaroSopraUnaFotoNeraLeVociELEliminaSiLeggono() {
    openMenuOver(photo = android.graphics.Color.BLACK, dark = false)
    assertLegible("tema chiaro, foto nera")
  }

  @Test
  fun neiCasiFacili_temaScuroSuNeroETemaChiaroSuBianco_reggonoAncora() {
    openMenuOver(photo = android.graphics.Color.BLACK, dark = true)
    assertLegible("tema scuro, foto nera")
  }

  @Test
  fun inTemaChiaroSopraUnaFotoBiancaReggonoAncora() {
    openMenuOver(photo = android.graphics.Color.WHITE, dark = false)
    assertLegible("tema chiaro, foto bianca")
  }

  private companion object {
    /** Dentro il margine di 16 dp della riga, lontano dal bordo del pannello: solo fondo. */
    const val RowSampleInset = 9f
    const val TextInset = 16f
    const val TextScan = 56f
    const val GlyphHalfHeight = 7f
  }
}
