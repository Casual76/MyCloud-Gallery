package dev.pampa.mycloudgallery.core.rules

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.AI_COMPUTE
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.INDEX_SYNC
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.PHOTO_BACKUP
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.VIDEO_BACKUP
import dev.pampa.mycloudgallery.core.rules.settings.QuickPhotoProtection
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.rules.settings.quickPhotosRuleSet
import dev.pampa.mycloudgallery.core.rules.traffic.CycleUsage
import org.junit.Test

/**
 * Tutti i preset contro gli scenari tipici: per ogni coppia (preset, scenario) quali attivita' girano
 * del tutto (`full`) e quali solo sul buffer locale (`local`, il Calcolo AI senza NAS o senza rete
 * ammessa). E' la tabella di docs/regole-attivita.md in forma eseguibile.
 *
 * Fase 4 (D10): gli scenari "NAS congelato" valutano i lavori che scrivono sul NAS (`writes = true`) e quelli che
 * leggono soltanto, per ogni preset.
 *
 * Fase 5A (P4): "Proteggi subito le foto nuove" (D34, approvata con Q7 (a)) contro gli stessi scenari e "Fai ora" del backup sui dati
 * mobili, per ogni preset.
 */
class PresetMatrixTest {

  private val evaluator = RuleEvaluator()

  private val all = BackgroundActivity.entries.toSet()
  private val light = setOf(PHOTO_BACKUP, INDEX_SYNC)
  private val none = emptySet<BackgroundActivity>()
  private val aiOnly = setOf(AI_COMPUTE)

  private class Outcome(val full: Set<BackgroundActivity>, val local: Set<BackgroundActivity> = emptySet())

  private fun outcome(preset: Preset, state: SystemState, writes: Boolean = false): Outcome {
    val rules = ruleSetOf(preset)
    val full = mutableSetOf<BackgroundActivity>()
    val local = mutableSetOf<BackgroundActivity>()
    for (activity in BackgroundActivity.entries) {
      val decision = evaluator.background(activity, rules, state, writesToNas = writes)
      if (decision is RuleDecision.Run) {
        if (decision.scope == RunScope.FULL) full += activity else local += activity
      }
    }
    return Outcome(full, local)
  }

  private fun check(scenario: String, state: SystemState, expected: Map<Preset, Outcome>, writes: Boolean = false) {
    assertThat(expected.keys).containsExactlyElementsIn(Preset.entries)
    for ((preset, want) in expected) {
      val got = outcome(preset, state, writes)
      assertWithMessage("$scenario - $preset (full)").that(got.full).isEqualTo(want.full)
      assertWithMessage("$scenario - $preset (solo buffer locale)").that(got.local).isEqualTo(want.local)
    }
  }

  @Test
  fun notteInCaricaInWifiDiCasa_tuttoParteConQualunquePreset() {
    check(
      "notte, in carica, Wi-Fi di casa",
      state(),
      Preset.entries.associateWith { Outcome(all) },
    )
  }

  @Test
  fun giornoInCaricaInWifiDiCasa() {
    check(
      "giorno, in carica, Wi-Fi di casa",
      state(now = at(12)),
      mapOf(
        Preset.NIGHT to Outcome(none),
        Preset.BALANCED to Outcome(all),
        Preset.WIFI_ALWAYS to Outcome(all),
        Preset.MAXIMUM to Outcome(all),
        Preset.CUSTOM to Outcome(none),
      ),
    )
  }

  @Test
  fun giornoABatteriaInWifiDiCasa() {
    check(
      "giorno, a batteria all'80%, Wi-Fi di casa",
      state(now = at(12), plugged = false),
      mapOf(
        Preset.NIGHT to Outcome(none),
        // Le leggere appena c'e' Wi-Fi anche a batteria; le pesanti aspettano la corrente.
        Preset.BALANCED to Outcome(light),
        Preset.WIFI_ALWAYS to Outcome(all),
        Preset.MAXIMUM to Outcome(all),
        Preset.CUSTOM to Outcome(none),
      ),
    )
  }

  @Test
  fun giornoABatteriaSottoSoglia_nessunPresetPartePerLeAttivitaCheLaRispettano() {
    check(
      "giorno, a batteria al 15%, Wi-Fi di casa",
      state(now = at(12), plugged = false, level = 15),
      mapOf(
        Preset.NIGHT to Outcome(none),
        Preset.BALANCED to Outcome(none),
        Preset.WIFI_ALWAYS to Outcome(none),
        Preset.MAXIMUM to Outcome(none),
        Preset.CUSTOM to Outcome(none),
      ),
    )
  }

  @Test
  fun giornoABatteriaSuWifiEsternoViaVpn() {
    check(
      "giorno, a batteria, Wi-Fi esterno via VPN",
      state(now = at(12), plugged = false, network = EXTERNAL_WIFI_VPN_NET),
      mapOf(
        Preset.NIGHT to Outcome(none),
        Preset.BALANCED to Outcome(light),
        // Le pesanti restano sul Wi-Fi di casa; il Calcolo AI elabora il buffer che ha.
        Preset.WIFI_ALWAYS to Outcome(light, aiOnly),
        // Massimo spegne "pesanti solo a casa".
        Preset.MAXIMUM to Outcome(all),
        Preset.CUSTOM to Outcome(none),
      ),
    )
  }

  @Test
  fun giornoABatteriaSuiDatiMobili() {
    check(
      "giorno, a batteria, dati mobili",
      state(now = at(12), plugged = false, network = MOBILE_VPN_NET),
      mapOf(
        Preset.NIGHT to Outcome(none),
        Preset.BALANCED to Outcome(none),
        Preset.WIFI_ALWAYS to Outcome(none, aiOnly),
        Preset.MAXIMUM to Outcome(all),
        Preset.CUSTOM to Outcome(none),
      ),
    )
  }

  @Test
  fun notteInCaricaSuiDatiMobili() {
    check(
      "notte, in carica, dati mobili",
      state(network = MOBILE_VPN_NET),
      mapOf(
        Preset.NIGHT to Outcome(none, aiOnly),
        Preset.BALANCED to Outcome(none, aiOnly),
        Preset.WIFI_ALWAYS to Outcome(none, aiOnly),
        Preset.MAXIMUM to Outcome(all),
        Preset.CUSTOM to Outcome(none, aiOnly),
      ),
    )
  }

  @Test
  fun inRoaming_nessunPresetFaNiente_nemmenoMassimo() {
    check(
      "notte, in carica, roaming",
      state(network = ROAMING_VPN_NET),
      Preset.entries.associateWith { Outcome(none) },
    )
  }

  @Test
  fun reteScadente_E_R4_nessunPresetLavoraInRete_ilCalcoloAiSulBuffer() {
    check(
      "notte, in carica, VPN su rete scadente",
      state(network = EXTERNAL_WIFI_VPN_NET.copy(poor = true)),
      Preset.entries.associateWith { Outcome(none, aiOnly) },
    )
  }

  @Test
  fun nasSpentoNellaNotte_solo_ilCalcoloAiLavoraSulBuffer() {
    check(
      "notte, in carica, NAS spento",
      state(nas = false),
      Preset.entries.associateWith { Outcome(none, aiOnly) },
    )
  }

  @Test
  fun risparmioEnergetico_nessunPresetLavora() {
    check(
      "notte, in carica, risparmio energetico",
      state(powerSave = true),
      Preset.entries.associateWith { Outcome(none) },
    )
  }

  @Test
  fun telefonoCaldo_nessunPresetLavora() {
    check(
      "notte, in carica, stato termico severo",
      state(thermal = ThermalStatus.SEVERE),
      Preset.entries.associateWith { Outcome(none) },
    )
  }

  @Test
  fun profiloNonAttivo_nessunPresetLavora_E_P19() {
    check(
      "notte, in carica, profilo non attivo",
      state(profileActive = false),
      Preset.entries.associateWith { Outcome(none) },
    )
  }

  @Test
  fun tettoDatiMobiliRaggiunto_massimoSiFerma_gliAltriPresetNonCambiano() {
    val full = usage(mobile = 20 * GB)
    val onMobile = state(now = at(12), plugged = false, network = MOBILE_VPN_NET)

    val rules = ruleSetOf(Preset.MAXIMUM)
    for (activity in BackgroundActivity.entries) {
      val decision = evaluator.background(activity, rules, onMobile, full)
      if (activity == AI_COMPUTE) {
        // Senza rete utile (tetto) il Calcolo AI lavora comunque sul buffer.
        assertThat(decision).isEqualTo(RuleDecision.Run(RunScope.LOCAL_BUFFER_ONLY, Pace.NORMAL, NetworkClass.MOBILE))
      } else {
        assertThat(decision).isInstanceOf(RuleDecision.Wait::class.java)
        assertThat((decision as RuleDecision.Wait).reason).isInstanceOf(WaitReason.CapReached::class.java)
      }
    }
  }

  // --- NAS congelato (D10, Fase 4) ----------------------------------------------------------------------------------

  @Test
  fun nasCongelato_chiScriveSulNasNonParteConNessunPreset_ilCalcoloAiResta_sulBuffer() {
    // Ne' "Massimo" (che scavalca quasi tutto) ne' la notte ideale: il congelamento non e' una preferenza.
    check(
      "notte, in carica, NAS congelato, lavori che scrivono",
      state(frozen = FreezeReason.VANISHED),
      Preset.entries.associateWith { Outcome(none, aiOnly) },
      writes = true,
    )
  }

  @Test
  fun nasCongelato_chiLeggeSoltanto_continuaComeSempre() {
    // La scansione (INDEX_SYNC in sola lettura) deve poter girare per accorgersi che la situazione e' tornata normale.
    check(
      "notte, in carica, NAS congelato, lavori che leggono",
      state(frozen = FreezeReason.CHANGED),
      Preset.entries.associateWith { Outcome(all) },
    )
  }

  @Test
  fun nasCongelato_diGiornoConNotte_lAttesaDiceCongelato_primaDellaFascia() {
    val rules = ruleSetOf(Preset.NIGHT)
    val decision =
      evaluator.background(
        INDEX_SYNC,
        rules,
        state(now = at(12), frozen = FreezeReason.NOT_MEDIA),
        writesToNas = true,
      )

    decision as RuleDecision.Wait
    assertThat(decision.reason).isEqualTo(WaitReason.Frozen(FreezeReason.NOT_MEDIA))
    // La fascia oraria resta fra gli ostacoli, dopo: quando l'utente scongela il lavoro aspetta ancora la notte.
    assertThat(decision.alsoBlockedBy.map { it::class }).contains(WaitReason.OutsideWindow::class)
  }

  @Test
  fun nasCongelato_inRoamingNessunPresetFaNiente_comeSenzaCongelamento() {
    check(
      "notte, in carica, roaming, NAS congelato",
      state(network = ROAMING_VPN_NET, frozen = FreezeReason.VANISHED),
      Preset.entries.associateWith { Outcome(none) },
      writes = true,
    )
  }

  // --- "Proteggi subito le foto nuove" (Fase 5, D34) ---------------------------------------------------------------------

  /** Le impostazioni di ogni preset, con "Proteggi subito le foto nuove" acceso (il default). */
  private val settingsOfEveryPreset: Map<Preset, RuleSettings> = Preset.entries.associateWith { RuleSettings(preset = it) }

  private fun quick(
    settings: RuleSettings,
    state: SystemState,
    usage: CycleUsage = usage(),
    running: Boolean = false,
  ) = QuickPhotoProtection.evaluate(evaluator, settings, state, usage, running)

  /** Con qualunque preset la corsa "foto nuove" ha lo stesso verdetto: [runs], oppure aspetta per [reason]. */
  private fun checkQuick(
    scenario: String,
    state: SystemState,
    runs: Boolean,
    reason: WaitReason? = null,
    usage: CycleUsage = usage(),
  ) {
    for ((preset, settings) in settingsOfEveryPreset) {
      val decision = quick(settings, state, usage)
      assertWithMessage("$scenario - $preset").that(decision.isRun).isEqualTo(runs)
      if (!runs && reason != null) {
        assertWithMessage("$scenario - $preset (motivo)").that((decision as RuleDecision.Wait).reason).isEqualTo(reason)
      }
    }
  }

  @Test
  fun fotoNuove_ilGiornoABatteriaInWifiDiCasa_partonoConQualunquePreset_ancheNotte() {
    // Il caso per cui esiste D34: con Notte (e Personalizzato) il backup normale delle foto non parte di giorno a batteria.
    for (preset in Preset.entries) {
      val normal = outcome(preset, state(now = at(12), plugged = false)).full.contains(PHOTO_BACKUP)
      assertWithMessage("senza D34 - $preset").that(normal).isEqualTo(preset in setOf(Preset.BALANCED, Preset.WIFI_ALWAYS, Preset.MAXIMUM))
    }
    checkQuick("giorno, a batteria all'80%, Wi-Fi di casa", state(now = at(12), plugged = false), runs = true)
  }

  @Test
  fun fotoNuove_laNotteInCaricaEIlGiornoInCarica_partono() {
    checkQuick("notte, in carica, Wi-Fi di casa", state(), runs = true)
    checkQuick("giorno, in carica, Wi-Fi di casa", state(now = at(12)), runs = true)
  }

  @Test
  fun fotoNuove_laSogliaDellaBatteriaVale_soloABatteria() {
    checkQuick(
      "giorno, a batteria al 15%",
      state(now = at(12), plugged = false, level = 15),
      runs = false,
      reason = WaitReason.BatteryLow(15, 20),
    )
    // Esattamente alla soglia si parte (come per ogni lavoro); in carica la soglia non si applica.
    checkQuick("giorno, a batteria al 20%", state(now = at(12), plugged = false, level = 20), runs = true)
    checkQuick("giorno, in carica al 5%", state(now = at(12), plugged = true, level = 5), runs = true)
    // La soglia scelta dall'utente vale anche qui.
    val strict = RuleSettings(batteryThresholdPercent = 50)
    assertThat(quick(strict, state(now = at(12), plugged = false, level = 40)).isRun).isFalse()
    assertThat(quick(strict, state(now = at(12), plugged = false, level = 50)).isRun).isTrue()
  }

  @Test
  fun fotoNuove_soloIlWifiDiCasa_maiUnWifiEsternoNeIDatiMobiliNeIlRoaming_nemmenoConMassimo() {
    checkQuick(
      "giorno, a batteria, Wi-Fi esterno via VPN",
      state(now = at(12), plugged = false, network = EXTERNAL_WIFI_VPN_NET),
      runs = false,
      reason = WaitReason.NetworkNotAllowed(NetworkClass.EXTERNAL_WIFI),
    )
    checkQuick(
      "giorno, in carica, dati mobili",
      state(now = at(12), network = MOBILE_VPN_NET),
      runs = false,
      reason = WaitReason.NetworkNotAllowed(NetworkClass.MOBILE),
    )
    checkQuick("notte, in carica, roaming", state(network = ROAMING_VPN_NET), runs = false, reason = WaitReason.Roaming)

    // Massimo apre i dati mobili a tutte le attivita' ma non alla corsa "foto nuove"; l'interruttore del roaming neppure.
    val maxWithRoaming = RuleSettings(preset = Preset.MAXIMUM, allowRoaming = true)
    assertThat(quick(maxWithRoaming, state(now = at(12), network = MOBILE_VPN_NET)).isRun).isFalse()
    assertThat(quick(maxWithRoaming, state(now = at(12), network = ROAMING_VPN_NET)).isRun).isFalse()
    // ... mentre il backup normale di Massimo sui dati mobili parte: la corsa e' piu' stretta, non piu' larga.
    val normal = evaluator.background(PHOTO_BACKUP, maxWithRoaming.toRuleSet(), state(now = at(12), network = MOBILE_VPN_NET))
    assertThat(normal.isRun).isTrue()
  }

  @Test
  fun fotoNuove_restanoTuttiGliAltriOstacoli() {
    checkQuick("NAS spento", state(nas = false), runs = false, reason = WaitReason.NasUnreachable)
    checkQuick("risparmio energetico", state(powerSave = true), runs = false, reason = WaitReason.PowerSaver)
    checkQuick("telefono caldo", state(thermal = ThermalStatus.SEVERE), runs = false, reason = WaitReason.Thermal(ThermalStatus.SEVERE))
    checkQuick("profilo non attivo", state(profileActive = false), runs = false, reason = WaitReason.ProfileInactive)
    checkQuick("rete scadente", state(network = HOME_WIFI_NET.copy(poor = true)), runs = false, reason = WaitReason.PoorNetwork)
    checkQuick("offline", state(network = NetworkState.OFFLINE), runs = false, reason = WaitReason.Offline)
  }

  @Test
  fun fotoNuove_scrivonoSulNas_quindiIlCongelamentoLeFerma_E_B19() {
    checkQuick("NAS congelato", state(frozen = FreezeReason.CHANGED), runs = false, reason = WaitReason.Frozen(FreezeReason.CHANGED))
  }

  @Test
  fun fotoNuove_ilTettoWifiVale() {
    val capped = RuleSettings(capsOverride = MonthlyCaps(mobileBytes = 1 * GB, wifiBytes = 10 * GB))

    val decision = quick(capped, state(now = at(12)), usage(wifi = 11 * GB))

    decision as RuleDecision.Wait
    assertThat(decision.reason).isInstanceOf(WaitReason.CapReached::class.java)
    assertThat(quick(capped, state(now = at(12)), usage(wifi = 2 * GB)).isRun).isTrue()
  }

  @Test
  fun fotoNuove_unLavoroInCorso_siFermaAlloStessoModo() {
    val mobile = state(now = at(12), plugged = false, network = MOBILE_VPN_NET)

    val decision = quick(RuleSettings(), mobile, running = true)

    assertThat(decision).isInstanceOf(RuleDecision.Stop::class.java)
  }

  @Test
  fun fotoNuove_nonToccanoIVideoNeLeAltreAttivita() {
    for ((preset, settings) in settingsOfEveryPreset) {
      val normal = settings.toRuleSet()
      val quick = settings.quickPhotosRuleSet()!!
      for (activity in BackgroundActivity.entries) {
        if (activity == PHOTO_BACKUP) continue
        assertWithMessage("$preset - $activity").that(quick.ruleFor(activity)).isEqualTo(normal.ruleFor(activity))
      }
      assertThat(quick.ruleFor(PHOTO_BACKUP)).isEqualTo(QuickPhotoProtection.RULE)
      assertThat(quick.caps).isEqualTo(normal.caps)
      assertThat(quick.batteryThresholdPercent).isEqualTo(normal.batteryThresholdPercent)
    }
    // Un video di giorno con Notte aspetta la fascia, anche se le foto nuove partono.
    val day = state(now = at(12))
    val settings = RuleSettings(preset = Preset.NIGHT)
    assertThat(quick(settings, day).isRun).isTrue()
    val video = evaluator.evaluate(WorkRequest.Background(VIDEO_BACKUP, writesToNas = true), settings.quickPhotosRuleSet()!!, day, usage())
    assertThat((video as RuleDecision.Wait).reason).isInstanceOf(WaitReason.OutsideWindow::class.java)
    // La richiesta e' sempre e solo quella delle foto.
    assertThat(QuickPhotoProtection.REQUEST).isEqualTo(WorkRequest.Background(PHOTO_BACKUP, writesToNas = true))
  }

  @Test
  fun fotoNuove_siPossonoSpegnere_eNonRiaccendonoUnBackupDelleFotoSpento() {
    val day = state(now = at(12))

    // Spenta: nessuna regola e il verdetto lo dice.
    val off = RuleSettings(protectNewPhotos = false)
    assertThat(off.quickPhotosRuleSet()).isNull()
    assertThat((quick(off, day) as RuleDecision.Wait).reason).isEqualTo(WaitReason.ActivityDisabled)

    // Backup delle foto spento dall'utente: la corsa non lo riaccende, con nessun preset.
    for (preset in Preset.entries) {
      val photosOff = RuleSettings(preset = preset, disabledActivities = setOf(PHOTO_BACKUP))
      assertWithMessage("$preset").that(photosOff.quickPhotosRuleSet()).isNull()
      assertWithMessage("$preset").that((quick(photosOff, day) as RuleDecision.Wait).reason).isEqualTo(WaitReason.ActivityDisabled)
    }
    val customOff =
      RuleSettings(
        preset = Preset.CUSTOM,
        customRules =
          mapOf(
            PHOTO_BACKUP to
              ActivityRule(window = null, power = PowerPolicy.BATTERY_ALLOWED, networks = setOf(AllowedNetwork.HOME_WIFI), enabled = false),
          ),
      )
    assertThat(customOff.quickPhotosRuleSet()).isNull()

    // Un lavoro in corso a cui si spegne la regola si ferma.
    assertThat(quick(off, day, running = true)).isInstanceOf(RuleDecision.Stop::class.java)
  }

  // --- "Fai ora" del backup sui dati mobili (proposta 10, E-B10) ---------------------------------------------------------

  /** ~97 GB di traffico: il primo backup dell'S25 (docs/fase5-progetto.md §2.9). */
  private val firstBackupTraffic = 97 * GB

  private fun now(activity: BackgroundActivity, estimated: Long? = firstBackupTraffic, confirmed: Boolean = false) =
    WorkRequest.UserInitiated(activity, estimated, confirmed, writesToNas = true)

  @Test
  fun faiOraDelBackup_suiDatiMobili_chiedeConfermaConLaStima_conQualunquePreset() {
    // Di giorno, a batteria, sui dati mobili: scavalca fascia e batteria ma non la rete a pagamento.
    val onMobile = state(now = at(12), plugged = false, network = MOBILE_VPN_NET)
    for (preset in Preset.entries) {
      val rules = ruleSetOf(preset)
      val cap = rules.caps.mobileBytes!!
      for (activity in listOf(PHOTO_BACKUP, VIDEO_BACKUP)) {
        val decision = evaluator.evaluate(now(activity), rules, onMobile, usage())

        decision as RuleDecision.NeedsConfirmation
        assertWithMessage("$preset - $activity").that(decision.kinds).contains(ConfirmationKind.MOBILE_DATA)
        // 97 GB superano il tetto mensile (1 GB, 10 GB con Massimo): la conferma lo dice (E-B10).
        assertWithMessage("$preset - $activity").that(decision.kinds).contains(ConfirmationKind.OVER_CAP)
        assertThat(decision.estimate).isEqualTo(TrafficEstimate(firstBackupTraffic, 0, cap))
      }
    }
  }

  @Test
  fun faiOraDelBackup_suiDatiMobili_confermato_parte_senzaPiuIlTetto() {
    val onMobile = state(now = at(12), plugged = false, network = MOBILE_VPN_NET)
    for (preset in Preset.entries) {
      for (activity in listOf(PHOTO_BACKUP, VIDEO_BACKUP)) {
        val decision = evaluator.evaluate(now(activity, confirmed = true), ruleSetOf(preset), onMobile, usage())

        assertWithMessage("$preset - $activity")
          .that(decision)
          .isEqualTo(RuleDecision.Run(RunScope.FULL, Pace.NORMAL, NetworkClass.MOBILE, remainingBytes = null))
      }
    }
  }

  @Test
  fun faiOraDelBackup_unaStimaDentroIlTetto_chiedeSoloLaConfermaDeiDatiMobili() {
    val onMobile = state(now = at(12), plugged = false, network = MOBILE_VPN_NET)

    // Massimo, 2 GB da fare con 10 GB di tetto: solo i dati mobili.
    val decision = evaluator.evaluate(now(PHOTO_BACKUP, estimated = 2 * GB), ruleSetOf(Preset.MAXIMUM), onMobile, usage())

    decision as RuleDecision.NeedsConfirmation
    assertThat(decision.kinds).containsExactly(ConfirmationKind.MOBILE_DATA)
    assertThat(decision.message).isEqualTo("Stai per usare i dati mobili. Servono circa 2 GB. Vuoi continuare?")
  }

  @Test
  fun faiOraDelBackup_alWifiDiCasa_partePerOgniPreset_aQualunqueOra_anchePocaBatteria() {
    // Scavalca fascia, alimentazione e soglia (8%): sul Wi-Fi di casa non chiede niente.
    val lowAtNoon = state(now = at(12), plugged = false, level = 8)
    for (preset in Preset.entries) {
      for (activity in listOf(PHOTO_BACKUP, VIDEO_BACKUP)) {
        val decision = evaluator.evaluate(now(activity), ruleSetOf(preset), lowAtNoon, usage())

        assertWithMessage("$preset - $activity").that(decision.isRun).isTrue()
      }
    }
  }

  @Test
  fun faiOraDelBackup_inRoaming_chiedeLaConfermaDelRoaming() {
    for (preset in Preset.entries) {
      val decision = evaluator.evaluate(now(PHOTO_BACKUP), ruleSetOf(preset), state(network = ROAMING_VPN_NET), usage())

      decision as RuleDecision.NeedsConfirmation
      assertWithMessage("$preset").that(decision.kinds).contains(ConfirmationKind.ROAMING)
    }
  }

  @Test
  fun faiOraDelBackup_nonScavalcaChiNonSiPuoFare_congelamentoNasSpentoReteCriticaTermica() {
    val rules = ruleSetOf(Preset.MAXIMUM)

    fun reason(state: SystemState) =
      (evaluator.evaluate(now(PHOTO_BACKUP, confirmed = true), rules, state, usage()) as RuleDecision.Wait).reason

    // Neanche un "Fai ora" confermato scrive su una share congelata (D10); ne' sul NAS spento, ne' senza rete, ne' su un Wi-Fi sconosciuto.
    assertThat(reason(state(frozen = FreezeReason.VANISHED))).isEqualTo(WaitReason.Frozen(FreezeReason.VANISHED))
    assertThat(reason(state(nas = false))).isEqualTo(WaitReason.NasUnreachable)
    assertThat(reason(state(network = NetworkState.OFFLINE))).isEqualTo(WaitReason.Offline)
    assertThat(reason(state(network = NetworkState(NetworkTransport.WIFI)))).isEqualTo(WaitReason.NetworkNotTrusted)
    assertThat(reason(state(thermal = ThermalStatus.CRITICAL))).isEqualTo(WaitReason.Thermal(ThermalStatus.CRITICAL))
  }
}
