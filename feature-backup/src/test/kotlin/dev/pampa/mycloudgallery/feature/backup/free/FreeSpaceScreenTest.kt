package dev.pampa.mycloudgallery.feature.backup.free

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTest
import org.junit.Test

/**
 * La pagina "Libera spazio" (`docs/fase5-progetto.md` §1.5 `FreeSpaceState`, §2.5, Q9, C13): ogni stato composto e provato, con l'avviso forte
 * in cima, i filtri, gli esclusi per motivo, e i tocchi che partono dai pulsanti giusti. Una finestra alta: la pagina scorre e il test vuole
 * vedere anche i pulsanti in fondo.
 */
class FreeSpaceScreenTest : ComposeBackupTallTest() {

  private class Calls {
    var confirm = 0
    var confirmNow = 0
    var cancel = 0
    var free = 0
    var reload = 0
    var done = 0
    val chosen = ArrayList<FreeSpaceKind>()
    val performed = ArrayList<BackupAction>()

    fun actions() = FreeSpaceActions(
      choose = { chosen += it },
      openConfirm = { confirm++ },
      openConfirmNow = { confirmNow++ },
      cancelVerification = { cancel++ },
      free = { free++ },
      reload = { reload++ },
      perform = { performed += it },
      done = { done++ },
    )
  }

  private val calls = Calls()
  private val current = mutableStateOf<FreeSpaceUi>(FreeSpaceUi.Idle)
  private val clockNow = mutableLongStateOf(0L)
  private var shown = false

  /** Mostra [state]; le chiamate dopo la prima cambiano solo lo stato (la scena si monta una volta sola per prova). */
  private fun showState(state: FreeSpaceUi, now: Long = 0L, fontScale: Float? = null) {
    if (shown) {
      composeRule.runOnIdle {
        current.value = state
        clockNow.longValue = now
      }
      composeRule.waitForIdle()
      return
    }
    shown = true
    current.value = state
    clockNow.longValue = now
    show(fontScale = fontScale) {
      FreeSpaceScreen(state = current.value, sheet = FreeSheet.None, taps = null, actions = calls.actions(), nowMs = clockNow.longValue, onBack = {})
    }
  }

  /** Il peso come lo scrive l'app: con lo spazio che non va a capo fra il numero e l'unita'. */
  private fun gb(text: String) = text.replace(" GB", "\u00A0GB")

  private fun summary(
    items: Int = 1_240,
    excluded: Map<FreeSpaceExclusion, Int> = FreeSpaceSamples.excludedSample,
    kind: FreeSpaceKind = FreeSpaceKind.ALL,
    needsGuardian: Int = 0,
  ) = FreeSpaceUi.Summary(FreeSpaceSamples.plan(items = items, excluded = excluded, needsGuardian = needsGuardian), kind)

  private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction())

  // --- Il riepilogo -------------------------------------------------------------------------------------------------------------

  @Test
  fun inCimaALRiepilogoCeLAvvisoForte() {
    showState(summary())
    composeRule.onNodeWithText("Il NAS non ha un backup esterno").assertExists()
    composeRule.onNodeWithText("Dopo, l’unica copia di queste foto e di questi video sarà sul NAS.", substring = true).assertExists()
    // L'avviso sta prima del riepilogo dei numeri.
    val warning = composeRule.onNodeWithText("Il NAS non ha un backup esterno").fetchSemanticsNode().boundsInRoot.top
    val numbers = composeRule.onNodeWithText("Si possono liberare", substring = true).fetchSemanticsNode().boundsInRoot.top
    assertThat(warning).isLessThan(numbers)
  }

  @Test
  fun ilRiepilogoDiceQuantiEQuantiGb() {
    showState(summary())
    // 1.240 elementi da 15 MB = 18,6 GB.
    composeRule.onNodeWithText(gb("Si possono liberare 19 GB")).assertExists()
    composeRule.onNodeWithText("1.240 foto e video sono già al sicuro sul NAS", substring = true).assertExists()
    composeRule.onNodeWithText("Gli ultimi 30 giorni restano sul telefono", substring = true).assertExists()
  }

  @Test
  fun iTreFiltriDelProgettoCiSono_EQuelloScelto_ESelezionato() {
    showState(summary(kind = FreeSpaceKind.VIDEOS_ONLY))
    composeRule.onNodeWithText("Tutto ciò che è al sicuro").assertExists()
    composeRule.onNodeWithText("Solo i video").assertExists()
    composeRule.onNodeWithText("Più vecchi di un anno").assertExists()
    composeRule.onNodeWithText("Più vecchi di un anno").performClick()
    composeRule.onNodeWithText("Solo i video").performClick()
    assertThat(calls.chosen).containsExactly(FreeSpaceKind.OLDER_THAN_A_YEAR, FreeSpaceKind.VIDEOS_ONLY).inOrder()
  }

  @Test
  fun gliEsclusiSiVedonoPerMotivoConIlNumero() {
    showState(summary())
    composeRule.onNodeWithText("Non li libero").assertExists()
    composeRule.onNodeWithText("Non ancora controllati sul NAS").assertExists()
    composeRule.onNodeWithText("12").assertExists()
    composeRule.onNodeWithText("Con una versione non al sicuro").assertExists()
    composeRule.onNodeWithText("In caricamento o con un problema").assertExists()
    composeRule.onNodeWithText("Degli ultimi giorni").assertExists()
    composeRule.onNodeWithText("214").assertExists()
  }

  @Test
  fun ogniMotivoDiEsclusioneHaIlSuoTesto() {
    val all = FreeSpaceExclusion.entries.associateWith { 1 }
    showState(summary(items = 3, excluded = all))
    val titles = listOf(
      "Non ancora controllati sul NAS", "Con una versione non al sicuro", "In caricamento o con un problema", "Lavori fermi",
      "Il NAS non risponde", "Fuori dalla cartella di prova", "Degli ultimi giorni", "La copia sul NAS è cambiata", "Non sono più sul telefono",
    )
    assertThat(titles).hasSize(FreeSpaceExclusion.entries.size)
    for (title in titles) composeRule.onNodeWithText(title).assertExists()
  }

  @Test
  fun iMediaConLaSolaCopiaComuneSiDiconoAParte() {
    showState(summary(needsGuardian = 40))
    composeRule.onNodeWithText("nell’area comune: 40", substring = true).assertExists()
  }

  @Test
  fun iDuePulsantiApronoIDueFogli_EIlTestoSpiegaLaDifferenza() {
    showState(summary())
    button("Libera spazio").assertIsEnabled().performClick()
    button("Libera subito…").assertIsEnabled().performClick()
    assertThat(calls.confirm).isEqualTo(1)
    assertThat(calls.confirmNow).isEqualTo(1)
    composeRule.onNodeWithText("Libera spazio mette i file nel cestino di Android per 30 giorni. Libera subito li toglie per sempre.").assertExists()
  }

  @Test
  fun senzaNienteDaLiberareIPulsantiSonoSpenti() {
    showState(summary(items = 0, excluded = mapOf(FreeSpaceExclusion.RECENT to 30)))
    composeRule.onNodeWithText("Per ora non c’è niente da liberare").assertExists()
    button("Libera spazio").assertIsNotEnabled()
    button("Libera subito…").assertIsNotEnabled()
    // L'avviso forte c'e' sempre, anche quando non c'e' niente da fare.
    composeRule.onNodeWithText("Il NAS non ha un backup esterno").assertExists()
  }

  // --- I passi ----------------------------------------------------------------------------------------------------------------

  @Test
  fun idle_DiceCheStaContando() {
    showState(FreeSpaceUi.Idle)
    composeRule.onNodeWithText("Sto contando cosa si può liberare…").assertExists()
  }

  @Test
  fun ilGuardianoDiceQuantiHaFatto() {
    showState(FreeSpaceUi.Guarding(12, 40))
    composeRule.onNodeWithText("Preparo le copie private").assertExists()
    composeRule.onNodeWithText("12 di 40").assertExists()
    button("Annulla").performClick()
    assertThat(calls.cancel).isEqualTo(1)
  }

  @Test
  fun laVerificaDiceQuantoHaLettoEPuoEssereAnnullata() {
    showState(FreeSpaceUi.Verifying(340, 1_240, 4_200_000_000L))
    composeRule.onNodeWithText("Ricontrollo le copie sul NAS").assertExists()
    composeRule.onNodeWithText(gb("340 di 1.240 · 4,2 GB letti")).assertExists()
    composeRule.onNodeWithText("Può durare un po’. Lascia aperta questa pagina.").assertExists()
    button("Annulla").performClick()
    assertThat(calls.cancel).isEqualTo(1)
  }

  @Test
  fun pronto_DiceQuantiEPerQuantoVale_ELAvvisoSiRipete() {
    val validUntil = 6L * 60 * 60 * 1000
    showState(FreeSpaceUi.Ready(0, 1, 1_240, 18_600_000_000L, permanent = false, validUntilMonotonicMs = validUntil), now = 60_000L)
    composeRule.onNodeWithText(gb("Pronto: 1.240 elementi, 19 GB")).assertExists()
    composeRule.onNodeWithText("La verifica vale ancora circa 5 h 59 min.", substring = true).assertExists()
    composeRule.onNodeWithText("Il NAS non ha un backup esterno").assertExists()
    button("Libera ora").performClick()
    assertThat(calls.free).isEqualTo(1)
  }

  @Test
  fun conPiuLottiIlPulsanteDicaQualeEIlSuccessivoVuoleUnAltroTocco() {
    showState(FreeSpaceUi.Ready(0, 3, 4_500, 4_500_000L, permanent = false, validUntilMonotonicMs = null))
    composeRule.onNodeWithText("Lotto 1 di 3").assertExists()
    button("Libera ora").assertExists()
    showState(FreeSpaceUi.Ready(1, 3, 2_500, 2_500_000L, permanent = false, validUntilMonotonicMs = null))
    composeRule.onNodeWithText("Lotto 2 di 3").assertExists()
    button("Continua con il prossimo lotto").performClick()
    assertThat(calls.free).isEqualTo(1)
  }

  @Test
  fun liberaSubitoDicaCheNonSiTornaIndietro() {
    showState(FreeSpaceUi.Ready(0, 1, 10, 150_000_000L, permanent = true, validUntilMonotonicMs = null))
    composeRule.onNodeWithText("Libera subito non si può annullare").assertExists()
    composeRule.onNodeWithText("Il telefono ti chiede il permesso di toglierli per sempre.", substring = true).assertExists()
    button("Elimina per sempre").performClick()
    assertThat(calls.free).isEqualTo(1)
  }

  @Test
  fun mentreIlTelefonoChiedeIlPermessoLaPaginaAspetta() {
    showState(FreeSpaceUi.Confirming(2, 3, permanent = false))
    composeRule.onNodeWithText("Aspetto la tua risposta").assertExists()
    composeRule.onNodeWithText("Il telefono ti chiede il permesso (lotto 2 di 3).").assertExists()
  }

  // --- La fine ------------------------------------------------------------------------------------------------------------------

  @Test
  fun fattoNelCestinoDiAndroidDiceCheSiRipristina() {
    showState(FreeSpaceUi.Done(items = 1_240, freedBytes = 18_600_000_000L, toAndroidTrash = true, kept = 0, dropped = 0, cancelled = false))
    composeRule.onNodeWithText(gb("Liberati 19 GB")).assertExists()
    composeRule.onNodeWithText("Sono nel cestino di Android per 30 giorni", substring = true).assertExists()
    button("Fatto").performClick()
    assertThat(calls.done).isEqualTo(1)
  }

  @Test
  fun fattoSubitoDiceCheSonoTolti() {
    showState(FreeSpaceUi.Done(items = 10, freedBytes = 150_000_000L, toAndroidTrash = false, kept = 0, dropped = 0, cancelled = false))
    composeRule.onNodeWithText("Sono stati tolti per sempre dal telefono.", substring = true).assertExists()
  }

  @Test
  fun fattoDiceQuantiSonoRimastiEQuantiSonoUscitiPerLaCopia() {
    showState(FreeSpaceUi.Done(items = 1_200, freedBytes = 18_000_000_000L, toAndroidTrash = true, kept = 30, dropped = 10, cancelled = false))
    composeRule.onNodeWithText("30 sono rimasti sul telefono.", substring = true).assertExists()
    composeRule.onNodeWithText("Per 10 la copia sul NAS non tornava", substring = true).assertExists()
  }

  @Test
  fun seNienteHaSuperatoIControlliLoDice() {
    showState(FreeSpaceUi.Done(items = 0, freedBytes = 0, toAndroidTrash = true, kept = 0, dropped = 12, cancelled = false))
    composeRule.onNodeWithText("Non ho liberato niente").assertExists()
    composeRule.onNodeWithText("Per 12 la copia sul NAS non tornava", substring = true).assertExists()
  }

  @Test
  fun seLUtenteRifiutaIlDialogoNonEraCambiatoNiente() {
    showState(FreeSpaceUi.Done(items = 0, freedBytes = 0, toAndroidTrash = true, kept = 0, dropped = 0, cancelled = true))
    composeRule.onNodeWithText("Non è cambiato niente").assertExists()
    composeRule.onNodeWithText("Hai detto di no al telefono. Nessun file è stato tolto.").assertExists()
  }

  // --- Quando non si puo' ---------------------------------------------------------------------------------------------------------

  @Test
  fun ogniBloccoHaIlSuoPercheEQuandoServeIlSuoTasto() {
    val expected = mapOf(
      FreeSpaceBlockedBy.AREA_FROZEN to ("Lavori fermi" to "Apri la Diagnostica"),
      FreeSpaceBlockedBy.NAS_UNREACHABLE to ("Il NAS non risponde" to "Riprova"),
      FreeSpaceBlockedBy.CONFIRMATION_REJECTED to ("Qualcosa è cambiato" to "Riprova"),
      FreeSpaceBlockedBy.NOT_AVAILABLE to ("Non è ancora disponibile" to null),
      FreeSpaceBlockedBy.NO_SYSTEM_DIALOG to ("Su questo telefono non si può" to null),
      FreeSpaceBlockedBy.EXPIRED to ("La verifica è scaduta" to "Ricomincia"),
    )
    assertThat(expected.keys).containsExactlyElementsIn(FreeSpaceBlockedBy.entries)
    for ((reason, texts) in expected) {
      showState(FreeSpaceUi.Blocked(reason))
      composeRule.onNodeWithText(texts.first).assertExists()
      val action = texts.second
      if (action != null) button(action).assertExists()
    }
  }

  @Test
  fun iTastiDeiBlocchiPassanoPerLeAzioniGiuste() {
    showState(FreeSpaceUi.Blocked(FreeSpaceBlockedBy.AREA_FROZEN))
    button("Apri la Diagnostica").performClick()
    assertThat(calls.performed).containsExactly(BackupAction.DIAGNOSTICS)
    showState(FreeSpaceUi.Blocked(FreeSpaceBlockedBy.EXPIRED))
    button("Ricomincia").performClick()
    assertThat(calls.reload).isEqualTo(1)
  }
}
