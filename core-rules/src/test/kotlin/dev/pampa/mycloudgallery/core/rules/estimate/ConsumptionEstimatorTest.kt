package dev.pampa.mycloudgallery.core.rules.estimate

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.ActivityRule
import dev.pampa.mycloudgallery.core.rules.AllowedNetwork
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.AI_COMPUTE
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.HQ_PREVIEWS
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.PHOTO_BACKUP
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.VIDEO_BACKUP
import dev.pampa.mycloudgallery.core.rules.GB
import dev.pampa.mycloudgallery.core.rules.MB
import dev.pampa.mycloudgallery.core.rules.MonthlyCaps
import dev.pampa.mycloudgallery.core.rules.PowerPolicy
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.ruleSetOf
import org.junit.Assert.assertThrows
import org.junit.Test

class ConsumptionEstimatorTest {

  private val estimator = DefaultConsumptionEstimator()
  private val measures = DeviceMeasures.PHASE_0
  private val profile = UsageProfile()

  private fun backlog(
    pending: Map<BackgroundActivity, Workload> = emptyMap(),
    monthly: Map<BackgroundActivity, Workload> = emptyMap(),
  ) = LibraryBacklog(pending, monthly)

  @Test
  fun leCostantiDiDefaultSonoQuelleDellaFase0() {
    assertThat(measures.smbWriteBytesPerSecond).isEqualTo(9.0e6) // ~9 MB/s, scrittura cifrata
    assertThat(measures.smbReadBytesPerSecond).isEqualTo(16.0e6) // ~16 MB/s, lettura cifrata, 4 flussi
    assertThat(measures.aiItemsPerSecond).isEqualTo(2.8) // GPU, con decodifica
    assertThat(measures.aiBatteryPercentPerThousandItems).isEqualTo(4.0) // ~4% ogni 1000 foto
    assertThat(measures.analysisPreviewBytes).isEqualTo(150_000L)
    // Fase 5 (docs/regole-attivita.md §6): le tre misure del backup, tutte ipotesi da ricalibrare a M2 e al primo backup vero.
    assertThat(measures.verifyReadBytesPerSecond).isEqualTo(16.0e6)
    assertThat(measures.perFileOverheadSeconds).isEqualTo(0.12)
    assertThat(measures.uploadStreams).isEqualTo(1)
  }

  @Test
  fun leMisureDelBackupHannoValoriAmmessi() {
    assertThrows(IllegalArgumentException::class.java) { measures.copy(verifyReadBytesPerSecond = 0.0) }
    assertThrows(IllegalArgumentException::class.java) { measures.copy(perFileOverheadSeconds = -0.1) }
    assertThrows(IllegalArgumentException::class.java) { measures.copy(uploadStreams = 0) }
    // Un costo per file nullo e' ammesso (la misura puo' venire fuori trascurabile).
    assertThat(measures.copy(perFileOverheadSeconds = 0.0).perFileOverheadSeconds).isEqualTo(0.0)
  }

  @Test
  fun calcoloAi_ventimilaFoto_sonoCircaDueOre_comeInFase0() {
    val estimate =
      estimator.estimate(ruleSetOf(Preset.NIGHT), backlog(pending = mapOf(AI_COMPUTE to Workload(20_000, 3 * GB))), measures, profile)

    val ai = estimate.perActivity.getValue(AI_COMPUTE)
    assertThat(ai.workHours).isWithin(0.01).of(1.984) // 20000 / 2,8 foto/s = 7143 s
    // 4% ogni 1000 foto: 80% di energia in totale, spesa in una notte perche' il lavoro ci sta tutto.
    assertThat(ai.pendingEnergyPercent).isWithin(1e-9).of(80.0)
    assertThat(estimate.batteryPercentPerNight).isWithin(1e-9).of(80.0)
    // Notte = solo in carica: niente di tutto questo pesa sulla batteria.
    assertThat(estimate.batteryDrainPercentPerNight).isEqualTo(0.0)
    // 8 ore di fascia x 0,8 di efficienza = 6,4 ore utili: basta una notte.
    assertThat(ai.usableHoursPerDay).isWithin(1e-9).of(6.4)
    assertThat(estimate.daysToFinish!!).isWithin(0.01).of(7142.857 / (6.4 * 3600))
  }

  @Test
  fun backup_tempoESpesaDalleVelocitaSmb_conLaRilettura_eIlCostoPerFile() {
    // Fase 5, D4: ogni file si scrive e si rilegge per intero. 10 GB in 5.000 file: T = B/W + B/R + N·o.
    val estimate =
      estimator.estimate(ruleSetOf(Preset.NIGHT), backlog(pending = mapOf(PHOTO_BACKUP to Workload(5000, 10 * GB))), measures, profile)

    val seconds = 10e9 / 9e6 + 10e9 / 16e6 + 5000 * 0.12
    assertThat(estimate.perActivity.getValue(PHOTO_BACKUP).workHours).isWithin(0.001).of(seconds / 3600)
    // Il traffico e' il doppio dei media (caricamento + rilettura): 20 GB x 0,5 %/GB.
    assertThat(estimate.perActivity.getValue(PHOTO_BACKUP).pendingTrafficBytes).isEqualTo(20 * GB)
    assertThat(estimate.batteryPercentPerNight).isWithin(1e-9).of(10.0)
  }

  @Test
  fun backup_leCopieGiaSulNasCostanoLaSolaRilettura() {
    // 40 GB gia' sul NAS da collegare: solo la rilettura, a 16 MB/s (§2.11 punto 5: "circa 45 minuti"), e 40 GB di traffico.
    val estimate =
      estimator.estimate(
        ruleSetOf(Preset.NIGHT),
        LibraryBacklog(verify = mapOf(VIDEO_BACKUP to Workload(300, 40 * GB))),
        measures,
        profile,
      )

    val video = estimate.perActivity.getValue(VIDEO_BACKUP)
    assertThat(video.workHours * 60).isWithin(0.1).of(40e9 / 16e6 / 60) // ~41,7 minuti
    assertThat(video.pendingTrafficBytes).isEqualTo(40 * GB)
    assertThat(estimate.batteryPercentPerNight).isWithin(1e-9).of(40 * 0.5) // 40 GB di traffico x 0,5 %/GB
  }

  @Test
  fun backup_laRilettura_eIlCostoPerFile_sonoSostituibili() {
    val slowVerify = measures.copy(verifyReadBytesPerSecond = 8.0e6, perFileOverheadSeconds = 0.5)
    val work = mapOf(PHOTO_BACKUP to Workload(1000, 4 * GB))

    val base = estimator.estimate(ruleSetOf(Preset.NIGHT), backlog(pending = work), measures, profile)
    val slow = estimator.estimate(ruleSetOf(Preset.NIGHT), backlog(pending = work), slowVerify, profile)

    assertThat(base.perActivity.getValue(PHOTO_BACKUP).workHours * 3600).isWithin(0.01).of(4e9 / 9e6 + 4e9 / 16e6 + 1000 * 0.12)
    assertThat(slow.perActivity.getValue(PHOTO_BACKUP).workHours * 3600).isWithin(0.01).of(4e9 / 9e6 + 4e9 / 8e6 + 1000 * 0.5)
  }

  @Test
  fun lettura_tempoDalleVelocitaSmbInLettura() {
    // Il sync dell'indice, la preparazione e le anteprime HQ leggono: 16 MB/s.
    val estimate =
      estimator.estimate(ruleSetOf(Preset.NIGHT), backlog(pending = mapOf(HQ_PREVIEWS to Workload(1000, 16 * GB / 10))), measures, profile)

    assertThat(estimate.perActivity.getValue(HQ_PREVIEWS).workHours).isWithin(0.001).of(1.6e9 / 16e6 / 3600)
  }

  @Test
  fun videoSuNotte_ilTempoSiSommaAQuelloDelleFoto_chiCondividonoLaBanda() {
    val rules = ruleSetOf(Preset.NIGHT)
    val estimate =
      estimator.estimate(
        rules,
        backlog(pending = mapOf(VIDEO_BACKUP to Workload(300, 250 * GB), PHOTO_BACKUP to Workload(5000, 10 * GB))),
        measures,
        profile,
      )

    // Ogni file si scrive e si rilegge (Fase 5): T = B/W + B/R + N·o.
    val videoSeconds = 250e9 / 9e6 + 250e9 / 16e6 + 300 * 0.12
    val photoSeconds = 10e9 / 9e6 + 10e9 / 16e6 + 5000 * 0.12
    // Pesante = solo Wi-Fi di casa; 6,4 ore utili; la banda e' una sola.
    assertThat(estimate.daysToFinish!!).isWithin(0.001).of((videoSeconds + photoSeconds) / (6.4 * 3600))
    // In una notte si smaltisce solo una parte: il resto continua la notte dopo.
    assertThat(estimate.perActivity.getValue(VIDEO_BACKUP).fractionPerNight).isLessThan(1.0)
    assertThat(estimate.perActivity.getValue(VIDEO_BACKUP).fractionPerNight)
      .isWithin(0.001).of(6.4 * 3600 / (videoSeconds + photoSeconds))
  }

  @Test
  fun pesantiSoloACasa_haPiuOreUtiliSeLaRegolaSiSpegne() {
    val backlog = backlog(pending = mapOf(VIDEO_BACKUP to Workload(300, 100 * GB)))
    val strict = ruleSetOf(Preset.WIFI_ALWAYS)
    val loose = strict.copy(heavyOnlyAtHome = false)

    val strictHours = estimator.estimate(strict, backlog, measures, profile).perActivity.getValue(VIDEO_BACKUP).usableHoursPerDay
    val looseHours = estimator.estimate(loose, backlog, measures, profile).perActivity.getValue(VIDEO_BACKUP).usableHoursPerDay

    // Solo casa: 14 h x 0,8. Anche Wi-Fi esterno: (14 + 1) h x 0,8.
    assertThat(strictHours).isWithin(1e-9).of(14 * 0.8)
    assertThat(looseHours).isWithin(1e-9).of(15 * 0.8)
  }

  @Test
  fun preset_batteriaChePesaDavveroSullaBatteria() {
    val backlog = backlog(pending = mapOf(PHOTO_BACKUP to Workload(1000, 4 * GB), AI_COMPUTE to Workload(3000, 450_000_000)))

    val night = estimator.estimate(ruleSetOf(Preset.NIGHT), backlog, measures, profile)
    val wifiAlways = estimator.estimate(ruleSetOf(Preset.WIFI_ALWAYS), backlog, measures, profile)

    assertThat(night.batteryDrainPercentPerNight).isEqualTo(0.0)
    // Wi-Fi sempre: tutto puo' girare a batteria, quindi tutta l'energia pesa sulla batteria.
    assertThat(wifiAlways.batteryDrainPercentPerNight).isWithin(1e-9).of(wifiAlways.batteryPercentPerNight)
    // 4 GB di foto = 8 GB di traffico (caricamento + rilettura) x 0,5 %/GB, piu' il Calcolo AI.
    assertThat(wifiAlways.batteryPercentPerNight).isWithin(1e-9).of(8 * 0.5 + 3 * 4.0)
  }

  @Test
  fun bilanciato_soloLeLeggerePesanoSullaBatteria() {
    val backlog = backlog(pending = mapOf(PHOTO_BACKUP to Workload(1000, 4 * GB), AI_COMPUTE to Workload(3000, 450_000_000)))

    val estimate = estimator.estimate(ruleSetOf(Preset.BALANCED), backlog, measures, profile)

    assertThat(estimate.batteryDrainPercentPerNight).isWithin(1e-9).of(8 * 0.5) // solo il backup foto (8 GB di traffico)
    assertThat(estimate.batteryPercentPerNight).isWithin(1e-9).of(8 * 0.5 + 12.0)
  }

  @Test
  fun aRegime_ilPresetNotteStaSotto_il2PerCentoAlGiorno() {
    // Un mese tipico di media nuovi: 600 foto (3 GB), 20 video (10 GB), tutto da indicizzare.
    val monthly =
      mapOf(
        PHOTO_BACKUP to Workload(600, 3 * GB),
        VIDEO_BACKUP to Workload(20, 10 * GB),
        AI_COMPUTE to Workload(620, 93 * MB),
      )

    val estimate = estimator.estimate(ruleSetOf(Preset.NIGHT), backlog(monthly = monthly), measures, profile)

    // (26 GB di traffico (13 GB di media, caricati e riletti) x 0,5 + 0,62 x 4) / 30 giorni.
    assertThat(estimate.steadyBatteryPercentPerDay).isWithin(1e-9).of((26 * 0.5 + 0.62 * 4.0) / 30.0)
    assertThat(estimate.steadyBatteryPercentPerDay).isLessThan(2.0) // obiettivo del piano §4
  }

  @Test
  fun datiMobili_zeroConNotte_eUnaQuotaConMassimo_entroIlTetto() {
    val monthly = mapOf(PHOTO_BACKUP to Workload(600, 3 * GB))
    val pending = mapOf(PHOTO_BACKUP to Workload(5000, 10 * GB))

    val night = estimator.estimate(ruleSetOf(Preset.NIGHT), backlog(pending, monthly), measures, profile)
    assertThat(night.mobileBytesPerMonth).isEqualTo(0)
    assertThat(night.mobileBytesFirstMonth).isEqualTo(0)
    // 3 GB di media = 6 GB di traffico (la rilettura conta, Fase 5).
    assertThat(night.wifiBytesPerMonth).isEqualTo(6 * GB)
    assertThat(night.wifiBytesFirstMonth).isEqualTo(26 * GB)

    // Massimo: le ore sul mobile sono 8 su 8 + 14 + 1 = 23.
    val share = 8.0 / 23.0
    val max = estimator.estimate(ruleSetOf(Preset.MAXIMUM), backlog(pending, monthly), measures, profile)
    assertThat(max.mobileBytesPerMonth.toDouble()).isWithin(1e6).of(6e9 * share)
    assertThat(max.mobileBytesFirstMonth.toDouble()).isWithin(1e6).of(26e9 * share)
    assertThat(max.perActivity.getValue(PHOTO_BACKUP).mobileShare).isWithin(1e-9).of(share)

    // Il tetto limita i dati mobili.
    val capped = ruleSetOf(Preset.MAXIMUM).copy(caps = MonthlyCaps(mobileBytes = 1 * GB, wifiBytes = null))
    val limited = estimator.estimate(capped, backlog(pending, monthly), measures, profile)
    assertThat(limited.mobileBytesPerMonth).isEqualTo(1 * GB)
    assertThat(limited.mobileBytesFirstMonth).isEqualTo(1 * GB)
  }

  @Test
  fun tettoWifi_limitaIDatiWifi() {
    val monthly = mapOf(PHOTO_BACKUP to Workload(600, 30 * GB))
    val capped = ruleSetOf(Preset.NIGHT).copy(caps = MonthlyCaps(mobileBytes = 1 * GB, wifiBytes = 5 * GB))

    assertThat(estimator.estimate(capped, backlog(monthly = monthly), measures, profile).wifiBytesPerMonth).isEqualTo(5 * GB)
  }

  @Test
  fun regoleCheNonLasciano_maiPartire_ilLavoroNonFinisceMai() {
    val rules = ruleSetOf(Preset.NIGHT)
    val noNetwork = rules.copy(rules = rules.rules + (PHOTO_BACKUP to rules.ruleFor(PHOTO_BACKUP).copy(networks = emptySet())))

    val estimate = estimator.estimate(noNetwork, backlog(pending = mapOf(PHOTO_BACKUP to Workload(10, 1 * GB))), measures, profile)

    assertThat(estimate.daysToFinish).isNull()
    assertThat(estimate.perActivity.getValue(PHOTO_BACKUP).daysToFinish).isNull()
    assertThat(EstimateText.describe(estimate)).contains("non finisce mai")
  }

  @Test
  fun ilCalcoloAiNonDipendeDallaRete_lavoraInLocale() {
    val rules = ruleSetOf(Preset.NIGHT)
    val noNetwork = rules.copy(rules = rules.rules + (AI_COMPUTE to rules.ruleFor(AI_COMPUTE).copy(networks = emptySet())))

    val estimate = estimator.estimate(noNetwork, backlog(pending = mapOf(AI_COMPUTE to Workload(1000, 150 * MB))), measures, profile)

    assertThat(estimate.daysToFinish).isNotNull()
  }

  @Test
  fun attivitaSpenta_nonContaNelleStime() {
    val rules = ruleSetOf(Preset.NIGHT)
    val off = rules.copy(rules = rules.rules + (VIDEO_BACKUP to rules.ruleFor(VIDEO_BACKUP).copy(enabled = false)))
    val backlog = backlog(pending = mapOf(VIDEO_BACKUP to Workload(300, 250 * GB), PHOTO_BACKUP to Workload(100, 1 * GB)))

    val estimate = estimator.estimate(off, backlog, measures, profile)

    assertThat(estimate.perActivity.getValue(VIDEO_BACKUP).enabled).isFalse()
    assertThat(estimate.perActivity.getValue(VIDEO_BACKUP).daysToFinish).isEqualTo(0.0)
    assertThat(estimate.batteryPercentPerNight).isWithin(1e-9).of(1.0) // solo 1 GB di foto (2 GB di traffico)
  }

  @Test
  fun senzaLavoroArretrato_tuttoZero() {
    val estimate = estimator.estimate(ruleSetOf(Preset.MAXIMUM), backlog(), measures, profile)

    assertThat(estimate.daysToFinish).isEqualTo(0.0)
    assertThat(estimate.batteryPercentPerNight).isEqualTo(0.0)
    assertThat(estimate.mobileBytesPerMonth).isEqualTo(0)
    assertThat(EstimateText.describe(estimate)).contains("nessun lavoro arretrato")
  }

  @Test
  fun lemisureSonoSostituibili() {
    val faster = measures.copy(aiItemsPerSecond = 12.0, aiBatteryPercentPerThousandItems = 1.5) // NPU
    val backlog = backlog(pending = mapOf(AI_COMPUTE to Workload(12_000, 1_800_000_000)))

    val base = estimator.estimate(ruleSetOf(Preset.NIGHT), backlog, measures, profile)
    val npu = estimator.estimate(ruleSetOf(Preset.NIGHT), backlog, faster, profile)

    assertThat(npu.perActivity.getValue(AI_COMPUTE).workHours).isWithin(1e-9).of(12_000 / 12.0 / 3600)
    assertThat(npu.batteryPercentPerNight).isLessThan(base.batteryPercentPerNight)
    assertThat(npu.batteryPercentPerNight).isWithin(1e-9).of(18.0)

    assertThrows(IllegalArgumentException::class.java) { measures.copy(aiItemsPerSecond = 0.0) }
    assertThrows(IllegalArgumentException::class.java) { Workload(-1, 0) }
  }

  @Test
  fun leOreUtiliDipendonoDallaFasciaDallaCaricaEDallaRete() {
    val rules = ruleSetOf(Preset.NIGHT)
    val custom =
      rules.copy(
        rules =
          rules.rules +
            (PHOTO_BACKUP to ActivityRule(window = null, power = PowerPolicy.PLUGGED_ONLY, networks = setOf(AllowedNetwork.HOME_WIFI)))
      )
    val backlog = backlog(pending = mapOf(PHOTO_BACKUP to Workload(100, 1 * GB)))

    // Nessuna fascia (24 h), ma serve la carica (8 h/giorno): 8 x 0,8.
    val hours = estimator.estimate(custom, backlog, measures, profile).perActivity.getValue(PHOTO_BACKUP).usableHoursPerDay
    assertThat(hours).isWithin(1e-9).of(6.4)
    // Piu' ore di carica (tablet sulla scrivania): salgono.
    val desk = estimator.estimate(custom, backlog, measures, profile.copy(pluggedHoursPerDay = 20.0))
    assertThat(desk.perActivity.getValue(PHOTO_BACKUP).usableHoursPerDay).isWithin(1e-9).of(14 * 0.8) // limitato dal Wi-Fi di casa
  }

  @Test
  fun ilRiepilogoInItaliano() {
    val estimate =
      estimator.estimate(ruleSetOf(Preset.NIGHT), backlog(pending = mapOf(AI_COMPUTE to Workload(20_000, 3 * GB))), measures, profile)

    assertThat(EstimateText.describe(estimate))
      .isEqualTo("Circa 80% di energia per notte (tutta dalla presa), 0 KB di dati mobili al mese, arretrato smaltito entro un giorno.")

    val long =
      estimator.estimate(
        ruleSetOf(Preset.WIFI_ALWAYS),
        backlog(pending = mapOf(VIDEO_BACKUP to Workload(300, 250 * GB))),
        measures,
        profile,
      )
    // 250 GB / 9 MB/s = 7,7 ore di lavoro; 11,2 ore utili al giorno -> 1 giorno... e con Notte: piu' notti.
    val nights =
      estimator.estimate(
        ruleSetOf(Preset.NIGHT),
        backlog(pending = mapOf(VIDEO_BACKUP to Workload(300, 750 * GB))),
        measures,
        profile,
      )
    assertThat(EstimateText.describe(nights)).contains("giorni")
    assertThat(EstimateText.describe(long)).contains("dalla batteria")
  }
}
