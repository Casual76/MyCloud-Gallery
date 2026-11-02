package dev.pampa.mycloudgallery.feature.widget

import android.app.Application
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.feature.widget.WidgetTestKit.all
import dev.pampa.mycloudgallery.feature.widget.WidgetTestKit.clickables
import dev.pampa.mycloudgallery.feature.widget.WidgetTestKit.images
import dev.pampa.mycloudgallery.feature.widget.WidgetTestKit.texts
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il widget composto davvero da Glance (Fase 10, P3, D3, D4, D8, D10, §2.2): per ogni caso dello stato e ogni misura, i `RemoteViews` che il launcher riceverebbe, applicati a una
 * vista. Si guarda **la struttura**: le foto sono tante quante le celle, il titolo c'e' solo per un ricordo, i messaggi non hanno foto, ogni cella si tocca e ha la sua
 * descrizione. (Gli screenshot, che mostrano l'aspetto, sono in `WidgetScreenshotTest`.)
 */
@RunWith(RobolectricTestRunner::class)
class GalleryWidgetGlanceTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  private fun bitmapImages(view: android.view.View) = view.images().filter { it.drawable is BitmapDrawable }

  @Test
  fun unRicordoHaTitoloECopertinaEFotoInOgniMisura() = runBlocking {
    for (size in WidgetSize.entries) {
      val frame = WidgetTestKit.memory(size)
      val view = WidgetTestKit.render(context, frame, size)
      assertThat(view.texts()).contains("Un anno fa · Oslo")
      assertThat(bitmapImages(view)).hasSize(size.cells)
      // Ogni foto ha la sua descrizione (tipo e data) e nessun'altra cosa.
      val descriptions = bitmapImages(view).map { it.contentDescription?.toString().orEmpty() }
      assertThat(descriptions).containsExactlyElementsIn(frame.cells.map { it.description })
    }
  }

  @Test
  fun leFotoACasoEIlBloccoNonHannoTitolo() = runBlocking {
    for (size in WidgetSize.entries) {
      for (frame in listOf(WidgetTestKit.photos(size), WidgetTestKit.locked(size))) {
        val view = WidgetTestKit.render(context, frame, size)
        assertThat(view.texts()).isEmpty()
        assertThat(bitmapImages(view)).hasSize(size.cells)
      }
    }
  }

  @Test
  fun iMessaggiNonHannoFoto() = runBlocking {
    for (size in WidgetSize.entries) {
      for (reason in WidgetEmpty.entries.filter { it != WidgetEmpty.NOT_READY }) {
        val text = if (reason == WidgetEmpty.NOT_CONFIGURED) "Configura MyCloud Gallery" else "Apri MyCloud Gallery"
        val view = WidgetTestKit.render(context, WidgetTestKit.message(size, reason, text), size)
        assertThat(view.texts()).containsExactly(text)
        assertThat(bitmapImages(view)).isEmpty()
      }
    }
  }

  @Test
  fun ogniCellaESiTocca() = runBlocking {
    for (size in WidgetSize.entries) {
      val view = WidgetTestKit.render(context, WidgetTestKit.photos(size), size)
      // Una per cella piu' la superficie intera ("Apri MyCloud Gallery").
      assertThat(view.clickables().size).isAtLeast(size.cells)
      assertThat(view.all<android.widget.TextView>()).isEmpty()
    }
  }
}
