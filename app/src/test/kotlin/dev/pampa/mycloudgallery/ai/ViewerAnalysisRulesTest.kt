package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.compute.UrgentWait
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.feature.viewer.menu.MediaAnalysisHold
import dev.pampa.mycloudgallery.feature.viewer.menu.MediaAnalysisKind
import org.junit.Test

/**
 * Pacchetto XF: le regole **pure** dello stato che il visore mostra. XS-6: il comando guarda la chiave **corrente**; XS-3 e XS-7: perche' una
 * richiesta non parte (verdetto di adesso per la richiesta non confermata, anteprime, altro dispositivo). Mutazioni nominate in ogni prova.
 */
class ViewerAnalysisRulesTest {

  private fun key(n: Int) = ContentKey(n.toString(16).padStart(64, '0'))

  private fun facts(
    current: ContentKey? = key(1),
    ready: Boolean = false,
    unsupported: Boolean = false,
    indexer: Boolean = true,
    modelMissing: Boolean = false,
    liveCurrent: ContentKey? = null,
    asked: Set<ContentKey> = emptySet(),
  ) = ViewerAnalysisRules.Facts(current, ready, unsupported, indexer, modelMissing, liveCurrent, asked)

  // ---- XS-6: la chiave corrente --------------------------------------------------------------------------------------------

  /** Una versione vecchia gia' analizzata non toglie il comando alla versione nuova: lo stato guarda solo la chiave corrente. */
  @Test
  fun laChiaveCorrenteNonAnalizzataResteInCodaAncheSeUnaVersioneVecchiaEPronta() {
    // `ready` e' il criterio del calcolo applicato alla chiave corrente: una versione vecchia non lo accende (la risolve `AppViewerAnalysis`
    // con la sola chiave corrente), quindi il caso e' "in coda" e il comando c'e'.
    assertThat(ViewerAnalysisRules.kindOf(facts(ready = false))).isEqualTo(MediaAnalysisKind.QUEUED)
    assertThat(ViewerAnalysisRules.kindOf(facts(ready = true))).isEqualTo(MediaAnalysisKind.READY)
  }

  @Test
  fun inAnalisiESoloQuandoIlMotoreLavoraLaChiaveCorrente() {
    val old = key(9)
    assertThat(ViewerAnalysisRules.kindOf(facts(liveCurrent = old))).isEqualTo(MediaAnalysisKind.QUEUED)
    assertThat(ViewerAnalysisRules.kindOf(facts(liveCurrent = key(1)))).isEqualTo(MediaAnalysisKind.ANALYZING)
    // Una richiesta fatta per una chiave vecchia non rende "prima" la nuova.
    assertThat(ViewerAnalysisRules.kindOf(facts(asked = setOf(old)))).isEqualTo(MediaAnalysisKind.QUEUED)
    assertThat(ViewerAnalysisRules.kindOf(facts(asked = setOf(key(1))))).isEqualTo(MediaAnalysisKind.FIRST)
  }

  @Test
  fun ilCasoSegueLOrdineDelleRegole() {
    assertThat(ViewerAnalysisRules.kindOf(facts(current = null))).isEqualTo(MediaAnalysisKind.UNKNOWN)
    assertThat(ViewerAnalysisRules.kindOf(facts(ready = true, unsupported = true))).isEqualTo(MediaAnalysisKind.READY)
    assertThat(ViewerAnalysisRules.kindOf(facts(unsupported = true))).isEqualTo(MediaAnalysisKind.UNSUPPORTED)
    assertThat(ViewerAnalysisRules.kindOf(facts(indexer = false))).isEqualTo(MediaAnalysisKind.NOT_HERE)
    assertThat(ViewerAnalysisRules.kindOf(facts(modelMissing = true))).isEqualTo(MediaAnalysisKind.MODEL_MISSING)
  }

  // ---- XS-3: la condizione vera, senza foglio -------------------------------------------------------------------------------

  /** Su dati mobili o in roaming la richiesta non confermata chiede conferma: nessun foglio, la capsula dice "parte sul Wi-Fi". */
  @Test
  fun unaConfermaPerDatiMobiliORoamingDiceParteSulWifi() {
    val mobile = RuleDecision.NeedsConfirmation(setOf(ConfirmationKind.MOBILE_DATA), estimate = null)
    val roaming = RuleDecision.NeedsConfirmation(setOf(ConfirmationKind.ROAMING, ConfirmationKind.MOBILE_DATA), estimate = null)
    assertThat(ViewerAnalysisRules.holdOf(null, mobile, AiStatus.Idle(null))).isEqualTo(MediaAnalysisHold.WIFI)
    assertThat(ViewerAnalysisRules.holdOf(null, roaming, AiStatus.Idle(null))).isEqualTo(MediaAnalysisHold.WIFI)
    // Il tetto superato non si puo' risolvere con il Wi-Fi: resta il motivo generico delle regole.
    val cap = RuleDecision.NeedsConfirmation(setOf(ConfirmationKind.OVER_CAP), estimate = null)
    assertThat(ViewerAnalysisRules.holdOf(null, cap, AiStatus.Idle(null))).isEqualTo(MediaAnalysisHold.RULES)
  }

  // ---- XS-7: il motivo della richiesta eseguita, di adesso -------------------------------------------------------------------

  /** Richiesta utente ferma per il caldo: dice "caldo" anche se l'ultimo stato del motore e' il generico "regole" (che prima la lasciava muta). */
  @Test
  fun ilCaldoDiUnaRichiestaUtenteSiDiceAncheConLoStatoGenericoRegole() {
    val hot = RuleDecision.Wait(WaitReason.Thermal(ThermalStatus.SEVERE))
    val status = AiStatus.Waiting(AiWaitReason.RULES)
    assertThat(ViewerAnalysisRules.holdOf(null, hot, status)).isEqualTo(MediaAnalysisHold.HOT)
  }

  /** Il verdetto di adesso vince sullo stato vecchio: raffreddato il telefono, "caldo" non resta attaccato. */
  @Test
  fun ilVerdettoDiAdessoVinceSuUnaPausaVecchia() {
    val oldStatus = AiStatus.Waiting(AiWaitReason.HOT)
    val canRunNow = RuleDecision.Run(RunScope.FULL, network = NetworkClass.HOME_WIFI)
    assertThat(ViewerAnalysisRules.holdOf(null, canRunNow, oldStatus)).isNull()
    // Solo senza verdetto si ripiega sull'ultimo stato, e solo per i suoi motivi specifici.
    assertThat(ViewerAnalysisRules.holdOf(null, null, oldStatus)).isEqualTo(MediaAnalysisHold.HOT)
    assertThat(ViewerAnalysisRules.holdOf(null, null, AiStatus.Waiting(AiWaitReason.RULES))).isNull()
    assertThat(ViewerAnalysisRules.holdOf(null, null, AiStatus.Idle(null))).isNull()
  }

  @Test
  fun leAnteprimeNonPronteEUnAltroDispositivoDiconoPerche() {
    val run = RuleDecision.Run(RunScope.FULL, network = NetworkClass.HOME_WIFI)
    // Quello che il motore ha visto della richiesta vince sul verdetto: le anteprime non ci sono, o l'unita' e' di un altro.
    assertThat(ViewerAnalysisRules.holdOf(UrgentWait.PREVIEW, run, AiStatus.Idle(null))).isEqualTo(MediaAnalysisHold.PREPARATION)
    assertThat(ViewerAnalysisRules.holdOf(UrgentWait.OTHER_DEVICE, run, AiStatus.Idle(null))).isEqualTo(MediaAnalysisHold.RULES)
    // Un guasto passeggero non e' un motivo: si ripiega sul verdetto.
    assertThat(ViewerAnalysisRules.holdOf(UrgentWait.RETRY, run, AiStatus.Idle(null))).isNull()
  }

  /**
   * XS2: l'attesa **vecchia** vista dall'ultima tappa (anteprime non pronte, unita' di un altro dispositivo) non copre il verdetto **di adesso** che ferma la
   * richiesta: se le regole la bloccano (caldo, conferma) si dice quello; l'attesa vale solo se le regole la lasciano partire. Mutazione: l'attesa vince sempre.
   */
  @Test
  fun unVerdettoCheFermaLaRichiestaVinceSuUnaAttesaVecchia() {
    val hot = RuleDecision.Wait(WaitReason.Thermal(ThermalStatus.SEVERE))
    assertThat(ViewerAnalysisRules.holdOf(UrgentWait.PREVIEW, hot, AiStatus.Idle(null))).isEqualTo(MediaAnalysisHold.HOT)
    val mobile = RuleDecision.NeedsConfirmation(setOf(ConfirmationKind.MOBILE_DATA), estimate = null)
    assertThat(ViewerAnalysisRules.holdOf(UrgentWait.OTHER_DEVICE, mobile, AiStatus.Idle(null))).isEqualTo(MediaAnalysisHold.WIFI)
  }

  @Test
  fun soloIlBufferLocaleELaRetePersaDiconoNas() {
    val local = RuleDecision.Run(RunScope.LOCAL_BUFFER_ONLY, network = NetworkClass.OFFLINE)
    assertThat(ViewerAnalysisRules.holdOf(null, local, AiStatus.Idle(null))).isEqualTo(MediaAnalysisHold.NAS)
    assertThat(ViewerAnalysisRules.holdOf(null, RuleDecision.Wait(WaitReason.NasUnreachable), AiStatus.Idle(null))).isEqualTo(MediaAnalysisHold.NAS)
  }
}
