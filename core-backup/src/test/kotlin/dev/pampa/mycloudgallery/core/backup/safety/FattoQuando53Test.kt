package dev.pampa.mycloudgallery.core.backup.safety

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyResult
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope
import dev.pampa.mycloudgallery.core.backup.testing.BackupInvariants
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il "Fatto quando" di 5.3 (`docs/fase5-progetto.md` §2.3), **scenario per scenario**: in nessuno scenario un media resta senza una copia
 * sotto il controllo del proprietario (salvo il limite dichiarato di Q4 (b), L44). Piu' gli edge case della tabella: E-B5, E-B8, E-B13,
 * E-B14, E-L3, E-S11.
 *
 * Lo spostamento nell'area comune e' quello della 4B (Cartelle NAS, piano "Sposta" fra share): il diario finto della 4B sta in
 * core-nasops, che core-backup **non puo' vedere** (D2, `Phase5ArchitectureTest`); qui se ne riproducono gli effetti sul NAS finto, gli
 * stessi del cammino `COPYING → … → SOURCE_TRASHED` (copia nell'area comune, sorgente nel cestino del NAS della sua share, 30 giorni) e
 * cio' che la scansione registra nel catalogo dopo (la sola copia comune).
 */
class FattoQuando53Test {

  private val bench = SafetyBench()
  private val kit = bench.kit
  private val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"

  private suspend fun assertClean(before: BackupInvariants.Snapshot) {
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = true)).isEmpty()
  }

  /**
   * Gli effetti sul NAS di uno spostamento della 4B dall'area privata all'area comune: la copia nell'area comune, la sorgente nel cestino
   * del NAS della share privata (`.mycloudgallery/trash/<dispositivo>/files/<id>`, 30 giorni).
   */
  private fun moveToCommonLike4B(data: ByteArray, privatePath: String, commonPath: String): SharePath {
    bench.putFile(kit.share, privatePath, data)
    val trash = NasLayout.trashItemFile(kit.deviceId, TrustIds.random())
    kit.nas.putFile(kit.commonShare, commonPath, data.content(), modifiedAt = kit.clock.nasNow!! - 1_000)
    kit.nas.rename(kit.share, privatePath, trash.value)
    return trash
  }

  /**
   * Scenario 1, "Condivisione: spostamento nell'area comune" (Cartelle NAS della 4B; album della Fase 7), risposta Q4 (b): subito dopo lo
   * spostamento il guardiano **non** crea la copia privata (il telefono ce l'ha ancora; la sorgente resta 30 giorni nel cestino del NAS);
   * parte quando sparisce la copia del telefono. Il cestino non si tocca.
   */
  @Test
  fun spostamentoNellAreaComuneQ4b() = runTest {
    val data = SafetyBench.bytes(45_000, 1)
    val trash = moveToCommonLike4B(data, "$dir/IMG_1.jpg", "Foto di famiglia/IMG_1.jpg")
    // Cio' che la scansione registra dopo lo spostamento: la copia privata non c'e' piu', resta quella comune; il telefono ce l'ha.
    val start = bench.commonOnly("IMG_1.jpg", data, onPhone = true, commonDir = "Foto di famiglia", fileAlreadyThere = true)
    val before = kit.snapshot()
    assertThat(kit.catalog.mediaSafety(start.mediaId)!!.status).isEqualTo(SafetyStatus.ONLY_COMMON_COPY)

    // Subito dopo lo spostamento: niente (Q4 (b)).
    val now = bench.guardian().ensure(GuardScope.All, 1.hours)
    assertThat(now.created).isEqualTo(0)
    assertThat(kit.snapshot().files.keys).isEqualTo(before.files.keys)

    // La copia del telefono sparisce (Samsung Gallery), la lettura del telefono lo registra: il guardiano crea la copia privata.
    bench.phoneLoses(start)
    val later = bench.guardUntilQuiet()
    assertThat(later.created).isEqualTo(1)
    assertThat(kit.nas.bytes(kit.share, "${bench.guardDir()}/IMG_1.jpg")!!.toList()).isEqualTo(data.toList())
    assertThat(kit.catalog.isSafe(start.mediaId)).isTrue()
    // Il cestino del NAS resta com'era: il guardiano crea, non toglie (D2).
    assertThat(kit.nas.bytes(kit.share, trash.value)!!.toList()).isEqualTo(data.toList())
    assertClean(before)
  }

  /**
   * Scenario 2, "Cancellazione da Samsung Gallery" (la copia del telefono sparisce, E-P11): gia' al sicuro = niente da fare; solo copia
   * comune = guardiano alla prima lettura del telefono che vede l'assenza; nessuna copia sul NAS = E-B4 (il motore: `GONE` con "persa
   * prima del backup"), il guardiano non c'entra.
   */
  @Test
  fun cancellazioneDaSamsungGallery() = runTest {
    val safe = bench.withPrivateCopy("IMG_S.jpg", SafetyBench.bytes(20_000, 2))
    val common = bench.commonOnly("IMG_C.jpg", SafetyBench.bytes(21_000, 3), onPhone = true)
    val nowhere = kit.addPhoneMedia("IMG_N.jpg", SafetyBench.bytes(22_000, 4).content())
    val before = kit.snapshot()
    bench.phoneLoses(safe)
    bench.phoneLoses(common)
    kit.phone.delete(kit.phoneIdOf(nowhere))

    val report = bench.guardUntilQuiet()
    assertThat(report.created).isEqualTo(1)
    assertThat(bench.guardItems().map { it.mediaId }).containsExactly(common.mediaId)
    assertThat(kit.catalog.isSafe(safe.mediaId)).isTrue()
    assertThat(kit.catalog.isSafe(common.mediaId)).isTrue()

    kit.resumeUntilQuiet()
    val lost = kit.store.all().single { it.mediaId == nowhere }
    assertThat(lost.state).isEqualTo(UploadState.GONE)
    assertThat(kit.catalog.problemOf(nowhere)).isEqualTo(BackupProblemCode.LOST_BEFORE_BACKUP)
    assertClean(before)
  }

  /**
   * Scenario 3, "Libera spazio" (D21, E-B14): per i media scelti, prima il guardiano (anche con il telefono che li ha ancora), poi la
   * verifica sul momento rilegge ogni copia privata. Una copia rovinata dopo la sua verifica non torna: esce da "al sicuro" e da cio' che
   * si puo' liberare (il resto del flusso e' della 5B).
   */
  @Test
  fun liberaSpazio() = runTest {
    val onlyCommon = bench.commonOnly("IMG_L1.jpg", SafetyBench.bytes(30_000, 5), onPhone = true)
    val rotten = bench.withPrivateCopy("IMG_L2.jpg", SafetyBench.bytes(31_000, 6), verifiedAt = kit.clock.nasNow!! - 1_000)
    val stamp = bench.stampOf(kit.share, rotten.commonPath!!.value)!!
    kit.nas.modify(kit.share, rotten.commonPath.value, SafetyBench.oneByteChanged(rotten.data).content(), modifiedAt = stamp.modifiedAtMillis)
    val chosen = setOf(onlyCommon.mediaId, rotten.mediaId)
    val before = kit.snapshot()

    val guard = bench.guardian().ensure(GuardScope.Media(chosen), 1.hours)
    assertThat(guard.created).isEqualTo(1)
    kit.clock.advance(1_000)
    val spot = bench.verifier().verify(VerifyScope.OnTheSpot(chosen), 1.hours)

    assertThat(spot.verified).isEqualTo(1)
    assertThat(spot.mismatched).isEqualTo(1)
    assertThat(kit.catalog.mediaSafety(onlyCommon.mediaId)!!.verifiedAtMillis).isEqualTo(kit.clock.nasNow)
    assertThat(kit.catalog.isSafe(rotten.mediaId)).isFalse()
    assertClean(before)
  }

  /**
   * Scenario 4, "Elimina solo dal telefono" (D22, E-D14): con la sola copia comune parte prima il guardiano; senza nessuna copia sul NAS il
   * guardiano non fa niente: tocca a "Prima mettilo al sicuro" (il backup), poi il media e' al sicuro.
   */
  @Test
  fun eliminaSoloDalTelefono() = runTest {
    val onlyCommon = bench.commonOnly("IMG_E1.jpg", SafetyBench.bytes(25_000, 7), onPhone = true)
    val notOnNas = kit.addPhoneMedia("IMG_E2.jpg", SafetyBench.bytes(26_000, 8).content())
    val before = kit.snapshot()

    val report = bench.guardian().ensure(GuardScope.Media(setOf(onlyCommon.mediaId, notOnNas)), 1.hours)
    assertThat(report.created).isEqualTo(1)
    assertThat(kit.catalog.isSafe(onlyCommon.mediaId)).isTrue()
    assertThat(kit.catalog.isSafe(notOnNas)).isFalse()

    // "Prima mettilo al sicuro": backup e verifica (il motore di riferimento al posto di quello di P2).
    kit.resumeUntilQuiet()
    assertThat(kit.catalog.isSafe(notOnNas)).isTrue()
    assertClean(before)
  }

  /**
   * Scenario 5, "Un familiare cancella la copia comune dal PC": con la copia privata gia' fatta resta quella (e la rotazione la conferma);
   * con il telefono che ha ancora il media (Q4 (b): nessuna copia privata) il backup normale la carica dal telefono. Limite dichiarato
   * (L44): se la copia del telefono sparisce **dopo** la cancellazione della comune, non c'e' piu' niente da copiare.
   */
  @Test
  fun unFamiliareCancellaLaCopiaComune() = runTest {
    val guarded = bench.commonOnly("IMG_F1.jpg", SafetyBench.bytes(20_000, 9))
    val stillOnPhone = bench.commonOnly("IMG_F2.jpg", SafetyBench.bytes(21_000, 10), onPhone = true)
    val limit = bench.commonOnly("IMG_F3.jpg", SafetyBench.bytes(22_000, 11), onPhone = true)
    bench.guardUntilQuiet()
    assertThat(kit.catalog.isSafe(guarded.mediaId)).isTrue()
    val before = kit.snapshot()

    for (s in listOf(guarded, stillOnPhone, limit)) kit.nas.delete(kit.commonShare, s.commonPath!!.value)
    val beforeDeletion = BackupInvariants.Snapshot(before.files.filterValues { !it.share.equals(kit.commonShare, ignoreCase = true) }, before.phoneShas)

    // (a) La copia privata resta, e la rotazione la conferma.
    val rotation = bench.verifier().verify(VerifyScope.Rotation, 1.hours)
    assertThat(rotation.verified).isEqualTo(1)
    assertThat(kit.catalog.isSafe(guarded.mediaId)).isTrue()

    // (b) Il telefono ce l'ha: il guardiano non tocca (Q4 (b)); il backup la carica dal telefono.
    kit.resumeUntilQuiet()
    assertThat(kit.catalog.isSafe(stillOnPhone.mediaId)).isTrue()

    // (c) L44: la copia del telefono sparisce dopo la cancellazione della comune: niente da copiare, mai "al sicuro" per finta.
    // (Nel giro sopra il backup l'ha gia' caricata dal telefono: qui si prova il guardiano da solo, con un media che non era in sorgente.)
    val lateLoss = bench.commonOnly("IMG_F4.jpg", SafetyBench.bytes(23_000, 12), onPhone = true)
    kit.nas.delete(kit.commonShare, lateLoss.commonPath!!.value)
    bench.phoneLoses(lateLoss)
    bench.guardUntilQuiet()
    assertThat(bench.guardItems().single { it.mediaId == lateLoss.mediaId }.state).isEqualTo(UploadState.GONE)
    assertThat(kit.catalog.isSafe(lateLoss.mediaId)).isFalse()
    assertClean(beforeDeletion)
  }

  /**
   * Scenario 6, "Reinstallazione o telefono nuovo" (E-B16, E-B5): il catalogo nuovo vede i file della cartella del dispositivo dalla
   * scansione; la deduplica li rilegge e li collega, senza caricare niente; un file diverso con lo stesso nome non si collega.
   */
  @Test
  fun reinstallazioneOTelefonoNuovo() = runTest {
    val same = (1..4).map { SafetyBench.bytes(15_000 + it, 20 + it) }
    same.forEachIndexed { i, data -> kit.putNasFile(kit.share, "$dir/IMG_R$i.jpg", data.content()) }
    val changed = SafetyBench.bytes(15_100, 30)
    kit.putNasFile(kit.share, "$dir/IMG_RX.jpg", SafetyBench.oneByteChanged(changed).content())
    val before = kit.snapshot()

    var linked = 0
    for ((i, data) in (same + changed).withIndex()) {
      val name = if (i < same.size) "IMG_R$i.jpg" else "IMG_RX.jpg"
      val id = kit.addPhoneMedia(name, data.content())
      val m = kit.catalog.media(id)!!
      val candidate = BackupCandidate(
        m.mediaId, m.versionId, m.phone!!, m.phoneStamp!!, m.size, m.kind, m.source, m.localDay, false, m.fileName, m.sortTime,
        QuickHashAlgorithm.ofContent(data),
      )
      kit.catalog.recordFullHash(m.mediaId, m.versionId, Sha256.of(data))
      val result = bench.finder().find(candidate, Sha256.of(data), kit.share)
      if (result is ExistingCopyResult.Found) {
        kit.catalog.attachVerifiedCopies(listOf(result.copy))
        linked++
      }
    }
    assertThat(linked).isEqualTo(same.size)
    assertThat(kit.nas.backupBytesWritten.get()).isEqualTo(0L)
    // Il diverso si carica (nel dubbio si carica) con un nome nuovo, accanto a quello che c'era.
    kit.resumeUntilQuiet()
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_RX (2).jpg")!!.toList()).isEqualTo(changed.toList())
    assertClean(before)
  }

  /** E-B8: una copia non piu' integra esce da "al sicuro"; il backup la rifa' se il telefono ce l'ha (con un nome nuovo). */
  @Test
  fun copiaNonPiuIntegraSiRifaDalTelefono() = runTest {
    val start = bench.withPrivateCopy("IMG_B8.jpg", SafetyBench.bytes(18_000, 13), verifiedAt = kit.clock.nasNow!! - 380L * 86_400_000)
    val stamp = bench.stampOf(kit.share, start.commonPath!!.value)!!
    kit.nas.modify(kit.share, start.commonPath.value, SafetyBench.oneByteChanged(start.data).content(), modifiedAt = stamp.modifiedAtMillis)
    val before = kit.snapshot()

    bench.verifier().verify(VerifyScope.Rotation, 1.hours)
    assertThat(kit.catalog.isSafe(start.mediaId)).isFalse()
    kit.resumeUntilQuiet()

    assertThat(kit.catalog.isSafe(start.mediaId)).isTrue()
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_B8 (2).jpg")!!.toList()).isEqualTo(start.data.toList())
    assertClean(before)
  }

  /** E-L3: identici in piu' cartelle = un media con piu' posizioni; le copie in eccesso non si toccano. */
  @Test
  fun identiciInPiuCartelleNonSiToccano() = runTest {
    val start = bench.withPrivateCopy("IMG_L3.jpg", SafetyBench.bytes(12_000, 14), verifiedAt = null)
    for (folder in listOf("Vecchio", "Vecchissimo")) {
      val path = SharePath.parse("$folder/IMG_L3.jpg")
      kit.catalog.putCopy(start.mediaId, start.versionId, kit.share, path, bench.putFile(kit.share, path.value, start.data))
    }
    val before = kit.snapshot()
    val report = bench.verifier().verify(VerifyScope.Unverified, 1.hours)
    assertThat(report.verified).isEqualTo(3)
    assertThat(kit.catalog.copiesOf(start.mediaId).filter { it.verifiedAtMillis != null }).hasSize(3)
    assertThat(kit.snapshot().files.keys).isEqualTo(before.files.keys)
    assertClean(before)
  }

  /**
   * E-S11: un dato remoto (file nell'area comune che il catalogo non conosce, voci del cestino, cartelle nuove) non avvia mai una copia o
   * una verifica, e niente di cio' che fanno guardiano e verifiche toglie qualcosa: dopo tutti i giri ogni file che c'era c'e' ancora.
   */
  @Test
  fun unDatoRemotoNonAvviaNienteENienteSiToglie() = runTest {
    val unknown = SafetyBench.bytes(10_000, 15)
    kit.nas.putFile(kit.commonShare, "Arrivati dal PC/IMG_U.jpg", unknown.content())
    kit.nas.putFile(kit.share, NasLayout.trashItemFile(kit.deviceId, TrustIds.random()).value, unknown.content())
    val mine = InMemoryBackupCatalog.Media(
      mediaId = MediaId.random(), versionId = VersionId.random(), kind = MediaKind.PHOTO, size = 10_000, quickHash = null, phone = null,
      phoneStamp = null, source = BackupSource.Camera, localDay = null, fileName = "IMG_U.jpg", sortTime = 0, fullHash = Sha256.of(unknown),
    )
    kit.catalog.put(mine) // un media proprio senza copie note: niente da proteggere per il guardiano
    val before = kit.snapshot()

    val guard = bench.guardUntilQuiet()
    for (scope in listOf(VerifyScope.Unverified, VerifyScope.FullHash, VerifyScope.Rotation)) bench.verifier().verify(scope, 1.hours)

    assertThat(guard.created + guard.linked).isEqualTo(0)
    assertThat(kit.store.all()).isEmpty()
    assertThat(kit.snapshot().files.keys).isEqualTo(before.files.keys)
    assertClean(before)
  }
}
