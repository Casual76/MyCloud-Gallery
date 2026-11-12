package dev.pampa.mycloudgallery.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * L'avviso appoggiato sopra la barra delle schede (pacchetto U, giro 1 delle critiche, UU-5): l'aggiornamento dentro l'app. Lo da' la radice
 * (`AppRoot`, che conosce `feature-update`); lo scaffold della galleria ([MainScaffold]) lo posa sopra la pillola, solo quando la pillola c'e' (non in
 * selezione, non con la ricerca aperta, non quando una barra delle azioni ne prende il posto). Il visore, le Impostazioni e le altre pagine stanno
 * sopra o fuori dallo scaffold: l'avviso non li copre mai. Null = nessun avviso (le prove dello scaffold).
 */
internal val LocalTabBarNotice = staticCompositionLocalOf<(@Composable (Modifier) -> Unit)?> { null }

/** L'altezza misurata dell'avviso (0 quando non dice niente), che [MainScaffold] aggiunge al fondo della Libreria (pacchetto U, giro 2, UU2-4). */
@Stable
internal class NoticeLift {
  var height: Dp by mutableStateOf(0.dp)
}
