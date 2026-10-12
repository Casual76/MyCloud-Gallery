package dev.pampa.mycloudgallery.core.rules

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.AI_COMPUTE
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.HQ_PREVIEWS
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.INDEX_SYNC
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.PHOTO_BACKUP
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.PREPARATION
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.VIDEO_BACKUP
import java.time.Duration
import java.time.LocalDateTime
import org.junit.Test

/** Il valutatore (piano 1.6), caso per caso. Le matrici preset x scenario sono in `PresetMatrixTest`. */
class RuleEvaluatorTest {

  private val evaluator = RuleEvaluator()
  private val notte = ruleSetOf(Preset.NIGHT)
  private val bilanciato = ruleSetOf(Preset.BALANCED)
  private val wifiSempre = ruleSetOf(Preset.WIFI_ALWAYS)
  private val massimo = ruleSetOf(Preset.MAXIMUM)

  private fun run(network: NetworkClass = NetworkClass.HOME_WIFI, remaining: Long? = null) =
    RuleDecision.Run(RunScope.FULL, Pace.NORMAL, network, remaining)

  private fun wait(decision: RuleDecision): WaitReason {
    assertThat(decision).isInstanceOf(RuleDecision.Wait::class.java)
    return (decision as RuleDecision.Wait).reason
  }

  private fun stop(decision: RuleDecision): WaitReason {
    assertThat(decision).isInstanceOf(RuleDecision.Stop::class.java)
    return (decision as RuleDecision.Stop).reason
  }

  // --- Preset Notte: fascia e alimentazione -----------------------------------------------------------

  @Test
  fun notte_dentroLaFasciaInCaricaInWifi_tutteLeAttivitaPartono() {
    for (activity in BackgroundActivity.entries) {
      assertThat(evaluator.background(activity, notte, state())).isEqualTo(run())
    }
  }

  @Test
  fun notte_fuoriFascia_aspettaEDiceQuandoRiprende() {
    val reason = wait(evaluator.background(PHOTO_BACKUP, notte, state(now = at(12))))

    assertThat(reason).isEqualTo(WaitReason.OutsideWindow(NightWindows.DEFAULT, at(23)))
    assertThat(reason.message).isEqualTo("In attesa della fascia oraria (23:00–07:00)")
  }

  @Test
  fun notte_confiniDellaFasciaALaMezzanotte() {
    val expected =
      mapOf(
        at(22, 59, 59) to false,
        at(23, 0, 0) to true,
        at(23, 59, 59) to true,
        at(0, 0, 0, day = 14) to true, // mezzanotte in punto, giorno dopo
        at(0, 0, 1, day = 14) to true,
        at(6, 59, 59, day = 14) to true,
        at(7, 0, 0, day = 14) to false,
        at(12) to false,
      )
    for ((moment, shouldRun) in expected) {
      val decision = evaluator.background(AI_COMPUTE, notte, state(now = moment))
      assertWithMessage("alle $moment").that(decision.isRun).isEqualTo(shouldRun)
    }
  }

  @Test
  fun notte_unLavoroChePassaLaMezzanotte_nonSiFermaAllaMezzanotte() {
    val before = evaluator.background(VIDEO_BACKUP, notte, state(now = at(23, 59, 59)), running = true)
    val after = evaluator.background(VIDEO_BACKUP, notte, state(now = at(0, 0, 0, day = 14)), running = true)

    assertThat(before).isEqualTo(run())
    assertThat(after).isEqualTo(run())
  }

  @Test
  fun notte_laFasciaSiChiudeMentreIlLavoroGira_dicefermati() {
    val decision =
      evaluator.background(VIDEO_BACKUP, notte, state(now = at(7, 0, 0, day = 14)), running = true)

    assertThat(stop(decision)).isInstanceOf(WaitReason.OutsideWindow::class.java)
  }

  @Test
  fun notte_senzaAlimentazione_dicheServeLaCarica() {
    val reason = wait(evaluator.background(PHOTO_BACKUP, notte, state(plugged = false)))

    assertThat(reason).isEqualTo(WaitReason.NeedsPower)
    assertThat(reason.message).isEqualTo("In attesa: serve la carica")
  }

  @Test
  fun notte_caricaFermaAll80ConCavoCollegato_contaComeInCarica() {
    // Protezione batteria Samsung: alimentazione collegata ma carica sospesa (status NOT_CHARGING).
    val paused = state(plugged = true, level = 80, chargingPaused = true)

    for (activity in BackgroundActivity.entries) {
      assertThat(evaluator.background(activity, notte, paused)).isEqualTo(run())
    }
  }

  @Test
  fun notte_caricaFermaAll90SulTablet_contaComeInCarica() {
    assertThat(evaluator.background(AI_COMPUTE, notte, state(level = 90, chargingPaused = true)))
      .isEqualTo(run())
  }

  // --- Batteria -----------------------------------------------------------------------------------------

  @Test
  fun aBatteria_allaSogliaEsatta_parteSottoLaSogliaNo() {
    val daytime = at(12)
    assertThat(evaluator.background(PHOTO_BACKUP, bilanciato, state(daytime, plugged = false, level = 20)))
      .isEqualTo(run())

    val reason = wait(evaluator.background(PHOTO_BACKUP, bilanciato, state(daytime, plugged = false, level = 19)))
    assertThat(reason).isEqualTo(WaitReason.BatteryLow(19, 20))
    assertThat(reason.message).isEqualTo("Batteria al 19%: in pausa sotto il 20%")
  }

  @Test
  fun laBatteriaScendeSottoSogliaDuranteUnLavoro_ilValutatoreDiceFermati() {
    val daytime = at(12)

    val ok = evaluator.background(PHOTO_BACKUP, wifiSempre, state(daytime, plugged = false, level = 21), running = true)
    val low = evaluator.background(PHOTO_BACKUP, wifiSempre, state(daytime, plugged = false, level = 19), running = true)

    assertThat(ok).isEqualTo(run())
    assertThat(stop(low)).isEqualTo(WaitReason.BatteryLow(19, 20))
  }

  @Test
  fun inCarica_laSogliaDellaBatteriaNonSiApplica() {
    assertThat(evaluator.background(AI_COMPUTE, wifiSempre, state(at(12), plugged = true, level = 4)))
      .isEqualTo(run())
  }

  @Test
  fun sogliaModificabile() {
    val strict = wifiSempre.copy(batteryThresholdPercent = 40)
    val daytime = at(12)

    assertThat(wait(evaluator.background(PHOTO_BACKUP, strict, state(daytime, plugged = false, level = 35))))
      .isEqualTo(WaitReason.BatteryLow(35, 40))
    assertThat(evaluator.background(PHOTO_BACKUP, strict, state(daytime, plugged = false, level = 40)))
      .isEqualTo(run())
  }

  @Test
  fun unFalsoDistaccoDiPochiSecondi_siTollerasoloMentreIlLavoroGira() {
    val glitch = state(plugged = false, unpluggedFor = Duration.ofSeconds(5))

    // In corso: il distacco di 5 secondi (visto sull'S25) non ferma il lavoro.
    assertThat(evaluator.background(AI_COMPUTE, notte, glitch, running = true)).isEqualTo(run())
    // Per partire serve invece l'alimentazione stabile.
    assertThat(wait(evaluator.background(AI_COMPUTE, notte, glitch))).isEqualTo(WaitReason.NeedsPower)
    // Un distacco vero (oltre la tolleranza) ferma il lavoro.
    val real = state(plugged = false, unpluggedFor = Duration.ofSeconds(60))
    assertThat(stop(evaluator.background(AI_COMPUTE, notte, real, running = true)))
      .isEqualTo(WaitReason.NeedsPower)
  }

  // --- Reti, tetti, roaming -----------------------------------------------------------------------------

  @Test
  fun massimo_datiMobiliDentroIlTetto_partonoERestituisconoIlResiduo() {
    val decision =
      evaluator.background(VIDEO_BACKUP, massimo, state(at(12), plugged = false, network = MOBILE_VPN_NET), usage(mobile = 3 * GB))

    assertThat(decision).isEqualTo(run(NetworkClass.MOBILE, remaining = 7 * GB))
  }

  @Test
  fun tettoDatiMobiliRaggiunto_dicheQuantoEraEDoveSiTrova() {
    val capped = massimo.copy(caps = MonthlyCaps(mobileBytes = 1 * GB, wifiBytes = null))

    val reason =
      wait(evaluator.background(PHOTO_BACKUP, capped, state(at(12), network = MOBILE_VPN_NET), usage(mobile = 1_200 * MB)))

    assertThat(reason).isEqualTo(WaitReason.CapReached(CapKind.MOBILE, 1_200 * MB, 1 * GB))
    assertThat(reason.message).isEqualTo("Tetto dati mobili raggiunto (1,2/1 GB)")
  }

  @Test
  fun tetto_ilConfineEsatto() {
    val capped = massimo.copy(caps = MonthlyCaps(mobileBytes = 1 * GB, wifiBytes = null))
    val mobile = state(at(12), network = MOBILE_VPN_NET)

    assertThat(evaluator.background(PHOTO_BACKUP, capped, mobile, usage(mobile = 1 * GB - 1)))
      .isEqualTo(run(NetworkClass.MOBILE, remaining = 1))
    assertThat(evaluator.background(PHOTO_BACKUP, capped, mobile, usage(mobile = 1 * GB)).isRun).isFalse()
  }

  @Test
  fun tettoWifi_separatoDaQuelloMobile() {
    val capped = wifiSempre.copy(caps = MonthlyCaps(mobileBytes = 1 * GB, wifiBytes = 50 * GB))
    val home = state(at(12))

    val reason = wait(evaluator.background(PHOTO_BACKUP, capped, home, usage(wifi = 51 * GB)))
    assertThat(reason.message).isEqualTo("Tetto dati Wi-Fi raggiunto (51/50 GB)")
    // I dati mobili usati non pesano sul tetto Wi-Fi, e viceversa.
    assertThat(evaluator.background(PHOTO_BACKUP, capped, home, usage(mobile = 5 * GB))).isEqualTo(run(remaining = 50 * GB))
  }

  @Test
  fun tettoSottoIlGiga_siMostraInMegabyte() {
    val capped = massimo.copy(caps = MonthlyCaps(mobileBytes = 500 * MB, wifiBytes = null))

    val reason =
      wait(evaluator.background(PHOTO_BACKUP, capped, state(at(12), network = MOBILE_VPN_NET), usage(mobile = 620 * MB)))

    assertThat(reason.message).isEqualTo("Tetto dati mobili raggiunto (620/500 MB)")
  }

  @Test
  fun roaming_stoppaTuttoAncheConIlPresetMassimo() {
    for (activity in BackgroundActivity.entries) {
      val reason =
        wait(evaluator.background(activity, massimo, state(at(12), plugged = false, network = ROAMING_VPN_NET)))
      assertThat(reason).isEqualTo(WaitReason.Roaming)
      assertThat(reason.message).isEqualTo("Roaming: attività sospese")
    }
  }

  @Test
  fun roaming_conIInterruttoreEsplicito_partePerLeAttivita_dentroIlTetto() {
    val allowed = massimo.copy(rules = massimo.rules.mapValues { (_, r) -> r.copy(networks = r.networks + AllowedNetwork.ROAMING) })

    val decision = evaluator.background(PHOTO_BACKUP, allowed, state(at(12), network = ROAMING_VPN_NET), usage(mobile = 1 * GB))

    assertThat(decision).isEqualTo(run(NetworkClass.ROAMING, remaining = 9 * GB))
  }

  @Test
  fun wifiAConsumo_trattatoComeDatiMobili_E_R13() {
    // Hotspot di un altro telefono raggiunto via VPN: con "Wi-Fi sempre" i dati mobili non sono ammessi.
    val reason = wait(evaluator.background(PHOTO_BACKUP, wifiSempre, state(at(12), network = METERED_WIFI_VPN_NET)))
    assertThat(reason).isEqualTo(WaitReason.NetworkNotAllowed(NetworkClass.MOBILE))

    // Con Massimo parte, ma pesa sul tetto dei dati mobili.
    val capped = massimo.copy(caps = MonthlyCaps(mobileBytes = 1 * GB, wifiBytes = null))
    assertThat(evaluator.background(PHOTO_BACKUP, capped, state(at(12), network = METERED_WIFI_VPN_NET), usage(mobile = 400 * MB)))
      .isEqualTo(run(NetworkClass.MOBILE, remaining = 600 * MB))
    assertThat(evaluator.background(PHOTO_BACKUP, capped, state(at(12), network = METERED_WIFI_VPN_NET), usage(mobile = 2 * GB)).isRun)
      .isFalse()
  }

  @Test
  fun wifiConPaginaDiAccesso_eOffline_E_R13() {
    val captive = NetworkState(NetworkTransport.WIFI, isHome = true, validated = false)

    assertThat(wait(evaluator.background(PHOTO_BACKUP, notte, state(network = captive)))).isEqualTo(WaitReason.Offline)
    assertThat(wait(evaluator.background(PHOTO_BACKUP, notte, state(network = NetworkState.OFFLINE))).message)
      .isEqualTo("Nessuna connessione: in attesa di una rete")
  }

  @Test
  fun reteNonRiconosciuta_nonSiContattaIlNas() {
    // Un Wi-Fi qualunque, VPN spenta: nessuna autenticazione verso il NAS (piano 2.1).
    val stranger = NetworkState(NetworkTransport.WIFI)

    val reason = wait(evaluator.background(PHOTO_BACKUP, wifiSempre, state(network = stranger)))

    assertThat(reason).isEqualTo(WaitReason.NetworkNotTrusted)
  }

  @Test
  fun dueReti_siSegueQuellaEffettivamenteUsata_E_R12() {
    // Il Wi-Fi c'e' ma Android sta usando i dati mobili (Wi-Fi scadente): per le regole conta il mobile,
    // e il traffico pesante non passa sui dati mobili.
    val defaultIsMobile = state(at(12), plugged = true, network = MOBILE_VPN_NET)

    assertThat(wait(evaluator.background(VIDEO_BACKUP, wifiSempre, defaultIsMobile)))
      .isEqualTo(WaitReason.NetworkNotAllowed(NetworkClass.MOBILE))
    // Anche con Massimo, se l'utente imposta "pesanti solo a casa" il video non parte sul mobile.
    val strict = massimo.copy(heavyOnlyAtHome = true)
    assertThat(wait(evaluator.background(VIDEO_BACKUP, strict, defaultIsMobile))).isEqualTo(WaitReason.HeavyNeedsHomeWifi)
  }

  @Test
  fun laReteCambiaAMeta_dalWifiAiDatiMobili_ilValutatoreDiceFermati() {
    val daytime = at(12)
    val onWifi = state(daytime, plugged = false, network = HOME_WIFI_NET)
    val onMobile = state(daytime, plugged = false, network = MOBILE_VPN_NET)

    assertThat(evaluator.background(PHOTO_BACKUP, wifiSempre, onWifi, running = true)).isEqualTo(run())
    assertThat(stop(evaluator.background(PHOTO_BACKUP, wifiSempre, onMobile, running = true)))
      .isEqualTo(WaitReason.NetworkNotAllowed(NetworkClass.MOBILE))
    // Se torna il Wi-Fi, riparte.
    assertThat(evaluator.background(PHOTO_BACKUP, wifiSempre, onWifi)).isEqualTo(run())
  }

  @Test
  fun laReteCambiaAMeta_conMassimoIlLavoroContinuaSuiDatiMobili() {
    val onMobile = state(at(12), plugged = false, network = MOBILE_VPN_NET)

    assertThat(evaluator.background(PHOTO_BACKUP, massimo, onMobile, running = true))
      .isEqualTo(run(NetworkClass.MOBILE, remaining = 10 * GB))
  }

  @Test
  fun attivitaPesante_suWifiEsternoViaVpn_aspettaCasa_leggeraParte() {
    val external = state(at(12), plugged = false, network = EXTERNAL_WIFI_VPN_NET)

    assertThat(evaluator.background(PHOTO_BACKUP, wifiSempre, external)).isEqualTo(run(NetworkClass.EXTERNAL_WIFI))
    assertThat(evaluator.background(INDEX_SYNC, wifiSempre, external)).isEqualTo(run(NetworkClass.EXTERNAL_WIFI))
    for (heavy in listOf(VIDEO_BACKUP, PREPARATION, HQ_PREVIEWS)) {
      val reason = wait(evaluator.background(heavy, wifiSempre, external))
      assertThat(reason).isEqualTo(WaitReason.HeavyNeedsHomeWifi)
      assertThat(reason.message).isEqualTo("Attività pesante: parte solo sul Wi-Fi di casa")
    }
  }

  @Test
  fun wifiDiCasaConVpnAccesa_resta_wifiDiCasa() {
    val homeThroughVpn = NetworkState(NetworkTransport.WIFI, viaVpn = true, isHome = true)

    assertThat(evaluator.background(VIDEO_BACKUP, notte, state(network = homeThroughVpn))).isEqualTo(run())
  }

  @Test
  fun reteScadente_leAttivitaAutomaticheAspettano_l_usoInterattivoNo_E_R4() {
    // VPN su una rete lenta o che si blocca (misurata dalla Fase 2).
    val poorHome = state(network = HOME_WIFI_NET.copy(viaVpn = true, poor = true))
    val poorExternal = state(now = at(12), plugged = false, network = EXTERNAL_WIFI_VPN_NET.copy(poor = true))

    val reason = wait(evaluator.background(PHOTO_BACKUP, notte, poorHome))
    assertThat(reason).isEqualTo(WaitReason.PoorNetwork)
    assertThat(reason.message).isEqualTo("Rete lenta o instabile: attività sospese")
    assertThat(wait(evaluator.background(PHOTO_BACKUP, wifiSempre, poorExternal))).isEqualTo(WaitReason.PoorNetwork)
    // Il lavoro gia' in corso si ferma.
    assertThat(stop(evaluator.background(VIDEO_BACKUP, notte, poorHome, running = true))).isEqualTo(WaitReason.PoorNetwork)
    // Il Calcolo AI non ha bisogno della rete: lavora sul buffer.
    assertThat(evaluator.background(AI_COMPUTE, notte, poorHome))
      .isEqualTo(RuleDecision.Run(RunScope.LOCAL_BUFFER_ONLY, Pace.NORMAL, NetworkClass.HOME_WIFI))
    // Uso interattivo: contenuto visibile, sync con l'app aperta e "Fai ora" funzionano (la UI mostra la lentezza).
    val screenOn = poorExternal.copy(appInForeground = true)
    assertThat(evaluator.evaluate(WorkRequest.VisibleContent, notte, screenOn, usage()).isRun).isTrue()
    assertThat(evaluator.background(INDEX_SYNC, notte, screenOn).isRun).isTrue()
    assertThat(evaluator.evaluate(now(VIDEO_BACKUP), notte, screenOn, usage()).isRun).isTrue()
  }

  @Test
  fun reteScadente_comeMotivoSecondario_dietroAiMotiviPiuImportanti() {
    val decision =
      evaluator.background(PHOTO_BACKUP, notte, state(now = at(12), network = HOME_WIFI_NET.copy(poor = true))) as RuleDecision.Wait

    assertThat(decision.reason).isEqualTo(WaitReason.PoorNetwork)
    assertThat(decision.alsoBlockedBy).containsExactly(WaitReason.OutsideWindow(NightWindows.DEFAULT, at(23)))
  }

  // --- Limiti di Android, risparmio energetico, temperatura ----------------------------------------------

  @Test
  fun limiteDiAndroidRaggiunto_ilTipoGiustoSiFerma_E_P14() {
    val dataSyncOut = state(limits = AndroidLimits(dataSyncBudgetExhausted = true))

    val reason = wait(evaluator.background(PHOTO_BACKUP, notte, dataSyncOut))
    assertThat(reason).isEqualTo(WaitReason.AndroidLimit(ForegroundBudget.DATA_SYNC))
    assertThat(reason.message).isEqualTo("Limite di Android raggiunto: riprende la notte dopo")
    // La quota dei servizi "mediaProcessing" e' un'altra.
    assertThat(evaluator.background(AI_COMPUTE, notte, dataSyncOut)).isEqualTo(run())

    val mediaOut = state(limits = AndroidLimits(mediaProcessingBudgetExhausted = true))
    assertThat(wait(evaluator.background(AI_COMPUTE, notte, mediaOut)))
      .isEqualTo(WaitReason.AndroidLimit(ForegroundBudget.MEDIA_PROCESSING))
    assertThat(wait(evaluator.background(PREPARATION, notte, mediaOut)))
      .isEqualTo(WaitReason.AndroidLimit(ForegroundBudget.MEDIA_PROCESSING))
    assertThat(evaluator.background(PHOTO_BACKUP, notte, mediaOut)).isEqualTo(run())
  }

  @Test
  fun quotaDeiJobEsaurita_fermaTutteLeAttivita() {
    val jobsOut = state(limits = AndroidLimits(jobQuotaExhausted = true))

    for (activity in BackgroundActivity.entries) {
      assertThat(wait(evaluator.background(activity, notte, jobsOut))).isEqualTo(WaitReason.AndroidLimit(null))
    }
  }

  @Test
  fun limiteDiAndroidRaggiunto_durantelUnLavoro_dicefermati() {
    val out = state(limits = AndroidLimits(dataSyncBudgetExhausted = true))

    assertThat(stop(evaluator.background(VIDEO_BACKUP, notte, out, running = true)))
      .isEqualTo(WaitReason.AndroidLimit(ForegroundBudget.DATA_SYNC))
  }

  @Test
  fun risparmioEnergetico_fermaIlBackground_ancheInCarica_E_P10() {
    val saver = state(powerSave = true)

    for (activity in BackgroundActivity.entries) {
      assertThat(wait(evaluator.background(activity, notte, saver))).isEqualTo(WaitReason.PowerSaver)
    }
    assertThat(wait(evaluator.background(PHOTO_BACKUP, notte, saver)).message)
      .isEqualTo("Risparmio energetico attivo: attività in pausa")
  }

  @Test
  fun risparmioEnergetico_usoInterattivoNormale() {
    val saver = state(at(12), plugged = false, level = 50, powerSave = true, foreground = true)

    // "Fai ora", sync dell'indice con l'app aperta e miniature visibili non sono background.
    assertThat(evaluator.evaluate(WorkRequest.UserInitiated(PHOTO_BACKUP), notte, saver, usage()).isRun).isTrue()
    assertThat(evaluator.background(INDEX_SYNC, notte, saver).isRun).isTrue()
    assertThat(evaluator.evaluate(WorkRequest.VisibleContent, notte, saver, usage()).isRun).isTrue()
  }

  @Test
  fun risparmioEnergetico_siPuoIgnorareConIlSuoInterruttore() {
    val ignoring = notte.copy(pauseOnPowerSaver = false)

    assertThat(evaluator.background(PHOTO_BACKUP, ignoring, state(powerSave = true))).isEqualTo(run())
  }

  @Test
  fun termicaSevera_sospendeIlBackground() {
    for (status in listOf(ThermalStatus.SEVERE, ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY)) {
      val reason = wait(evaluator.background(AI_COMPUTE, notte, state(thermal = status)))
      assertThat(reason).isEqualTo(WaitReason.Thermal(status))
      assertThat(reason.message).isEqualTo("Telefono troppo caldo: in pausa finché si raffredda")
    }
  }

  @Test
  fun termicaLieveOModerata_ritmoRidottoSoloPerLeAttivitaACpu() {
    for (status in listOf(ThermalStatus.LIGHT, ThermalStatus.MODERATE)) {
      val hot = state(thermal = status)
      assertThat(evaluator.background(AI_COMPUTE, notte, hot)).isEqualTo(RuleDecision.Run(RunScope.FULL, Pace.REDUCED, NetworkClass.HOME_WIFI))
      assertThat(evaluator.background(PREPARATION, notte, hot)).isEqualTo(RuleDecision.Run(RunScope.FULL, Pace.REDUCED, NetworkClass.HOME_WIFI))
      assertThat(evaluator.background(PHOTO_BACKUP, notte, hot)).isEqualTo(run())
    }
    assertThat(evaluator.background(AI_COMPUTE, notte, state(thermal = ThermalStatus.NONE))).isEqualTo(run())
  }

  @Test
  fun termica_headroom() {
    // 0,85 -> ritmo ridotto; 0,95 -> sospeso (docs/android.md, 2.3).
    assertThat((evaluator.background(AI_COMPUTE, notte, state(headroom = 0.8f)) as RuleDecision.Run).pace).isEqualTo(Pace.NORMAL)
    assertThat((evaluator.background(AI_COMPUTE, notte, state(headroom = 0.9f)) as RuleDecision.Run).pace).isEqualTo(Pace.REDUCED)
    assertThat(wait(evaluator.background(AI_COMPUTE, notte, state(headroom = 0.95f)))).isInstanceOf(WaitReason.Thermal::class.java)
    assertThat(wait(evaluator.background(AI_COMPUTE, notte, state(headroom = 1.0f)))).isInstanceOf(WaitReason.Thermal::class.java)
  }

  @Test
  fun termica_isteresi_ripartePoloDopoIlRaffreddamento_E_A5() {
    val cooling = state(thermal = ThermalStatus.NONE, timeSinceSevere = Duration.ofMinutes(2))
    val cooled = state(thermal = ThermalStatus.NONE, timeSinceSevere = Duration.ofMinutes(6))

    assertThat(wait(evaluator.background(AI_COMPUTE, notte, cooling))).isEqualTo(WaitReason.Thermal(ThermalStatus.NONE))
    assertThat(evaluator.background(AI_COMPUTE, notte, cooled)).isEqualTo(run())
  }

  @Test
  fun termicaSevera_durantelUnLavoro_dicefermati_poiRiprende() {
    val severe = state(thermal = ThermalStatus.SEVERE)

    assertThat(stop(evaluator.background(AI_COMPUTE, notte, severe, running = true))).isInstanceOf(WaitReason.Thermal::class.java)
    assertThat(evaluator.background(AI_COMPUTE, notte, state(thermal = ThermalStatus.NONE))).isEqualTo(run())
  }

  // --- Profilo, ruoli, NAS, attivita' spente --------------------------------------------------------------

  @Test
  fun profiloNonAttivo_ilLavoroEInRinviato_E_P19() {
    val inactive = state(profileActive = false)

    for (activity in BackgroundActivity.entries) {
      val reason = wait(evaluator.background(activity, notte, inactive))
      assertThat(reason).isEqualTo(WaitReason.ProfileInactive)
    }
    assertThat(WaitReason.ProfileInactive.message)
      .isEqualTo("Profilo non attivo: il lavoro riprende quando torni su questo profilo")
    // Nemmeno la sync con l'app "aperta" parte su un profilo non attivo.
    assertThat(wait(evaluator.background(INDEX_SYNC, notte, state(profileActive = false, foreground = true))))
      .isEqualTo(WaitReason.ProfileInactive)
  }

  @Test
  fun nasSpento_ilBackupAspetta_ilCalcoloAiLavoraSulBuffer_E_A6() {
    val noNas = state(nas = false)

    val reason = wait(evaluator.background(PHOTO_BACKUP, notte, noNas))
    assertThat(reason).isEqualTo(WaitReason.NasUnreachable)
    assertThat(reason.message).isEqualTo("NAS spento o non raggiungibile")
    assertThat(wait(evaluator.background(PREPARATION, notte, noNas))).isEqualTo(WaitReason.NasUnreachable)
    assertThat(wait(evaluator.background(INDEX_SYNC, notte, noNas))).isEqualTo(WaitReason.NasUnreachable)

    // Il Calcolo AI elabora solo cio' che ha in locale, poi si ferma con lo stato.
    assertThat(evaluator.background(AI_COMPUTE, notte, noNas))
      .isEqualTo(RuleDecision.Run(RunScope.LOCAL_BUFFER_ONLY, Pace.NORMAL, NetworkClass.HOME_WIFI))
  }

  @Test
  fun calcoloAiSenzaNas_masoloSeLeAltreRegoleLoConsentono() {
    // Senza NAS ma fuori fascia: il buffer locale non si elabora di giorno con il preset Notte.
    val reason = wait(evaluator.background(AI_COMPUTE, notte, state(now = at(12), nas = false)))

    assertThat(reason).isInstanceOf(WaitReason.OutsideWindow::class.java)
  }

  @Test
  fun calcoloAiOffline_lavoraSulBuffer() {
    val decision = evaluator.background(AI_COMPUTE, notte, state(network = NetworkState.OFFLINE))

    assertThat(decision)
      .isEqualTo(RuleDecision.Run(RunScope.LOCAL_BUFFER_ONLY, Pace.NORMAL, NetworkClass.OFFLINE))
  }

  @Test
  fun ruoli_senzaIlRuoloL_attivitaNonParte() {
    val none = state(roles = DeviceRoles())

    val ai = wait(evaluator.background(AI_COMPUTE, notte, none))
    assertThat(ai).isEqualTo(WaitReason.RoleDisabled(DeviceRole.INDEXER))
    assertThat(ai.message).isEqualTo("Questo dispositivo non è un indicizzatore")
    assertThat(wait(evaluator.background(PREPARATION, notte, none))).isEqualTo(WaitReason.RoleDisabled(DeviceRole.PREPARER))
    // Le attivita' senza ruolo non sono toccate.
    assertThat(evaluator.background(PHOTO_BACKUP, notte, none)).isEqualTo(run())
    // I due ruoli sono indipendenti.
    assertThat(evaluator.background(AI_COMPUTE, notte, state(roles = DeviceRoles(indexer = true)))).isEqualTo(run())
    assertThat(wait(evaluator.background(PREPARATION, notte, state(roles = DeviceRoles(indexer = true)))))
      .isEqualTo(WaitReason.RoleDisabled(DeviceRole.PREPARER))
  }

  @Test
  fun attivitaSpentaDall_utente() {
    val off =
      notte.copy(rules = notte.rules + (HQ_PREVIEWS to notte.ruleFor(HQ_PREVIEWS).copy(enabled = false)))

    assertThat(wait(evaluator.background(HQ_PREVIEWS, off, state()))).isEqualTo(WaitReason.ActivityDisabled)
    assertThat(evaluator.background(PHOTO_BACKUP, off, state())).isEqualTo(run())
  }

  @Test
  fun piuMotivi_ilPrimoEQuelloPiuImportante_glialtriSonoInAlsoBlockedBy() {
    // Roaming, di giorno, a batteria: il motivo principale e' il roaming. Il NAS non si cita: senza
    // rete usabile la sua raggiungibilita' non e' un'informazione.
    val decision =
      evaluator.background(
        PHOTO_BACKUP,
        notte,
        state(now = at(12), plugged = false, network = ROAMING_VPN_NET, nas = false),
      ) as RuleDecision.Wait

    assertThat(decision.reason).isEqualTo(WaitReason.Roaming)
    assertThat(decision.alsoBlockedBy)
      .containsExactly(WaitReason.OutsideWindow(NightWindows.DEFAULT, at(23)), WaitReason.NeedsPower)
      .inOrder()
  }

  // --- App in primo piano (proposta 3) --------------------------------------------------------------------

  @Test
  fun inPrimoPiano_laSyncDellIndiceScavalcaFasciaCaricaEBatteria() {
    val screenOn = state(at(12), plugged = false, level = 10, network = EXTERNAL_WIFI_VPN_NET, foreground = true)

    assertThat(evaluator.background(INDEX_SYNC, notte, screenOn)).isEqualTo(run(NetworkClass.EXTERNAL_WIFI))
    // Le altre attivita' continuano a seguire le regole anche con l'app aperta.
    assertThat(wait(evaluator.background(PHOTO_BACKUP, notte, screenOn)))
      .isInstanceOf(WaitReason.OutsideWindow::class.java)
  }

  @Test
  fun inBackground_laSyncDellIndiceSegueLeRegole() {
    val screenOff = state(at(12), foreground = false)

    assertThat(wait(evaluator.background(INDEX_SYNC, notte, screenOff))).isInstanceOf(WaitReason.OutsideWindow::class.java)
  }

  @Test
  fun inPrimoPiano_suiDatiMobili_valgonoITetti() {
    val capped = notte.copy(caps = MonthlyCaps(mobileBytes = 1 * GB, wifiBytes = null))
    val onMobile = state(at(12), plugged = false, network = MOBILE_VPN_NET, foreground = true)

    // Nessun preset ammette i dati mobili in background, ma il contenuto visibile si carica nel tetto.
    assertThat(evaluator.background(INDEX_SYNC, capped, onMobile, usage(mobile = 300 * MB)))
      .isEqualTo(run(NetworkClass.MOBILE, remaining = 700 * MB))
    assertThat(evaluator.evaluate(WorkRequest.VisibleContent, capped, onMobile, usage(mobile = 300 * MB)))
      .isEqualTo(run(NetworkClass.MOBILE, remaining = 700 * MB))

    val over = usage(mobile = 1_200 * MB)
    assertThat(wait(evaluator.background(INDEX_SYNC, capped, onMobile, over)))
      .isEqualTo(WaitReason.CapReached(CapKind.MOBILE, 1_200 * MB, 1 * GB))
    assertThat(wait(evaluator.evaluate(WorkRequest.VisibleContent, capped, onMobile, over)))
      .isInstanceOf(WaitReason.CapReached::class.java)
  }

  @Test
  fun inPrimoPiano_ilRoamingFermaAncheLeMiniature() {
    val roaming = state(at(12), plugged = false, network = ROAMING_VPN_NET, foreground = true)

    assertThat(wait(evaluator.evaluate(WorkRequest.VisibleContent, notte, roaming, usage()))).isEqualTo(WaitReason.Roaming)
    assertThat(wait(evaluator.background(INDEX_SYNC, notte, roaming))).isEqualTo(WaitReason.Roaming)
  }

  @Test
  fun inPrimoPiano_serveComunqueLaReteEIlNas() {
    val offline = state(foreground = true, network = NetworkState.OFFLINE)
    val noNas = state(foreground = true, nas = false)

    assertThat(wait(evaluator.evaluate(WorkRequest.VisibleContent, notte, offline, usage()))).isEqualTo(WaitReason.Offline)
    assertThat(wait(evaluator.evaluate(WorkRequest.VisibleContent, notte, noNas, usage()))).isEqualTo(WaitReason.NasUnreachable)
  }

  @Test
  fun miniatureVisibili_conLAppInBackground_nonHannoSenso() {
    val reason = wait(evaluator.evaluate(WorkRequest.VisibleContent, notte, state(foreground = false), usage()))

    assertThat(reason).isEqualTo(WaitReason.AppNotVisible)
  }

  // --- "Fai ora" (proposta 10) ----------------------------------------------------------------------------

  private fun now(activity: BackgroundActivity, estimated: Long? = null, confirmed: Boolean = false) =
    WorkRequest.UserInitiated(activity, estimated, confirmed)

  @Test
  fun faiOra_scavalcaFasciaCaricaESoglia() {
    val daytimeLow = state(at(12), plugged = false, level = 8, foreground = true)

    for (activity in BackgroundActivity.entries) {
      assertThat(evaluator.evaluate(now(activity), notte, daytimeLow, usage())).isEqualTo(run())
    }
  }

  @Test
  fun faiOra_scavalcaLeQuoteDiAndroidEIlRisparmioEnergetico() {
    val limited = state(foreground = true, powerSave = true, limits = AndroidLimits(jobQuotaExhausted = true))

    assertThat(evaluator.evaluate(now(VIDEO_BACKUP), notte, limited, usage())).isEqualTo(run())
  }

  @Test
  fun faiOra_suWifiEsternoNonChiedeConferma_ancheSePesante() {
    val external = state(at(12), plugged = false, network = EXTERNAL_WIFI_VPN_NET, foreground = true)

    assertThat(evaluator.evaluate(now(VIDEO_BACKUP), notte, external, usage())).isEqualTo(run(NetworkClass.EXTERNAL_WIFI))
  }

  @Test
  fun faiOra_suiDatiMobili_chiedeConfermaConLaStima() {
    val onMobile = state(at(12), plugged = false, network = MOBILE_VPN_NET, foreground = true)

    val decision = evaluator.evaluate(now(VIDEO_BACKUP, estimated = 350 * MB), notte, onMobile, usage(mobile = 100 * MB))

    assertThat(decision).isInstanceOf(RuleDecision.NeedsConfirmation::class.java)
    decision as RuleDecision.NeedsConfirmation
    assertThat(decision.kinds).containsExactly(ConfirmationKind.MOBILE_DATA)
    assertThat(decision.estimate).isEqualTo(TrafficEstimate(350 * MB, 100 * MB, 1 * GB))
    assertThat(decision.message).isEqualTo("Stai per usare i dati mobili. Servono circa 350 MB. Vuoi continuare?")
  }

  @Test
  fun faiOra_suiDatiMobili_confermato_parte() {
    val onMobile = state(at(12), plugged = false, network = MOBILE_VPN_NET, foreground = true)

    val decision =
      evaluator.evaluate(now(PHOTO_BACKUP, estimated = 350 * MB, confirmed = true), notte, onMobile, usage(mobile = 100 * MB))

    assertThat(decision).isEqualTo(run(NetworkClass.MOBILE, remaining = 900 * MB))
  }

  @Test
  fun faiOra_laStimaSuperaIlTetto_chiedeConfermaDelSuperamento_E_B10() {
    val onMobile = state(at(12), network = MOBILE_VPN_NET, foreground = true)

    val ask = evaluator.evaluate(now(VIDEO_BACKUP, estimated = 2 * GB), notte, onMobile, usage(mobile = 100 * MB))
    ask as RuleDecision.NeedsConfirmation
    assertThat(ask.kinds).containsExactly(ConfirmationKind.MOBILE_DATA, ConfirmationKind.OVER_CAP)
    assertThat(ask.message)
      .isEqualTo("Stai per usare i dati mobili. Servono circa 2 GB. Tetto mensile: 0,1/1 GB. Vuoi continuare?")

    // Confermato: il tetto non ferma piu' questo lavoro.
    val go = evaluator.evaluate(now(VIDEO_BACKUP, estimated = 2 * GB, confirmed = true), notte, onMobile, usage(mobile = 100 * MB))
    assertThat(go).isEqualTo(run(NetworkClass.MOBILE, remaining = null))
  }

  @Test
  fun faiOra_tettoGiaRaggiunto_chiedeConferma_E_B10() {
    val onMobile = state(at(12), network = MOBILE_VPN_NET, foreground = true)

    val decision = evaluator.evaluate(now(PHOTO_BACKUP), notte, onMobile, usage(mobile = 1_500 * MB))

    decision as RuleDecision.NeedsConfirmation
    assertThat(decision.kinds).containsExactly(ConfirmationKind.MOBILE_DATA, ConfirmationKind.OVER_CAP)
  }

  @Test
  fun faiOra_inRoaming_chiedeConferma() {
    val decision = evaluator.evaluate(now(PHOTO_BACKUP, estimated = 50 * MB), notte, state(network = ROAMING_VPN_NET, foreground = true), usage())

    decision as RuleDecision.NeedsConfirmation
    assertThat(decision.kinds).containsExactly(ConfirmationKind.ROAMING)
    assertThat(decision.message).isEqualTo("Sei in roaming: potrebbero esserci costi aggiuntivi. Servono circa 50 MB. Vuoi continuare?")
  }

  @Test
  fun faiOra_tettoWifiRaggiunto_chiedeConferma() {
    val capped = notte.copy(caps = MonthlyCaps(mobileBytes = 1 * GB, wifiBytes = 10 * GB))

    val decision = evaluator.evaluate(now(VIDEO_BACKUP), capped, state(foreground = true), usage(wifi = 11 * GB))

    decision as RuleDecision.NeedsConfirmation
    assertThat(decision.kinds).containsExactly(ConfirmationKind.OVER_CAP)
  }

  @Test
  fun faiOra_nonScavalcaChiNonSiPuoFare() {
    val field = state(foreground = true)

    assertThat(wait(evaluator.evaluate(now(PHOTO_BACKUP), notte, field.copy(nasReachable = false), usage())))
      .isEqualTo(WaitReason.NasUnreachable)
    assertThat(wait(evaluator.evaluate(now(PHOTO_BACKUP), notte, field.copy(network = NetworkState.OFFLINE), usage())))
      .isEqualTo(WaitReason.Offline)
    assertThat(wait(evaluator.evaluate(now(PHOTO_BACKUP), notte, field.copy(network = NetworkState(NetworkTransport.WIFI)), usage())))
      .isEqualTo(WaitReason.NetworkNotTrusted)
    assertThat(wait(evaluator.evaluate(now(AI_COMPUTE), notte, field.copy(roles = DeviceRoles()), usage())))
      .isEqualTo(WaitReason.RoleDisabled(DeviceRole.INDEXER))
  }

  @Test
  fun faiOra_calcoloAiSenzaNas_lavoraSulBuffer_senzaConfermaDiRete() {
    val onMobileNoNas = state(network = MOBILE_VPN_NET, nas = false, foreground = true)

    assertThat(evaluator.evaluate(now(AI_COMPUTE), notte, onMobileNoNas, usage()))
      .isEqualTo(RuleDecision.Run(RunScope.LOCAL_BUFFER_ONLY, Pace.NORMAL, NetworkClass.MOBILE))
  }

  @Test
  fun temperaturaCritica_fermaTuttoAncheFaiOraEIlContenutoVisibile() {
    val critical = state(thermal = ThermalStatus.CRITICAL, foreground = true)

    assertThat(wait(evaluator.evaluate(now(PHOTO_BACKUP), notte, critical, usage())))
      .isEqualTo(WaitReason.Thermal(ThermalStatus.CRITICAL))
    assertThat(wait(evaluator.evaluate(WorkRequest.VisibleContent, notte, critical, usage())))
      .isEqualTo(WaitReason.Thermal(ThermalStatus.CRITICAL))
    assertThat(wait(evaluator.background(INDEX_SYNC, notte, critical)))
      .isEqualTo(WaitReason.Thermal(ThermalStatus.CRITICAL))
    // SEVERE si puo' ancora scavalcare con "Fai ora".
    assertThat(evaluator.evaluate(now(PHOTO_BACKUP), notte, state(thermal = ThermalStatus.SEVERE, foreground = true), usage()).isRun)
      .isTrue()
  }

  @Test
  fun faiOra_laReteCambiaAMeta_dalWifiAiDatiMobili_ilLavoroSiFermaInAttesaDiConferma() {
    val onMobile = state(foreground = true, network = MOBILE_VPN_NET)

    val decision = evaluator.evaluate(now(VIDEO_BACKUP), notte, onMobile, usage(), running = true)

    assertThat(stop(decision)).isEqualTo(WaitReason.ConfirmationRequired(setOf(ConfirmationKind.MOBILE_DATA)))
    assertThat(stop(decision).message).isEqualTo("Serve la tua conferma per usare i dati mobili")
  }

  // --- Messaggi -------------------------------------------------------------------------------------------

  @Test
  fun ognunoDeiMotiviHaUnTestoInItaliano() {
    val reasons =
      listOf(
        WaitReason.ProfileInactive,
        WaitReason.RoleDisabled(DeviceRole.INDEXER),
        WaitReason.RoleDisabled(DeviceRole.PREPARER),
        WaitReason.ActivityDisabled,
        WaitReason.AppNotVisible,
        WaitReason.Roaming,
        WaitReason.Offline,
        WaitReason.NetworkNotTrusted,
        WaitReason.PoorNetwork,
        WaitReason.OutsideWindow(NightWindows.DEFAULT, LocalDateTime.of(2026, 10, 13, 23, 0)),
        WaitReason.NeedsPower,
        WaitReason.BatteryLow(10, 20),
        WaitReason.PowerSaver,
        WaitReason.Thermal(ThermalStatus.SEVERE),
        WaitReason.NetworkNotAllowed(NetworkClass.MOBILE),
        WaitReason.NetworkNotAllowed(NetworkClass.EXTERNAL_WIFI),
        WaitReason.NetworkNotAllowed(NetworkClass.HOME_WIFI),
        WaitReason.HeavyNeedsHomeWifi,
        WaitReason.CapReached(CapKind.MOBILE, 2 * GB, 1 * GB),
        WaitReason.CapReached(CapKind.WIFI, 2 * GB, 1 * GB),
        WaitReason.AndroidLimit(null),
        WaitReason.AndroidLimit(ForegroundBudget.DATA_SYNC),
        WaitReason.NasUnreachable,
        WaitReason.ConfirmationRequired(setOf(ConfirmationKind.ROAMING)),
        WaitReason.ConfirmationRequired(setOf(ConfirmationKind.OVER_CAP)),
      )

    for (reason in reasons) {
      assertThat(reason.message).isNotEmpty()
      // Niente segnaposto ne' codici interni nel testo per l'utente.
      assertThat(reason.message).doesNotContain("_")
      assertThat(reason.message).doesNotContain("null")
    }
    assertThat(reasons.map { it.message }.toSet().size).isAtLeast(reasons.size - 3) // quasi tutti distinti
  }
}
