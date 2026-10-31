package dev.pampa.mycloudgallery.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Un **ospite per finestra** per gli oggetti del processo che ne hanno uno solo (Fase 10, P4, D18: "selezioni, scorrimento, pannelli e navigazione sono per finestra"). Le azioni del backup
 * e i comandi della Libreria sono del processo ma agiscono su una finestra (navigano, aprono i permessi, lanciano il foglio di sistema): con due finestre un ospite registrato da
 * "l'ultima che si e' composta" faceva navigare o chiedere i permessi nella finestra sbagliata.
 *
 * Regola: l'ospite e' quello della **finestra che ha il fuoco** (e' quella in cui l'utente ha appena toccato); la prima finestra a comporsi lo prende subito se nessuno ce l'ha, cosi' con
 * una finestra sola non cambia niente, nemmeno mentre un dialogo di sistema le toglie il fuoco. Quando la finestra dell'ospite si chiude, l'ospite si libera (e lo riprende l'altra
 * finestra quando ha il fuoco: e' quella davanti). [current] legge chi e' l'ospite adesso, [set] lo cambia.
 */
@Composable
fun <H : Any> WindowHost(host: H, key: Any, current: () -> H?, set: (H?) -> Unit) {
  val latestCurrent = rememberUpdatedState(current)
  val latestSet = rememberUpdatedState(set)
  DisposableEffect(key, host) {
    if (latestCurrent.value() == null) latestSet.value(host)
    onDispose { if (latestCurrent.value() === host) latestSet.value(null) }
  }
  val focused = LocalWindowInfo.current.isWindowFocused
  LaunchedEffect(key, host, focused) {
    if (focused) latestSet.value(host)
  }
}
