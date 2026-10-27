package dev.pampa.mycloudgallery.feature.people.erase

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.DeviceAck
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.feature.people.LARGE_TEXT
import dev.pampa.mycloudgallery.feature.people.PeopleScreenTest
import dev.pampa.mycloudgallery.feature.people.contract.EraseSheetState
import org.junit.Test

/**
 * Il foglio e la pagina "Disattiva e cancella" (Fase 8, P8; §2.8, D29, D34, E-F34): cosa dice l'anteprima (nomi, album e scelta, cronologia, dispositivi
 * con chi e' spento e da quando, recinzione), la conferma costruita al tocco con l'anteprima mostrata, il pulsante spento senza anteprima o senza scelta,
 * l'avanzamento con ogni motivo d'attesa, la fine, i dispositivi che mancano. Accessibilita': TalkBack (descrizioni, scelta annunciata), bersagli da 48 dp e
 * nessuna sovrapposizione a 1x e a 1,3x, in chiaro e in scuro.
 */
class FacesEraseSheetTest : PeopleScreenTest() {

  private val phone = DeviceAck("Telefono di casa", "a".repeat(26), AckState.CONFIRMED, null, thisDevice = true)
  private val tablet = DeviceAck("Tablet del soggiorno", "b".repeat(26), AckState.WAITING, NOON_3_NOV, thisDevice = false)
  private val lost = DeviceAck("Telefono vecchio", "c".repeat(26), AckState.NOT_EXPECTED, null, thisDevice = false)

  /** Un dispositivo dismesso (non revocato) che non ha confermato: le sue teste sono congelate, tiene ferma la fine finche' non lo si revoca (L193). */
  private val retired = DeviceAck("Telefono in cassetto", "d".repeat(26), AckState.WAITING, NOON_3_NOV, thisDevice = false, dismissed = true)
  private val preview = EraseSheetState.Preview(4, albumsWithPeople = 2, historyWithNames = true, devices = listOf(phone, tablet, lost), choice = PeopleAlbumsChoice.KEEP_PHOTOS, localOnly = false)
  private val fenced = EraseSheetState.Preview(1, albumsWithPeople = 0, historyWithNames = false, devices = emptyList(), choice = null, localOnly = true)
  private val token = EraseSheetToken(previewDigest = 42, shownAtMonotonicNanos = 9_000)

  private class Calls {
    val confirmations = mutableListOf<FacesEraseConfirmation>()
    val choices = mutableListOf<PeopleAlbumsChoice>()
    var cancel = 0
    var reload = 0
    var close = 0
    var openDevices = 0
  }

  private fun sheet(state: EraseSheetState?, token: EraseSheetToken?, calls: Calls, withDevicesAction: Boolean = true) = @androidx.compose.runtime.Composable {
    FacesEraseSheet(
      state = state,
      token = token,
      onConfirm = { calls.confirmations += it },
      onChoose = { calls.choices += it },
      onCancel = { calls.cancel++ },
      onReload = { calls.reload++ },
      onClose = { calls.close++ },
      onBack = { calls.close++ },
      onOpenDevices = if (withDevicesAction) ({ calls.openDevices++ }) else null,
    )
  }

  private fun scroll(text: String) {
    // La pagina e' una lista pigra: quello che e' sotto non e' composto finche' non si scorre.
    composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))
  }

  @Test
  fun lAnteprimaDiceCosaSiPerdeEChiDeveConfermare() {
    showAll { sheet(preview, token, Calls())() }
    assertShows(
      "anteprima",
      "Cancella i volti e i nomi delle persone dal NAS e da tutti i tuoi dispositivi. Le foto non si toccano.",
      "Perderai i nomi di 4 persone.",
      "2 album smart usano le persone.",
      "Tieni le foto di adesso come album normali",
      "Togli solo la regola",
      "Le ricerche recenti con i nomi si cancellano.",
    )
    scroll("Telefono vecchio")
    assertShows("dispositivi", "Telefono di casa (questo dispositivo)", "Tablet del soggiorno", "Non si fa vivo dal 3 novembre", "Revocato o perso: non si aspetta")
    assertDoesNotShow("anteprima", "Le persone sono solo su questo dispositivo")
  }

  @Test
  fun conLaRecinzioneSiCancellaSoloQui() {
    showAll { sheet(fenced, token, Calls())() }
    assertShows("recinzione", "Le persone sono solo su questo dispositivo: si cancellano qui.", "Cancella i volti e i nomi delle persone. Le foto non si toccano.", "Perderai il nome di 1 persona.")
    assertDoesNotShow("recinzione", "I tuoi dispositivi", "album smart", "dal NAS")
    assertThat(spokenControls()).contains("Disattiva Persone e cancella i volti e i nomi da questo dispositivo")
  }

  @Test
  fun laConfermaNasceAlToccoConLAnteprimaMostrata() {
    val calls = Calls()
    showAll { sheet(preview, token, calls)() }
    clickText("Togli solo la regola")
    scroll("Annulla")
    assertThat(calls.choices).containsExactly(PeopleAlbumsChoice.DROP_RULE)
    clickConfirm()
    val confirmation = calls.confirmations.single()
    assertThat(confirmation.previewDigest).isEqualTo(42)
    assertThat(confirmation.shownAtMonotonicNanos).isEqualTo(9_000)
    clickText("Annulla", substring = false)
    assertThat(calls.cancel).isEqualTo(1)
  }

  @Test
  fun senzaAnteprimaOSenzaSceltaIlPulsanteNonConferma() {
    val calls = Calls()
    val show = switching<Pair<EraseSheetState.Preview, EraseSheetToken?>>(preview.copy(choice = null) to token) { (state, t) -> sheet(state, t, calls)() }
    scroll("Annulla")
    assertThat(confirmDisabled()).isTrue()
    show(preview to null)
    scroll("Annulla")
    assertThat(confirmDisabled()).isTrue()
    assertThat(calls.confirmations).isEmpty()
  }

  @Test
  fun avanzamentoConOgniMotivoEFine() {
    val messages = mapOf(
      EraseWait.NAS to "Aspetto il NAS",
      EraseWait.FROZEN to "congelata",
      EraseWait.CLOCK_JUMP to "L’ora del NAS è saltata",
      EraseWait.READ_ONLY to "sola lettura",
      EraseWait.EMERGENCY_STOP to "Le scritture sul NAS sono ferme",
      EraseWait.NOT_TRUSTED to "non è ancora approvato",
      EraseWait.DEVICES to "Manca la conferma",
      EraseWait.RECYCLE_BIN to "svuotalo dal pannello del NAS",
    )
    val first = EraseSheetState.Progress(StepState.DONE, StepState.WAITING, listOf(phone, tablet), EraseWait.NAS)
    val show = switching<EraseSheetState>(first) { state -> sheet(state, null, Calls())() }
    for ((wait, text) in messages) {
      show(first.copy(waiting = wait))
      assertShows(wait.code, "Su questo dispositivo", "Sul NAS", text)
    }
    show(EraseSheetState.Progress(StepState.DONE, StepState.NOT_NEEDED, emptyList(), null))
    assertShows("recinzione", "Non serve: niente sul NAS")
    show(EraseSheetState.Done)
    assertShows("fine", "Fatto ovunque")
    show(EraseSheetState.WaitingDevices(listOf(tablet)))
    assertShows("dispositivi mancanti", "Manca qualche dispositivo", "Tablet del soggiorno")
    show(EraseSheetState.Expired)
    assertShows("scaduta", "Il riepilogo è cambiato", "niente è stato cancellato")
    show(EraseSheetState.Started)
    assertShows("partita", "Cancellazione iniziata")
    show(EraseSheetState.Confirming)
    assertShows("in corso", "Inizio la cancellazione")
  }

  // --- Correzioni F, L193: il dismesso che tiene ferma la fine --------------------------------------------------------------------------------

  @Test
  fun unDismessoCheTieneFermaLaFineDiceCosaFareESenzaCambiareLAttesa() {
    val state = EraseSheetState.WaitingDevices(listOf(phone, retired))
    showAll { sheet(state, null, Calls())() }
    // La pagina continua ad aspettare (com'e'): lo stato e' lo stesso di sempre, la riga dice cosa fare.
    assertShows("dismesso", "Manca qualche dispositivo", "Telefono in cassetto", "Dismesso: se non lo userai più, revocalo per chiudere la cancellazione")
    assertDoesNotShow("dismesso", "cancellerà appena si riaccende", "Non si fa vivo dal 3 novembre")
  }

  @Test
  fun ilTastoPortaAImpostazioniDispositivi() {
    val calls = Calls()
    showAll { sheet(EraseSheetState.WaitingDevices(listOf(phone, retired)), null, calls)() }
    clickText("Apri Dispositivi", substring = false)
    assertThat(calls.openDevices).isEqualTo(1)
  }

  @Test
  fun unDispositivoNormaleOUnDismessoCheHaConfermatoNonHaNeFraseNeTasto() {
    // Un dispositivo spento che non e' dismesso: cancellera' appena si riaccende, niente tasto e niente frase del dismesso.
    val show = switching<EraseSheetState>(EraseSheetState.WaitingDevices(listOf(phone, tablet))) { state -> sheet(state, null, Calls())() }
    assertShows("spento", "Non si fa vivo dal 3 novembre")
    assertDoesNotShow("spento", "Dismesso:", "Apri Dispositivi")
    // Un dismesso che ha gia' confermato non tiene ferma niente.
    show(EraseSheetState.WaitingDevices(listOf(retired.copy(state = AckState.CONFIRMED), tablet)))
    assertDoesNotShow("dismesso confermato", "Dismesso:", "Apri Dispositivi")
  }

  @Test
  fun senzaLaRottaDelleImpostazioniLaFraseCeEIlTastoNo() {
    showAll { sheet(EraseSheetState.WaitingDevices(listOf(phone, retired)), null, Calls(), withDevicesAction = false)() }
    assertShows("senza rotta", "Dismesso: se non lo userai più, revocalo per chiudere la cancellazione")
    assertDoesNotShow("senza rotta", "Apri Dispositivi")
  }

  @Test
  fun ilDismessoSiVedeAncheNellAnteprimaENell_avanzamento() {
    val show = switching<EraseSheetState>(preview.copy(devices = listOf(phone, retired))) { state -> sheet(state, token, Calls())() }
    scroll("Dismesso:")
    assertShows("anteprima", "Dismesso: se non lo userai più, revocalo per chiudere la cancellazione", "Apri Dispositivi")
    show(EraseSheetState.Progress(StepState.DONE, StepState.WAITING, listOf(phone, retired), EraseWait.DEVICES))
    assertShows("avanzamento", "Manca la conferma di qualche dispositivo", "Dismesso: se non lo userai più, revocalo per chiudere la cancellazione", "Apri Dispositivi")
  }

  @Test
  fun talkBackDiceDoveAndareIlTastoDelDismesso() {
    showAll { sheet(EraseSheetState.WaitingDevices(listOf(phone, retired)), null, Calls())() }
    assertThat(spokenControls()).contains("Apre Impostazioni, Dispositivi, dove puoi revocare un dispositivo che non usi più")
  }

  @Test
  fun talkBackDiceCosaFannoIPulsantiEQualeSceltaC() {
    showAll { sheet(preview, token, Calls())() }
    val selected = composeRule.onAllNodes(hasText("Tieni le foto di adesso come album normali", substring = true)).fetchSemanticsNodes()
      .any { node -> generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.Selected) == true } }
    assertWithMessage("la scelta preselezionata si annuncia come scelta").that(selected).isTrue()
    scroll("Annulla")
    val spoken = spokenControls()
    assertThat(spoken).contains("Disattiva Persone e cancella i volti e i nomi dal NAS e da tutti i tuoi dispositivi")
    assertThat(spoken).contains("Chiude senza cancellare niente")
  }

  @Test
  fun bersagliDa48DpENessunaSovrapposizione() {
    checkScenes(null, *scenes())
  }

  @Test
  fun bersagliDa48DpENessunaSovrapposizioneATestoGrande() {
    checkScenes(LARGE_TEXT, *scenes())
  }

  @Test
  fun ilTemaScuroCompone() {
    show(dark = true) { sheet(preview, token, Calls())() }
    assertShows("scuro", "Perderai i nomi di 4 persone.")
  }

  private fun scenes(): Array<Pair<String, @androidx.compose.runtime.Composable () -> Unit>> {
    val calls = Calls()
    return arrayOf(
      "anteprima" to sheet(preview, token, calls),
      "recinzione" to sheet(fenced, token, calls),
      "avanzamento" to sheet(EraseSheetState.Progress(StepState.DONE, StepState.WAITING, listOf(phone, tablet, lost), EraseWait.FROZEN), null, calls),
      "dispositivi mancanti" to sheet(EraseSheetState.WaitingDevices(listOf(tablet)), null, calls),
      "dismesso che tiene ferma la fine" to sheet(EraseSheetState.WaitingDevices(listOf(phone, retired)), null, calls),
      "dismesso nell'anteprima" to sheet(preview.copy(devices = listOf(phone, retired)), token, calls),
      "fine" to sheet(EraseSheetState.Done, null, calls),
      "scaduta" to sheet(EraseSheetState.Expired, null, calls),
    )
  }

  private fun confirmDisabled(): Boolean {
    val confirm = composeRule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Disattiva Persone e cancella i volti e i nomi dal NAS e da tutti i tuoi dispositivi"))
      .fetchSemanticsNodes().single()
    return confirm.config.contains(SemanticsProperties.Disabled)
  }

  private fun clickConfirm() {
    val confirm = composeRule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Disattiva Persone e cancella i volti e i nomi dal NAS e da tutti i tuoi dispositivi"))
      .fetchSemanticsNodes().firstOrNull() ?: return
    val action = confirm.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.action ?: return
    composeRule.runOnUiThread { action() }
    composeRule.waitForIdle()
  }

  private companion object {
    /** Il 3 novembre a mezzogiorno UTC: lo stesso giorno in ogni fuso d'Europa. */
    const val NOON_3_NOV = 1_793_707_200_000L
  }
}
