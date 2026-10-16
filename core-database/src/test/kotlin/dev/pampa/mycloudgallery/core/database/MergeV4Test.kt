package dev.pampa.mycloudgallery.core.database

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.entity.EditSessionEntity
import dev.pampa.mycloudgallery.core.database.entity.FreeSpaceItemEntity
import dev.pampa.mycloudgallery.core.database.entity.FreeSpaceRunEntity
import dev.pampa.mycloudgallery.core.database.entity.PendingTaskEntity
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VisualSignature
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'unione di due identita' e la rinomina di un'identita' adottata (D7) non perdono nessuna riga delle tabelle della v4: diario del
 * backup, problema, firme visive, gruppi di copie simili e le tabelle della 5B (Fase 5A, P1). `MergeReferencesTest` controlla che
 * l'elenco dei riferimenti coincida con lo schema; qui si prova che l'unione e la rinomina li spostino davvero.
 */
@RunWith(AndroidJUnit4::class)
class MergeV4Test : CatalogTestBase() {

  /**
   * Due media con **gli stessi byte** (hash completo uguale) ma nati separati (impronta rapida diversa): si uniscono con `merge`,
   * come quando l'hash completo arriva dopo. Il minore sopravvive.
   */
  private suspend fun twins(n: Int): Pair<MediaId, MediaId> {
    val full = smallFull(n)
    val other = full.copy(quickHash = QuickHash("f".repeat(64)))
    val a = catalog.record(phoneObservation("IMG_${n}_a.jpg", 100L + n, full, T0 - n)).mediaId!!
    val b = catalog.record(phoneObservation("IMG_${n}_b.jpg", 200L + n, other, T0 - n)).mediaId!!
    check(a != b) { "I gemelli dovevano nascere separati." }
    return a to b
  }

  /** Una riga in ogni tabella della v4 che punta a un media o a una versione. */
  private suspend fun rowsOf(media: MediaId, version: VersionId, withSignature: Boolean, withProblem: Boolean) {
    db.backupDiaryDao().insert(backupItemEntity(media, version, state = "uploading", temp = ".mycloudgallery/tmp/tablet-bk-${media.value.take(4)}"))
    db.backupDiaryDao().insert(backupItemEntity(media, version, state = "done", kind = "guard_copy"))
    if (withProblem) catalog.markBackupProblem(media, "verify_failed")
    if (withSignature) catalog.saveSignature(version, VisualSignature.of(1, 1, ByteArray(64) { (it + media.value.hashCode()).toByte() }))
    db.pendingTaskDao().insert(PendingTaskEntity(kind = "delete_original_video", state = "open", mediaId = media.value, versionId = version.value, bytes = 5, createdAt = T0, updatedAt = T0))
    db.editSessionDao().insert(
      EditSessionEntity(
        mediaId = media.value, versionId = version.value, target = "phone_view", state = "open", phoneVolume = "external_primary",
        phoneMediaStoreId = 1, phoneMediaStoreVersion = Db.STORE_V1, startSize = 2_000, startModifiedAt = T0, startGeneration = 7,
        relativePath = "DCIM/Camera/", takenAtUtc = T0, cacheName = null, cacheSha256 = null, startedAt = T0, expiresAt = T0 + 1_800_000,
      ),
    )
    val run = db.freeSpaceDao().insertRun(
      FreeSpaceRunEntity(state = "ready", filter = "all", excludeRecentDays = 30, planHash = "p", deleteMode = "android_trash", itemCount = 1, totalBytes = 2_000, batchesTotal = 1, batchesDone = 0, createdAt = T0, updatedAt = T0),
    )
    db.freeSpaceDao().insertItems(
      listOf(
        FreeSpaceItemEntity(
          runId = run, itemIndex = 0, state = "verified", mediaId = media.value, versionId = version.value, phoneLocationId = 1,
          nasLocationId = 2, size = 2_000, verifiedSize = 2_000, verifiedModifiedAt = T0, verifiedFileId = null, batchIndex = 0, reason = null,
          updatedAt = T0,
        ),
      ),
    )
  }

  private suspend fun assertEverythingPointsTo(survivor: MediaId, version: VersionId, items: Int = 4) {
    val diary = db.backupDiaryDao().forMedia(survivor.value)
    assertThat(diary).hasSize(items)
    assertThat(diary.map { it.versionId }.toSet()).containsExactly(version.value)
    assertThat(db.pendingTaskDao().inState("open").map { it.mediaId to it.versionId }.toSet()).containsExactly(survivor.value to version.value)
    assertThat(db.editSessionDao().inState("open").map { it.mediaId to it.versionId }.toSet()).containsExactly(survivor.value to version.value)
    val freeItems = db.freeSpaceDao().runsInState(listOf("ready")).flatMap { db.freeSpaceDao().items(it.runId) }
    assertThat(freeItems.map { it.mediaId to it.versionId }.toSet()).containsExactly(survivor.value to version.value)
    db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
  }

  @Test
  fun unUnioneNonPerdeUnaRigaDiDiarioDiProblemaDiFirmaNeDelleTabelleDellaCinqueB() = runBlocking<Unit> {
    val (a, b) = twins(1)
    val versionA = catalog.versionOf(a)
    val versionB = catalog.versionOf(b)
    // Il perdente ha tutto; il sopravvissuto ha il suo diario ma niente firma ne' problema.
    val survivor = minOf(a, b)
    val loser = maxOf(a, b)
    rowsOf(survivor, catalog.versionOf(survivor), withSignature = false, withProblem = false)
    rowsOf(loser, catalog.versionOf(loser), withSignature = true, withProblem = true)
    assertThat(db.backupDiaryDao().forMedia(survivor.value)).hasSize(2)
    assertThat(db.backupDiaryDao().forMedia(loser.value)).hasSize(2)

    val outcome = catalog.merge(a, b)
    assertThat(outcome).isEqualTo(MediaCatalog.MergeOutcome.Merged(survivor, loser))

    // Le righe del perdente sono del sopravvissuto, sulla sua versione (stessi byte: la versione del perdente e' la stessa).
    val version = catalog.versionOf(survivor)
    assertThat(version).isAnyOf(versionA, versionB)
    assertEverythingPointsTo(survivor, version, items = 4)
    assertThat(db.backupDiaryDao().forMedia(loser.value)).isEmpty()
    // Il problema del perdente passa al sopravvissuto (che non ne aveva) e accende il segno; la firma segue la versione.
    assertThat(db.backupProblemDao().get(survivor.value)!!.code).isEqualTo("verify_failed")
    assertThat(db.backupProblemDao().count()).isEqualTo(1)
    assertThat(catalog.mediaSafety(loser)!!.problem).isEqualTo("verify_failed") // l'alias vale per il sopravvissuto
    assertThat(catalog.signatureOf(version)).isNotNull()
    assertThat(db.visualSignatureDao().count()).isEqualTo(1)
    assertThat(db.backupDiaryDao().citedTemps()).hasSize(2) // i temporanei restano citati: nessuno li toglie
  }

  @Test
  fun seTutteEDueLeVersioniHannoLaFirmaEIlProblemaResteQuelloDelSopravvissutoSenzaErrori() = runBlocking<Unit> {
    val (a, b) = twins(2)
    val survivor = minOf(a, b)
    val loser = maxOf(a, b)
    rowsOf(survivor, catalog.versionOf(survivor), withSignature = true, withProblem = true)
    rowsOf(loser, catalog.versionOf(loser), withSignature = true, withProblem = true)
    val keptSignature = catalog.signatureOf(catalog.versionOf(survivor))!!

    assertThat(catalog.merge(a, b)).isInstanceOf(MediaCatalog.MergeOutcome.Merged::class.java)

    assertEverythingPointsTo(survivor, catalog.versionOf(survivor))
    assertThat(db.visualSignatureDao().count()).isEqualTo(1)
    assertThat(catalog.signatureOf(catalog.versionOf(survivor))).isEqualTo(keptSignature)
    assertThat(db.backupProblemDao().count()).isEqualTo(1)
  }

  @Test
  fun unGruppoDiCopieSimiliSopravvivePercheIlSopravvissutoPrendeIlPostoDelPerdente() = runBlocking<Unit> {
    val (a, b) = twins(3)
    val c = catalog.record(phoneObservation("IMG_c.jpg", 300, smallFull(30), T0 - 30)).mediaId!!
    val survivor = minOf(a, b)
    val loser = maxOf(a, b)
    // Il perdente sta con c in un gruppo di copie simili, la cui copertina e' proprio lui.
    val group = (catalog.groupSimilar(listOf(loser, c), cover = loser) as SimilarGroupOutcome.Grouped).group

    catalog.merge(a, b)

    // Il gruppo non si perde: il sopravvissuto ne prende il posto, copertina compresa.
    val info = checkNotNull(catalog.similarGroupOf(survivor))
    assertThat(info.group).isEqualTo(group)
    assertThat(info.members.map { it.mediaId }).containsExactly(survivor, c)
    assertThat(info.cover).isEqualTo(survivor)
    assertThat(catalog.indexRows(listOf(survivor, c, loser)).map { it.mediaId }).containsExactly(survivor.value)
    assertThat(db.mediaDao().group(group.value)!!.kind).isEqualTo(MediaGroupKind.SIMILAR_COPIES.code)
  }

  @Test
  fun seDueCopieUniteStavanoNelloStessoGruppoDiCopieSimiliIlGruppoRestaSoloSeHaAncoraDueMedia() = runBlocking<Unit> {
    val (a, b) = twins(4)
    val c = catalog.record(phoneObservation("IMG_c.jpg", 300, smallFull(40), T0 - 40)).mediaId!!
    val survivor = minOf(a, b)
    val group = (catalog.groupSimilar(listOf(a, b, c), cover = c) as SimilarGroupOutcome.Grouped).group
    catalog.merge(a, b)
    assertThat(catalog.similarGroupOf(survivor)!!.members.map { it.mediaId }).containsExactly(survivor, c)
    assertThat(db.mediaDao().get(survivor.value)!!.groupId).isEqualTo(group.value)

    // Con due soli media, uniti, il gruppo rimarrebbe con uno: si scioglie, nessun gruppo di un media solo.
    val (x, y) = twins(5)
    val onlyPair = (catalog.groupSimilar(listOf(x, y), cover = x) as SimilarGroupOutcome.Grouped).group
    catalog.merge(x, y)
    assertThat(db.mediaDao().group(onlyPair.value)).isNull()
    assertThat(db.mediaDao().get(minOf(x, y).value)!!.groupId).isNull()
    assertThat(catalog.indexRows(listOf(x, y)).map { it.mediaId }).containsExactly(minOf(x, y).value)
  }

  @Test
  fun unaCopiaNascostaUnitaAUnGruppoDiCopieSimiliNascondeTutteLeCopie() = runBlocking<Unit> {
    val (a, b) = twins(6)
    val c = catalog.record(phoneObservation("IMG_c.jpg", 300, smallFull(60), T0 - 60)).mediaId!!
    val survivor = minOf(a, b)
    val loser = maxOf(a, b)
    catalog.groupSimilar(listOf(survivor, c), cover = survivor)
    // Il perdente era nascosto (e fuori dal gruppo): nel dubbio si nasconde, e il nascosto e' contagioso (E-H1).
    catalog.setPersonalFlags(loser, PersonalFlags(hidden = true))
    assertThat(db.personalDao().get(c.value)?.hidden ?: false).isFalse()

    catalog.merge(a, b)

    assertThat(db.personalDao().get(survivor.value)!!.hidden).isTrue()
    assertThat(db.personalDao().get(c.value)!!.hidden).isTrue()
    assertThat(catalog.indexRows(listOf(survivor, c, loser))).isEmpty()
  }

  @Test
  fun laRinominaDiUnIdentitaAdottataSpostaOgniRigaDellaV4() = runBlocking<Unit> {
    val media = catalog.record(phoneObservation("IMG_1.jpg", 101, smallFull(7), T0 - 7)).mediaId!!
    val version = catalog.versionOf(media)
    rowsOf(media, version, withSignature = true, withProblem = true)
    val newMedia = MediaId("00000000-0000-4000-8000-0000000000c1")
    val newVersion = VersionId("00000000-0000-4000-8000-0000000000c2")

    // Le chiavi esterne sono differite: il controllo e' al commit, quando tutto torna coerente.
    db.withTransaction {
      db.identityDao().renameMedia(media.value, newMedia.value)
      db.identityDao().renameVersion(version.value, newVersion.value)
    }

    assertEverythingPointsTo(newMedia, newVersion, items = 2)
    assertThat(db.backupProblemDao().get(newMedia.value)!!.code).isEqualTo("verify_failed")
    assertThat(db.visualSignatureDao().get(newVersion.value)).isNotNull()
    assertThat(db.backupDiaryDao().forMedia(media.value)).isEmpty()
  }
}
