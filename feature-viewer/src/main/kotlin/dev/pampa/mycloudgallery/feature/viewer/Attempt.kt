package dev.pampa.mycloudgallery.feature.viewer

import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.model.MediaId
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

/**
 * Fase 7 (P9, D23, E-H2): dopo un "Nascondi" riuscito, se i familiari vedono ancora [id] (area comune, album condiviso). Se non si riesce a
 * saperlo si dice di si: un avviso in piu' non toglie niente, uno in meno lascerebbe credere che nessuno lo veda.
 */
internal suspend fun familyStillSees(actions: LocalMediaActions, id: MediaId): Boolean =
  try {
    actions.familyStillSees(setOf(id)) > 0
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    true
  }
