package dev.pampa.mycloudgallery.main

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import dev.pampa.mycloudgallery.navigation.AppAlbumsTab

/**
 * La scheda **Album** della pillola (G2, lista dell'utente: "come Foto di Apple, gli album al posto di Cerca"), con il contenuto di G2b: la
 * griglia di copertine in stile Galleria Samsung (Preferiti, Fotocamera e Video in testa, poi album, album smart con l'icona IA, Screenshot e
 * Messaggistica per ultima apertura). La scheda disegna da se' il titolo con "+" e "⋮" e consegna il suo fondale alla pillola
 * ([CollectionsSlot.backdropOut]): l'ospite non aggiunge una barra. L'elenco di sempre ("Tutti gli album") si apre dal menu "⋮".
 *
 * Gli screenshot e le prove dello scaffold mostrano la stessa scheda (`AlbumsTabScreen` su uno stato dato) con lo stesso spazio e lo stesso
 * fondale (K3a: prima una cornice a parte, rimasta da G2, mostrava l'elenco degli album di prima).
 *
 * @param nav la navigazione dell'app: le uscite della scheda verso le pagine degli album e delle raccolte-filtro ([AppAlbumsTab]).
 */
@Composable
fun AlbumsTabHost(slot: CollectionsSlot, nav: NavController) {
  AppAlbumsTab(nav = nav, contentPadding = slot.contentPadding, backdropOut = slot.backdropOut)
}
