package dev.pampa.mycloudgallery.core.rules.estimate

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.PHOTO_BACKUP
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.VIDEO_BACKUP
import dev.pampa.mycloudgallery.core.rules.GB
import dev.pampa.mycloudgallery.core.rules.MB
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.ruleSetOf
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 5A, P4: la tabella dei preset del §2.9 di `docs/fase5-progetto.md` riprodotta dallo stimatore con la **composizione del §2.9**
 * (le dimensioni medie ipotizzate dal documento, i conteggi veri dell'S25 del 12/10). Per ogni numero il test confronta il valore
 * ottenuto con quello scritto nel documento (che e' arrotondato, "~45 min", "~97 GB"): le tolleranze sono quelle dell'arrotondamento.
 *
 * Formula: T = B/W + B/R + N·o, con W = 9 MB/s, R = 16 MB/s, o = 0,12 s; il traffico e' il doppio dei media (caricamento + rilettura).
 */
class BackupEstimatorTest {

  private val measures = DeviceMeasures.PHASE_0
  private val estimator = BackupEstimator(DefaultConsumptionEstimator(), measures, UsageProfile())

  /** Un gruppo della tabella "Composizione dell'S25" del §2.9: [files] media di [eachBytes] byte in media. */
  private class Group(val files: Long, val eachBytes: Long) {
    val workload: Workload get() = Workload(files, files * eachBytes)
  }

  // La composizione del §2.9 (conteggi veri del 12/10, dimensioni medie ipotizzate).
  private val camera = Group(866, 4 * MB) // DCIM/Camera
  private val dcimSub = Group(1_088, 3 * MB) // sottocartella di DCIM
  private val screenshots = Group(915, 700_000)
  private val messaging = Group(2_105, 200_000) // Android/media/...
  private val quickShare = Group(654, 2_500_000)
  private val pictures = Group(646, 1_500_000) // Pictures e altre
  private val videos = Group(384, 100 * MB)

  private val photoGroups = listOf(camera, dcimSub, screenshots, messaging, quickShare, pictures)
  private val photos = Workload(photoGroups.sumOf { it.files }, photoGroups.sumOf { it.files * it.eachBytes })
  private val s25 = BackupWork(upload = MediaVolume(photos, videos.workload))

  private fun minutes(seconds: Double) = seconds / 60.0

  private fun hours(seconds: Double) = seconds / 3600.0

  /** Un lavoro di [bytes] byte di media in [files] file (per il caso basso e il caso alto del §2.9). */
  private fun work(files: Long, bytes: Long) = BackupWork(upload = MediaVolume(photos = Workload(files, bytes)))

  // --- La composizione -------------------------------------------------------------------------------------------------

  @Test
  fun laComposizioneDellS25_eQuellaDelDocumento() {
    assertThat(photos.items).isEqualTo(6_274)
    assertThat(videos.files).isEqualTo(384)
    assertThat(photos.items + videos.files).isEqualTo(6_658)
    // Documento: foto ~10,4 GB, video ~38 GB (38,4 con 100 MB l'uno), totale ~48 GB.
    assertThat(photos.bytes / 1e9).isWithin(0.1).of(10.4)
    assertThat(videos.workload.bytes / 1e9).isWithin(0.5).of(38.0)
    assertThat((photos.bytes + videos.workload.bytes) / 1e9).isWithin(1.0).of(48.0)
  }

  // --- Tempo del primo backup (in casa, S25): T = B/W + B/R + N·o --------------------------------------------------------

  @Test
  fun ilTempoDelPrimoBackup_riproduceLaTabellaDelDocumento() {
    val photoCost = estimator.cost(s25.photosOnly())
    val videoCost = estimator.cost(s25.videosOnly())
    val total = estimator.cost(s25)

    // Foto (6.274, ~10,4 GB): caricamento ~19 min, rilettura ~11, costo per file ~13, tempo ~45 min (19 + 11 + 13 = 43).
    assertThat(minutes(photoCost.uploadSeconds)).isWithin(1.0).of(19.0)
    assertThat(minutes(photoCost.rereadSeconds)).isWithin(1.0).of(11.0)
    assertThat(minutes(photoCost.perFileSeconds)).isWithin(1.0).of(13.0)
    assertThat(minutes(photoCost.workSeconds)).isWithin(3.0).of(45.0)
    // Video (384, ~38 GB): ~70 min, ~40 min, ~1 min, tempo ~1 h 50.
    assertThat(minutes(videoCost.uploadSeconds)).isWithin(1.5).of(70.0)
    assertThat(minutes(videoCost.rereadSeconds)).isWithin(1.0).of(40.0)
    assertThat(minutes(videoCost.perFileSeconds)).isWithin(0.5).of(1.0)
    assertThat(minutes(videoCost.workSeconds)).isWithin(3.0).of(110.0)
    // Totale: caricamento ~90 min, rilettura ~50, costo per file ~14, tempo ~2,6 h.
    assertThat(minutes(total.uploadSeconds)).isWithin(1.5).of(90.0)
    assertThat(minutes(total.rereadSeconds)).isWithin(1.5).of(50.0)
    assertThat(minutes(total.perFileSeconds)).isWithin(1.0).of(14.0)
    assertThat(hours(total.workSeconds)).isWithin(0.1).of(2.6)
  }

  @Test
  fun casoBassoEAlto_ilTempoSta_traUnOraEMezzaECinqueOreETrequarti() {
    // Totale da ~26 a ~106 GB con gli stessi 6.658 file: caso basso ~1,5 h, caso alto ~5,3 h.
    assertThat(hours(estimator.cost(work(6_658, 26 * GB)).workSeconds)).isWithin(0.1).of(1.5)
    assertThat(hours(estimator.cost(work(6_658, 106 * GB)).workSeconds)).isWithin(0.1).of(5.3)
  }

  @Test
  fun leSolePredefinite_costanoCircaLaMeta() {
    // "Solo le sorgenti predefinite (fotocamera e screenshot, ~1.800 foto e i video della fotocamera) costano circa la meta'":
    // fotocamera + screenshot = 1.781 foto (~4,1 GB) e i video della fotocamera: la meta' dei 384 (~19 GB).
    val defaults = BackupWork(upload = MediaVolume((camera.workload + screenshots.workload), Workload(192, 192 * 100 * MB)))

    val ratio = estimator.cost(defaults).workSeconds / estimator.cost(s25).workSeconds
    assertThat(ratio).isWithin(0.1).of(0.5)
  }

  // --- Traffico e batteria del primo backup ------------------------------------------------------------------------------

  @Test
  fun ilTraffico_eIlDoppioDeiMedia_laRilettura_conta() {
    val total = estimator.cost(s25)
    // Valore centrale ~97 GB (48 caricati + 48 riletti); caso basso ~52, alto ~212.
    assertThat(total.uploadedBytes).isEqualTo(photos.bytes + videos.workload.bytes)
    assertThat(total.rereadBytes).isEqualTo(total.uploadedBytes)
    assertThat(total.trafficBytes / 1e9).isWithin(1.0).of(97.0)
    assertThat(estimator.cost(work(6_658, 26 * GB)).trafficBytes / 1e9).isWithin(0.5).of(52.0)
    assertThat(estimator.cost(work(6_658, 106 * GB)).trafficBytes / 1e9).isWithin(0.5).of(212.0)
  }

  @Test
  fun ilTettoDeiDatiMobili_valeMetaDeiMedia() {
    // "Con Massimo al piu' il tetto di 10 GB, cioe' ~5 GB di media (la rilettura conta)".
    assertThat(BackupCosts.mediaBytesWithin(10 * GB)).isEqualTo(5 * GB)
  }

  // --- Primo backup per preset (valore centrale, ~6.700 media, ~48 GB) -----------------------------------------------------

  @Test
  fun notte_finisceInUnaNotte_dallaPresa() {
    val plan = estimator.scheduled(ruleSetOf(Preset.NIGHT), s25)

    // 23-07 in carica sono ~6,4 h utili (8 h x efficienza 0,8) contro ~2,6 h di lavoro.
    assertThat(plan.perActivity.getValue(PHOTO_BACKUP).usableHoursPerDay).isWithin(1e-9).of(6.4)
    assertThat(plan.perActivity.getValue(VIDEO_BACKUP).usableHoursPerDay).isWithin(1e-9).of(6.4)
    assertThat(plan.nights).isEqualTo(1)
    assertThat(plan.neverFinishes).isFalse()
    // 0% dalla batteria (dalla presa); ~97 GB sul Wi-Fi di casa, niente dati mobili.
    assertThat(plan.batteryDrainPercent).isEqualTo(0.0)
    assertThat(plan.mobileBytes).isEqualTo(0)
    assertThat(plan.wifiBytes / 1e9).isWithin(1.0).of(97.0)
  }

  @Test
  fun notte_ancheIlCasoAlto_staInUnaNotte_eSeIlTelefonoNonRestaInCarica_servonoDue() {
    // Il caso alto (5,3 h) sta in una notte.
    assertThat(estimator.scheduled(ruleSetOf(Preset.NIGHT), work(6_658, 106 * GB)).nights).isEqualTo(1)

    // "2 notti se il telefono non resta in carica tutta la notte": 3 ore di carica a notte -> 2,4 h utili contro ~2,6 h di lavoro.
    val shortCharge = BackupEstimator(DefaultConsumptionEstimator(), measures, UsageProfile(pluggedHoursPerDay = 3.0))
    assertThat(shortCharge.scheduled(ruleSetOf(Preset.NIGHT), s25).nights).isEqualTo(2)
  }

  @Test
  fun bilanciato_leFotoInGiornata_iVideoLaNotte_dieciTredicePerCentoSeABatteria() {
    val plan = estimator.scheduled(ruleSetOf(Preset.BALANCED), s25)

    val photoEstimate = plan.perActivity.getValue(PHOTO_BACKUP)
    // Foto in giornata appena c'e' il Wi-Fi (~45 min), anche a batteria; video con la corrente (6,4 h utili al giorno).
    assertThat(photoEstimate.workHours * 60).isWithin(3.0).of(45.0)
    assertThat(photoEstimate.fractionPerNight).isEqualTo(1.0)
    assertThat(photoEstimate.usableHoursPerDay).isWithin(1e-9).of(12.0) // 14 h di Wi-Fi di casa + 1 h di VPN = 15 h, x efficienza 0,8
    assertThat(plan.perActivity.getValue(VIDEO_BACKUP).usableHoursPerDay).isWithin(1e-9).of(6.4)
    assertThat(plan.nights).isEqualTo(1)
    // "~10-13% per le foto se a batteria (traffico piu' preparazione)": il modello del traffico da' 10,4% (la preparazione, qualche
    // decimo di secondo di CPU a foto, non entra nel modello dell'energia: e' il limite basso dell'intervallo del documento).
    assertThat(plan.batteryDrainPercent).isWithin(0.1).of(10.4)
    assertThat(plan.batteryDrainPercent).isAtLeast(10.0)
    assertThat(plan.batteryDrainPercent).isAtMost(13.0)
    assertThat(plan.mobileBytes).isEqualTo(0)
    assertThat(plan.wifiBytes / 1e9).isWithin(1.0).of(97.0)
  }

  @Test
  fun wifiSempre_circaDueOreESei_anceABatteria_quarantaCinqueCinquantaPerCento() {
    val plan = estimator.scheduled(ruleSetOf(Preset.WIFI_ALWAYS), s25)

    assertThat(hours(plan.cost.workSeconds)).isWithin(0.1).of(2.6)
    assertThat(plan.nights).isEqualTo(1)
    // "~45-50% secondo il modello: meglio in carica": tutto il traffico (97 GB) a 0,5 %/GB.
    assertThat(plan.batteryDrainPercent).isAtLeast(45.0)
    assertThat(plan.batteryDrainPercent).isAtMost(50.0)
    assertThat(plan.batteryDrainPercent).isWithin(1e-9).of(plan.energyPercent)
    assertThat(plan.mobileBytes).isEqualTo(0)
  }

  @Test
  fun massimo_comeWifiSempre_aCasa_efuoriCasaIDatiMobiliEntroIlTettoDiDieciGb() {
    val plan = estimator.scheduled(ruleSetOf(Preset.MAXIMUM), s25)
    val wifiAlways = estimator.scheduled(ruleSetOf(Preset.WIFI_ALWAYS), s25)

    assertThat(hours(plan.cost.workSeconds)).isWithin(0.1).of(2.6)
    // Fino a 10 GB di dati mobili (il tetto del preset), il resto sul Wi-Fi.
    assertThat(plan.mobileBytes).isEqualTo(10 * GB)
    assertThat(plan.mobileBytes + plan.wifiBytes).isEqualTo(plan.cost.trafficBytes)
    // A casa la batteria e' quella di "Wi-Fi sempre"; il modello aggiunge il fattore radio x2 sulla quota di ore sui dati mobili.
    assertThat(plan.batteryDrainPercent).isAtLeast(wifiAlways.batteryDrainPercent)
    assertThat(plan.batteryDrainPercent).isWithin(0.01).of(wifiAlways.batteryDrainPercent * (1.0 + 8.0 / 23.0))
  }

  @Test
  fun faiOra_circaDueOreESei_subito_scavalcaFasciaECarica() {
    val plan = estimator.startNow(s25)

    assertThat(hours(plan.cost.workSeconds)).isWithin(0.1).of(2.6)
    assertThat(plan.nights).isEqualTo(0)
    assertThat(plan.daysToFinish).isEqualTo(0.0)
    // Come "Wi-Fi sempre" se a batteria.
    assertThat(plan.batteryDrainPercent).isWithin(2.0).of(48.0)
    assertThat(plan.wifiBytes / 1e9).isWithin(1.0).of(97.0)
    assertThat(plan.mobileBytes).isEqualTo(0)
  }

  @Test
  fun faiOra_suiDatiMobili_tuttoIlTrafficoPassaDaLi_eLaRadioCostaIlDoppio() {
    val wifi = estimator.startNow(s25)
    val mobile = estimator.startNow(s25, onMobileData = true)

    assertThat(mobile.wifiBytes).isEqualTo(0)
    assertThat(mobile.mobileBytes).isEqualTo(mobile.cost.trafficBytes)
    assertThat(mobile.energyPercent).isWithin(1e-9).of(wifi.energyPercent * measures.mobileEnergyFactor)
  }

  // --- La stessa tabella dallo stimatore generale (PresetEstimateTable) ------------------------------------------------------

  @Test
  fun laTabellaDeiPreset_dallArretratoVero_diceGliStessiNumeri() = runTest {
    val source = BacklogSource { s25.toBacklog() }

    val rows = PresetEstimateTable().build(source).associateBy { it.preset }

    // Notte: dalla presa, dentro una notte. Bilanciato: ~10% dalla batteria (le sole foto). Wi-Fi sempre e Massimo: tutto.
    assertThat(rows.getValue(Preset.NIGHT).estimate.batteryDrainPercentPerNight).isEqualTo(0.0)
    assertThat(rows.getValue(Preset.NIGHT).estimate.daysToFinish!!).isLessThan(1.0)
    assertThat(rows.getValue(Preset.BALANCED).estimate.batteryDrainPercentPerNight).isWithin(0.1).of(10.4)
    assertThat(rows.getValue(Preset.WIFI_ALWAYS).estimate.batteryDrainPercentPerNight).isWithin(2.5).of(48.0)
    assertThat(rows.getValue(Preset.MAXIMUM).estimate.mobileBytesFirstMonth).isEqualTo(10 * GB)
    for (preset in listOf(Preset.NIGHT, Preset.BALANCED, Preset.WIFI_ALWAYS)) {
      assertThat(rows.getValue(preset).estimate.mobileBytesFirstMonth).isEqualTo(0)
    }
  }

  // --- Copie gia' sul NAS (§2.11 punto 5) e a regime ------------------------------------------------------------------------

  @Test
  fun leCopieGiaSulNas_costanoLaSolaRilettura_quaranta_gb_circaQuarantacinqueMinuti() {
    val reread = BackupWork(reread = MediaVolume(videos = Workload(300, 40 * GB)))

    val cost = estimator.cost(reread)

    assertThat(cost.uploadedBytes).isEqualTo(0)
    assertThat(cost.trafficBytes).isEqualTo(40 * GB)
    assertThat(minutes(cost.workSeconds)).isWithin(5.0).of(45.0) // 41,7 min: "circa 45 minuti"
  }

  @Test
  fun ilSommario_dellaScenaDelDocumento_caricaIlNuovoERileggeIlGiaPresente() {
    // 40 GB gia' presenti (da collegare) e il resto da caricare: i tempi si sommano.
    val onlyNew = BackupWork(upload = MediaVolume(photos, Workload(0, 0)))
    val withLinked = onlyNew.copy(reread = MediaVolume(videos = Workload(300, 40 * GB)))

    val extra = estimator.cost(withLinked).workSeconds - estimator.cost(onlyNew).workSeconds
    assertThat(extra).isWithin(0.5).of(40e9 / 16e6)
  }

  @Test
  fun aRegime_duecentoFotoEDieciVideoAlMese_seiMinutiEUnoVirgolaSettePerCento() {
    // 200 foto e 10 video al mese, ~1,7 GB (200 x 3,5 MB + 10 x 100 MB): ~3,4 GB di traffico, ~6 minuti di lavoro,
    // ~1,7% di batteria al mese (~0,06% al giorno): dentro l'obiettivo del 2% al giorno.
    val monthly = BackupWork(upload = MediaVolume(Workload(200, 700 * MB), Workload(10, 1_000 * MB)))
    val backlog =
      LibraryBacklog(monthlyNew = mapOf(PHOTO_BACKUP to monthly.upload.photos, VIDEO_BACKUP to monthly.upload.videos))

    val estimate = DefaultConsumptionEstimator().estimate(ruleSetOf(Preset.NIGHT), backlog, measures, UsageProfile())

    assertThat(estimate.wifiBytesPerMonth / 1e9).isWithin(0.1).of(3.4)
    assertThat(minutes(estimator.cost(monthly).workSeconds)).isWithin(1.0).of(6.0)
    assertThat(estimate.steadyBatteryPercentPerDay * 30).isWithin(0.1).of(1.7)
    assertThat(estimate.steadyBatteryPercentPerDay).isWithin(0.01).of(0.06)
    assertThat(estimate.steadyBatteryPercentPerDay).isLessThan(2.0)
  }

  // --- Regole che non lasciano partire il backup -----------------------------------------------------------------------------

  @Test
  fun unBackupSpentoONonPianificabile_nonFinisceMai() {
    val night = ruleSetOf(Preset.NIGHT)

    // Foto spente nelle impostazioni (attivita' disattivata): con lavoro da fare non parte mai.
    val off = RuleSettings(disabledActivities = setOf(PHOTO_BACKUP)).toRuleSet()
    val disabled = estimator.scheduled(off, s25)
    assertThat(disabled.neverFinishes).isTrue()
    assertThat(disabled.daysToFinish).isNull()
    assertThat(disabled.nights).isEqualTo(0)

    // Nessuna rete ammessa: nessuna ora utile.
    val noNetwork = night.copy(rules = night.rules + (VIDEO_BACKUP to night.ruleFor(VIDEO_BACKUP).copy(networks = emptySet())))
    assertThat(estimator.scheduled(noNetwork, s25).neverFinishes).isTrue()

    // Senza lavoro non c'e' nulla che non finisce: niente notti, niente "mai".
    val nothing = estimator.scheduled(night, BackupWork())
    assertThat(nothing.neverFinishes).isFalse()
    assertThat(nothing.nights).isEqualTo(0)
  }

  @Test
  fun ilCostoVieneDalleMisureSostituibili() {
    val measured = BackupEstimator(DefaultConsumptionEstimator(), measures.copy(smbWriteBytesPerSecond = 18.0e6, perFileOverheadSeconds = 0.06), UsageProfile())

    val base = estimator.cost(s25)
    val faster = measured.cost(s25)

    assertThat(faster.uploadSeconds).isWithin(1e-6).of(base.uploadSeconds / 2)
    assertThat(faster.perFileSeconds).isWithin(1e-6).of(base.perFileSeconds / 2)
    assertThat(faster.rereadSeconds).isEqualTo(base.rereadSeconds)
  }
}
