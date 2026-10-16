package dev.pampa.mycloudgallery.core.library.noop

import dev.pampa.mycloudgallery.core.library.similar.ChoiceOutcome
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopies
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesGroup
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesProgress
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesReport
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fase 5 (D36), null-object del rilevatore delle copie simili finche' P15 non lo consegna: nessun gruppo, niente da confrontare.
 * "Nel dubbio non si unisce": senza rilevatore ogni copia resta una cella a se' (come prima della Fase 5), e il riepilogo del primo
 * backup non aspetta un confronto che non c'e'.
 */
object NoSimilarCopies : SimilarCopies {
  override val progress: StateFlow<SimilarCopiesProgress> = MutableStateFlow(SimilarCopiesProgress.SETTLED).asStateFlow()

  override suspend fun run(budget: Duration): SimilarCopiesReport = SimilarCopiesReport(0, 0, 0, 0, 0)

  override suspend fun groupOf(mediaId: MediaId): SimilarCopiesGroup? = null

  override suspend fun chooseCover(mediaId: MediaId): ChoiceOutcome = ChoiceOutcome.NOT_IN_GROUP

  override suspend fun separate(mediaId: MediaId): ChoiceOutcome = ChoiceOutcome.NOT_IN_GROUP
}
