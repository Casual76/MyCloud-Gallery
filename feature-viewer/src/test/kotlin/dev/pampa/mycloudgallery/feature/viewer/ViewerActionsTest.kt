package dev.pampa.mycloudgallery.feature.viewer

import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.security.input.SensitiveClipboard
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * Il menu a capacita' (D13) e le azioni: **solo cio' che funziona**. Senza azioni locali non ci sono preferito e
 * nascondi, senza azioni di sistema non ci sono condividi, copia ed elimina; su Android 10 l'eliminazione non c'e'.
 * Ogni azione che esce dall'app (condividi, apri in Mappe, elimina) passa da un tocco dell'utente, e l'eliminazione
 * anche da un foglio che dice che sul NAS non c'e' ancora una copia (D14).
 */
class ViewerActionsTest : ViewerTestBase() {

  private val currentId get() = fixture.idAt(4)
  private val currentRef: MediaRef get() = fixture.items.ref(currentId)

  private fun openMenu() {
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText("Informazioni").fetchSemanticsNodes().isNotEmpty() }
  }

  private fun hasMenuItem(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  @Test
  fun conTuttiIServiziIlMenuHaTutteLeVociEdElencoInBassoLeQuattroAzioni() {
    show()
    for (description in listOf("Condividi", "Aggiungi ai preferiti", "Informazioni", "Elimina dal telefono")) {
      composeRule.onNodeWithContentDescription(description).assertExists()
    }
    openMenu()
    for (item in listOf("Aggiungi ai preferiti", "Nascondi", "Condividi", "Copia", "Informazioni", "Elimina dal telefono")) {
      assertThat(hasMenuItem(item)).isTrue()
    }
  }

  @Test
  fun senzaAzioniLocaliNonCiSonoPreferitoENascondi() {
    show(services = services(actions = null))
    composeRule.onAllNodesWithContentDescription("Aggiungi ai preferiti").assertCountEquals(0)
    openMenu()
    assertThat(hasMenuItem("Nascondi")).isFalse()
    assertThat(hasMenuItem("Aggiungi ai preferiti")).isFalse()
    assertThat(hasMenuItem("Condividi")).isTrue()
  }

  @Test
  fun senzaIlCestinoDiSistemaNonCeEliminaDalTelefono() {
    intents.canTrash = false
    show()
    composeRule.onAllNodesWithContentDescription("Elimina dal telefono").assertCountEquals(0)
    openMenu()
    assertThat(hasMenuItem("Elimina dal telefono")).isFalse()
  }

  @Test
  fun senzaAzioniDiSistemaNonCiSonoCondividiCopiaEElimina() {
    show(services = services(intents = null))
    composeRule.onAllNodesWithContentDescription("Condividi").assertCountEquals(0)
    openMenu()
    for (item in listOf("Condividi", "Copia", "Elimina dal telefono")) assertThat(hasMenuItem(item)).isFalse()
    assertThat(hasMenuItem("Informazioni")).isTrue()
  }

  @Test
  fun ilPreferitoSiAggiungeEResponde() {
    show()
    composeRule.onNodeWithContentDescription("Aggiungi ai preferiti").performClick()
    composeRule.waitForIdle()
    assertThat(actions.favorites).containsExactly(currentId)
    // La stella risponde subito, prima che l'indice confermi.
    composeRule.onNodeWithContentDescription("Togli dai preferiti").assertExists()
    composeRule.onNodeWithContentDescription("Togli dai preferiti").performClick()
    composeRule.waitForIdle()
    assertThat(actions.favorites).isEmpty()
  }

  @Test
  fun nascondiMandaLElementoAiNascostiEDiceDoveLoTrova() {
    show()
    openMenu()
    composeRule.onNodeWithText("Nascondi").performClick()
    composeRule.waitForIdle()
    assertThat(actions.hidden).containsExactly(currentId)
    // L'indice lo toglie dalla vista: il viewer passa al vicino e l'avviso dice dov'e' andato.
    fixture.items.publish(fixture.without(setOf(currentId)))
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Nascosto").assertExists()
    composeRule.onNodeWithText("Lo trovi in Raccolte, Nascosti.").assertExists()
  }

  /** Un catalogo che non scrive (disco pieno, database in errore): ogni azione solleva. */
  private class FailingActions : LocalMediaActions {
    var calls = 0

    override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) {
      calls++
      throw IllegalStateException("disco pieno")
    }

    override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
      calls++
      throw IllegalStateException("disco pieno")
    }
  }

  /** Un "Nascondi" che aspetta il via del test: il catalogo ha tolto la cella, ma la scrittura non e' ancora finita. */
  private class GatedActions(val gate: CompletableDeferred<Boolean>) : LocalMediaActions {
    override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = Unit

    override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
      check(gate.await()) { "scrittura fallita" }
    }
  }

  /** R8: se "Nascondi" non riesce lo dice, e non annuncia "Nascosto" (ne' prima ne' dopo). */
  @Test
  fun nascondiCheNonRiesceLoDiceENonAnnunciaNascosto() {
    val failing = FailingActions()
    show(services = services(actions = failing))
    openMenu()
    composeRule.onNodeWithText("Nascondi").performClick()
    composeRule.waitForIdle()

    assertThat(failing.calls).isEqualTo(1)
    composeRule.onNodeWithText("Non è stato possibile nascondere").assertExists()
    composeRule.onNodeWithText("L’elemento è ancora nella Libreria. Riprova fra poco.").assertExists()
    composeRule.onAllNodesWithText("Nascosto").assertCountEquals(0)
    // Anche se la vista cambiasse dopo (la ricostruzione), non e' mai "Nascosto".
    fixture.items.publish(fixture.without(setOf(currentId)))
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Nascosto").assertCountEquals(0)
  }

  /** R8: l'elemento sparisce dalla vista prima della fine della scrittura: l'avviso lo da' l'esito, non la sparizione. */
  @Test
  fun lAvvisoNascostoArrivaSoloAScritturaRiuscita() {
    val gate = CompletableDeferred<Boolean>()
    show(services = services(actions = GatedActions(gate)))
    openMenu()
    composeRule.onNodeWithText("Nascondi").performClick()
    composeRule.waitForIdle()
    // Il catalogo ha gia' tolto la cella dall'indice, la scrittura no.
    fixture.items.publish(fixture.without(setOf(currentId)))
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Nascosto").assertCountEquals(0)
    composeRule.onAllNodesWithText("Questo elemento non c’è più").assertCountEquals(0)

    gate.complete(true)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Nascosto").assertExists()
  }

  @Test
  fun unaScritturaCheFallisceDopoLaSparizioneDiceCheNonERiuscita() {
    val gate = CompletableDeferred<Boolean>()
    show(services = services(actions = GatedActions(gate)))
    openMenu()
    composeRule.onNodeWithText("Nascondi").performClick()
    composeRule.waitForIdle()
    fixture.items.publish(fixture.without(setOf(currentId)))
    composeRule.waitForIdle()

    gate.complete(false)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Non è stato possibile nascondere").assertExists()
    composeRule.onAllNodesWithText("Nascosto").assertCountEquals(0)
  }

  @Test
  fun unPreferitoNonSalvatoTornaComeEraEloDice() {
    val failing = FailingActions()
    show(services = services(actions = failing))
    composeRule.onNodeWithContentDescription("Aggiungi ai preferiti").performClick()
    composeRule.waitForIdle()

    assertThat(failing.calls).isEqualTo(1)
    composeRule.onNodeWithText("Preferiti non aggiornati").assertExists()
    // La stella non resta accesa: il cuore dice ancora "Aggiungi".
    composeRule.onNodeWithContentDescription("Aggiungi ai preferiti").assertExists()
    composeRule.onAllNodesWithContentDescription("Togli dai preferiti").assertCountEquals(0)
  }

  @Test
  fun mostraCheNonRiesceLoDiceEResta() {
    val failing = FailingActions()
    show(request = fixture.request(secure = true), services = services(actions = failing))
    openMenu()
    composeRule.onNodeWithText("Mostra nella Libreria").performClick()
    composeRule.waitForIdle()

    assertThat(failing.calls).isEqualTo(1)
    composeRule.onNodeWithText("Non è stato possibile mostrarlo di nuovo").assertExists()
    composeRule.onAllNodesWithText("Di nuovo nella Libreria").assertCountEquals(0)
  }

  @Test
  fun neiNascostiLaVoceERiportaNellaLibreria() {
    actions.hidden += currentId
    show(request = fixture.request(secure = true))
    openMenu()
    assertThat(hasMenuItem("Nascondi")).isFalse()
    composeRule.onNodeWithText("Mostra nella Libreria").performClick()
    composeRule.waitForIdle()
    assertThat(actions.hidden).isEmpty()
  }

  @Test
  fun condividiPassaDalleAzioniDiSistema() {
    show()
    composeRule.onNodeWithContentDescription("Condividi").performClick()
    composeRule.waitForIdle()
    assertThat(intents.shares).hasSize(1)
    assertThat(intents.shares.single()).containsExactly(currentRef)
    val started = shadowOf(composeRule.activity).nextStartedActivity
    assertThat(started.action).isEqualTo(Intent.ACTION_CHOOSER)
  }

  @Test
  fun copiaMetteL_UriNegliAppuntiENonMarcaSensibileUnMediaNormale() {
    show()
    openMenu()
    composeRule.onNodeWithText("Copia").performClick()
    composeRule.waitForIdle()
    val clip = composeRule.activity.getSystemService(ClipboardManager::class.java).primaryClip!!
    assertThat(clip.getItemAt(0).uri.toString()).isEqualTo("content://media/external_primary/1004")
    assertThat(clip.description.extras?.getBoolean(SensitiveClipboard.EXTRA_IS_SENSITIVE) ?: false).isFalse()
    composeRule.onNodeWithText("Copiato negli appunti").assertExists()
  }

  @Test
  fun copiaDaiNascostiMarcaGliAppuntiComeSensibili() {
    show(request = fixture.request(secure = true))
    openMenu()
    composeRule.onNodeWithText("Copia").performClick()
    composeRule.waitForIdle()
    val clip = composeRule.activity.getSystemService(ClipboardManager::class.java).primaryClip!!
    assertThat(clip.description.extras?.getBoolean(SensitiveClipboard.EXTRA_IS_SENSITIVE)).isTrue()
  }

  @Test
  fun eliminaChiedeConfermaEAvvisaCheSulNasNonCeUnaCopia() {
    show()
    composeRule.onNodeWithContentDescription("Elimina dal telefono").performClick()
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText("Eliminare la foto dal telefono?").fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNode(hasText("Non c’è ancora una copia sul NAS", substring = true)).assertExists()
    // Niente e' partito: serve la conferma.
    assertThat(intents.trashRequests).isEmpty()
    composeRule.onNode(hasText("Elimina dal telefono") and hasClickAction()).performClick()
    composeRule.waitForIdle()
    assertThat(intents.trashRequests).hasSize(1)
    val (refs, trash) = intents.trashRequests.single()
    assertThat(refs).containsExactly(currentRef)
    assertThat(trash).isTrue()
  }

  @Test
  fun annullaNonEliminaNiente() {
    show()
    composeRule.onNodeWithContentDescription("Elimina dal telefono").performClick()
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText("Annulla").fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithText("Annulla").performClick()
    composeRule.waitForIdle()
    assertThat(intents.trashRequests).isEmpty()
  }

  @Test
  fun apriInMappeChiedeConfermaEPoiLanciaGeo() {
    show()
    composeRule.onNodeWithTag(ViewerTags.Pager).performTouchInput { swipeUp() }
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText("Apri in Mappe").fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithText("Apri in Mappe").performClick()
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText("Aprire in Mappe?").fetchSemanticsNodes().isNotEmpty() }
    assertThat(shadowOf(composeRule.activity).peekNextStartedActivity()).isNull()
    // Il bottone del foglio e' l'ultimo in ordine di disegno (la riga delle informazioni sta sotto).
    composeRule.onAllNodes(hasText("Apri in Mappe") and hasClickAction()).onLast().performClick()
    composeRule.waitForIdle()
    val started = shadowOf(composeRule.activity).nextStartedActivity
    assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
    assertThat(started.data.toString()).isEqualTo("geo:59.912700,10.746100?q=59.912700,10.746100")
  }

  private companion object {
    const val TIMEOUT = 10_000L
  }
}
