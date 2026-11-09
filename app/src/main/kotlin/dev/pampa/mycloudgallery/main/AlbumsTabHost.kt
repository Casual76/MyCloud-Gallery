package dev.pampa.mycloudgallery.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import dev.antigravity.fluidengine.ui.fluid.FluidScrollToTopBus
import dev.antigravity.fluidengine.ui.fluid.ProvideFluidChrome
import dev.antigravity.fluidengine.ui.fluid.rememberFluidChromeController
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListRoute

/**
 * La scheda **Album** della pillola (G2, lista dell'utente: "come Foto di Apple, gli album al posto di Cerca"). Per ora ospita l'elenco degli
 * album che c'era gia' (`AlbumListRoute` di feature-albums, la stessa pagina di "Tutti gli album"), senza il tasto indietro: e' una scheda,
 * non una pagina aperta da un'altra. Il pacchetto G2b ridisegna il contenuto (copertine, Preferiti, Fotocamera e Video in testa, gli album
 * smart con l'icona IA): all'integrazione il suo schermo si collega **qui**, dentro [AlbumsTabFrame], e lo scaffold non cambia.
 *
 * @param onOpenAlbum apre la pagina di un album (la sua rotta, sopra la schermata principale).
 * @param onNewSmart "Nuovo album" smart: l'editor delle regole parte dal nome.
 */
@Composable
fun AlbumsTabHost(
  slot: CollectionsSlot,
  thumbnails: AlbumThumbnails,
  onOpenAlbum: (AlbumId) -> Unit,
  onNewSmart: (title: String) -> Unit,
) {
  AlbumsTabFrame(slot) {
    AlbumListRoute(thumbnails = thumbnails, onOpenAlbum = onOpenAlbum, onNewSmart = onNewSmart)
  }
}

/**
 * La cornice della scheda Album: una pagina `FluidScreen` non sa di stare sopra la pillola, e glielo dice la shell dell'engine
 * (`ProvideFluidChrome`): lo spazio della pillola ([CollectionsSlot.contentPadding]) che la pagina si lascia in fondo, cosi' il contenuto
 * scorre sotto la pillola come nelle Raccolte, e il registro dei fondali, da cui la pillola riceve il vetro della pagina davanti
 * ([CollectionsSlot.backdropOut]). Separata perche' la usano anche gli screenshot dello scaffold, con l'elenco su stati dati.
 */
@Composable
internal fun AlbumsTabFrame(slot: CollectionsSlot, content: @Composable () -> Unit) {
  val chrome = rememberFluidChromeController()
  val scrollToTop = remember { FluidScrollToTopBus() }
  // Il fondale della pagina davanti (quello che la `FluidScreen` dell'elenco registra) va alla pillola. Cambia quando la pagina registra il
  // suo (all'arrivo), non a ogni fotogramma.
  val pageBackdrop = chrome.activeBackdrop.value
  if (pageBackdrop != null) SideEffect { slot.backdropOut(pageBackdrop) }
  ProvideFluidChrome(controller = chrome, bottomInset = slot.contentPadding.calculateBottomPadding(), scrollToTop = scrollToTop) {
    content()
  }
}
