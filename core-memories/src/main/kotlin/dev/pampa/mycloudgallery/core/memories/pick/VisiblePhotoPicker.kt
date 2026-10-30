package dev.pampa.mycloudgallery.core.memories.pick

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.PhotoPicker
import dev.pampa.mycloudgallery.core.memories.contract.PickResult
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PickSpec
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.coroutines.cancellation.CancellationException

/**
 * La scelta delle foto con il **filtro finale dei Nascosti** (D31, E-H8, C27): un media nascosto non entra mai in un ricordo e non e' mai
 * copertina.
 *
 * I candidati vengono gia' da `VisibleMedia`, ma un media puo' diventare nascosto fra la lettura dell'istantanea e la fine della scelta.
 * Qui si chiede la [HiddenMask] due volte: **prima** (i nascosti non partecipano a niente, nemmeno ai doppioni: non tolgono il posto a una
 * foto visibile) e **dopo** (sugli elementi scelti e sulla copertina). Se dopo la scelta qualcuno risulta nascosto, esce e si sceglie di nuovo
 * sul resto, fino a [MAX_ROUNDS] volte; se non si stabilizza il ricordo non nasce.
 *
 * **Fallisce chiuso** (come `HiddenMask`): se la maschera lancia un errore (non una cancellazione) prima della scelta tutti i candidati si
 * considerano nascosti, se lo lancia dopo la scelta il ricordo non nasce. Un nascosto mai, una foto in meno al massimo.
 *
 * I nascosti non si contano per motivo: `DropReason` non ha un motivo "nascosto" e il numero di nascosti di una Libreria e' gia' un'informazione
 * su di loro (modifica del contratto proposta nel resoconto di P7, non fatta qui).
 */
class VisiblePhotoPicker(
  private val picker: PhotoPicker,
  private val mask: HiddenMask,
) {

  suspend fun pick(candidates: List<CandidateCell>, spec: PickSpec, signals: PickSignals): PickResult {
    val first = hiddenAmong(candidates.map { it.id }) ?: return picker.pick(emptyList(), spec, signals)
    val hidden = HashSet<MediaId>(first)
    var last: PickResult? = null
    repeat(MAX_ROUNDS) {
      val visible = if (hidden.isEmpty()) candidates else candidates.filter { it.id !in hidden }
      val result = picker.pick(visible, spec, signals)
      if (!result.accepted) return result
      val now = hiddenAmong(result.members) ?: return PickResult.rejected(result.dropped)
      if (now.isEmpty()) return result
      hidden += now
      last = result
    }
    return PickResult.rejected(last?.dropped.orEmpty())
  }

  /** Quali di [ids] sono nascosti adesso; null se la maschera non risponde (guasto: chi chiama fallisce chiuso). */
  private suspend fun hiddenAmong(ids: List<MediaId>): Set<MediaId>? {
    if (ids.isEmpty()) return emptySet()
    return try {
      mask.hiddenAmong(ids)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }

  override fun toString(): String = "VisiblePhotoPicker"

  companion object {
    /** Quante volte si rifa la scelta se, a scelta finita, qualcuno risulta nascosto. */
    const val MAX_ROUNDS: Int = 3
  }
}
