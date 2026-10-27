package dev.pampa.mycloudgallery.feature.nas.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.feature.nas.R

/**
 * Una frase che una pagina mostra sopra la lista dopo un gesto (l'esito di "Sposta", il motivo per cui un piano non parte) e
 * che l'utente chiude. Solo risorse e codici: mai un testo che viene dal NAS.
 *
 * @property opensOperations la frase ha il "Vedi" che porta a "Operazioni in corso" (l'operazione e' partita).
 * @property counted correzioni F (R3): se c'e', al posto di [text] la frase e' un plurale col suo numero (quanti elementi sono rimasti dove sono perche' fanno
 * parte di un album condiviso in cui non si possono eliminare, spostare o rinominare). [text] resta una frase valida per chi non sa disegnare il numero.
 * Solo un conteggio, mai un nome.
 */
@Immutable
internal data class UiNotice(
  @StringRes val text: Int,
  val tone: NasTone,
  val opensOperations: Boolean = false,
  val counted: CountedText? = null,
)

/** Un plurale con il numero che lo sceglie e lo riempie. */
@Immutable
internal data class CountedText(@PluralsRes val plural: Int, val count: Int)

/** L'avviso per l'esito di un piano che non c'e': il motivo, in tono di allarme. */
internal fun noticeFor(rejected: PlanOutcome.Rejected): UiNotice = UiNotice(planRejectionRes(rejected.reason), NasTone.Alarm)

/**
 * L'avviso per l'esito di `start`: "Operazione avviata" (con il "Vedi") o il motivo per cui non e' partita. [leftOut] (R3, correzioni F): gli elementi che il piano
 * confermato ha lasciato dove sono perche' fanno parte di un album condiviso in cui l'utente non puo' toccarli; se ce ne sono, anche a operazione partita
 * l'avviso lo dice (una selezione mista: «avviata, ma N no, perche'...»).
 */
internal fun noticeFor(outcome: StartOutcome, leftOut: Int = 0): UiNotice = when {
  leftOut > 0 && (outcome is StartOutcome.Started || outcome is StartOutcome.AlreadyStarted) ->
    UiNotice(R.string.nas_started, NasTone.Attention, opensOperations = true, counted = CountedText(R.plurals.nas_started_partly_protected, leftOut))
  outcome is StartOutcome.Started -> UiNotice(startOutcomeRes(outcome), NasTone.Calm, opensOperations = true)
  outcome is StartOutcome.AlreadyStarted -> UiNotice(startOutcomeRes(outcome), NasTone.Calm, opensOperations = true)
  else -> UiNotice(startOutcomeRes(outcome), NasTone.Alarm)
}
