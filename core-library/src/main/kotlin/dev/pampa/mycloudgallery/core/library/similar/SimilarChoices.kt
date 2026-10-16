package dev.pampa.mycloudgallery.core.library.similar

import dev.pampa.mycloudgallery.core.model.MediaId

// Fase 5A, P15 (docs/fase5-progetto.md §2.12, E-L13): le SCELTE DELL'UTENTE sulle copie simili ("Mostra questa come principale" e
// "Separa"), viste dal rilevatore. Sono dati personali sincronizzati fra i dispositivi dell'utente (core-personal, voci `MEDIA_FLAG`
// `similar_cover` e `similar_apart:<id>`): core-library non vede core-personal, quindi le legge e le scrive da questa porta, che `:app`
// lega a `SimilarCopiesChoices` di core-personal (stesse firme, tipi di core-model).

/**
 * La memoria delle scelte dell'utente. Le letture vedono anche le scelte fatte su questo dispositivo e non ancora datate (senza ora del
 * NAS non si pubblicano, ma valgono subito: un "Separa" fatto senza rete non si riforma al giro dopo).
 */
interface SimilarChoices {

  /**
   * La copia che l'utente ha scelto come principale fra [members], se ce n'e' una: se su piu' dispositivi o in momenti diversi ne ha
   * scelte due, vale la scelta piu' recente. Null se non ha mai scelto (o ha scelto un media che non e' qui).
   */
  suspend fun chosenCover(members: Collection<MediaId>): MediaId?

  /** Le coppie separate dall'utente con entrambi i media in [ids]. */
  suspend fun separatedPairs(ids: Collection<MediaId>): SeparatedPairs

  /**
   * "Mostra questa come principale": salva la scelta di [chosen] e toglie quella di [others] (le altre copie del gruppo che
   * l'avevano), cosi' la scelta successiva e' sempre un cambiamento vero e vince sull'ora.
   */
  suspend fun saveCover(chosen: MediaId, others: Collection<MediaId>)

  /** "Separa": [member] non e' una copia di nessuno di [others], per sempre e su tutti i dispositivi dell'utente. */
  suspend fun saveSeparation(member: MediaId, others: Collection<MediaId>)
}
