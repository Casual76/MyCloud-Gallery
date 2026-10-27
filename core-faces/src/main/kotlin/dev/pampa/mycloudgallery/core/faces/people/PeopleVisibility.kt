package dev.pampa.mycloudgallery.core.faces.people

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.StateFlow

/**
 * **L'unico punto** in cui le persone chiedono cosa si puo' mostrare (docs/fase8-progetto.md §2.9, D28, riga `PEOPLE` del registro dei
 * Nascosti): ogni conteggio, copertina, pagina, proposta, volto del viewer e voce del dizionario passa da [shown]. I volti dei media
 * nascosti **si calcolano e si raggruppano** (F6-Q6) ma non si mostrano mai: qui si decide.
 *
 * Il percorso e' sempre lo stesso, e il **filtro finale e' l'ultimo passo** (D22): i candidati -> [VisibleMedia.visible] (presenti, non
 * nascosti, non membri di un gruppo con un nascosto) -> [HiddenMask.hiddenAmong] subito prima di mostrare (un media nascosto un attimo fa,
 * dopo l'istantanea, non deve comparire) -> **solo celle dell'istantanea della Libreria** (una persona compare nella cella che ha il suo
 * volto: se la Libreria non ha ancora la cella, la persona non la vede ancora). Senza istantanea non e' mostrabile niente.
 */
class PeopleVisibility(private val visible: VisibleMedia, private val mask: HiddenMask) {

  /** L'istantanea della Libreria (senza nascosti, una cella per gruppo): le viste delle persone si costruiscono sopra di lei. */
  val snapshot: StateFlow<TimelineSnapshot?> get() = visible.snapshot

  /** Quali di [candidates] (celle) si possono mostrare adesso. Un errore delle fonti fallisce chiuso (niente e' mostrabile). */
  suspend fun shown(candidates: Collection<MediaId>): Set<MediaId> {
    if (candidates.isEmpty()) return emptySet()
    val snap = visible.snapshot.value ?: return emptySet()
    val seen = visible.visible(candidates)
    val masked = mask.hiddenAmong(seen)
    return (seen - masked).filterTo(HashSet()) { snap.indexOf(it) >= 0 }
  }

  /**
   * Come [shown], ma per il media **aperto** (il viewer): vero se l'utente puo' vedere [media] adesso. Dai Nascosti il viewer apre anche un
   * nascosto: li' la riga "Persone" c'e', ma questa risposta e' falsa e le pagine che ne nascono non mostrano mai altri nascosti.
   */
  suspend fun mayShow(media: MediaId): Boolean = shown(listOf(media)).isNotEmpty()
}
