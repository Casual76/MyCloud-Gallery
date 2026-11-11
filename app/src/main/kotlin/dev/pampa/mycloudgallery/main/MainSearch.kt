package dev.pampa.mycloudgallery.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.search.SearchController

/**
 * Il cablaggio della ricerca dentro la Libreria nella schermata principale (H4 della fase H): quando la barra e' aperta, se il campo deve prendere il
 * fuoco, e che cosa sa il motore di ogni passo. Prima stava dentro `MainDestination` (ViewModel di Hilt, niente prove); qui si prova con un
 * [SearchController] vero (CS-18 del giro 1).
 *
 * - **Il cerchio** ([openFromCircle]): la barra si apre con il campo pronto a scrivere (la tastiera sale) e il motore si scalda.
 * - **Indietro o la X** ([close]): la barra si chiude e la prossima apertura riparte da capo (il campo vuoto, la Libreria dietro).
 * - **"Trova simili"** ([findSimilar], dal viewer o dalla selezione): la barra si apre **senza tastiera**, con i simili gia' cercati.
 * - **Un risultato toccato** ([resultOpened]): la ricerca entra fra le recenti.
 *
 * Aperta e fuoco sopravvivono alla ricreazione dell'attivita' (stato salvato); il testo e i risultati stanno nel controller (il suo ViewModel).
 */
@Stable
internal class MainSearch(
  private val controller: SearchController,
  private val openState: MutableState<Boolean>,
  private val focusState: MutableState<Boolean>,
) {
  /** La barra di ricerca e' aperta (sopra la scheda di adesso, con la Libreria dietro). */
  val open: Boolean get() = openState.value

  /** Il campo deve prendere il fuoco (una volta: [focused] consuma la richiesta). */
  val focus: Boolean get() = focusState.value

  fun openFromCircle() {
    // Avvio istantaneo: l'apertura di Cerca si misura dal tocco sul cerchio al primo fotogramma della barra aperta.
    if (!open) AvvioTrace.pageOpened(AvvioTrace.PAGE_SEARCH)
    focusState.value = true
    openState.value = true
    controller.onOpened()
  }

  fun close() {
    openState.value = false
    focusState.value = false
    controller.clear()
  }

  fun findSimilar(media: List<MediaId>) {
    focusState.value = false
    if (!open) AvvioTrace.pageOpened(AvvioTrace.PAGE_SEARCH)
    openState.value = true
    controller.onOpened()
    controller.findSimilar(media)
  }

  fun focused() {
    focusState.value = false
  }

  fun resultOpened() = controller.onResultOpened()
}

/** Il cablaggio della ricerca per [controller], con aperta e fuoco nello stato salvato. */
@Composable
internal fun rememberMainSearch(controller: SearchController): MainSearch {
  val open = rememberSaveable { mutableStateOf(false) }
  val focus = rememberSaveable { mutableStateOf(false) }
  return remember(controller, open, focus) { MainSearch(controller, open, focus) }
}
