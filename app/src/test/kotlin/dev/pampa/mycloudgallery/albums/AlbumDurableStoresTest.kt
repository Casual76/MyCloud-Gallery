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

  /**
   * I5 (revisione di sicurezza della Fase 7): una riga del diario che questa versione non sa leggere (un codice di un'app piu' nuova, una
   * colonna rovinata) non sparisce in silenzio. Prima l'elemento usciva dall'elenco e l'operazione si chiudeva con lui ancora in viaggio;
   * un'operazione illeggibile spariva da `openOps` e da `opByPlan` (lo stesso piano poteva ripartire). Adesso resta, ferma: nessun passo la
   * tocca, l'operazione non si chiude, la riga non si sovrascrive, e si conta.
   */
  @Test
  fun i5UnaRigaIllegibileDelDiarioRestaFermaENonSparisce() = runBlocking<Unit> {
    val world = HiddenWorldBuilder.build(app, WorldSpec.random(8, 12))
    try {
      val diary = RoomShareDiary(world.db) { 1_000L }
      fun op(n: Int) = ShareOpRecord(
        id = ShareOpId("op-$n"), album = AlbumId.derived(dev.pampa.mycloudgallery.core.model.AccountId.of("test"), ByteArray(16) { n.toByte() }),
        kind = SharePlanKind.SHARE, planHash = SharePlanHash("%064x".format(n)), title = "Prova", level = ShareLevel.ADD,
        nonceHex = "00".repeat(16), folder = ShareFolder("test-comune", SharePath.of("Album condivisi", "Prova")), hidden = HiddenChoice.LEAVE_OUT,
      )
      val first = op(1)
      val items = world.ids.take(3).mapIndexed { i, media ->
        ShareItemRecord(first.id, i, media, ShareItemAction.MOVE_IN, ShareItemState.PLANNED, mine = true, size = 100L + i)
      }
      diary.create(first, items)
      diary.update(items[0].copy(state = ShareItemState.FAILED, failure = ShareFailure.SOURCE_GONE))
      diary.update(items[1].copy(state = ShareItemState.FAILED, failure = ShareFailure.SOURCE_GONE))
      // Il terzo elemento ha uno stato che questa versione non conosce (scritto da un'app piu' nuova).
      world.db.openHelper.writableDatabase.execSQL("UPDATE album_share_item SET state = 'futuro' WHERE op_id = 'op-1' AND item_index = 2")

      val read = RoomShareDiary(world.db) { 2_000L }
      val all = read.items(first.id)
      assertThat(all.map { it.index }).containsExactly(0, 1, 2).inOrder()
      val stuck = all[2]
      assertThat(stuck.state).isEqualTo(ShareItemState.TRANSFERRING)
      assertThat(stuck.ticket).isNull()
      assertThat(stuck.transferKey).isNull()
      assertThat(stuck.media).isEqualTo(items[2].media)
      // L'operazione non risulta finita (gli altri due sono terminali) e la riga non si sovrascrive.
      assertThat(dev.pampa.mycloudgallery.core.albums.share.ShareOrchestrator.stateOf(first, all))
        .isInstanceOf(dev.pampa.mycloudgallery.core.albums.contract.ShareOpState.Running::class.java)
      assertThrows(IllegalStateException::class.java) { runBlocking { read.update(stuck.copy(state = ShareItemState.FAILED, failure = ShareFailure.TRANSFER_FAILED)) } }
      assertThat(read.unreadableRows).isEqualTo(1)

      // Un'operazione con una colonna illeggibile: resta fra quelle aperte, il suo piano si ritrova, i suoi elementi sono tutti fermi.
      val second = op(2)
      diary.create(second, listOf(ShareItemRecord(second.id, 0, world.ids[5], ShareItemAction.MOVE_IN, ShareItemState.IN_COMMON, mine = true, size = 9)))
      world.db.openHelper.writableDatabase.execSQL("UPDATE album_share_op SET kind = 'futuro' WHERE op_id = 'op-2'")
      val reborn = RoomShareDiary(world.db) { 3_000L }
      assertThat(reborn.openOps().map { it.id }).containsExactly(first.id, second.id).inOrder()
      val frozenOp = reborn.opByPlan(second.planHash)!!
      assertThat(frozenOp.nonceHex).isNull()
      assertThat(frozenOp.folder).isNull()
      assertThat(reborn.items(second.id).single().state).isEqualTo(ShareItemState.TRANSFERRING)
      assertThat(reborn.items(second.id).single().ticket).isNull()
      assertThat(reborn.unreadableRows).isEqualTo(2)
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

  }

  /**
   * R9 (revisione di sicurezza della Fase 7): un registro che c'e' ma non si legge non vale "vuoto" e la prima modifica non lo sovrascrive.
   * Prima: le richieste al guardiano rovinate valevano "nessuna" e la prima `add` le sovrascriveva; i legami dei trasferimenti illeggibili
   * facevano dire a `find` "nessun trasferimento" (SD-2 non distingueva "non so"); un registro oltre il tetto valeva vuoto.
   */
  @Test
  fun r9UnRegistroIllegibileValeNonSoENonSiSovrascrive() = runBlocking<Unit> {
    val garbage = byteArrayOf(0, 0, 0, 1, 0x7f, 0x7f, 0x7f, 0x7f, 1, 2, 3)
    val a = MediaId("11111111-2222-3333-4444-000000000001")

    // Le richieste del guardiano: il file rovinato va da parte intero (non si sovrascrive) e si riparte vuoti; la richiesta nuova si registra.
    // (Le richieste accelerano il guardiano, che dal catalogo trova comunque i media con la sola copia comune.)
    val safe = File(dir, "safe").apply { writeBytes(garbage) }
    val log = FileSafeCopyRequestLog(safe)
    assertThat(log.pending()).isEmpty()
    assertThat(File(dir, "safe.bad").readBytes()).isEqualTo(garbage)
    log.add(setOf(a))
    assertThat(FileSafeCopyRequestLog(safe).pending()).containsExactly(a)

    // I legami dei trasferimenti: `find` non dice "nessuno" (SD-2), dice "non so" (lancia); il file resta com'e'.
    val plansFile = File(dir, "plans").apply { writeBytes(garbage) }
    val plans = TransferPlans(plansFile)
    assertThrows(UnreadableRecords::class.java) { runBlocking { plans.planOf(TransferKey("k:1")) } }
    assertThrows(UnreadableRecords::class.java) { runBlocking { plans.bind(TransferKey("k:2"), dev.pampa.mycloudgallery.core.nasops.contract.PlanHash("%064x".format(2))) } }
    assertThat(plansFile.readBytes()).isEqualTo(garbage)
    assertThat(plans.unreadable).isAtLeast(1)

    // Le intenzioni dei caricamenti: oltre il tetto del file valgono "non so", non "vuoto".
    val big = File(dir, "journal-big")
    java.io.RandomAccessFile(big, "rw").use { it.setLength(DurableFile.MAX_BYTES + 1) }
    assertThrows(UnreadableRecords::class.java) { runBlocking { FileAlbumUploadJournal(big).intent("k:1") } }
    assertThat(big.length()).isEqualTo(DurableFile.MAX_BYTES + 1)

    // Torna leggibile (un errore passeggero): si rilegge, niente era rimasto in memoria.
    plansFile.delete()
    assertThat(plans.planOf(TransferKey("k:1"))).isNull()

    // Dove "vuoto" e' innocuo (i segni "Nuove"): il file rovinato va da parte, intero, e si riparte vuoti.
    val seenFile = File(dir, "seen-bad").apply { writeBytes(garbage) }
    val seen = FileSmartSeenMarks(seenFile)
    val album = AlbumId.derived(dev.pampa.mycloudgallery.core.model.AccountId.of("test"), ByteArray(16) { 2 })
    assertThat(seen.seenUpTo(album)).isNull()
    assertThat(File(dir, "seen-bad.bad").readBytes()).isEqualTo(garbage)
    seen.markSeen(album, 5)
    assertThat(FileSmartSeenMarks(seenFile).seenUpTo(album)).isEqualTo(5)
  }

  /** R9: i registri dei trasferimenti tengono solo le chiavi delle operazioni aperte della condivisione (non crescono senza fine). */
  @Test
  fun r9IRegistriDeiTrasferimentiSiPotanoConIlDiario() = runBlocking<Unit> {
    val plans = TransferPlans(File(dir, "plans-prune"))
    val journal = FileAlbumUploadJournal(File(dir, "journal-prune"))
    val hash = dev.pampa.mycloudgallery.core.nasops.contract.PlanHash("%064x".format(7))
    plans.bind(TransferKey("k:aperta"), hash)
    plans.bind(TransferKey("k:chiusa"), hash)
    journal.recordIntent("k:caricamento-chiuso", AlbumUploadIntent("test-comune", SharePath.of("Album condivisi", "Prova"), emptyList()))
    journal.recordIntent("k:aperta", AlbumUploadIntent("test-comune", SharePath.of("Album condivisi", "Prova"), emptyList()))
    val registers = TransferRegisters(plans, journal)
    // Una chiave legata mentre si legge il diario (dopo la lettura delle chiavi) non si tocca.
    val removed = registers.prune {
      plans.bind(TransferKey("k:nuova"), hash)
      setOf("k:aperta")
    }
    assertThat(removed).isEqualTo(2)
    assertThat(plans.keys()).containsExactly("k:aperta", "k:nuova")
    assertThat(journal.keys()).containsExactly("k:aperta")
    assertThat(TransferPlans(File(dir, "plans-prune")).planOf(TransferKey("k:nuova"))).isEqualTo(hash)
  }
}
