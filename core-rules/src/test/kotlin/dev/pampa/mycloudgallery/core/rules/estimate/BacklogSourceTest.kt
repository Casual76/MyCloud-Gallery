package dev.pampa.mycloudgallery.core.rules.estimate

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.AI_COMPUTE
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.HQ_PREVIEWS
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.INDEX_SYNC
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.PHOTO_BACKUP
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.PREPARATION
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.VIDEO_BACKUP
import dev.pampa.mycloudgallery.core.rules.GB
import dev.pampa.mycloudgallery.core.rules.MB
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * L'arretrato vero e la tabella dei preset per **12.900 media** (la vista di `alessio`: area privata piu' area comune,
 * §2.10 di docs/fase4-progetto.md). Due scenari, entrambi sulla stessa libreria di 11.300 foto (28 GB) e 1.600 video
 * (250 GB):
 *
 *  - **primo dispositivo**: l'indice non c'e' ancora, quindi rilevare tutto e preparare tutto (Preparatore);
 *  - **dispositivo che si unisce**: l'indice c'e' gia', serve solo scaricare la replica (Standard).
 */
class BacklogSourceTest {

  private val costs = NasCosts.DOC_2_10
  private val measures = DeviceMeasures.PHASE_0

  private val photos = Workload(items = 11_300, bytes = 28 * GB)
  private val videos = Workload(items = 1_600, bytes = 250 * GB)
  private val archive = MediaVolume(photos, videos)
  private val media = archive.items

  /** Un mese tipico: 300 foto da 4 MB e 20 video da 100 MB, sul NAS e (per il Preparatore) da preparare. */
  private val arrivals =
    MonthlyArrivals(
      onNas = MediaVolume(Workload(300, 1_200 * MB), Workload(20, 2 * GB)),
      toPrepare = MediaVolume(Workload(300, 1_200 * MB), Workload(20, 2 * GB)),
    )

  private fun firstDevice() =
    BacklogFacts(toDetect = archive, toPrepare = archive, monthly = arrivals)

  private fun joiningDevice(depth: ReplicaDepth = ReplicaDepth.STANDARD) =
    BacklogFacts(
      replica = costs.replica(media, depth),
      hq = costs.hq(media, depth),
      monthly = arrivals.copy(toPrepare = MediaVolume.NONE),
    )

  // --- Dai fatti all'arretrato ---------------------------------------------------------------------------------------

  @Test
  fun lAnagrafeDelleCosti_sonoQuelleDelParagrafo2_10() {
    assertThat(media).isEqualTo(12_900)
    assertThat(costs.replicaBytesPerMedia(ReplicaDepth.SAVER)).isEqualTo(6_500) // meta + S
    assertThat(costs.replicaBytesPerMedia(ReplicaDepth.STANDARD)).isEqualTo(28_500) // meta + S + M
    // Primo allineamento della tabella "Replica sul telefono": Risparmio ~100 MB, Standard ~380 MB, Alta ~5,6 GB (con i ~20 MB
    // di database e istantanea che non si scaricano).
    assertThat(costs.replica(media, ReplicaDepth.SAVER).bytes).isEqualTo(83_850_000L)
    assertThat(costs.replica(media, ReplicaDepth.STANDARD).bytes).isEqualTo(367_650_000L)
    val high = costs.replica(media, ReplicaDepth.HIGH).bytes + costs.hq(media, ReplicaDepth.HIGH).bytes
    assertThat(high).isEqualTo(5_527_650_000L)
    assertThat(costs.hq(media, ReplicaDepth.STANDARD)).isEqualTo(Workload.NONE) // l'HQ si scarica solo con "Alta"
  }

  @Test
  fun ilRilevamento_costaIlPiccoloTrattoLettoPerFileEnonLaDimensioneDelFile() {
    val detection = costs.detection(archive)

    // 11.300 foto da 128 KiB + 1.600 video da 128 KiB + ~400 KB d'indice: ~2,3 GB (il documento dice ~2,2 GB per 12.244).
    assertThat(detection.items).isEqualTo(12_900)
    assertThat(detection.bytes).isEqualTo(11_300 * 131_072L + 1_600 * 531_072L)
    assertThat(detection.bytes.toDouble()).isWithin(1e8).of(2.33e9)
  }

  @Test
  fun laPreparazione_leggeLeFotoPerIntero_iVideoNo_eScriveTutteLeClassi() {
    val prep = costs.preparation(archive)

    val read = 28 * GB + 1_600 * 5_000_000L
    val written = 11_300 * 578_500L + 1_600 * 878_500L
    assertThat(prep.bytes).isEqualTo(read + written)
    assertThat(prep.items).isEqualTo(12_900)
    // Mai 250 GB: i video non si scaricano.
    assertThat(prep.bytes).isLessThan(60 * GB)
  }

  @Test
  fun ilPrimoDispositivo_hasLArretratoDelRilevamentoEDellaPreparazione() {
    val backlog = firstDevice().toLibraryBacklog(ReplicaDepth.STANDARD)

    assertThat(backlog.pending(INDEX_SYNC).bytes).isEqualTo(costs.detection(archive).bytes)
    assertThat(backlog.pending(PREPARATION)).isEqualTo(costs.preparation(archive))
    // Niente da scaricare dalla replica (l'indice lo ha scritto lui), niente HQ da scaricare, niente backup (Fase 5).
    assertThat(backlog.pending(HQ_PREVIEWS)).isEqualTo(Workload.NONE)
    assertThat(backlog.pending(PHOTO_BACKUP)).isEqualTo(Workload.NONE)
    assertThat(backlog.pending(VIDEO_BACKUP)).isEqualTo(Workload.NONE)
    assertThat(backlog.pending(AI_COMPUTE)).isEqualTo(Workload.NONE)
  }

  @Test
  fun unDispositivoCheSiUnisce_scaricaSoloLaReplicaAlLivelloScelto() {
    val standard = joiningDevice(ReplicaDepth.STANDARD).toLibraryBacklog(ReplicaDepth.STANDARD)
    val high = joiningDevice(ReplicaDepth.HIGH).toLibraryBacklog(ReplicaDepth.HIGH)

    assertThat(standard.pending(INDEX_SYNC)).isEqualTo(Workload(12_900, 367_650_000L))
    assertThat(standard.pending(PREPARATION)).isEqualTo(Workload.NONE)
    assertThat(standard.pending(HQ_PREVIEWS)).isEqualTo(Workload.NONE)
    assertThat(high.pending(HQ_PREVIEWS)).isEqualTo(Workload(12_900, 5_160_000_000L))
  }

  @Test
  fun ilFlussoMensile_seguiMediaNuoviEDipendeDalLivello() {
    val standard = joiningDevice().toLibraryBacklog(ReplicaDepth.STANDARD)
    val high = joiningDevice(ReplicaDepth.HIGH).toLibraryBacklog(ReplicaDepth.HIGH)

    // 320 media nuovi: replica 28,5 KB l'uno + rilevamento (300 foto da 128 KiB, 20 video da ~512 KiB).
    val detection = 300 * 131_072L + 20 * 531_072L
    assertThat(standard.monthlyNew(INDEX_SYNC)).isEqualTo(Workload(320, 320 * 28_500L + detection))
    assertThat(standard.monthlyNew(HQ_PREVIEWS)).isEqualTo(Workload.NONE)
    assertThat(high.monthlyNew(HQ_PREVIEWS)).isEqualTo(Workload(320, 320 * 400_000L))
    // Chi non e' Preparatore non prepara.
    assertThat(standard.monthlyNew(PREPARATION)).isEqualTo(Workload.NONE)
  }

  @Test
  fun ilBackupDelTelefono_eGliHashCompletiDeiVideoStannoNelleAttivitaDelBackup() {
    val facts =
      BacklogFacts(
        toProtect = MediaVolume(Workload(150, 600 * MB), Workload(12, 1_800 * MB)),
        toFullHash = Workload(300, 40 * GB),
        toAnalyze = 1_000,
      )

    val backlog = facts.toLibraryBacklog(ReplicaDepth.STANDARD)

    assertThat(backlog.pending(PHOTO_BACKUP)).isEqualTo(Workload(150, 600 * MB))
    assertThat(backlog.pending(VIDEO_BACKUP)).isEqualTo(Workload(312, 1_800 * MB + 40 * GB))
    assertThat(backlog.pending(AI_COMPUTE)).isEqualTo(Workload(1_000, 150 * MB))
  }

  @Test
  fun costiNegativiENumeriNegativiNonSiAccettano() {
    val negativeCost = runCatching { NasCosts(thumbSBytes = -1) }.exceptionOrNull()
    val negativeCount = runCatching { BacklogFacts(toAnalyze = -1) }.exceptionOrNull()

    assertThat(negativeCost).isInstanceOf(IllegalArgumentException::class.java)
    assertThat(negativeCount).isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun lAnagrafeDeiFatti_leggeDalLettore_eSegueIlLivelloCorrente() = runTest {
    var depth = ReplicaDepth.STANDARD
    var reads = 0
    val source = FactsBacklogSource({ reads++; joiningDevice(depth) }, { depth })

    val standard = source.current()
    depth = ReplicaDepth.HIGH
    val high = source.current()

    assertThat(reads).isEqualTo(2)
    assertThat(standard.monthlyNew(HQ_PREVIEWS)).isEqualTo(Workload.NONE)
    assertThat(high.monthlyNew(HQ_PREVIEWS).items).isEqualTo(320)
  }

  // --- La tabella dei preset con 12.900 media -----------------------------------------------------------------------

  private suspend fun table(facts: BacklogFacts, depth: ReplicaDepth = ReplicaDepth.STANDARD) =
    PresetEstimateTable()
      .build(FactsBacklogSource({ facts }, { depth }), highQuality = depth == ReplicaDepth.HIGH)
      .associateBy { it.preset }

  @Test
  fun tabellaDeiPreset_primoDispositivo_conNotteBastaUnaNotte_eNonPesaSullaBatteria() = runTest {
    val rows = table(firstDevice())
    val night = rows.getValue(Preset.NIGHT).estimate

    // ~46 GB di traffico a 16 MB/s: meno di un'ora di lavoro, "con il preset Notte basta una notte" (§2.10).
    assertThat(night.daysToFinish!!).isLessThan(1.0)
    assertThat(night.perActivity.getValue(PREPARATION).workHours).isWithin(0.05).of(43_942_650_000.0 / 16e6 / 3600)
    // Tutta dalla presa; nessun dato mobile.
    assertThat(night.batteryDrainPercentPerNight).isEqualTo(0.0)
    assertThat(night.mobileBytesPerMonth).isEqualTo(0L)
    assertThat(night.mobileBytesFirstMonth).isEqualTo(0L)
    // L'obiettivo del piano §4: sotto il 2% al giorno a regime con Notte.
    assertThat(night.steadyBatteryPercentPerDay).isLessThan(2.0)
    assertThat(night.batteryPercentPerNight).isWithin(0.5).of(23.1)
  }

  @Test
  fun tabellaDeiPreset_primoDispositivo_ognunoSmaltisceInUnGiorno_edAMassimoPesaSullaBatteria() = runTest {
    val rows = table(firstDevice())

    for (preset in PresetEstimateTable.DEFAULT_PRESETS) {
      assertThat(rows.getValue(preset).estimate.daysToFinish!!).isLessThan(1.0)
    }
    // Wi-Fi sempre e Massimo possono lavorare a batteria: la stessa energia pesa sulla batteria.
    assertThat(rows.getValue(Preset.WIFI_ALWAYS).estimate.batteryDrainPercentPerNight).isWithin(0.5).of(23.1)
    assertThat(rows.getValue(Preset.NIGHT).estimate.batteryDrainPercentPerNight).isEqualTo(0.0)
    // Bilanciato: le leggere (rilevamento) a batteria, la preparazione solo in carica.
    val balanced = rows.getValue(Preset.BALANCED).estimate
    assertThat(balanced.batteryDrainPercentPerNight).isWithin(0.1).of(2.33 * 0.5)
  }

  @Test
  fun tabellaDeiPreset_primoDispositivo_soloMassimoUsaIDatiMobili_entroIlTetto() = runTest {
    val rows = table(firstDevice())

    for (preset in listOf(Preset.NIGHT, Preset.BALANCED, Preset.WIFI_ALWAYS)) {
      assertThat(rows.getValue(preset).estimate.mobileBytesFirstMonth).isEqualTo(0L)
    }
    val maximum = rows.getValue(Preset.MAXIMUM).estimate
    assertThat(maximum.mobileBytesFirstMonth).isEqualTo(PresetCatalog.MAXIMUM_MOBILE_CAP_BYTES) // l'arretrato satura il tetto di 10 GB
    assertThat(maximum.mobileBytesPerMonth).isLessThan(PresetCatalog.MAXIMUM_MOBILE_CAP_BYTES) // a regime, molto meno
  }

  @Test
  fun tabellaDeiPreset_dispositivoCheSiUnisce_laReplicaStandardSiScaricaInUnPaioDiSecondi() = runTest {
    val rows = table(joiningDevice())

    for (preset in PresetEstimateTable.DEFAULT_PRESETS) {
      val estimate = rows.getValue(preset).estimate
      assertThat(estimate.daysToFinish!!).isLessThan(1.0)
      assertThat(estimate.perActivity.getValue(INDEX_SYNC).workHours * 3600)
        .isWithin(1.0)
        .of(367_650_000.0 / measures.smbReadBytesPerSecond) // ~23 s a 16 MB/s
      // L'HQ non si scarica con Standard: l'attivita' e' spenta.
      assertThat(estimate.perActivity.getValue(HQ_PREVIEWS).enabled).isFalse()
    }
  }

  @Test
  fun tabellaDeiPreset_conLaQualitaAlta_l_HQEntraNelleStime() = runTest {
    val standard = table(joiningDevice(ReplicaDepth.STANDARD), ReplicaDepth.STANDARD).getValue(Preset.WIFI_ALWAYS).estimate
    val high = table(joiningDevice(ReplicaDepth.HIGH), ReplicaDepth.HIGH).getValue(Preset.WIFI_ALWAYS).estimate

    assertThat(high.perActivity.getValue(HQ_PREVIEWS).enabled).isTrue()
    // 5,16 GB di HQ a 16 MB/s: ~5,4 minuti. (Con la preparazione del primo dispositivo lo ha gia' scritto.)
    assertThat(high.perActivity.getValue(HQ_PREVIEWS).workHours * 3600).isWithin(1.0).of(5_160_000_000.0 / 16e6)
    assertThat(high.batteryPercentPerNight).isGreaterThan(standard.batteryPercentPerNight)
  }

  @Test
  fun laFraseDellaTabella_usaLoStessoTestoDelleStime() = runTest {
    val row = table(firstDevice()).getValue(Preset.NIGHT)

    assertThat(row.summary).isEqualTo(EstimateText.describe(row.estimate))
    assertThat(row.summary).contains("0 KB di dati mobili al mese")
    assertThat(row.summary).contains("arretrato smaltito entro un giorno")
  }

  @Test
  fun unaLibreriaSenzaArretrato_nonHaNienteDaStimare() = runTest {
    val rows = table(BacklogFacts())

    for (row in rows.values) {
      assertThat(row.estimate.daysToFinish).isEqualTo(0.0)
      assertThat(row.estimate.batteryPercentPerNight).isEqualTo(0.0)
    }
  }

  @Test
  fun ordine_deiPresetDellaTabellaEQuelloDellOnboarding() = runTest {
    val rows = PresetEstimateTable().build(BacklogSource { LibraryBacklog() })

    assertThat(rows.map { it.preset }).containsExactly(Preset.NIGHT, Preset.BALANCED, Preset.WIFI_ALWAYS, Preset.MAXIMUM).inOrder()
  }
}
