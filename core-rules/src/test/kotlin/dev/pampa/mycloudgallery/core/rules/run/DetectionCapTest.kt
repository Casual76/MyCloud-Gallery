package dev.pampa.mycloudgallery.core.rules.run

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.EXTERNAL_WIFI_VPN_NET
import dev.pampa.mycloudgallery.core.rules.GB
import dev.pampa.mycloudgallery.core.rules.MB
import dev.pampa.mycloudgallery.core.rules.MOBILE_VPN_NET
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.Pace
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RuleEvaluator
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.ruleSetOf
import dev.pampa.mycloudgallery.core.rules.state
import dev.pampa.mycloudgallery.core.rules.usage
import org.junit.Test

/** Il tetto del rilevamento fuori casa (D22, §2.3, §2.8): 50 MB per giro fuori dal Wi-Fi di casa, nessun tetto a casa. */
class DetectionCapTest {

  private val detect = WorkRequest.Background(BackgroundActivity.INDEX_SYNC)

  private fun run(network: NetworkClass, remaining: Long? = null, scope: RunScope = RunScope.FULL) =
    RuleDecision.Run(scope, Pace.NORMAL, network, remaining)

  @Test
  fun sonoCinquantaMegabyte() {
    assertThat(DetectionCap.OFF_HOME_BYTES_PER_ROUND).isEqualTo(50 * MB)
  }

  @Test
  fun perRete_acasaNienteTetto_fuoriCasaCinquantaMega_senzaReteZero() {
    assertThat(DetectionCap.forNetwork(NetworkClass.HOME_WIFI)).isNull()
    assertThat(DetectionCap.forNetwork(NetworkClass.EXTERNAL_WIFI)).isEqualTo(50 * MB)
    assertThat(DetectionCap.forNetwork(NetworkClass.MOBILE)).isEqualTo(50 * MB)
    assertThat(DetectionCap.forNetwork(NetworkClass.ROAMING)).isEqualTo(50 * MB)
    assertThat(DetectionCap.forNetwork(NetworkClass.OFFLINE)).isEqualTo(0L)
    assertThat(DetectionCap.forNetwork(NetworkClass.UNTRUSTED)).isEqualTo(0L)
  }

  @Test
  fun ilTettoMensileResta_vinceIlPiuBasso() {
    // 30 MB nel tetto dei dati mobili: meno dei 50 MB del giro.
    assertThat(DetectionCap.byteCap(detect, run(NetworkClass.MOBILE, remaining = 30 * MB))).isEqualTo(30 * MB)
    // 80 MB: vince il giro.
    assertThat(DetectionCap.byteCap(detect, run(NetworkClass.MOBILE, remaining = 80 * MB))).isEqualTo(50 * MB)
    // Fuori casa senza tetto mensile (Wi-Fi esterno): i soli 50 MB.
    assertThat(DetectionCap.byteCap(detect, run(NetworkClass.EXTERNAL_WIFI))).isEqualTo(50 * MB)
  }

  @Test
  fun aCasa_valeSoloIlTettoMensile_seCe() {
    assertThat(DetectionCap.byteCap(detect, run(NetworkClass.HOME_WIFI))).isNull()
    assertThat(DetectionCap.byteCap(detect, run(NetworkClass.HOME_WIFI, remaining = 3 * GB))).isEqualTo(3 * GB)
  }

  @Test
  fun ilContenutoVisibile_nonHaIlTettoDiGiro_soloQuelloMensile() {
    val visible = WorkRequest.VisibleContent

    assertThat(DetectionCap.byteCap(visible, run(NetworkClass.MOBILE))).isNull()
    assertThat(DetectionCap.byteCap(visible, run(NetworkClass.MOBILE, remaining = 30 * MB))).isEqualTo(30 * MB)
  }

  @Test
  fun senzaReteUtile_ilRilevamentoNonLeggeNulla() {
    assertThat(DetectionCap.byteCap(detect, run(NetworkClass.OFFLINE, scope = RunScope.LOCAL_BUFFER_ONLY))).isEqualTo(0L)
    assertThat(DetectionCap.byteCap(detect, run(NetworkClass.HOME_WIFI, scope = RunScope.LOCAL_BUFFER_ONLY))).isEqualTo(0L)
  }

  // --- Dal verdetto vero del valutatore -----------------------------------------------------------------------------

  private val evaluator = RuleEvaluator()

  @Test
  fun dalValutatore_wifiEsternoViaVpn_cinquantaMega() {
    val decision = evaluator.evaluate(detect, ruleSetOf(Preset.WIFI_ALWAYS), state(network = EXTERNAL_WIFI_VPN_NET), usage())

    decision as RuleDecision.Run
    assertThat(decision.network).isEqualTo(NetworkClass.EXTERNAL_WIFI)
    assertThat(DetectionCap.byteCap(detect, decision)).isEqualTo(50 * MB)
  }

  @Test
  fun dalValutatore_aCasaNienteTetto() {
    val decision = evaluator.evaluate(detect, ruleSetOf(Preset.NIGHT), state(), usage())

    decision as RuleDecision.Run
    assertThat(DetectionCap.byteCap(detect, decision)).isNull()
  }

  @Test
  fun dalValutatore_datiMobiliVicinoAlTetto_restanoSoloIByteRimasti() {
    // "Massimo" usa i dati mobili entro 10 GB; ne sono stati usati 9,98.
    val decision =
      evaluator.evaluate(detect, ruleSetOf(Preset.MAXIMUM), state(network = MOBILE_VPN_NET), usage(mobile = 9_980 * MB))

    decision as RuleDecision.Run
    assertThat(decision.remainingBytes).isEqualTo(20 * MB)
    assertThat(DetectionCap.byteCap(detect, decision)).isEqualTo(20 * MB)
  }
}
