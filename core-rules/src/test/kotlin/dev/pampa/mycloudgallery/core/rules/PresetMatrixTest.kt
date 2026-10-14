package dev.pampa.mycloudgallery.core.rules

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.AI_COMPUTE
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.INDEX_SYNC
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.PHOTO_BACKUP
import org.junit.Test

/**
 * Tutti i preset contro gli scenari tipici: per ogni coppia (preset, scenario) quali attivita' girano
 * del tutto (`full`) e quali solo sul buffer locale (`local`, il Calcolo AI senza NAS o senza rete
 * ammessa). E' la tabella di docs/regole-attivita.md in forma eseguibile.
 *
 * Fase 4 (D10): gli scenari "NAS congelato" valutano i lavori che scrivono sul NAS (`writes = true`) e quelli che
 * leggono soltanto, per ogni preset.
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
}
