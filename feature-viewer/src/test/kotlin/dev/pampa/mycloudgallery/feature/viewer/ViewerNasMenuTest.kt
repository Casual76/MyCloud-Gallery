package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase 4B (P16): l'aggancio delle voci del NAS ("Sposta", "Rinomina", "Elimina…", P14) al menu a capacita' del viewer. "Sposta" e
 * "Rinomina" stanno nel gruppo di Condividi, Copia e Informazioni (prima di Informazioni); "Elimina…" in fondo, dopo l'interruzione, con
 * l'altra voce che cancella. Senza voci del NAS il menu e' quello di sempre.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "it")
class ViewerNasMenuTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  private val handlers = ViewerActionHandlers({}, {}, {}, {}, {}, {}, {})

  private fun labels(entries: List<GalleryMenuEntry>): List<String> =
    entries.map { if (it is GalleryMenuEntry.Item) it.label else "|" }

  @Test
  fun leVociDelNasStannoNeiDueGruppiGiusti() {
    val capabilities = ViewerCapabilities(favorite = true, hide = true, share = false, copy = false, trash = false, download = true)
    val menu = viewerMenuEntries(
      resources,
      capabilities,
      isFavorite = false,
      secure = false,
      handlers = handlers,
      nasActions = listOf(GalleryMenuEntry.Item("Sposta", {}), GalleryMenuEntry.Item("Rinomina", {})),
      nasDelete = listOf(GalleryMenuEntry.Item("Elimina…", {}, destructive = true)),
    )
    val info = resources.getString(R.string.viewer_action_info)
    val download = resources.getString(R.string.viewer_action_download)
    val shown = labels(menu)
    assertThat(shown.indexOf("Sposta")).isGreaterThan(shown.indexOf(download))
    assertThat(shown.indexOf("Rinomina")).isLessThan(shown.indexOf(info))
    // "Elimina…" e' l'ultima, dopo un'interruzione di gruppo.
    assertThat(shown.last()).isEqualTo("Elimina…")
    assertThat(shown[shown.size - 2]).isEqualTo("|")
  }

  @Test
  fun senzaVociDelNasIlMenuNonCambia() {
    val capabilities = ViewerCapabilities(favorite = true, hide = true, share = true, copy = true, trash = true)
    val before = viewerMenuEntries(resources, capabilities, isFavorite = true, secure = false, handlers = handlers)
    val after = viewerMenuEntries(resources, capabilities, isFavorite = true, secure = false, handlers = handlers, nasActions = emptyList(), nasDelete = emptyList())
    assertThat(labels(after)).isEqualTo(labels(before))
  }
}
