package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidates
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.StateFlow

// I NASCOSTI NEI VIAGGI (docs/fase7-progetto.md D22, E-H7; docs/decisioni.md "Fase 7 P9, integrazione: le superfici vere di P3"). **Una sola
// fonte**: tutto cio' che i viaggi e gli eventi leggono o mostrano passa da qui. Le query dei candidati di P1 tolgono i nascosti ma non hanno il
// contagio dei gruppi (un membro non nascosto di un gruppo in cui un altro lo e'), quindi:
//  - la RILEVAZIONE parte solo da `VisibleMedia.visible` (un nascosto non crea un viaggio, non sposta la casa, non fa un evento);
//  - tutto cio' che si MOSTRA (conteggio, copertina) ripassa dal filtro finale `HiddenMask` subito prima: un media nascosto un attimo fa
//    (dopo la rilevazione) non compare ne' si conta.

internal class TripVisibility(private val visible: VisibleMedia, private val mask: HiddenMask) {

  /** L'istantanea della Libreria: cambia quando si nasconde o si aggiunge qualcosa, e le proposte si ricontrollano. */
  val snapshot: StateFlow<TimelineSnapshot?> get() = visible.snapshot

  /** Fra [ids] quelli che la Libreria mostra (presenti, non nascosti, non membri di un gruppo nascosto): l'ingresso della rilevazione. */
  suspend fun visibleAmong(ids: Collection<MediaId>): Set<MediaId> = if (ids.isEmpty()) emptySet() else visible.visible(ids)

  /** Fra [ids] quelli che non sono nascosti **adesso**: il filtro finale, subito prima di mostrare. */
  suspend fun withoutHidden(ids: Collection<MediaId>): Set<MediaId> =
    if (ids.isEmpty()) emptySet() else ids.toCollection(LinkedHashSet()).also { it.removeAll(mask.hiddenAmong(ids)) }
}

/**
 * La lettura dei candidati: i media **propri** (telefono di questo dispositivo e area privata del mio account, D18), non screenshot ne'
 * registrazioni dello schermo (E-AL4: non sono foto di un viaggio), visibili. In ordine di giorno e istante, come li da' la porta.
 */
internal class TripReader(private val candidates: AlbumCandidates, private val visibility: TripVisibility) {

  /**
   * Gli scatti propri visibili, per tutta la Libreria o per i soli [days]; con [finalMask] anche il filtro finale dei nascosti (per cio' che
   * si mostra). Fra una pagina e l'altra chiede se il tempo e' finito: [expired] vero = null (niente di parziale: una casa stimata su meta'
   * Libreria sarebbe sbagliata).
   */
  suspend fun mine(days: LocalDayRange? = null, finalMask: Boolean = false, expired: () -> Boolean = { false }): List<AlbumCandidate>? {
    val out = ArrayList<AlbumCandidate>()
    var cursor: CandidateCursor? = null
    do {
      if (expired()) return null
      val page = candidates.page(CandidateFilter(days = days, sources = MINE), cursor, PAGE)
      val kept = page.items.filter { it.mine && !it.screen }
      if (kept.isNotEmpty()) {
        val ids = kept.map { it.media }
        val shown = visibility.visibleAmong(ids).let { if (finalMask) visibility.withoutHidden(it) else it }
        for (c in kept) if (c.media in shown) out += c
      }
      // Una porta che restituisce sempre lo stesso cursore non deve far girare la rilevazione per sempre.
      if (page.next != null && page.next == cursor) break
      cursor = page.next
    } while (cursor != null)
    return out
  }

  private companion object {
    const val PAGE = 2_000
    val MINE: Set<SmartSource> = setOf(SmartSource.MY_PHONE, SmartSource.MY_PRIVATE)
  }
}
