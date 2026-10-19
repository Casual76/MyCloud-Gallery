package dev.pampa.mycloudgallery.core.backup.free

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceWarning
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P9, il piano di "Libera spazio" (§2.5 punto 1 e "Escluso perche'", D21, Q9, E-S11, D13, E-B22): **dal catalogo locale soltanto**, con
 * gli esclusi per motivo. Un elemento entra solo se ogni versione da proteggere ha una copia privata verificata (e il custode delle versioni
 * di P8 e' d'accordo); mai non verificati, in caricamento, in errore, con versioni scoperte, con l'area congelata, fuori da `mcg-prova`.
 */
class FreeSpacePlanTest {

  private val bench = FreeSpaceBench()

  @Test
  fun q9_diSerieTuttoIlSicuroTranneGliUltimiTrentaGiorni_eIFiltriSoloVideoEPiuVecchiDiUnAnno() = runTest {
    val oldPhoto = bench.safe(localDay = 20_240_101)
    val recentPhoto = bench.safe(localDay = 20_261_010) // 9 giorni fa
    val oldVideo = bench.safe(localDay = 20_240_201, kind = MediaKind.VIDEO)
    val recentVideo = bench.safe(localDay = 20_261_001, kind = MediaKind.VIDEO) // 18 giorni fa
    val summer = bench.safe(localDay = 20_260_601) // 4 mesi fa
    val undated = bench.safe(localDay = null)

    val all = bench.space.plan(FreeSpaceFilter())
    assertThat(all.items.map { it.mediaId }).containsExactly(oldPhoto.id, oldVideo.id, summer.id, undated.id)
    assertThat(all.excluded).containsExactly(FreeSpaceExclusion.RECENT, 2)
    assertThat(all.totalBytes).isEqualTo(4L * 4_096)

    val videos = bench.space.plan(FreeSpaceFilter(FreeSpaceKind.VIDEOS_ONLY))
    assertThat(videos.items.map { it.mediaId }).containsExactly(oldVideo.id)
    assertThat(videos.items.single().isVideo).isTrue()
    assertThat(videos.excluded).containsExactly(FreeSpaceExclusion.RECENT, 1)

    val old = bench.space.plan(FreeSpaceFilter(FreeSpaceKind.OLDER_THAN_A_YEAR))
    assertThat(old.items.map { it.mediaId }).containsExactly(oldPhoto.id, oldVideo.id)

    // Senza giorni esclusi entrano anche i recenti; la scelta dalla Libreria contiene al massimo quei media.
    assertThat(bench.space.plan(FreeSpaceFilter(excludeRecentDays = 0)).itemCount).isEqualTo(6)
    assertThat(bench.space.plan(FreeSpaceFilter(selection = setOf(recentPhoto.id, recentVideo.id), excludeRecentDays = 0)).items.map { it.mediaId })
      .containsExactly(recentPhoto.id, recentVideo.id)
  }

  @Test
  fun lAvvisoForteCeSempre_ePerLiberaSubitoAncheIlDefinitivo() = runTest {
    assertThat(bench.space.plan(FreeSpaceFilter()).warnings).containsExactly(FreeSpaceWarning.NO_EXTERNAL_BACKUP)
    bench.safe()
    val trash = bench.space.plan(FreeSpaceFilter())
    val now = bench.space.plan(FreeSpaceFilter(), permanent = true)
    assertThat(trash.warnings).containsExactly(FreeSpaceWarning.NO_EXTERNAL_BACKUP)
    assertThat(now.warnings).containsExactly(FreeSpaceWarning.NO_EXTERNAL_BACKUP, FreeSpaceWarning.PERMANENT)
    assertThat(now.requiredConfirmations).isEqualTo(2)
    assertThat(now.hash).isNotEqualTo(trash.hash)
  }

  @Test
  fun maiNonVerificati_inCaricamento_inErrore_conVersioniScoperte_oNonPropri() = runTest {
    val good = bench.safe()
    bench.safe(verified = false) // copia privata mai riletta
    bench.safe(privateCopy = false) // nessuna copia sul NAS
    bench.safe(hashKnown = false, verified = true) // "verificata" senza un hash completo noto: non conta (F4-D8)
    bench.safe(owner = AccountId("test2")) // non mio
    val uploading = bench.safe()
    val failed = bench.safe()
    bench.edited(originalVerified = false) // l'originale in Versioni/ non e' verificato
    bench.edited(originalCopy = false) // l'originale non c'e' piu' da nessuna parte: non blocca per sempre (SafetyRules)
    bench.store.create(
      listOf(
        NewBackupItem(
          UploadKind.NEW_COPY, uploading.id, uploading.current, 4_096, uploading.phoneRef, null, FreeSpaceBench.PRIVATE,
          SharePath.parse("Backup/S25/2024/01/x.jpg"),
        ),
      ),
    )
    bench.catalog.setProblem(failed.id, BackupProblemCode.VERIFY_FAILED)

    val plan = bench.space.plan(FreeSpaceFilter())
    assertThat(plan.itemCount).isEqualTo(2)
    assertThat(plan.items.map { it.mediaId }).contains(good.id)
    assertThat(plan.excluded).containsExactly(
      FreeSpaceExclusion.NOT_VERIFIED, 4,
      FreeSpaceExclusion.IN_PROGRESS_OR_ERROR, 2,
      FreeSpaceExclusion.VERSION_NOT_SAFE, 1,
    )
    // L'elemento nel piano porta il timbro della copia privata contro cui e' stato deciso (il controllo finale).
    assertThat(plan.items.first { it.mediaId == good.id }.safeCopy).isNotNull()
  }

  @Test
  fun versioni_ilCustodeDelleVersioniDiP8DeveEssereDAccordo() = runTest {
    val agreed = bench.edited()
    val p8Disagrees = bench.edited(p8Safe = false)
    val unknownToP8 = bench.edited()
    bench.versions.setPlan(
      dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan(
        unknownToP8.id, emptyList(), dev.pampa.mycloudgallery.core.backup.contract.VersionArea.OWN_PRIVATE,
        dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability.NOT_AVAILABLE,
      ),
    )
    val plan = bench.space.plan(FreeSpaceFilter())
    assertThat(plan.items.map { it.mediaId }).containsExactly(agreed.id)
    // P8 dice "attuale non al sicuro" per uno, non conosce l'altro: entrambi restano sul telefono.
    assertThat(plan.excluded).containsExactly(FreeSpaceExclusion.NOT_VERIFIED, 1, FreeSpaceExclusion.VERSION_NOT_SAFE, 1)
    // Si libera la copia del telefono della versione attuale.
    assertThat(plan.items.single().versionId).isEqualTo(agreed.current)
  }

  @Test
  fun eB22_conLAreaCongelataOLeScrittureFermeNienteEntra() = runTest {
    bench.safe()
    bench.safe()
    bench.gate.default = BackupGateState.FROZEN
    assertThat(bench.space.plan(FreeSpaceFilter()).let { it.items to it.excluded }).isEqualTo(emptyList<Any>() to mapOf(FreeSpaceExclusion.AREA_FROZEN to 2))
    bench.gate.default = BackupGateState.WRITES_STOPPED
    assertThat(bench.space.plan(FreeSpaceFilter()).excluded).containsExactly(FreeSpaceExclusion.AREA_FROZEN, 2)
    // La pausa dell'utente (R4) ferma chi scrive, non "Libera spazio" sulle copie gia' al sicuro.
    bench.gate.default = BackupGateState.PAUSED
    assertThat(bench.space.plan(FreeSpaceFilter()).itemCount).isEqualTo(2)
  }

  @Test
  fun d12_r2_senzaUnBackupAttivoNienteSiLibera() = runTest {
    bench.safe()
    for (state in listOf(BackupGateState.TEST_ONLY, BackupGateState.NO_PRIVATE_AREA, BackupGateState.NOT_READY)) {
      bench.gate.default = state
      val plan = bench.space.plan(FreeSpaceFilter())
      assertThat(plan.isEmpty).isTrue()
      assertThat(plan.excluded).containsExactly(FreeSpaceExclusion.NOT_VERIFIED, 1)
    }
  }

  @Test
  fun d13_conLaRecinzioneNienteFuoriDaMcgProva() = runTest {
    bench.fence = TestFence
    bench.restart()
    bench.safe(folder = "DCIM/Camera/")
    bench.safe(folder = "Pictures/WhatsApp/")
    val inFence = bench.safe(folder = "DCIM/mcg-prova/")
    val inFence2 = bench.safe(folder = "Pictures/mcg-prova/")
    val plan = bench.space.plan(FreeSpaceFilter())
    assertThat(plan.items.map { it.mediaId }).containsExactly(inFence.id, inFence2.id)
    assertThat(plan.excluded).containsExactly(FreeSpaceExclusion.OUTSIDE_TEST_FENCE, 2)
  }

  @Test
  fun eB14_conLaSolaCopiaComuneEntraConIlGuardiano() = runTest {
    val common = bench.safe(privateCopy = false, commonCopy = true)
    val plan = bench.space.plan(FreeSpaceFilter())
    assertThat(plan.items.map { it.mediaId }).containsExactly(common.id)
    assertThat(plan.needsGuardian).isEqualTo(1)
    assertThat(plan.items.single().safeCopy).isNull()
    // Con il cancello in pausa il guardiano non scrive: quell'elemento non e' liberabile.
    bench.gate.default = BackupGateState.PAUSED
    assertThat(bench.space.plan(FreeSpaceFilter()).excluded).containsExactly(FreeSpaceExclusion.NOT_VERIFIED, 1)
  }

  @Test
  fun eS11_ilPianoNonLeggeIlNas_eUnFileDelNasNonAggiungeNiente() = runTest {
    repeat(20) { bench.safe() }
    // File "dal PC" nell'area privata e in quella comune che il catalogo non conosce: non sono candidati.
    repeat(5) { bench.nas.putFile(FreeSpaceBench.PRIVATE, "Backup/S25/2024/01/estraneo_$it.jpg", dev.pampa.mycloudgallery.core.nas.testing.NasContent.of(bench.bytes(100, it))) }
    val opens = bench.nas.opens.get()
    val stats = bench.nas.statCalls.get()
    val lists = bench.nas.listCalls.get()
    val read = bench.nas.bytesRead.get()
    val plan = bench.space.plan(FreeSpaceFilter())
    assertThat(plan.itemCount).isEqualTo(20)
    assertThat(listOf(bench.nas.opens.get(), bench.nas.statCalls.get(), bench.nas.listCalls.get())).containsExactly(opens, stats, lists).inOrder()
    assertThat(bench.nas.bytesRead.get()).isEqualTo(read)
    // Il catalogo si scorre a pagine (7 per pagina nel banco): nessuna lista intera in memoria per costruire il piano.
    assertThat(bench.catalog.pages).isAtLeast(3)
  }

  @Test
  fun laSceltaDellaLibreria_alMassimoQueiMedia_eQuelliSenzaCopiaSulTelefonoSiContano() = runTest {
    val a = bench.safe()
    bench.safe()
    val nasOnly = bench.safe(onPhone = false)
    val plan = bench.space.plan(FreeSpaceFilter(selection = setOf(a.id, nasOnly.id, MediaId.random())))
    assertThat(plan.items.map { it.mediaId }).containsExactly(a.id)
    assertThat(plan.excluded).containsExactly(FreeSpaceExclusion.GONE_FROM_PHONE, 2)
  }

  @Test
  fun senzaLOraDelNasUnMediaDatatoSiTrattaComeRecente() = runTest {
    bench.safe(localDay = 20_200_101)
    bench.safe(localDay = null)
    bench.today = null
    val plan = bench.space.plan(FreeSpaceFilter())
    assertThat(plan.itemCount).isEqualTo(1)
    assertThat(plan.excluded).containsExactly(FreeSpaceExclusion.RECENT, 1)
    assertThat(bench.space.plan(FreeSpaceFilter(FreeSpaceKind.OLDER_THAN_A_YEAR)).isEmpty).isTrue()
  }

  @Test
  fun unaCopiaDiUnaltraAreaPrivataODiAreaSconosciutaNonFaAlSicuro() = runTest {
    val m = bench.safe(privateCopy = false)
    // Una copia "verificata" in una share che non e' la mia area privata (area sconosciuta): al massimo il guardiano, mai "al sicuro".
    bench.addLocation(m.id, bench.nasCopy(m.current, FreeSpaceBench.OTHER_PRIVATE, "Backup/x.jpg", m.data.getValue(m.current), verifiedAt = bench.clock.nasNow))
    val plan = bench.space.plan(FreeSpaceFilter())
    assertThat(plan.needsGuardian).isEqualTo(1)
    assertThat(plan.items.single().safeCopy).isNull()
  }

  /** La recinzione di D13 come quella di debug: con l'account `test` solo `DCIM/mcg-prova/` e `Pictures/mcg-prova/`. */
  object TestFence : BackupFence {
    override fun allowsPhoneFolder(account: String, relativePath: String): Boolean =
      relativePath.startsWith("DCIM/mcg-prova/") || relativePath.startsWith("Pictures/mcg-prova/")

    override fun destinationRoot(account: String): SharePath? = SharePath.of("mcg-campo")
  }
}
