package dev.pampa.mycloudgallery.core.backup.plan

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.SummaryCount
import dev.pampa.mycloudgallery.core.backup.contract.SummaryWarning
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import dev.pampa.mycloudgallery.core.rules.estimate.BackupEstimator
import dev.pampa.mycloudgallery.core.rules.estimate.DefaultConsumptionEstimator
import dev.pampa.mycloudgallery.core.rules.estimate.DeviceMeasures
import dev.pampa.mycloudgallery.core.rules.estimate.UsageProfile
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il riepilogo del primo backup (Fase 5A, P4; `docs/fase5-progetto.md` §1.5, §2.1, §2.9, §2.11): numeri e GB veri per sorgente, i tre
 * numeri, lo stato "sto ancora confrontando", la stima con le regole attuali e le scelte "Stanotte" e "Inizia ora".
 */
class BackupSummaryTest {

  private val telegram = BackupSource.Folder("external_primary", "Pictures/Telegram/")
  private val mb = 1_000_000L
  private val gb = 1_000_000_000L

  // --- Numeri veri ----------------------------------------------------------------------------------------------------------------

  @Test
  fun numeriEGigabyteVeri_perFotoEVideo_ePerSorgente() = runTest {
    val world = PlanWorld(sources = BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots, telegram)))
    world.add(
      phoneMedia(1, size = 4 * mb), phoneMedia(2, size = 3_500_000), phoneMedia(3, size = 5 * mb),
      phoneMedia(4, source = BackupSource.Screenshots, size = 700_000),
      phoneMedia(5, source = BackupSource.Screenshots, size = 800_000),
      phoneMedia(6, source = telegram, size = 200_000),
      phoneMedia(7, MediaKind.VIDEO, size = 120 * mb, name = "V7.mp4"),
      phoneMedia(8, MediaKind.VIDEO, source = telegram, size = 80 * mb, name = "V8.mp4"),
      // Fuori dalle sorgenti scelte, o non propri: non contano.
      phoneMedia(9, source = BackupSource.Folder("external_primary", "Pictures/Altro/"), size = 9 * mb),
      phoneMedia(10, mine = false),
    )

    val summary = world.planner.summary()

    assertThat(summary.fresh).isEqualTo(SummaryCount(photos = 6, videos = 2, photoBytes = 4 * mb + 3_500_000 + 5 * mb + 700_000 + 800_000 + 200_000, videoBytes = 200 * mb))
    assertThat(summary.linked).isEqualTo(SummaryCount.ZERO)
    assertThat(summary.similar).isEqualTo(SummaryCount.ZERO)
    assertThat(summary.inCommonArea).isEqualTo(SummaryCount.ZERO)
    assertThat(summary.toUpload).isEqualTo(summary.fresh)
    assertThat(summary.bySource.keys).containsExactly("camera", "screenshots", telegram.id)
    assertThat(summary.bySource.getValue("camera")).isEqualTo(SummaryCount(3, 1, 12_500_000, 120 * mb))
    assertThat(summary.bySource.getValue("screenshots")).isEqualTo(SummaryCount(2, 0, 1_500_000, 0))
    assertThat(summary.bySource.getValue(telegram.id)).isEqualTo(SummaryCount(1, 1, 200_000, 80 * mb))
    assertThat(summary.comparing).isFalse()
    assertThat(summary.estimate).isNotNull()
  }

  @Test
  fun senzaNiente_ilRiepilogoEVuoto_ediceCheCeDaAspettare() = runTest {
    val world = PlanWorld(sources = BackupSources.NONE)
    world.add(phoneMedia(1))
    world.existing.state = ExistingCopiesState(privateAreaScanned = false, similarPending = 0)

    val summary = world.planner.summary()

    assertThat(summary.fresh).isEqualTo(SummaryCount.ZERO)
    assertThat(summary.estimate).isNull()
    assertThat(summary.estimateNow).isNull()
    assertThat(summary.comparing).isTrue()
    assertThat(PlanWorld().planner.summary()).isEqualTo(BackupSummary.EMPTY)
  }

  // --- I tre numeri (§2.11) --------------------------------------------------------------------------------------------------------

  @Test
  fun treNumeri_giaSulNas_conUnaCopiaSimile_nuovi() = runTest {
    val world = PlanWorld(similar = SimilarCopyLookup { setOf(mid(3)) })
    world.add(
      // 1 e 2: gia' sul NAS in un vecchio backup (stessa dimensione e stessa impronta rapida): collegati dopo la rilettura.
      phoneMedia(1, size = 4 * mb, quickHash = qh('a')),
      phoneMedia(2, size = 3 * mb, quickHash = qh('b')),
      // 3: una copia simile (ricompressa da un cloud): si carica e si vedra' insieme.
      phoneMedia(3, size = 5 * mb, quickHash = qh('c')),
      // 4: la sola copia e' nell'area comune: non si carica, non e' "al sicuro" (punto 3).
      phoneMedia(4, size = 2 * mb, quickHash = qh('d')),
      // 5, 6, 7: nuovi. Il 7 e' un video.
      phoneMedia(5, size = 1 * mb, quickHash = qh('e')),
      phoneMedia(6, size = 1 * mb, quickHash = qh('f')),
      phoneMedia(7, MediaKind.VIDEO, size = 100 * mb, quickHash = qh('7'), name = "V7.mp4"),
    )
    world.oldBackupFile(101, 4 * mb, qh('a'))
    world.oldBackupFile(102, 3 * mb, qh('b'))
    world.putCopy(world.memory.media(mid(4))!!, COMMON, "Foto famiglia/IMG_4.jpg")

    val summary = world.planner.summary()

    assertThat(summary.linked).isEqualTo(SummaryCount(2, 0, 7 * mb, 0))
    assertThat(summary.similar).isEqualTo(SummaryCount(1, 0, 5 * mb, 0))
    assertThat(summary.fresh).isEqualTo(SummaryCount(2, 1, 2 * mb, 100 * mb))
    assertThat(summary.inCommonArea).isEqualTo(SummaryCount(1, 0, 2 * mb, 0))
    // "Da caricare M + K": le simili e i nuovi; i collegati e quelli dell'area comune no.
    assertThat(summary.toUpload).isEqualTo(SummaryCount(3, 1, 7 * mb, 100 * mb))
    assertThat(summary.bySource.getValue("camera")).isEqualTo(summary.toUpload)
  }

  @Test
  fun unCatalogoCheRestituisceIMediaConLaSolaCopiaComune_stannoAParte_nonSonoDaCaricare_2_11_punto3() = runTest {
    // Il catalogo li restituisce come candidati (come il fake di P0): il riepilogo li mette in `inCommonArea`, mai fra i tre numeri.
    val all =
      listOf(
        candidate(1, size = 4 * mb, sortTime = 40),
        candidate(2, size = 3 * mb, sortTime = 30),
        candidate(3, size = 50 * mb, kind = MediaKind.VIDEO, sortTime = 20, name = "V3.mp4"),
        candidate(4, size = 2 * mb, sortTime = 10),
      )
    val world = PlanWorld(catalog = ListCatalog(all, onlyCommonCopy = setOf(mid(2), mid(3))))

    val summary = world.planner.summary()

    assertThat(summary.inCommonArea).isEqualTo(SummaryCount(1, 1, 3 * mb, 50 * mb))
    assertThat(summary.fresh).isEqualTo(SummaryCount(2, 0, 6 * mb, 0))
    assertThat(summary.toUpload).isEqualTo(SummaryCount(2, 0, 6 * mb, 0))
    assertThat(summary.linked).isEqualTo(SummaryCount.ZERO)
    assertThat(summary.similar).isEqualTo(SummaryCount.ZERO)
    assertThat(summary.bySource.getValue("camera")).isEqualTo(SummaryCount(2, 0, 6 * mb, 0))
    // La stima conta solo cio' che si carica: 6 MB, non 59.
    val seconds = 6e6 / 9e6 + 6e6 / 16e6 + 2 * 0.12
    assertThat(summary.estimateNow!!.durationMillis.toDouble()).isWithin(1.0).of(seconds * 1000)
    // E il piano non li propone.
    assertThat(world.planner.nextOverall(10).map { it.mediaId }).containsExactly(mid(1), mid(4)).inOrder()
  }

  @Test
  fun inCommonArea_vieneDalContoDelCatalogo_ancheSeIlCatalogoNonRestituisceQueiMediaFraICandidati() = runTest {
    // Come fara' il catalogo vero: i media con la sola copia comune sono esclusi dai candidati ma contati in `safetyCounts`.
    val world = PlanWorld(catalog = ListCatalog(listOf(candidate(1, size = 4 * mb), candidate(2, size = 3 * mb)), commonNotReturned = 5))

    val summary = world.planner.summary()

    assertThat(summary.inCommonArea.items).isEqualTo(5)
    assertThat(summary.inCommonArea.photos).isEqualTo(5) // il tipo non si sa: foto, di dimensione ignota
    assertThat(summary.inCommonArea.bytes).isEqualTo(0)
    // Non sono ne' fra i tre numeri ne' nella stima: si carica solo cio' che e' davvero da caricare.
    assertThat(summary.fresh).isEqualTo(SummaryCount(2, 0, 7 * mb, 0))
    assertThat(summary.toUpload).isEqualTo(summary.fresh)
    assertThat(summary.linked).isEqualTo(SummaryCount.ZERO)
    assertThat(summary.similar).isEqualTo(SummaryCount.ZERO)
    val seconds = 7e6 / 9e6 + 7e6 / 16e6 + 2 * 0.12
    assertThat(summary.estimateNow!!.durationMillis.toDouble()).isWithin(1.0).of(seconds * 1000)
  }

  @Test
  fun inCommonArea_ilNumeroELoDaIlCatalogo_iByteDeiCandidatiRestituitiNonSiContanoDueVolte() = runTest {
    // Il catalogo ne conta 4 e ne restituisce comunque 2 come candidati (con tipo e byte noti): 4 in tutto, non 6.
    val all = listOf(candidate(1, size = 4 * mb, sortTime = 30), candidate(2, size = 3 * mb, sortTime = 20), candidate(3, size = 60 * mb, kind = MediaKind.VIDEO, sortTime = 10, name = "V.mp4"))
    val world = PlanWorld(catalog = ListCatalog(all, onlyCommonCopy = setOf(mid(2), mid(3)), commonNotReturned = 2))

    val summary = world.planner.summary()

    assertThat(summary.inCommonArea).isEqualTo(SummaryCount(photos = 3, videos = 1, photoBytes = 3 * mb, videoBytes = 60 * mb))
    assertThat(summary.fresh).isEqualTo(SummaryCount(1, 0, 4 * mb, 0))
  }

  @Test
  fun inCommonArea_senzaNienteDaCaricare_ilNumeroCeComunque() = runTest {
    val world = PlanWorld(catalog = ListCatalog(emptyList(), commonNotReturned = 3))

    val summary = world.planner.summary()

    assertThat(summary.inCommonArea.items).isEqualTo(3)
    assertThat(summary.toUpload).isEqualTo(SummaryCount.ZERO)
    assertThat(summary.estimate).isNull()
  }

  @Test
  fun seTuttoELaSolaCopiaComune_nienteDaCaricare_ilRiepilogoLoDice() = runTest {
    val world = PlanWorld(catalog = ListCatalog(listOf(candidate(1, size = 4 * mb)), onlyCommonCopy = setOf(mid(1))))

    val summary = world.planner.summary()

    assertThat(summary.inCommonArea.items).isEqualTo(1)
    assertThat(summary.toUpload).isEqualTo(SummaryCount.ZERO)
    assertThat(summary.estimate).isNull()
    assertThat(summary.estimateNow).isNull()
  }

  @Test
  fun serveLImprontaRapida_laSolaDimensioneNonBasta_nelDubbioSiCarica() = runTest {
    val world = PlanWorld()
    world.add(phoneMedia(1, size = 4 * mb, quickHash = null))
    // Un file della stessa dimensione nell'area privata, ma il candidato non ha l'impronta rapida: non si dice "gia' sul NAS".
    world.oldBackupFile(101, 4 * mb, qh('a'))

    val summary = world.planner.summary()

    assertThat(summary.linked).isEqualTo(SummaryCount.ZERO)
    assertThat(summary.fresh.photos).isEqualTo(1)
  }

  @Test
  fun unaCopiaConUnaltraImpronta_oAltraDimensione_nonELaStessa() = runTest {
    val world = PlanWorld()
    world.add(phoneMedia(1, size = 4 * mb, quickHash = qh('a')), phoneMedia(2, size = 4 * mb, quickHash = qh('b')))
    world.oldBackupFile(101, 4 * mb, qh('c')) // stessa dimensione, impronta diversa
    world.oldBackupFile(102, 4 * mb + 1, qh('b')) // impronta uguale, dimensione diversa

    val summary = world.planner.summary()

    assertThat(summary.linked).isEqualTo(SummaryCount.ZERO)
    assertThat(summary.fresh.photos).isEqualTo(2)
  }

  @Test
  fun sullaScansioneNonFinita_icandidatiContanoTuttiFraINuovi_ilRiepilogoLoDice() = runTest {
    val world = PlanWorld()
    world.add(phoneMedia(1, size = 4 * mb, quickHash = qh('a')), phoneMedia(2, size = 3 * mb, quickHash = qh('b')))
    world.oldBackupFile(101, 4 * mb, qh('a'))
    world.existing.state = ExistingCopiesState(privateAreaScanned = false, similarPending = 0)

    val provisional = world.planner.summary()

    // "Sto cercando cosa c'e' gia' sul NAS...": non si sa ancora, numeri provvisori.
    assertThat(provisional.comparing).isTrue()
    assertThat(provisional.existing.privateAreaScanned).isFalse()
    assertThat(provisional.linked).isEqualTo(SummaryCount.ZERO)
    assertThat(provisional.fresh.photos).isEqualTo(2)

    // Scansione finita ma il confronto delle copie simili no: "sto confrontando 120 foto...".
    world.existing.state = ExistingCopiesState(privateAreaScanned = true, similarPending = 120)
    val comparing = world.planner.summary()
    assertThat(comparing.comparing).isTrue()
    assertThat(comparing.existing.similarPending).isEqualTo(120)
    assertThat(comparing.linked.photos).isEqualTo(1)

    world.existing.state = ExistingCopiesState.SETTLED
    val settled = world.planner.summary()
    assertThat(settled.comparing).isFalse()
    assertThat(settled.linked.photos).isEqualTo(1)
    assertThat(settled.fresh.photos).isEqualTo(1)
  }

  @Test
  fun senzaAreaPrivata_nienteSiCollega_tuttoENuovo() = runTest {
    val world = PlanWorld(privateShare = null)
    world.add(phoneMedia(1, size = 4 * mb, quickHash = qh('a')))
    world.oldBackupFile(101, 4 * mb, qh('a'))

    val summary = world.planner.summary()

    assertThat(summary.linked).isEqualTo(SummaryCount.ZERO)
    assertThat(summary.fresh.photos).isEqualTo(1)
  }

  @Test
  fun ilRiepilogoLeggeTutteLePagine() = runTest {
    val world = PlanWorld(pageSize = 7)
    world.add(*(1..100).map { phoneMedia(it, size = 1 * mb) }.toTypedArray())
    world.add(*(101..110).map { phoneMedia(it, MediaKind.VIDEO, size = 50 * mb, name = "V$it.mp4") }.toTypedArray())

    val summary = world.planner.summary()

    assertThat(summary.fresh).isEqualTo(SummaryCount(100, 10, 100 * mb, 500 * mb))
  }

  // --- La stima con le regole attuali (§2.9) -----------------------------------------------------------------------------------

  /** La composizione del §2.9: i conteggi veri dell'S25 del 12/10, le dimensioni medie ipotizzate dal documento. */
  private fun PlanWorld.addS25(): Unit {
    val folders =
      listOf(
        Triple(BackupSource.Camera, 866, 4 * mb),
        Triple(BackupSource.Folder("external_primary", "DCIM/Altro/"), 1_088, 3 * mb),
        Triple(BackupSource.Screenshots, 915, 700_000L),
        Triple(BackupSource.Folder("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"), 2_105, 200_000L),
        Triple(BackupSource.Folder("external_primary", "Download/Quick Share/"), 654, 2_500_000L),
        Triple(BackupSource.Folder("external_primary", "Pictures/"), 646, 1_500_000L),
      )
    var n = 1
    for ((source, count, size) in folders) repeat(count) { add(phoneMedia(n, source = source, size = size, sortTime = 1_000_000L - n)); n++ }
    repeat(384) { add(phoneMedia(n, MediaKind.VIDEO, source = BackupSource.Camera, size = 100 * mb, name = "V$n.mp4", sortTime = 1_000_000L - n)); n++ }
  }

  private val s25Sources =
    BackupSources(
      setOf(
        BackupSource.Camera, BackupSource.Screenshots, BackupSource.Folder("external_primary", "DCIM/Altro/"),
        BackupSource.Folder("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"),
        BackupSource.Folder("external_primary", "Download/Quick Share/"), BackupSource.Folder("external_primary", "Pictures/"),
      ),
    )

  @Test
  fun ilPrimoBackupDellS25_ilRiepilogoDiceLeStesseCoseDelDocumento() = runTest {
    // In carica e sul Wi-Fi di casa: nessun avviso.
    val world = PlanWorld(sources = s25Sources, conditions = PhoneConditions(onMobileData = false, pluggedIn = true))
    world.addS25()

    val summary = world.planner.summary()

    assertThat(summary.fresh.photos).isEqualTo(6_274)
    assertThat(summary.fresh.videos).isEqualTo(384)
    assertThat(summary.fresh.photoBytes / 1e9).isWithin(0.1).of(10.4)
    assertThat(summary.fresh.videoBytes / 1e9).isWithin(0.5).of(38.0)
    // Notte (predefinita): ~2,6 h di lavoro, una notte, dalla presa, ~97 GB sul Wi-Fi di casa, niente dati mobili.
    val tonight = summary.estimate!!
    assertThat(tonight.durationMillis / 3_600_000.0).isWithin(0.1).of(2.6)
    assertThat(tonight.nights).isEqualTo(1)
    assertThat(tonight.neverFinishes).isFalse()
    assertThat(tonight.batteryPercent).isNull()
    assertThat(tonight.mobileBytes).isEqualTo(0)
    assertThat(tonight.wifiBytes / 1e9).isWithin(1.0).of(97.0)
    // "Inizia ora": ~2,6 h da subito.
    val now = summary.estimateNow!!
    assertThat(now.durationMillis).isEqualTo(tonight.durationMillis)
    assertThat(now.nights).isEqualTo(0)
    assertThat(summary.warnings).isEmpty()
  }

  @Test
  fun laStimaSegueIlPreset() = runTest {
    val world = PlanWorld(sources = s25Sources)
    world.addS25()

    world.rules = PresetCatalog.ruleSet(Preset.BALANCED)
    // Bilanciato: le foto in giornata, anche a batteria: ~10% (le sole foto, 20,8 GB di traffico).
    assertThat(world.planner.summary().estimate!!.batteryPercent).isEqualTo(10)

    world.rules = PresetCatalog.ruleSet(Preset.WIFI_ALWAYS)
    assertThat(world.planner.summary().estimate!!.batteryPercent).isIn(45..50)

    world.rules = PresetCatalog.ruleSet(Preset.MAXIMUM)
    val maximum = world.planner.summary().estimate!!
    assertThat(maximum.mobileBytes).isEqualTo(10 * gb) // il tetto di Massimo
    assertThat(maximum.mobileBytes + maximum.wifiBytes).isEqualTo(world.planner.summary().estimateNow!!.wifiBytes)
  }

  @Test
  fun lePiuDiUnaNotte_ELAvviso() = runTest {
    // 20 video da 1 GB e 1 sola ora di carica a notte (0,8 h utili) contro ~1 h di lavoro: due notti.
    val estimator = BackupEstimator(DefaultConsumptionEstimator(), DeviceMeasures.PHASE_0, UsageProfile(pluggedHoursPerDay = 1.0))
    val world = PlanWorld(estimator = estimator)
    world.add(*(1..20).map { phoneMedia(it, MediaKind.VIDEO, size = 1 * gb, name = "V$it.mp4") }.toTypedArray())

    val summary = world.planner.summary()

    assertThat(summary.estimate!!.nights).isEqualTo(2)
    assertThat(summary.warnings).contains(SummaryWarning.LONG_FIRST_BACKUP)
    assertThat(summary.estimateNow!!.nights).isEqualTo(0)
  }

  @Test
  fun unBackupSpento_nonFinisceMai_ilRiepilogoLoDiceNonCeScrittoZeroNotti() = runTest {
    val world = PlanWorld()
    world.add(phoneMedia(1), phoneMedia(2, MediaKind.VIDEO, name = "V.mp4"))
    world.rules = RuleSettings(disabledActivities = setOf(BackgroundActivity.PHOTO_BACKUP)).toRuleSet()

    val estimate = world.planner.summary().estimate!!

    assertThat(estimate.neverFinishes).isTrue()
    assertThat(estimate.nights).isEqualTo(0)
    // "Inizia ora" lo scavalca: parte comunque.
    assertThat(world.planner.summary().estimateNow!!.neverFinishes).isFalse()
  }

  @Test
  fun leCopieGiaPresentiSiRileggonoESoltanto_costanoLaRilettura() = runTest {
    val world = PlanWorld()
    world.add(
      phoneMedia(1, size = 2 * gb, quickHash = qh('a'), name = "VID_1.jpg"),
      phoneMedia(2, size = 1 * gb, quickHash = qh('b')),
    )
    world.oldBackupFile(101, 2 * gb, qh('a')) // il primo c'e' gia' (da rileggere e collegare), il secondo no (da caricare)

    val summary = world.planner.summary()

    assertThat(summary.linked).isEqualTo(SummaryCount(1, 0, 2 * gb, 0))
    assertThat(summary.fresh).isEqualTo(SummaryCount(1, 0, 1 * gb, 0))
    // T = (1 GB / 9 MB/s) + ((1 GB + 2 GB) / 16 MB/s) + 1 file x 0,12 s; il collegamento non paga ne' scrittura ne' costo per file.
    val seconds = 1e9 / 9e6 + 3e9 / 16e6 + 0.12
    assertThat(summary.estimateNow!!.durationMillis.toDouble()).isWithin(1.0).of(seconds * 1000)
    // Traffico: 1 GB caricato + 1 GB riletto + 2 GB riletti (le copie da collegare).
    assertThat(summary.estimateNow!!.wifiBytes).isEqualTo(4 * gb)
  }

  // --- Gli avvisi e "Inizia ora" --------------------------------------------------------------------------------------------------

  @Test
  fun iniziaOra_suiDatiMobili_avvisaEConteggiaIlTrafficoSuiDatiMobili() = runTest {
    val world = PlanWorld(conditions = PhoneConditions(onMobileData = true, pluggedIn = false))
    world.add(phoneMedia(1, MediaKind.VIDEO, size = 3 * gb, name = "V.mp4"))

    val summary = world.planner.summary()

    assertThat(summary.warnings).containsAtLeast(SummaryWarning.MOBILE_DATA, SummaryWarning.ON_BATTERY)
    val now = summary.estimateNow!!
    assertThat(now.mobileBytes).isEqualTo(6 * gb) // 3 GB caricati + 3 GB riletti
    assertThat(now.wifiBytes).isEqualTo(0)
    // La radio mobile costa il doppio: 6 GB x 0,5 %/GB x 2.
    assertThat(now.batteryPercent).isEqualTo(6)
    // "Stanotte" segue il preset: niente dati mobili.
    assertThat(summary.estimate!!.mobileBytes).isEqualTo(0)
  }

  @Test
  fun iniziaOra_inCarica_nienteAvvisoDiBatteria_ENessunaSpesa() = runTest {
    val world = PlanWorld(conditions = PhoneConditions(onMobileData = false, pluggedIn = true))
    world.add(phoneMedia(1, MediaKind.VIDEO, size = 3 * gb, name = "V.mp4"))

    val summary = world.planner.summary()

    assertThat(summary.warnings).doesNotContain(SummaryWarning.ON_BATTERY)
    assertThat(summary.warnings).doesNotContain(SummaryWarning.MOBILE_DATA)
    assertThat(summary.estimateNow!!.batteryPercent).isNull()
  }

  @Test
  fun pocoLavoro_nienteAvvisoDiBatteria_enelDubbioSiAvvisaSoloSeSeNePesa() = runTest {
    val small = PlanWorld(conditions = PhoneConditions(pluggedIn = false))
    small.add(phoneMedia(1, size = 1 * mb))
    assertThat(small.planner.summary().warnings).doesNotContain(SummaryWarning.ON_BATTERY)

    // Alimentazione non nota con tanto lavoro: meglio avvisare.
    val big = PlanWorld(conditions = PhoneConditions.UNKNOWN)
    big.add(phoneMedia(1, MediaKind.VIDEO, size = 5 * gb, name = "V.mp4"))
    assertThat(big.planner.summary().warnings).contains(SummaryWarning.ON_BATTERY)
  }

  @Test
  fun glialtriAvvisi_dalleCondizioniDelTelefono() = runTest {
    val world =
      PlanWorld(conditions = PhoneConditions(partialMediaAccess = true, mediaLocationMissing = true, phoneStorageLow = true))
    world.add(phoneMedia(1))

    assertThat(world.planner.summary().warnings)
      .containsExactly(SummaryWarning.PARTIAL_ACCESS, SummaryWarning.MEDIA_LOCATION, SummaryWarning.PHONE_STORAGE_LOW)
  }

  // --- Il riepilogo e il piano dicono la stessa cosa --------------------------------------------------------------------------------

  @Test
  fun ciCheIlRiepilogoPromette_eCioCheIlPianoDa() = runTest {
    val world = PlanWorld(sources = BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots, telegram)))
    world.add(
      phoneMedia(1), phoneMedia(2, source = BackupSource.Screenshots), phoneMedia(3, source = telegram),
      phoneMedia(4, MediaKind.VIDEO, name = "V4.mp4"),
      phoneMedia(5, quickHash = qh('a')),
    )
    world.oldBackupFile(101, 4 * mb, qh('a')) // il 5 e' gia' sul NAS: il riepilogo lo conta fra i collegati, il piano lo propone ancora (D8 lo collega)

    val summary = world.planner.summary()
    val planned = world.planner.nextOverall(100)

    assertThat(planned.size).isEqualTo(summary.fresh.items + summary.similar.items + summary.linked.items)
    assertThat(planned.count { it.backupKind == BackupKind.VIDEOS }).isEqualTo(summary.fresh.videos)
  }
}
