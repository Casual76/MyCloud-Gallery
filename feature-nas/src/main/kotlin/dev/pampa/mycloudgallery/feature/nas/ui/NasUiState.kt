package dev.pampa.mycloudgallery.feature.nas.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome

/**
 * Una frase che una pagina mostra sopra la lista dopo un gesto (l'esito di "Sposta", il motivo per cui un piano non parte) e
 * che l'utente chiude. Solo risorse e codici: mai un testo che viene dal NAS.
 *
 * @property opensOperations la frase ha il "Vedi" che porta a "Operazioni in corso" (l'operazione e' partita).
 */
@Immutable
internal data class UiNotice(
  @StringRes val text: Int,
  val tone: NasTone,
  val opensOperations: Boolean = false,
)

/** L'avviso per l'esito di un piano che non c'e': il motivo, in tono di allarme. */
internal fun noticeFor(rejected: PlanOutcome.Rejected): UiNotice = UiNotice(planRejectionRes(rejected.reason), NasTone.Alarm)

/** L'avviso per l'esito di `start`: "Operazione avviata" (con il "Vedi") o il motivo per cui non e' partita. */
internal fun noticeFor(outcome: StartOutcome): UiNotice = when (outcome) {
  is StartOutcome.Started -> UiNotice(startOutcomeRes(outcome), NasTone.Calm, opensOperations = true)
  is StartOutcome.AlreadyStarted -> UiNotice(startOutcomeRes(outcome), NasTone.Calm, opensOperations = true)
  else -> UiNotice(startOutcomeRes(outcome), NasTone.Alarm)
}
