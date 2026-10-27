package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * La risposta del selettore dei media a chi l'ha chiesto (Fase 7, revisione di fine fase, AA-3). Chi apre il selettore ("Aggiungi foto" di un album, le foto
 * d'esempio di uno smart) lascia qui una richiamata, in memoria; il selettore e' una rotta, e una rotta sopravvive alla morte del processo (Android la
 * rimette in pila) mentre la richiamata no. Prima, dopo la morte del processo, "Scegli" tornava indietro senza dire niente e la scelta andava persa.
 *
 * Adesso ci sono tre esiti:
 *  - [PickOutcome.Delivered]: la richiamata c'era, ha ricevuto la scelta;
 *  - [PickOutcome.Recovered]: la richiamata e' persa ma il selettore era "Aggiungi foto" a un album ([forAlbum] e' nella rotta, che sopravvive): la scelta
 *    diventa una richiesta "Aggiungi ad album" con l'album gia' scelto (un personale riceve subito, un condiviso apre la conferma col conteggio, D12);
 *  - [PickOutcome.Lost]: la richiamata e' persa e la scelta non si puo' ricostruire (le foto d'esempio di uno smart stanno nello stato di una schermata che
 *    non c'e' piu'): la schermata dice «La scelta non vale piu', riprova» invece di tacere.
 */
class PickRelay(private val requests: AlbumRequests) {
  private var callback: ((List<MediaId>) -> Unit)? = null

  /** Il selettore sta per aprirsi: [onPicked] riceve' la scelta (una volta sola). */
  fun start(onPicked: (List<MediaId>) -> Unit) {
    callback = onPicked
  }

  /** La scelta del selettore. [forAlbum] e' l'album della rotta ("Aggiungi foto"), null per le foto d'esempio. */
  fun picked(ids: List<MediaId>, forAlbum: String?): PickOutcome {
    val target = callback
    callback = null
    if (ids.isEmpty()) return PickOutcome.Nothing
    if (target != null) {
      target(ids)
      return PickOutcome.Delivered
    }
    val album = forAlbum?.let { runCatching { AlbumId(it) }.getOrNull() }
    if (album != null && requests.add(ids, album = album) != null) return PickOutcome.Recovered
    return PickOutcome.Lost
  }

  fun cancel() {
    callback = null
  }
}

/** Com'e' finita la scelta del selettore. */
enum class PickOutcome { Nothing, Delivered, Recovered, Lost }
