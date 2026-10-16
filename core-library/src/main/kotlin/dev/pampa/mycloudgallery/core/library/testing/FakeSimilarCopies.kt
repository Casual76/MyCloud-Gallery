package dev.pampa.mycloudgallery.core.library.testing

import dev.pampa.mycloudgallery.core.library.similar.ChoiceOutcome
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopies
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesGroup
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesProgress
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesReport
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fase 5 (D36): il rilevatore delle copie simili finto, per la striscia del viewer (P15), il riepilogo del primo backup (P4, tramite
 * la porta di core-backup) e gli screenshot. I gruppi li mette il test ([put]); "Mostra questa come principale" e "Separa" li
 * applicano come farebbe quello vero (la scelta vince sulla regola, un gruppo con una copia sola si scioglie) e si ricordano in
 * [chosenCovers] e [separated] (al posto dei dati personali).
 */
class FakeSimilarCopies(initial: SimilarCopiesProgress = SimilarCopiesProgress.SETTLED) : SimilarCopies {

  private val groups = LinkedHashMap<MediaId, SimilarCopiesGroup>()

  /** Il confronto: il test lo fa avanzare ([MutableStateFlow.value]). */
  override val progress: MutableStateFlow<SimilarCopiesProgress> = MutableStateFlow(initial)

  /** Le copertine scelte dall'utente, in ordine. */
  val chosenCovers: MutableList<MediaId> = ArrayList()

  /** Le copie separate, in ordine. */
  val separated: MutableList<MediaId> = ArrayList()

  /** Quante volte e' girata la manutenzione. */
  var runs: Int = 0
    private set

  /** Mette (o sostituisce) un gruppo: ogni sua copia lo ritrova con [groupOf]. */
  fun put(group: SimilarCopiesGroup) {
    synchronized(groups) {
      groups.values.removeAll { it.id == group.id }
      for (copy in group.copies) groups[copy.mediaId] = group
    }
  }

  override suspend fun run(budget: Duration): SimilarCopiesReport {
    runs++
    val pending = progress.value.pending
    progress.value = SimilarCopiesProgress.SETTLED
    return SimilarCopiesReport(signed = pending, grouped = 0, dissolved = 0, ambiguous = 0, pending = 0)
  }

  override suspend fun groupOf(mediaId: MediaId): SimilarCopiesGroup? = synchronized(groups) { groups[mediaId] }

  override suspend fun chooseCover(mediaId: MediaId): ChoiceOutcome = synchronized(groups) {
    val group = groups[mediaId] ?: return ChoiceOutcome.NOT_IN_GROUP
    chosenCovers += mediaId
    val updated = group.copy(cover = mediaId, coverChosenByUser = true)
    for (copy in updated.copies) groups[copy.mediaId] = updated
    ChoiceOutcome.APPLIED
  }

  override suspend fun separate(mediaId: MediaId): ChoiceOutcome = synchronized(groups) {
    val group = groups[mediaId] ?: return ChoiceOutcome.NOT_IN_GROUP
    separated += mediaId
    groups.remove(mediaId)
    val rest = group.copies.filter { it.mediaId != mediaId }
    if (rest.size < 2) {
      rest.forEach { groups.remove(it.mediaId) }
    } else {
      val cover = if (group.cover == mediaId) rest.first().mediaId else group.cover
      val updated = group.copy(copies = rest, cover = cover, coverChosenByUser = group.coverChosenByUser && group.cover != mediaId)
      for (copy in rest) groups[copy.mediaId] = updated
    }
    ChoiceOutcome.APPLIED
  }
}
