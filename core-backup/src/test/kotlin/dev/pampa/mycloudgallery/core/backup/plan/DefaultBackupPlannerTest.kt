package dev.pampa.mycloudgallery.core.backup.plan

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.testing.ReferenceBackupPlanner
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il piano (Fase 5A, P4): quali candidati e in che ordine (D11), dove sul NAS (D6, Q8, E-B17), E-B12, §2.11 punti 1 e 3, la recinzione
 * di D13 e "Proteggi subito le foto nuove" (D34).
 */
class DefaultBackupPlannerTest {

  private val folder = DeviceFolder("S25 di prova")

  private fun idsOf(list: List<dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate>) = list.map { it.mediaId }

  // --- Ordine (D11) -----------------------------------------------------------------------------------------------------------

  @Test
  fun lefotoVengonoPrimaDeiVideo_ognunoDalPiuRecente() = runTest {
    val world = PlanWorld()
    world.add(
      phoneMedia(1, sortTime = 100),
      phoneMedia(2, sortTime = 300),
      phoneMedia(3, sortTime = 200),
      // I video sono piu' recenti delle foto, ma vengono dopo.
      phoneMedia(4, MediaKind.VIDEO, sortTime = 900, name = "VID_4.mp4"),
      phoneMedia(5, MediaKind.VIDEO, sortTime = 800, name = "VID_5.mp4"),
    )
    val planner = world.planner

    assertThat(idsOf(planner.next(BackupKind.PHOTOS, 10))).containsExactly(mid(2), mid(3), mid(1)).inOrder()
    assertThat(idsOf(planner.next(BackupKind.VIDEOS, 10))).containsExactly(mid(4), mid(5)).inOrder()
    // Il piano su tutto il telefono: prima tutte le foto, poi i video.
    assertThat(idsOf(planner.nextOverall(10))).containsExactly(mid(2), mid(3), mid(1), mid(4), mid(5)).inOrder()
    assertThat(idsOf(planner.nextOverall(4))).containsExactly(mid(2), mid(3), mid(1), mid(4)).inOrder()
    assertThat(idsOf(planner.nextOverall(2))).containsExactly(mid(2), mid(3)).inOrder()
  }

  @Test
  fun ilLimiteDaiPiuRecenti() = runTest {
    val world = PlanWorld()
    world.add(*(1..10).map { phoneMedia(it, sortTime = it.toLong()) }.toTypedArray())

    assertThat(idsOf(world.planner.next(BackupKind.PHOTOS, 3))).containsExactly(mid(10), mid(9), mid(8)).inOrder()
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { world.planner.next(BackupKind.PHOTOS, 0) } }
  }

  @Test
  fun lePaginePiuPiccoleDelLimite_nonFanno_perdereNeCambiareNiente() = runTest {
    val world = PlanWorld(pageSize = 4)
    world.add(*(1..20).map { phoneMedia(it, sortTime = it.toLong()) }.toTypedArray())

    assertThat(idsOf(world.planner.next(BackupKind.PHOTOS, 20))).containsExactlyElementsIn((20 downTo 1).map { mid(it) }).inOrder()
  }

  @Test
  fun lOriginalePrimaDellaVersione_ancheSeIlCatalogoLaDaAlContrario_E_E5() = runTest {
    val catalog =
      ListCatalog(
        listOf(
          candidate(1, sortTime = 100, original = true),
          candidate(1, sortTime = 100, original = false),
          candidate(2, sortTime = 300, original = true),
          candidate(2, sortTime = 300, original = false),
          candidate(3, sortTime = 200, original = false), // una versione senza originale da caricare (gia' sul NAS)
        ),
      )
    val world = PlanWorld(catalog = catalog)

    val next = world.planner.next(BackupKind.PHOTOS, 10)

    // Piu' recente prima; dentro uno stesso media l'originale prima della sua versione.
    assertThat(next.map { it.mediaId to it.original })
      .containsExactly(mid(2) to true, mid(2) to false, mid(3) to false, mid(1) to true, mid(1) to false)
      .inOrder()
  }

  @Test
  fun unaPaginaPienaCheSpezzaOriginaleEVersione_nonNePerdeNessuno() = runTest {
    val all =
      (1..5).flatMap { n -> listOf(candidate(n, sortTime = n * 10L, original = true), candidate(n, sortTime = n * 10L, original = false)) }
    for (pageSize in DefaultBackupPlanner.MIN_PAGE..11) {
      val world = PlanWorld(catalog = ListCatalog(all), pageSize = pageSize)

      val next = world.planner.next(BackupKind.PHOTOS, 100)

      assertThat(next.map { it.versionId }).containsExactlyElementsIn(all.map { it.versionId })
      assertThat(next.map { it.mediaId }.distinct()).containsExactly(mid(5), mid(4), mid(3), mid(2), mid(1)).inOrder()
      // Per ogni media l'originale viene prima.
      for (n in 1..5) {
        val mine = next.filter { it.mediaId == mid(n) }
        assertThat(mine.map { it.original }).containsExactly(true, false).inOrder()
      }
    }
  }

  @Test
  fun chiHaGiaUnElementoNelDiario_nonSiProponeDiNuovo_eNonBloccaQuelliDopo() = runTest {
    val world = PlanWorld(pageSize = 4)
    world.add(*(1..6).map { phoneMedia(it, sortTime = it.toLong()) }.toTypedArray())
    // I tre piu' recenti sono gia' nel diario (in attesa): il piano arriva comunque ai successivi.
    world.planned += listOf(vid(6), vid(5), vid(4))

    assertThat(idsOf(world.planner.next(BackupKind.PHOTOS, 2))).containsExactly(mid(3), mid(2)).inOrder()
  }

  // --- Sorgenti (E-B12) ------------------------------------------------------------------------------------------------------

  @Test
  fun soloLeSorgentiScelte_ESeNeTogliOUna_quelliNuoviNonSiCaricanoPiu_E_B12() = runTest {
    val world = PlanWorld()
    val telegram = BackupSource.Folder("external_primary", "Pictures/Telegram/")
    world.add(
      phoneMedia(1, source = BackupSource.Camera),
      phoneMedia(2, source = BackupSource.Screenshots),
      phoneMedia(3, source = telegram),
    )
    val planner = world.planner
    // Di default: fotocamera e screenshot (il passo 6 dell'onboarding); la cartella di Telegram non e' scelta.
    assertThat(idsOf(planner.next(BackupKind.PHOTOS, 10))).containsExactly(mid(2), mid(1))

    world.settings.sources.value = BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots, telegram))
    assertThat(idsOf(planner.next(BackupKind.PHOTOS, 10))).containsExactly(mid(3), mid(2), mid(1))

    // Tolgo gli screenshot: i nuovi non si caricano piu'...
    world.settings.sources.value = BackupSources(setOf(BackupSource.Camera, telegram))
    assertThat(idsOf(planner.next(BackupKind.PHOTOS, 10))).containsExactly(mid(3), mid(1))
    // ... ma quelli gia' al sicuro restano tali (il catalogo non si tocca) e non si ricaricano se la sorgente torna.
    val safe = phoneMedia(4, source = BackupSource.Screenshots, sortTime = 40)
    world.add(safe)
    world.memory.recordFullHash(safe.mediaId, safe.versionId, Sha256("1".repeat(64)))
    val copy = VerifiedCopy(safe.mediaId, safe.versionId, PRIVATE, SharePath.parse("Backup/S25/Screenshot/2026/10/IMG_4.jpg"), NasStamp(safe.size, 1, 1), 1)
    world.memory.attachVerifiedCopies(listOf(copy))
    assertThat(world.memory.isSafe(mid(4))).isTrue()
    world.settings.sources.value = BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots, telegram))
    assertThat(idsOf(planner.next(BackupKind.PHOTOS, 10))).containsExactly(mid(3), mid(2), mid(1))
    assertThat(world.memory.isSafe(mid(4))).isTrue()
  }

  @Test
  fun senzaSorgenti_nienteDaCaricare() = runTest {
    val world = PlanWorld(sources = BackupSources.NONE)
    world.add(phoneMedia(1))

    assertThat(world.planner.next(BackupKind.PHOTOS, 10)).isEmpty()
    assertThat(world.planner.nextOverall(10)).isEmpty()
  }

  // --- §2.11: cosa c'e' gia' sul NAS ------------------------------------------------------------------------------------------

  @Test
  fun fincheLaScansioneDellAreaPrivataNonEFinita_ilPianoAspetta_ILoDice_2_11() = runTest {
    val world = PlanWorld()
    world.add(phoneMedia(1), phoneMedia(2))
    world.existing.state = ExistingCopiesState(privateAreaScanned = false, similarPending = 0)

    assertThat(world.planner.next(BackupKind.PHOTOS, 10)).isEmpty()
    assertThat(world.planner.summary().comparing).isTrue()

    // Scansione finita: il piano parte, anche se il confronto delle copie simili non e' ancora finito (solo i numeri sono provvisori).
    world.existing.state = ExistingCopiesState(privateAreaScanned = true, similarPending = 120)
    assertThat(world.planner.next(BackupKind.PHOTOS, 10)).hasSize(2)
    assertThat(world.planner.summary().comparing).isTrue()

    world.existing.state = ExistingCopiesState.SETTLED
    assertThat(world.planner.summary().comparing).isFalse()
  }

  @Test
  fun laSolaCopiaNellAreaComune_nonSiRicarica_2_11_punto3() = runTest {
    val world = PlanWorld()
    val shared = phoneMedia(1)
    world.add(shared, phoneMedia(2))
    world.putCopy(shared, COMMON, "Foto famiglia/IMG_1.jpg")

    // Il media 1 risulta "sul NAS (area comune)": nessun caricamento nell'area privata (Q4 (b): ci pensera' il guardiano).
    assertThat(idsOf(world.planner.next(BackupKind.PHOTOS, 10))).containsExactly(mid(2))
  }

  @Test
  fun unCatalogoCheRestituisceIMediaConLaSolaCopiaComune_ilPianoNonLiProponeMai_2_11_punto3() = runTest {
    // Non ci si fida del catalogo (ne' del fake di P0, che li include): il piano li toglie da solo.
    val all = (1..12).map { candidate(it, sortTime = it.toLong()) }
    val common = setOf(mid(12), mid(9), mid(5), mid(1))
    for (pageSize in listOf(DefaultBackupPlanner.MIN_PAGE, 5, 500)) {
      val world = PlanWorld(catalog = ListCatalog(all, onlyCommonCopy = common), pageSize = pageSize)

      val next = world.planner.next(BackupKind.PHOTOS, 100)

      assertThat(idsOf(next)).containsExactly(mid(11), mid(10), mid(8), mid(7), mid(6), mid(4), mid(3), mid(2)).inOrder()
      // Il limite si riempie con quelli che restano, non con quelli tolti.
      assertThat(idsOf(world.planner.next(BackupKind.PHOTOS, 3))).containsExactly(mid(11), mid(10), mid(8)).inOrder()
      assertThat(world.planner.nextOverall(100).map { it.mediaId }).doesNotContain(mid(12))
    }
  }

  @Test
  fun leFotoNuove_nonProponganoIMediaConLaSolaCopiaComune() = runTest {
    val world = PlanWorld(catalog = ListCatalog(listOf(candidate(1, sortTime = 100), candidate(2, sortTime = 200)), onlyCommonCopy = setOf(mid(2))))
    world.clock.nasNow = null

    assertThat(idsOf(world.planner.next(BackupKind.PHOTOS, 10, BackupTrigger.QUICK_NEW_PHOTOS))).containsExactly(mid(1))
  }

  // --- Destinazione (D6) ---------------------------------------------------------------------------------------------------------

  @Test
  fun laDestinazioneUsaLaDataDiScattoElOraDelNasPerLeDateAssurde() = runTest {
    val world = PlanWorld()
    val planner = world.planner

    assertThat(planner.destination(candidate(1, localDay = 20261012), folder).value).isEqualTo("Backup/S25 di prova/2026/10/IMG_1.jpg")
    assertThat(planner.destination(candidate(2, source = BackupSource.Screenshots, localDay = 20250305, name = "S.png"), folder).value)
      .isEqualTo("Backup/S25 di prova/Screenshot/2025/03/S.png")
    assertThat(planner.destination(candidate(3, localDay = null), folder).value).isEqualTo("Backup/S25 di prova/Senza data/IMG_3.jpg")
    assertThat(planner.destination(candidate(4, localDay = 19790101), folder).value).isEqualTo("Backup/S25 di prova/Senza data/IMG_4.jpg")
    // Orologio del NAS: 16/10/2026 -> "oltre domani" e' dal 18.
    assertThat(planner.destination(candidate(5, localDay = 20261017), folder).value).isEqualTo("Backup/S25 di prova/2026/10/IMG_5.jpg")
    assertThat(planner.destination(candidate(6, localDay = 20261018), folder).value).isEqualTo("Backup/S25 di prova/Senza data/IMG_6.jpg")
    // Il NAS di domani: lo stesso giorno diventa credibile. L'ora del telefono non c'entra mai.
    world.clock.advance(86_400_000L)
    assertThat(planner.destination(candidate(6, localDay = 20261018), folder).value).isEqualTo("Backup/S25 di prova/2026/10/IMG_6.jpg")
    // Senza ora del NAS: solo le date che nessuna fotocamera puo' avere.
    world.clock.nasNow = null
    assertThat(planner.destination(candidate(7, localDay = 20261231), folder).value).isEqualTo("Backup/S25 di prova/2026/12/IMG_7.jpg")
    assertThat(planner.destination(candidate(8, localDay = 30000101), folder).value).isEqualTo("Backup/S25 di prova/Senza data/IMG_8.jpg")
  }

  @Test
  fun laRegolaDelloOltreDomani_eUnaFunzionePura_cheIlGuardianoPuoUsare() {
    val nasNow = 1_792_108_800_000L // 16/10/2026 00:00 UTC
    assertThat(BackupPaths.isCredibleDayAt(20261017, nasNow)).isTrue() // domani
    assertThat(BackupPaths.isCredibleDayAt(20261018, nasNow)).isFalse() // oltre domani
    assertThat(BackupPaths.isCredibleDayAt(19891231, nasNow)).isFalse()
    assertThat(BackupPaths.isCredibleDayAt(20261018, null)).isTrue() // senza l'ora del NAS: solo i limiti che non dipendono da un orologio
    assertThat(BackupPaths.isCredibleDayAt(21010101, null)).isFalse()
    // Stessa regola della destinazione: Senza data esattamente quando la data non e' credibile.
    for (day in listOf(20261016, 20261017, 20261018, 19791231, 20260230, 20300101)) {
      val noDate = BackupPaths.dateFolders(day, BackupPaths.tomorrowOf(nasNow)) == listOf(BackupPaths.UNDATED)
      assertThat(noDate).isEqualTo(!BackupPaths.isCredibleDayAt(day, nasNow))
    }
  }

  @Test
  fun laDestinazioneECoerenteConIlPianoDiRiferimentoDiP0_neiCasiComuni() = runTest {
    val world = PlanWorld()
    val reference = ReferenceBackupPlanner(world.memory)
    for (c in listOf(
      candidate(1), candidate(2, source = BackupSource.Screenshots), candidate(3, localDay = null),
      candidate(4, source = BackupSource.Folder("external_primary", "Pictures/Telegram/"), name = "t.jpg"),
    )) {
      assertThat(world.planner.destination(c, folder)).isEqualTo(reference.destination(c, folder))
    }
  }

  // --- D13: recinzione del telefono in debug con gli account di prova ----------------------------------------------------------

  @Test
  fun conLaRecinzione_soloMcgProva_eSiScriveInMcgCampo_D13() = runTest {
    val prova = BackupSource.Folder("external_primary", "DCIM/mcg-prova/")
    val provaPictures = BackupSource.Folder("external_primary", "Pictures/mcg-prova/")
    val world = PlanWorld(sources = BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots, prova, provaPictures)), fence = ProvaFence(), account = "test")
    world.add(
      phoneMedia(1, source = BackupSource.Camera), // le foto vere della famiglia: mai
      phoneMedia(2, source = BackupSource.Screenshots),
      phoneMedia(3, source = prova, name = "P3.jpg"),
      phoneMedia(4, source = provaPictures, name = "P4.jpg"),
    )
    val planner = world.planner

    val next = planner.next(BackupKind.PHOTOS, 10)

    assertThat(idsOf(next)).containsExactly(mid(4), mid(3)).inOrder()
    assertThat(planner.destination(next[1], folder).value).isEqualTo("mcg-campo/Backup/S25 di prova/mcg-prova/2026/10/P3.jpg")
    assertThat(planner.destination(next[0], folder).value).isEqualTo("mcg-campo/Backup/S25 di prova/mcg-prova/2026/10/P4.jpg")
    // Anche il riepilogo conta solo quelle.
    assertThat(planner.summary().fresh.photos).isEqualTo(2)
  }

  @Test
  fun conLaRecinzioneESenzaAccount_siFallisceChiuso() = runTest {
    val world = PlanWorld(fence = ProvaFence(), account = null)
    world.add(phoneMedia(1))
    val planner = world.planner

    assertThat(planner.next(BackupKind.PHOTOS, 10)).isEmpty()
    assertThat(planner.summary().fresh.items).isEqualTo(0)
    assertThrows(IllegalStateException::class.java) { planner.destination(candidate(1), folder) }
  }

  @Test
  fun conLAccountVero_laRecinzioneNonSiApplica() = runTest {
    val world = PlanWorld(fence = ProvaFence(), account = "alessio")
    world.add(phoneMedia(1), phoneMedia(2, source = BackupSource.Screenshots))
    val planner = world.planner

    assertThat(idsOf(planner.next(BackupKind.PHOTOS, 10))).containsExactly(mid(2), mid(1))
    assertThat(planner.destination(candidate(1), folder).value).isEqualTo("Backup/S25 di prova/2026/10/IMG_1.jpg")
  }

  // --- D34: "Proteggi subito le foto nuove" ------------------------------------------------------------------------------------

  private val hour = 3_600_000L

  @Test
  fun fotoNuove_soloFotoDellaFotocamera_maiVideo_D34() = runTest {
    val world = PlanWorld()
    val now = world.clock.nasNow!!
    world.add(
      phoneMedia(1, sortTime = now - hour), // una foto appena scattata
      phoneMedia(2, source = BackupSource.Screenshots, sortTime = now - hour), // uno screenshot: dalla fotocamera soltanto
      phoneMedia(3, MediaKind.VIDEO, sortTime = now - hour, name = "VID_3.mp4"), // mai un video
    )
    val planner = world.planner

    assertThat(idsOf(planner.next(BackupKind.PHOTOS, 10, BackupTrigger.QUICK_NEW_PHOTOS))).containsExactly(mid(1))
    assertThat(planner.next(BackupKind.VIDEOS, 10, BackupTrigger.QUICK_NEW_PHOTOS)).isEmpty()
    // Il giro normale le vede tutte.
    assertThat(idsOf(planner.next(BackupKind.PHOTOS, 10, BackupTrigger.SCHEDULED))).containsExactly(mid(2), mid(1)).inOrder()
    assertThat(planner.nextOverall(10, BackupTrigger.QUICK_NEW_PHOTOS).map { it.mediaId }).containsExactly(mid(1))
  }

  @Test
  fun fotoNuove_soloQuelleRecenti_misurateDallOraDelNas_nonDalTelefono() = runTest {
    val world = PlanWorld()
    val now = world.clock.nasNow!!
    val day = 24 * hour
    world.add(
      phoneMedia(1, sortTime = now - hour),
      phoneMedia(2, sortTime = now - 2 * day),
      phoneMedia(3, sortTime = now - 4 * day), // oltre i 3 giorni: l'arretrato lo prende il giro notturno
      phoneMedia(4, sortTime = now - 40 * day),
    )
    val planner = world.planner

    assertThat(idsOf(planner.next(BackupKind.PHOTOS, 10, BackupTrigger.QUICK_NEW_PHOTOS))).containsExactly(mid(1), mid(2)).inOrder()
    // Senza l'ora del NAS non si sa cosa e' "recente": resta il tetto sul numero (sotto).
    world.clock.nasNow = null
    assertThat(idsOf(planner.next(BackupKind.PHOTOS, 10, BackupTrigger.QUICK_NEW_PHOTOS))).hasSize(4)
  }

  @Test
  fun fotoNuove_unTettoSulNumero_nonSiSvuotaIlPrimoBackup() = runTest {
    val world = PlanWorld()
    val now = world.clock.nasNow!!
    world.add(*(1..60).map { phoneMedia(it, sortTime = now - it * 60_000L) }.toTypedArray())
    val planner = world.planner

    assertThat(planner.next(BackupKind.PHOTOS, 100, BackupTrigger.QUICK_NEW_PHOTOS)).hasSize(DefaultBackupPlanner.QUICK_MAX_ITEMS)
    assertThat(planner.next(BackupKind.PHOTOS, 5, BackupTrigger.QUICK_NEW_PHOTOS)).hasSize(5)
    assertThat(planner.next(BackupKind.PHOTOS, 100, BackupTrigger.SCHEDULED)).hasSize(60)
  }

  @Test
  fun fotoNuove_senzaLaFotocameraTraLeSorgenti_nienteEAncheLeFotoNuoveAspettanoLaScansione() = runTest {
    val world = PlanWorld(sources = BackupSources(setOf(BackupSource.Screenshots)))
    val now = world.clock.nasNow!!
    world.add(phoneMedia(1, sortTime = now - hour), phoneMedia(2, source = BackupSource.Screenshots, sortTime = now - hour))
    assertThat(world.planner.next(BackupKind.PHOTOS, 10, BackupTrigger.QUICK_NEW_PHOTOS)).isEmpty()

    world.settings.sources.value = BackupSources.DEFAULT
    world.existing.state = ExistingCopiesState(privateAreaScanned = false, similarPending = 0)
    assertThat(world.planner.next(BackupKind.PHOTOS, 10, BackupTrigger.QUICK_NEW_PHOTOS)).isEmpty()
  }

  @Test
  fun ilPianoDiRiferimentoDiP0_ignoraIlTrigger_laFirmaCompatibile() = runTest {
    val world = PlanWorld()
    world.add(phoneMedia(1), phoneMedia(2, MediaKind.VIDEO, name = "v.mp4"))
    val reference = ReferenceBackupPlanner(world.memory)

    // L'aggiunta al contratto e' compatibile: chi non la conosce risponde come `next(kind, limit)`.
    assertThat(reference.next(BackupKind.PHOTOS, 10, BackupTrigger.QUICK_NEW_PHOTOS)).hasSize(1)
    assertThat(reference.next(BackupKind.VIDEOS, 10, BackupTrigger.QUICK_NEW_PHOTOS)).hasSize(1)
  }
}
