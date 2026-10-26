package dev.pampa.mycloudgallery.feature.viewer.menu

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.ViewerActionHandlers
import dev.pampa.mycloudgallery.feature.viewer.ViewerCapabilities
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.viewerMenuEntries
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le tre voci degli album nel menu ⋮ del viewer (Fase 7, P10, D21, D35, D39, E-D16): compaiono solo se la porta le da' per questo media (le decide la tabella dei
 * permessi, il viewer non la vede), nell'ordine del progetto, prima di Informazioni; non sono distruttive (il file resta dov'e'); senza album il menu non cambia.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "it")
class AlbumMenuTest {

  private val resources get() = BackupActionsSamples.resources
  private val media = MediaId("00000000-0000-4000-8000-000000000001")
  private val all = ViewerAlbumMenu(addToAlbum = true, removeFromAlbum = true, setAsCover = true)

  private fun entries(menu: ViewerAlbumMenu, add: () -> Unit = {}, cover: () -> Unit = {}, remove: () -> Unit = {}) =
    albumMenuEntries(resources, menu, add, cover, remove)

  private fun labels(entries: List<GalleryMenuEntry>) = BackupActionsSamples.labels(entries)

  @Test
  fun leTreVociNellOrdineDelProgetto() {
    assertThat(labels(entries(all))).containsExactly("Aggiungi ad album", "Imposta come copertina", "Rimuovi dall’album").inOrder()
  }

  @Test
  fun unaVoceCheLaTabellaNonDaNonCompare() {
    // "Gli altri aggiungono", elemento di un altro: nessun "Rimuovi" (E-D16), nessuna copertina; si puo' sempre aggiungere a un proprio album.
    assertThat(labels(entries(ViewerAlbumMenu(addToAlbum = true, removeFromAlbum = false, setAsCover = false)))).containsExactly("Aggiungi ad album")
    assertThat(labels(entries(ViewerAlbumMenu(addToAlbum = false, removeFromAlbum = true, setAsCover = false)))).containsExactly("Rimuovi dall’album")
    assertThat(entries(ViewerAlbumMenu.NONE)).isEmpty()
  }

  @Test
  fun inUnoSmartUnElementoAutomaticoSiTogliePerSempre() {
    assertThat(labels(entries(all.copy(removeIsExclude = true)))).contains("Togli dall’album")
    assertThat(labels(entries(all.copy(removeIsExclude = true)))).doesNotContain("Rimuovi dall’album")
  }

  @Test
  fun nessunaVoceDegliAlbumEDistruttiva() {
    assertThat(entries(all).filterIsInstance<GalleryMenuEntry.Item>().none { it.destructive }).isTrue()
  }

  @Test
  fun ognivoceChiamaSoloLaSuaRichiestaEDopoIlTocco() {
    val calls = ArrayList<String>()
    val shown = entries(all, { calls += "add" }, { calls += "cover" }, { calls += "remove" })
    // Mostrare le voci non chiama niente.
    assertThat(calls).isEmpty()
    (shown[0] as GalleryMenuEntry.Item).onClick()
    (shown[1] as GalleryMenuEntry.Item).onClick()
    (shown[2] as GalleryMenuEntry.Item).onClick()
    assertThat(calls).containsExactly("add", "cover", "remove").inOrder()
  }

  private val handlers = ViewerActionHandlers({}, {}, {}, {}, {}, {}, {})
  private val caps = ViewerCapabilities(favorite = true, hide = true, share = true, copy = true, trash = true)

  @Test
  fun leVociStannoNelGruppoDiCondividiPrimaDiInformazioniEL_EliminaResta_Ultima() {
    val menu = viewerMenuEntries(resources, caps, isFavorite = false, secure = false, handlers = handlers, albumActions = entries(all))
    val shown = labels(menu)
    assertThat(shown.indexOf("Aggiungi ad album")).isGreaterThan(shown.indexOf(resources.getString(R.string.viewer_action_copy)))
    assertThat(shown.indexOf("Rimuovi dall’album")).isLessThan(shown.indexOf(resources.getString(R.string.viewer_action_info)))
    assertThat(shown.last()).isEqualTo(resources.getString(R.string.viewer_action_delete))
  }

  @Test
  fun senzaVociDegliAlbumIlMenuNonCambia() {
    val before = viewerMenuEntries(resources, caps, isFavorite = true, secure = false, handlers = handlers)
    val after = viewerMenuEntries(resources, caps, isFavorite = true, secure = false, handlers = handlers, albumActions = entries(ViewerAlbumMenu.NONE))
    assertThat(labels(after)).isEqualTo(labels(before))
  }

  @Test
  fun inUnViewerSenzaAlbumNonCiSonoVoci() {
    val services = ViewerServices()
    assertThat(services.albums).isSameInstanceAs(NoViewerAlbumActions)
    assertThat(services.albums.menu(media).any).isFalse()
    // E non scrive niente: il null-object risponde "non riuscito" senza toccare nulla.
    assertThat(runBlocking { services.albums.removeFromAlbum(media) }).isEqualTo(AlbumMenuResult.FAILED)
    assertThat(runBlocking { services.albums.setAsCover(media) }).isEqualTo(AlbumMenuResult.FAILED)
  }
}
