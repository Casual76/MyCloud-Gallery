package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.MediaId

// I NASCOSTI MAI (D14, D22, E-AL8, E-H1; docs/decisioni.md "Fase 7 P9, integrazione: le superfici vere di P3"). Le query dei candidati di P1
// **non** hanno il contagio dei gruppi nascosti: un gruppo con un membro nascosto a meta' (un dato arrivato da un altro dispositivo) non lo
// toglie nessuna query. Per questo **ogni** candidato passa da qui prima di contare qualcosa (anche come punto fermo della regola B, anche per
// trovare il centro di un luogo o le serie di un viaggio): [VisibleMedia.visible] (presente, non nascosto, non membro di un gruppo nascosto).
// Quel che si **scrive** (un elemento, un suggerito) passa anche dal filtro finale [HiddenMask], subito prima della scrittura: un media nascosto
// un attimo fa non diventa un elemento. Un errore di uno dei due non diventa "tutto visibile": l'eccezione risale e il motore non scrive niente.

internal class VisibleCandidates(
  private val source: SmartCandidates,
  private val visible: VisibleMedia,
  private val mask: HiddenMask,
) {
  /** Dei candidati [items] solo quelli che si possono mostrare adesso (`VisibleMedia`). */
  suspend fun shown(items: List<SmartCandidate>): List<SmartCandidate> {
    if (items.isEmpty()) return items
    val ok = visible.visible(items.map { it.media })
    return if (ok.size == items.size) items else items.filter { it.media in ok }
  }

  /**
   * Di [ids] (candidati gia' passati da `VisibleMedia`) quelli che si possono scrivere adesso: il filtro finale `HiddenMask`. Un id che il catalogo
   * non conosce piu' conta come nascosto (fallire chiusi), quindi un media sparito nel frattempo non arriva mai a una riga con la chiave esterna.
   */
  suspend fun shownFinal(ids: Collection<MediaId>): Set<MediaId> {
    if (ids.isEmpty()) return emptySet()
    return ids.toSet() - mask.hiddenAmong(ids)
  }

  suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): SmartCandidatePage =
    source.page(filter, after, limit).let { SmartCandidatePage(shown(it.items), it.next) }

  suspend fun placePage(places: Set<Int>, after: CandidateCursor?, limit: Int): SmartCandidatePage =
    source.placePage(places, after, limit).let { SmartCandidatePage(shown(it.items), it.next) }

  suspend fun candidatesOf(media: Collection<MediaId>): List<SmartCandidate> = shown(source.candidatesOf(media))

  suspend fun daysOf(media: Collection<MediaId>) = source.daysOf(media)

  suspend fun visibleTotal(): Int = source.visibleTotal()

  /** Tutti i candidati di [filter], pagina per pagina (per chi li vuole tutti: le serie di un viaggio, un'anteprima). */
  suspend fun all(filter: CandidateFilter, pageSize: Int = SmartTuning.PAGE): List<SmartCandidate> {
    val out = ArrayList<SmartCandidate>()
    var after: CandidateCursor? = null
    while (true) {
      val page = page(filter, after, pageSize)
      out += page.items
      after = page.next ?: break
    }
    return out
  }
}
