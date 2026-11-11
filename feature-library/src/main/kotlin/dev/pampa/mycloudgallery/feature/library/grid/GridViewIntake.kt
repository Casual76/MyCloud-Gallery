package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.runtime.snapshotFlow
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fase H (D3): **come la griglia riceve le viste nuove** del catalogo. Al primo avvio la lettura del telefono e del NAS ne produce a raffica, e
 * ognuna costa alla griglia i quattro layout (100 mila righe ciascuno, fuori dal thread principale) e, prima di H3, un `requestScrollToItem` che
 * fermava lo slancio (vedi [MediaGridState.holdsDataChange]).
 *
 * Qui le viste si **dosano**: a griglia ferma entrano subito, come prima; mentre l'utente scorre (dito o slancio) ne entra al piu' una ogni
 * [doseMillis], sempre l'ultima arrivata (le intermedie non servono a nessuno). Appena la griglia si ferma entra l'ultima. Chi produce i dati non
 * cambia (e' di HR): cambia solo quanto spesso la griglia li prende.
 *
 * @param now l'orologio in millisecondi (monotono): i test passano quello del loro orologio virtuale.
 */
suspend fun MediaGridState.followViews(
  views: Flow<TimelineView>,
  doseMillis: Long = ViewDoseWhileScrollingMillis,
  now: () -> Long = { System.nanoTime() / NanosPerMilli },
) {
  // La vista che la griglia ha gia' conta come appena entrata: una raffica che comincia mentre si scorre aspetta la sua dose.
  var lastApplied = now()
  views.collectLatest { next ->
    if (next === view) return@collectLatest
    if (listState.isScrollInProgress) {
      // La scadenza e' fissa (dall'ultima vista entrata), non ricomincia a ogni vista nuova: durante una raffica ne entra comunque una ogni tanto.
      val wait = doseMillis - (now() - lastApplied)
      if (wait > 0) withTimeoutOrNull(wait) { snapshotFlow { listState.isScrollInProgress }.first { !it } }
    }
    view = next
    lastApplied = now()
  }
}

/** Mentre si scorre entra al piu' una vista nuova ogni tanto (il layout si prepara; lo scambio sullo schermo aspetta comunque la griglia ferma). */
const val ViewDoseWhileScrollingMillis: Long = 1_000L

private const val NanosPerMilli = 1_000_000L
