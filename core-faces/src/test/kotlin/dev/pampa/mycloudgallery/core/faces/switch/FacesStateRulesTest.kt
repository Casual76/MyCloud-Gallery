package dev.pampa.mycloudgallery.core.faces.switch

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.model.FacesSetting
import org.junit.Test

/**
 * La tabella del §2.1 (docs/fase8-progetto.md) riga per riga, sulla funzione pura che porta la voce dell'account e lo stato locale nello stato
 * di questo dispositivo. L'interruttore vero la usa a ogni cambio; qui si prova senza orologi, file ne' flussi.
 */
class FacesStateRulesTest {

  private val g = SwitchRig.generation(1)
  private val other = SwitchRig.generation(2)

  private fun seen(version: Int = 1) = FacesLocal(noticedGeneration = g, noticedTextVersion = version, dataGeneration = g)

  private fun derive(setting: FacesSetting?, local: FacesLocal = FacesLocal.EMPTY, fenced: Boolean = false, current: Int = 1) =
    FacesStateRules.derive(setting, local, fenced, current)

  // --- Spenta ------------------------------------------------------------------------------------------------------------------------

  @Test
  fun voceAssenteOSpentaEPersoneSpenta() {
    assertThat(derive(null)).isEqualTo(FacesState.Off)
    assertThat(derive(FacesSetting.Off(g))).isEqualTo(FacesState.Off)
    // L'esclusione locale resta ricordata ma da spenta non si vede: la sezione Persone spiega cosa fa e propone di attivare.
    assertThat(derive(null, FacesLocal(excluded = true))).isEqualTo(FacesState.Off)
    assertThat(derive(FacesSetting.Off(g), FacesLocal(excluded = true))).isEqualTo(FacesState.Off)
  }

  // --- Attiva (D3) e recinzione (D34) -------------------------------------------------------------------------------------------------

  @Test
  fun attivaQuandoLAvvisoEVistoPerQuellaGenerazione() {
    assertThat(derive(FacesSetting.On(g, 1), seen())).isEqualTo(FacesState.On(g, HereState.ACTIVE))
    assertThat(derive(FacesSetting.On(g, 1), seen()).activeHere).isTrue()
  }

  @Test
  fun sulleShareVereLaRecinzioneTieneTuttoInLocale() {
    assertThat(derive(FacesSetting.On(g, 1), seen(), fenced = true)).isEqualTo(FacesState.On(g, HereState.LOCAL_ONLY))
    // Locale ma attiva: calcola e mostra su questo dispositivo (non sincronizza).
    assertThat(derive(FacesSetting.On(g, 1), seen(), fenced = true).activeHere).isTrue()
  }

  // --- Altri dispositivi dell'account (D4, E-F7) --------------------------------------------------------------------------------------

  @Test
  fun unaVoceArrivataDaUnAltroDispositivoAspettaLAvviso_NienteSiTratta() {
    val state = derive(FacesSetting.On(g, 1))
    assertThat(state).isEqualTo(FacesState.AwaitingNotice(g, 1))
    assertThat(state.activeHere).isFalse()
  }

  @Test
  fun l_AvvisoEPerGenerazione_DopoUnaRiattivazioneSiRivede() {
    // Disattiva e cancella e poi una riattivazione altrove (E-F33): generazione nuova, avviso nuovo.
    assertThat(derive(FacesSetting.On(other, 1), seen())).isEqualTo(FacesState.AwaitingNotice(other, 1))
  }

  @Test
  fun unAvvisoVistoSoloPerUnTestoPiuVecchioNonBasta() {
    val local = seen(version = 1)
    assertThat(derive(FacesSetting.On(g, 1), local, current = 2)).isEqualTo(FacesState.AwaitingNotice(g, 2))
    assertThat(derive(FacesSetting.On(g, 2), local, current = 2)).isEqualTo(FacesState.AwaitingNotice(g, 2))
  }

  // --- Esclusione (D4) ---------------------------------------------------------------------------------------------------------------

  @Test
  fun lEsclusioneVinceSuTutto_AnchePrimaDellAvvisoEDopoUnTestoNuovo() {
    val excluded = FacesLocal(excluded = true)
    assertThat(derive(FacesSetting.On(g, 1), excluded)).isEqualTo(FacesState.On(g, HereState.EXCLUDED))
    assertThat(derive(FacesSetting.On(g, 1), seen().copy(excluded = true))).isEqualTo(FacesState.On(g, HereState.EXCLUDED))
    assertThat(derive(FacesSetting.On(g, 1), seen().copy(excluded = true), current = 2)).isEqualTo(FacesState.On(g, HereState.EXCLUDED))
    // Un'app piu' nuova ha acceso con un testo che questa non conosce: un dispositivo escluso resta escluso, non chiede di aggiornare.
    assertThat(derive(FacesSetting.On(g, 9), excluded)).isEqualTo(FacesState.On(g, HereState.EXCLUDED))
    assertThat(derive(FacesSetting.On(g, 1), excluded).activeHere).isFalse()
  }

  // --- Testo cambiato ---------------------------------------------------------------------------------------------------------------

  @Test
  fun conUnTestoNuovoDellAppTuttiTornanoAllAvviso_ILDatiRestano() {
    val local = seen(version = 1)
    val state = derive(FacesSetting.On(g, 1), local, current = 2)
    assertThat(state).isEqualTo(FacesState.AwaitingNotice(g, 2))
    // I dati si ricordano ancora (lo stato locale non li tocca): il calcolo si ferma, ma niente si cancella.
    assertThat(local.dataGeneration).isEqualTo(g)
    // Riletto il testo nuovo (avviso visto per la versione 2) si torna attivi, anche se l'account ha ancora la versione vecchia.
    assertThat(derive(FacesSetting.On(g, 1), seen(version = 2), current = 2)).isEqualTo(FacesState.On(g, HereState.ACTIVE))
    assertThat(derive(FacesSetting.On(g, 2), seen(version = 2), current = 2)).isEqualTo(FacesState.On(g, HereState.ACTIVE))
  }

  // --- Voce sconosciuta (E-F12) --------------------------------------------------------------------------------------------------------

  @Test
  fun unaFormaSconosciutaEDaAggiornare_ENienteSiTrattaSiPubblicaOSiCancella() {
    assertThat(derive(FacesSetting.Unknown)).isEqualTo(FacesState.NeedsUpdate)
    assertThat(derive(FacesSetting.Unknown, seen())).isEqualTo(FacesState.NeedsUpdate)
    assertThat(derive(FacesSetting.Unknown, FacesLocal(excluded = true))).isEqualTo(FacesState.NeedsUpdate)
    assertThat(FacesState.NeedsUpdate.activeHere).isFalse()
    // Un testo di un'app piu' nuova (versione che questa non sa mostrare): stessa cosa.
    assertThat(derive(FacesSetting.On(g, 2), seen(), current = 1)).isEqualTo(FacesState.NeedsUpdate)
  }

  @Test
  fun soloUnaProvaEsplicitaDiceCheIDatiNonSonoPiuCorrenti() {
    assertThat(FacesStateRules.replacedBy(FacesSetting.Off(g), g)).isTrue()
    assertThat(FacesStateRules.replacedBy(FacesSetting.Off(other), g)).isTrue()
    assertThat(FacesStateRules.replacedBy(FacesSetting.On(other, 1), g)).isTrue()
    assertThat(FacesStateRules.replacedBy(FacesSetting.On(g, 1), g)).isFalse()
    // Nessuna prova: voce assente (store non ancora aperto, registro azzerato) o sconosciuta (app piu' nuova).
    assertThat(FacesStateRules.replacedBy(null, g)).isFalse()
    assertThat(FacesStateRules.replacedBy(FacesSetting.Unknown, g)).isFalse()
  }

  // --- Cancellazione in corso ---------------------------------------------------------------------------------------------------------

  @Test
  fun unaCancellazioneInCorsoVinceSulleVoci() {
    val erasing = seen().copy(erasing = g)
    for (setting in listOf(null, FacesSetting.Off(g), FacesSetting.On(g, 1), FacesSetting.On(other, 1), FacesSetting.Unknown)) {
      assertThat(derive(setting, erasing)).isEqualTo(FacesState.Erasing(g))
    }
    assertThat(derive(FacesSetting.On(g, 1), erasing).activeHere).isFalse()
  }

  @Test
  fun lavorareSuQuestoStatoNonMostraLaGenerazione() {
    // D37: mai la generazione nei testi (log, Diagnostica): i toString degli stati non la portano.
    assertThat(derive(FacesSetting.On(g, 1), seen()).toString()).doesNotContain(g.base32)
    assertThat(derive(FacesSetting.On(g, 1)).toString()).doesNotContain(g.base32)
    assertThat(seen().toString()).doesNotContain(g.base32)
  }

  /** "Avviso che blocca scaricamento e calcolo": il cancello dei lavori dice di no in ogni stato dove Persone non lavora, e di si' solo da attiva. */
  @Test
  fun ilCancelloDeiLavoriDiceDiNoDoveNonSiLavora() {
    val states = listOf(
      FacesState.Off, FacesState.AwaitingNotice(g, 1), FacesState.On(g, HereState.EXCLUDED), FacesState.NeedsUpdate, FacesState.Erasing(g),
      FacesState.On(g, HereState.ACTIVE), FacesState.On(g, HereState.LOCAL_ONLY),
    )
    val stops = states.map { FacesStateRules.stopReason(it) }
    assertThat(stops).containsExactly(
      FacesStop.NOT_ACTIVE, FacesStop.NOT_ACTIVE, FacesStop.NOT_ACTIVE, FacesStop.NOT_ACTIVE, FacesStop.ERASING, null, null,
    ).inOrder()
    val waits = states.map { FacesStateRules.waitReason(it) }
    assertThat(waits).containsExactly(null, FacesWait.NOTICE, FacesWait.EXCLUDED, FacesWait.NEEDS_UPDATE, FacesWait.ERASING, null, null).inOrder()
  }
}
