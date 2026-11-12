package dev.pampa.mycloudgallery.main

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.Insets
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.GalleryScaffold
import dev.pampa.mycloudgallery.SHOT_PHONE
import dev.pampa.mycloudgallery.ScaffoldScenes
import dev.pampa.mycloudgallery.core.designsystem.chrome.GallerySearchBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * La salita con la tastiera (H4; la prova che i critici del giro 1 chiedevano, e CU-4): con la ricerca aperta a campo vuoto, quando la tastiera sale la
 * barra si ferma [GallerySearchBarDefaults.KeyboardGap] sopra di lei e **la Libreria dietro sale della stessa quantita'** (le foto piu' recenti restano
 * sopra la tastiera); quando scende tornano giu' tutte e due.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
class RicercaTastieraTest {

  private val compose = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

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

  private fun barBottom(): Float = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.bottom

  /** Una foto della Libreria (le celle si chiamano «Foto, …»), presa per la sua posizione: la stessa prima e dopo. */
  private fun photoTop(index: Int): Float =
    compose.onAllNodesWithContentDescription("Foto, ", substring = true).fetchSemanticsNodes()[index].boundsInRoot.top

  @Test
  fun aCampoVuoto_laBarraELaLibreriaSalgonoInsiemeConLaTastiera_eTornanoGiu() {
    val scenes = ScaffoldScenes()
    compose.setContent { MyCloudGalleryTheme { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchIdle) } }
    compose.waitForIdle()
    val photos = compose.onAllNodesWithContentDescription("Foto, ", substring = true).fetchSemanticsNodes()
    // Una foto in basso, quella che la tastiera coprirebbe.
    val probe = photos.indices.maxBy { photos[it].boundsInRoot.top }
    val barBefore = barBottom()
    val photoBefore = photoTop(probe)
    val rootHeight = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
    // La barra a riposo sta [BottomMargin] + [RestInset] sopra il bordo (qui la barra di sistema non c'e'): con la tastiera sale finche' il suo bordo basso
    // e' [KeyboardGap] sopra la tastiera.
    val (gap, rest) = with(compose.density) {
      GallerySearchBarDefaults.KeyboardGap.toPx() to (FluidFloatingTabBarDefaults.BottomMargin + GallerySearchBarDefaults.RestInset).toPx()
    }

    val ime = (rootHeight * 0.4f).toInt()
    compose.runOnIdle { compose.activity.keyboard(ime) }
    compose.waitForIdle()
    val barShift = barBefore - barBottom()
    val photoShift = photoBefore - photoTop(probe)
    // La barra sta sopra la tastiera, a [KeyboardGap]; la Libreria sale della stessa quantita', nello stesso fotogramma.
    assertThat(abs(barShift - (ime + gap - rest))).isAtMost(2f)
    assertThat(barShift).isGreaterThan(0f)
    assertThat(abs(photoShift - barShift)).isAtMost(1f)

    compose.runOnIdle { compose.activity.keyboard(0) }
    compose.waitForIdle()
    assertThat(abs(barBottom() - barBefore)).isAtMost(1f)
    assertThat(abs(photoTop(probe) - photoBefore)).isAtMost(1f)
  }

  /**
   * CS-21 (H1 x H4): il viewer aperto dalla Libreria **sollevata** (ricerca a campo vuoto, tastiera su) parte dalla cella che si vede. La Libreria sale con
   * un `translationY` nel disegno di un antenato, che non rifa' il layout: i bordi dell'ancora (`grid.viewerAnchor`, quella che `LibraryScreen` mette nella
   * richiesta del viewer) devono salire con la cella disegnata. Prima si leggevano da una posizione in cache e restavano dove la cella era a tastiera chiusa.
   */
  @Test
  fun conLaLibreriaSollevata_ilViewerParteDallaCellaCheSiVede() {
    val scenes = ScaffoldScenes()
    compose.setContent { MyCloudGalleryTheme { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchIdle) } }
    compose.waitForIdle()
    val grid = scenes.library.grid
    val anchor = grid.viewerAnchor
    val photos = compose.onAllNodesWithContentDescription("Foto, ", substring = true).fetchSemanticsNodes()
    // Una foto in basso, quella che la tastiera coprirebbe, e il suo media: quello i cui bordi (a tastiera chiusa) contengono il centro della cella.
    val probe = photos.indices.maxBy { photos[it].boundsInWindow.top }
    val cellBefore = photos[probe].boundsInWindow
    val id = compose.runOnIdle {
      val snapshot = grid.view.snapshot
      (snapshot.size - 1 downTo 0).asSequence().map { snapshot.idAt(it) }.first { anchor.boundsOf(it)?.holds(cellBefore.center.x, cellBefore.center.y) == true }
    }
    val before = compose.runOnIdle { anchor.boundsOf(id) }!!

    val rootHeight = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
    compose.runOnIdle { compose.activity.keyboard((rootHeight * 0.4f).toInt()) }
    compose.waitForIdle()
    val cellAfter = compose.onAllNodesWithContentDescription("Foto, ", substring = true).fetchSemanticsNodes()[probe].boundsInWindow
    val after = compose.runOnIdle { anchor.boundsOf(id) }!!

    // La cella e' salita davvero, e i bordi del viewer sono saliti con lei, della stessa quantita', alla stessa cella.
    val lifted = cellBefore.top - cellAfter.top
    assertThat(lifted).isGreaterThan(50f)
    assertThat(abs((before.top - after.top) - lifted)).isAtMost(1f)
    assertThat(abs((before.bottom - after.bottom) - lifted)).isAtMost(1f)
    assertThat(abs(after.left - before.left)).isAtMost(1f)
    assertThat(after.holds(cellAfter.center.x, cellAfter.center.y)).isTrue()
  }

  /**
   * Prova sull'S25 del 12/11/2026: senza un `windowSoftInputMode` Android fa scorrere tutta la finestra per tenere visibile il campo con il fuoco, e quella
   * salita si sommava a quella della barra (barra a meta' schermo, suggerimenti in alto fuori dallo schermo). Con la ricerca aperta la finestra chiede
   * `adjustResize` (nessuno scorrimento, solo gli inset); alla chiusura torna com'era, cosi' le altre pagine tengono il comportamento di prima. Una
   * finestra senza modalita' dichiarata non puo' tornare a «non specificata»: si rimette `adjustPan`, quella che Android applicava.
   */
  @Test
  @Suppress("DEPRECATION")
  fun conLaRicercaApertaLaFinestraNonScorrePerLaTastiera_eAllaChiusuraTornaComEra() {
    val scenes = ScaffoldScenes()
    var open by mutableStateOf(true)
    fun adjust(): Int = compose.runOnIdle { compose.activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST }
    val before = adjust()
    assertThat(before).isEqualTo(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED)
    compose.setContent { MyCloudGalleryTheme { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = open, search = scenes.searchIdle) } }
    compose.waitForIdle()
    assertThat(adjust()).isEqualTo(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

    open = false
    compose.waitForIdle()
    assertThat(adjust()).isEqualTo(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)

    // E di nuovo aperta, di nuovo ferma.
    open = true
    compose.waitForIdle()
    assertThat(adjust()).isEqualTo(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
  }

  private fun dev.pampa.mycloudgallery.core.library.contract.PixelRect.holds(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

  /**
   * La tastiera aperta (o chiusa con 0), per le prove: Robolectric non disegna una tastiera ne' manda gli inset da solo, e la finestra della decorazione
   * non li passa ai figli: si consegnano alla vista di Compose (come in `feature-settings`, `ImeTestSupport`).
   */
  private fun ComponentActivity.keyboard(heightPx: Int) {
    val insets = WindowInsets.Builder()
      .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, heightPx))
      .setVisible(WindowInsets.Type.ime(), heightPx > 0)
      .build()
    fun walk(view: View) {
      if (view.javaClass.simpleName == "AndroidComposeView") view.dispatchApplyWindowInsets(insets)
      if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
    }
    walk(window.decorView)
  }
}
