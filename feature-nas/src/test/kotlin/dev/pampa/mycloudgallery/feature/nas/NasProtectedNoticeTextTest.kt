package dev.pampa.mycloudgallery.feature.nas

import androidx.compose.ui.test.onAllNodesWithText
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.feature.nas.ui.NasNoticeLine
import dev.pampa.mycloudgallery.feature.nas.ui.NasTone
import dev.pampa.mycloudgallery.feature.nas.ui.UiNotice
import dev.pampa.mycloudgallery.feature.nas.ui.noticeFor
import org.junit.Test

/**
 * Le frasi che Cartelle NAS dice quando un file fa parte di un album condiviso in cui non si puo' eliminare, spostare o rinominare (Fase 7, correzioni F, R3,
 * risposta dell'utente del 27/10): che l'operazione **non e' riuscita**, **perche'** e, se la selezione era mista, **quanti** no. Il testo e' quello che l'utente
 * legge; la logica che lo sceglie la prova `NasFoldersProtectedTest`.
 */
class NasProtectedNoticeTextTest : ComposeNasTest() {

  private fun shows(text: String): Boolean = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

  private fun assertShows(scene: String, vararg texts: String) {
    for (text in texts) assertWithMessage("«$text» manca in $scene").that(shows(text)).isTrue()
  }

  @Test
  fun ilRifiutoDiUnFileDiUnAlbumCondivisoDiceCheNonERiuscitoEPerche() {
    val notice = noticeFor(PlanOutcome.Rejected(PlanRejection.SHARED_ALBUM_ITEM))
    show { NasNoticeLine(notice = notice, onDismiss = {}) }

    assertShows(
      "rifiuto",
      "Non è riuscito",
      "album condiviso",
      "non puoi eliminarlo, spostarlo o rinominarlo",
      "chi ha creato l’album",
    )
  }

  @Test
  fun unaSelezioneMistaDiceQuantiNoEPerche_alSingolare() {
    val one = UiNotice(R.plurals.nas_started_partly_protected, NasTone.Attention, opensOperations = true, count = 1)
    show { NasNoticeLine(notice = one, onDismiss = {}, onOpenOperations = {}) }

    assertShows("singolare", "Operazione avviata, ma 1 elemento no", "fa parte di un album condiviso", "resta dov’è", "Operazioni in corso")
  }

  @Test
  fun unaSelezioneMistaDiceQuantiNoEPerche_alPlurale() {
    val many = UiNotice(R.plurals.nas_started_partly_protected, NasTone.Attention, opensOperations = true, count = 12)
    show { NasNoticeLine(notice = many, onDismiss = {}, onOpenOperations = {}) }

    assertShows("plurale", "Operazione avviata, ma 12 elementi no", "fanno parte di un album condiviso", "restano dove sono", "non puoi eliminarli, spostarli o rinominarli")
  }
}
