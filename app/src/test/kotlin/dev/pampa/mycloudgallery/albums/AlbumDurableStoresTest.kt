package dev.pampa.mycloudgallery.albums

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpRecord
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanHash
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadIntent
import dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadItem
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.hidden.HiddenWorldBuilder
import dev.pampa.mycloudgallery.hidden.WorldSpec
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * I registri durevoli degli album (P13, L76): il diario della condivisione su Room (campo per campo, passi controllati dalla macchina a
 * stati, ripresa dopo la morte del processo) e i piccoli registri su file (caricamenti verso gli album, richieste del guardiano, segni
 * "visto"), che dopo un processo nuovo ritrovano esattamente cio' che avevano.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlbumDurableStoresTest {
  private val app: Application = ApplicationProvider.getApplicationContext()
  private val dir: File = Files.createTempDirectory("albumi").toFile()

  @Test
  fun ilDiarioSuRoomRitrovaOperazioniEPassiEControllaLaMacchinaAStati() = runBlocking<Unit> {
    val world = HiddenWorldBuilder.build(app, WorldSpec.random(7, 12))
    try {
      val diary = RoomShareDiary(world.db) { 1_000L }
      val op = ShareOpRecord(
        id = ShareOpId("op-1"), album = AlbumId.derived(dev.pampa.mycloudgallery.core.model.AccountId.of("test"), ByteArray(16)),
        kind = SharePlanKind.SHARE, planHash = SharePlanHash("%064x".format(5)), title = "Prova", level = ShareLevel.ADD,
        nonceHex = "00".repeat(16), folder = ShareFolder("test-comune", SharePath.of("Album condivisi", "Prova")), hidden = HiddenChoice.LEAVE_OUT,
      )
      val items = world.ids.take(3).mapIndexed { i, media ->
        ShareItemRecord(op.id, i, media, ShareItemAction.MOVE_IN, ShareItemState.PLANNED, mine = true, size = 100L + i, contentKey = ContentKey("%064x".format(i + 1)),
          source = NasItemRef("test", SharePath.of("Foto", "a$i.jpg")))
      }
      diary.create(op, items)
      assertThat(diary.opByPlan(op.planHash)).isEqualTo(op)
      assertThat(diary.openOps()).containsExactly(op)
      assertThat(diary.items(op.id)).isEqualTo(items)

      val moving = items[0].copy(state = ShareItemState.TRANSFERRING, transferKey = TransferKey("k:1"), ticket = TransferTicket("op:9"), wait = ShareWait.NO_SPACE)
      diary.update(moving)
      val failed = items[1].copy(state = ShareItemState.FAILED, failure = ShareFailure.SOURCE_CHANGED)
      diary.update(failed)
      // Un passo saltato e' un errore di programmazione (come nel diario di riferimento).
      assertThrows(IllegalStateException::class.java) { runBlocking { diary.update(items[2].copy(state = ShareItemState.PUBLISHED)) } }

      // Un processo nuovo: lo stesso diario.
      val reborn = RoomShareDiary(world.db) { 2_000L }
      assertThat(reborn.items(op.id)).isEqualTo(listOf(moving, failed, items[2]))
      reborn.finish(op.id)
      assertThat(reborn.openOps()).isEmpty()
      assertThat(reborn.opByPlan(op.planHash)!!.finished).isTrue()
      assertThat(reborn.countsByState().associate { it.status to it.count }).containsExactly("transferring", 1, "failed", 1, "planned", 1)
    } finally {
      world.db.close()
    }
  }

  @Test
  fun iRegistriSuFileRitrovanoTuttoDopoUnProcessoNuovo() = runBlocking<Unit> {
    val item = AlbumUploadItem(MediaId("11111111-2222-3333-4444-555555555555"), VersionId("11111111-2222-3333-4444-666666666666"), 1234, PhoneRef("external_primary", 42, "x1"),
      FileStamp(1234, 99, 7), QuickHash("%064x".format(3)), "IMG_1.jpg")
    val journal = FileAlbumUploadJournal(File(dir, "journal"))
    journal.recordIntent("k:1", AlbumUploadIntent("test-comune", SharePath.of("Album condivisi", "Prova"), listOf(item)))
    // Una chiave gia' vista non cambia (idempotenza).
    journal.recordIntent("k:1", AlbumUploadIntent("altra", SharePath.of("X"), emptyList()))
    journal.recordItems("k:1", listOf(BackupItemId(5), BackupItemId(6)))
    val again = FileAlbumUploadJournal(File(dir, "journal")).intent("k:1")!!
    assertThat(again.share).isEqualTo("test-comune")
    assertThat(again.folder).isEqualTo(SharePath.of("Album condivisi", "Prova"))
    assertThat(again.created).containsExactly(BackupItemId(5), BackupItemId(6)).inOrder()
    val read = again.items.single()
    assertThat(listOf(read.mediaId, read.versionId, read.size, read.phone, read.expected, read.quickHash, read.fileName))
      .isEqualTo(listOf(item.mediaId, item.versionId, item.size, item.phone, item.expected, item.quickHash, item.fileName))

    val log = FileSafeCopyRequestLog(File(dir, "safe"))
    val a = MediaId("11111111-2222-3333-4444-000000000001")
    val b = MediaId("11111111-2222-3333-4444-000000000002")
    log.add(setOf(a, b))
    log.remove(setOf(a))
    assertThat(FileSafeCopyRequestLog(File(dir, "safe")).pending()).containsExactly(b)

    val marks = FileSmartSeenMarks(File(dir, "seen"))
    val album = AlbumId.derived(dev.pampa.mycloudgallery.core.model.AccountId.of("test"), ByteArray(16) { 1 })
    marks.markSeen(album, 77)
    assertThat(FileSmartSeenMarks(File(dir, "seen")).seenUpTo(album)).isEqualTo(77)

    // Un file rovinato vale "vuoto" (si perde la memoria, non si inventa niente).
    File(dir, "safe").writeBytes(byteArrayOf(1, 2, 3))
    assertThat(FileSafeCopyRequestLog(File(dir, "safe")).pending()).isEmpty()
  }
}
