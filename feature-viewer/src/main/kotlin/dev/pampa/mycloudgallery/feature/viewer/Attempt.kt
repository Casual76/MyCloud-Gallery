package dev.pampa.mycloudgallery.feature.viewer

import kotlin.coroutines.cancellation.CancellationException

/**
 * Esegue un'azione sul catalogo (preferito, nascondi) e dice se e' riuscita. L'azione puo' sollevare (disco pieno, database
 * in errore, galleria ferma): l'eccezione non deve arrivare alla composizione, che chiuderebbe l'app, ne' essere ingoiata
 * senza dirlo (revisione di sicurezza, R8): chi chiama mostra un avviso se la risposta e' falso. Solo l'annullamento passa.
 */
internal suspend fun attempt(action: suspend () -> Unit): Boolean =
  try {
    action()
    true
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    false
  }
