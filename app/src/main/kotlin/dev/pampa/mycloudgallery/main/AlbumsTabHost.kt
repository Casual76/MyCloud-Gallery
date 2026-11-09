package dev.pampa.mycloudgallery.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import dev.antigravity.fluidengine.ui.fluid.FluidScrollToTopBus
import dev.antigravity.fluidengine.ui.fluid.ProvideFluidChrome
import androidx.navigation.NavController
import dev.antigravity.fluidengine.ui.fluid.rememberFluidChromeController
import dev.pampa.mycloudgallery.navigation.AppAlbumsTab

/**
 * La scheda **Album** della pillola (G2, lista dell'utente: "come Foto di Apple, gli album al posto di Cerca"), con il contenuto di G2b: la
 * griglia di copertine in stile Galleria Samsung (Preferiti, Fotocamera e Video in testa, poi album, album smart con l'icona IA, Screenshot e
 * Messaggistica per ultima apertura). La scheda disegna da se' il titolo con "+" e "⋮" e consegna il suo fondale alla pillola
 * ([CollectionsSlot.backdropOut]): l'ospite non aggiunge una barra. L'elenco di sempre ("Tutti gli album") si apre dal menu "⋮".
 *
 * @param nav la navigazione dell'app: le uscite della scheda verso le pagine degli album e delle raccolte-filtro ([AppAlbumsTab]).
 */
@Composable
fun AlbumsTabHost(slot: CollectionsSlot, nav: NavController) {
  AppAlbumsTab(nav = nav, contentPadding = slot.contentPadding, backdropOut = slot.backdropOut)
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
