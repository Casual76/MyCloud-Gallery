package dev.pampa.mycloudgallery.feature.update.ui

import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.feature.update.CheckState
import dev.pampa.mycloudgallery.feature.update.R
import dev.pampa.mycloudgallery.feature.update.UpdatesState
import dev.pampa.mycloudgallery.feature.update.install.InstallStep
import dev.pampa.mycloudgallery.feature.update.policy.UpdateProblem

// CHE COSA MOSTRA L'AGGIORNAMENTO, DA UNA TABELLA SOLA (pacchetto U; giro 1 delle critiche). La pagina, l'avviso e la pagina «versione fermata»
// disegnano lo stesso modello: la figura (l'icona dell'app con il suo segno, o l'anello che avanza), due parole, al piu' un tasto pieno e uno leggero.
// Pure, provate senza comporre (UpdatePanelModelTest): lo stato si dice una volta, e due posti non possono dire cose diverse della stessa installazione.
// Mai un'attesa senza tasti (UU-1): «Pronta» ha «Installa» e «Annulla»; dopo il passaggio ad Android «Sto installando», e «Pronta» solo se non arriva un
// esito (giro 2, UU2-1).

/** La figura: che cosa si vede al posto delle parole. */
internal enum class UpdateFigure {
  IDLE, CHECKING, UP_TO_DATE, UNAVAILABLE, FOUND, UNUSABLE, METERED, PERMISSION, DOWNLOADING, WORKING, READY, AWAITING, INSTALLED, FAILED,

  /** Nella pagina «versione fermata», senza una versione nuova: la correzione arriva (giro 2, UU2-7). Mai la spunta di «App aggiornata» li'. */
  COMING,
}

/** I tasti. */
internal enum class UpdateAction(@param:StringRes val label: Int) {
  CHECK(R.string.update_check_now),

  /** «Riprova» dopo un controllo non riuscito (giro 2, UU2-10): lo stesso controllo di «Controlla adesso». */
  RECHECK(R.string.update_retry),
  INSTALL(R.string.update_action),
  INSTALL_READY(R.string.update_install),
  CANCEL_READY(R.string.update_cancel),
  DOWNLOAD_METERED(R.string.update_download_anyway),
  CANCEL_METERED(R.string.update_cancel),
  ALLOW(R.string.update_allow),
  CANCEL_DOWNLOAD(R.string.update_cancel),
  RETRY(R.string.update_retry),
  CLOSE(R.string.update_close),
}

/**
 * Il modello: [progress] e' l'avanzamento dello scaricamento (0..1) o null; [problem] il perche' di un errore. [primaryFilled] falso quando il tasto
 * principale non e' la cosa da fare (UU-10: «Controlla adesso» con l'app gia' aggiornata e' un tasto leggero).
 */
internal data class UpdatePanelModel(
  val figure: UpdateFigure,
  val primary: UpdateAction? = null,
  val secondary: UpdateAction? = null,
  val progress: Float? = null,
  val problem: UpdateProblem? = null,
  val primaryFilled: Boolean = true,
)

internal object UpdatePanels {

  /**
   * Il modello di [state]. L'installazione, se c'e', viene prima del controllo; la domanda dei dati mobili prima di tutto. [stopped]: dentro la pagina
   * «versione fermata», dove «App aggiornata» contraddirebbe il titolo (UU2-7).
   */
  fun of(state: UpdatesState, stopped: Boolean = false): UpdatePanelModel {
    if (state.askMetered) return UpdatePanelModel(UpdateFigure.METERED, UpdateAction.DOWNLOAD_METERED, UpdateAction.CANCEL_METERED)
    when (val step = state.install) {
      InstallStep.Preparing -> return UpdatePanelModel(UpdateFigure.WORKING, secondary = UpdateAction.CANCEL_DOWNLOAD)
      is InstallStep.Downloading -> return UpdatePanelModel(UpdateFigure.DOWNLOADING, secondary = UpdateAction.CANCEL_DOWNLOAD, progress = step.progress)
      InstallStep.Verifying, InstallStep.Installing -> return UpdatePanelModel(UpdateFigure.WORKING)
      InstallStep.AwaitingUser -> return UpdatePanelModel(UpdateFigure.AWAITING)
      is InstallStep.Ready -> return UpdatePanelModel(UpdateFigure.READY, UpdateAction.INSTALL_READY, UpdateAction.CANCEL_READY)
      InstallStep.Installed -> return UpdatePanelModel(UpdateFigure.INSTALLED)
      InstallStep.NeedsPermission -> return UpdatePanelModel(UpdateFigure.PERMISSION, UpdateAction.ALLOW, UpdateAction.CLOSE)
      is InstallStep.Failed -> return failed(step.problem)
      null -> Unit
    }
    return when (val check = state.check) {
      CheckState.Idle -> UpdatePanelModel(UpdateFigure.IDLE, UpdateAction.CHECK)
      CheckState.Checking -> UpdatePanelModel(UpdateFigure.CHECKING)
      CheckState.UpToDate ->
        if (stopped) UpdatePanelModel(UpdateFigure.COMING, UpdateAction.CHECK) else UpdatePanelModel(UpdateFigure.UP_TO_DATE, UpdateAction.CHECK, primaryFilled = false)
      CheckState.Unavailable -> UpdatePanelModel(UpdateFigure.UNAVAILABLE, UpdateAction.RECHECK)
      is CheckState.Found -> UpdatePanelModel(UpdateFigure.FOUND, UpdateAction.INSTALL)
      is CheckState.Unusable -> UpdatePanelModel(UpdateFigure.UNUSABLE, UpdateAction.CHECK, problem = check.problem, primaryFilled = false)
    }
  }

  /** Un guasto: «Riprova» solo dove serve (UU-6); altrimenti «Chiudi» e la riga «Avvisa chi ti ha dato l'app». */
  private fun failed(problem: UpdateProblem): UpdatePanelModel =
    if (problem.retryable) {
      UpdatePanelModel(UpdateFigure.FAILED, UpdateAction.RETRY, UpdateAction.CLOSE, problem = problem)
    } else {
      UpdatePanelModel(UpdateFigure.FAILED, secondary = UpdateAction.CLOSE, problem = problem)
    }

  /** La frase di un errore, dell'app: i problemi dell'elenco sono una frase sola (chi usa l'app non pubblica); il resto ha la sua. */
  @StringRes
  fun problemText(problem: UpdateProblem): Int = when (problem) {
    UpdateProblem.CHECKSUM_MISSING,
    UpdateProblem.SIZE_MISSING,
    UpdateProblem.TOO_LARGE,
    UpdateProblem.BAD_NAME,
    UpdateProblem.BAD_VERSION,
    UpdateProblem.OTHER_SOURCE,
    -> R.string.update_problem_published
    UpdateProblem.DOWNLOAD_FAILED -> R.string.update_problem_download
    UpdateProblem.CHECKSUM_MISMATCH -> R.string.update_problem_checksum
    UpdateProblem.UNREADABLE -> R.string.update_problem_unreadable
    UpdateProblem.OTHER_APP -> R.string.update_problem_other_app
    UpdateProblem.WRONG_VERSION -> R.string.update_problem_wrong_version
    UpdateProblem.NOT_NEWER -> R.string.update_problem_not_newer
    UpdateProblem.OTHER_SIGNATURE -> R.string.update_problem_signature
    UpdateProblem.NO_SPACE -> R.string.update_problem_space
    UpdateProblem.BLOCKED -> R.string.update_problem_blocked
    UpdateProblem.INCOMPATIBLE -> R.string.update_problem_incompatible
    UpdateProblem.INSTALL_FAILED -> R.string.update_problem_install
  }
}
