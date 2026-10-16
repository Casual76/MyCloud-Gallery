package dev.pampa.mycloudgallery.core.backup.safety

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupInvariants
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.ServerCopyMode
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * D9, Q4 (b), E-B13, E-B14, E-B17, E-D12, E-D13, E-S11, F4-D8, D13, D32: il guardiano delle copie private, scenario per scenario. Ogni
 * scenario che scrive finisce con gli invarianti del §5.4 veri (nessun file a meta' visibile, nessun doppione, nessuna sostituzione,
 * nessuna perdita, temporanei solo `-bk-` citati dal diario e nessuno a lavoro finito).
 */
class PrivateCopyGuardianTest {

  private val bench = SafetyBench()
  private val kit = bench.kit

  private suspend fun assertClean(before: BackupInvariants.Snapshot) {
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = true)).isEmpty()
  }

  /** La fotografia "di prima" con la sorgente gia' cambiata dal PC (E-D13): il cambiamento e' del PC, non del guardiano. */
  private fun BackupInvariants.Snapshot.withSourceChangedTo(share: String, path: String, bytes: ByteArray): BackupInvariants.Snapshot =
    BackupInvariants.Snapshot(
      files.mapValues { (_, f) ->
        if (f.share == share && f.path == path) BackupInvariants.FileFact(f.share, f.path, Sha256.of(bytes), f.fileId) else f
      },
      phoneShas,
    )

  /** D9: copia lato server dall'area comune, verifica completa, pubblicazione in `Backup/Area comune/AAAA/MM/`; la sorgente resta. */
  @Test
  fun copiaLatoServerVerificataEPubblicataNellAreaPrivata() = runTest {
    val start = bench.commonOnly("IMG_1.jpg", SafetyBench.bytes(50_000, 1))
    val before = kit.snapshot()
    val copiesBefore = kit.nas.serverCopies.get()

    val report = bench.guardian().ensure(GuardScope.All, 1.hours)

    assertThat(report.created).isEqualTo(1)
    assertThat(report.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(report.bytesCopied).isEqualTo(50_000L)
    assertThat(kit.nas.bytes(kit.share, "${bench.guardDir()}/IMG_1.jpg")!!.toList()).isEqualTo(start.data.toList())
    assertThat(kit.nas.bytes(kit.commonShare, start.commonPath!!.value)!!.toList()).isEqualTo(start.data.toList())
    assertThat(kit.catalog.isSafe(start.mediaId)).isTrue()
    assertThat(kit.nas.serverCopies.get() - copiesBefore).isEqualTo(1)
    // Nessun byte passato dal telefono: e' tutta copia lato server.
    assertThat(kit.nas.backupBytesWritten.get()).isEqualTo(0L)
    assertThat(bench.guardItems().single().state).isEqualTo(UploadState.DONE)
    assertClean(before)
  }

  /**
   * Q4 (b): con l'ambito "tutti" il guardiano non tocca un media che il telefono ha ancora (niente copia privata subito dopo uno
   * spostamento nell'area comune); con l'ambito dei media chiesti (prima di "Libera spazio", "Elimina solo dal telefono") si'.
   */
  @Test
  fun conLAmbitoTuttiSoloIMediaCheIlTelefonoNonHaPiu() = runTest {
    val onPhone = bench.commonOnly("IMG_P.jpg", SafetyBench.bytes(20_000, 2), onPhone = true)
    val before = kit.snapshot()

    val all = bench.guardian().ensure(GuardScope.All, 1.hours)
    assertThat(all.created).isEqualTo(0)
    assertThat(all.skipped).isEqualTo(1)
    assertThat(kit.snapshot().files.keys).isEqualTo(before.files.keys)
    assertThat(kit.store.all()).isEmpty()

    val asked = bench.guardian().ensure(GuardScope.Media(setOf(onPhone.mediaId)), 1.hours)
    assertThat(asked.created).isEqualTo(1)
    assertThat(kit.catalog.isSafe(onPhone.mediaId)).isTrue()
    assertClean(before)
  }

  /** §2.3: senza l'hash della versione si rilegge qui la sorgente, valida solo con timbro e `qh1` della versione; l'hash entra nel catalogo. */
  @Test
  fun senzaHashDellaVersioneSiRileggeLaSorgente() = runTest {
    val start = bench.commonOnly("IMG_2.jpg", SafetyBench.bytes(300_000, 3), hashKnown = false)
    val noQuick = bench.commonOnly("IMG_3.jpg", SafetyBench.bytes(30_000, 4), hashKnown = false, quickKnown = false)
    val before = kit.snapshot()

    val report = bench.guardian().ensure(GuardScope.All, 1.hours)

    assertThat(report.created).isEqualTo(1)
    assertThat(kit.catalog.media(start.mediaId)!!.fullHash).isEqualTo(Sha256.of(start.data))
    assertThat(kit.catalog.isSafe(start.mediaId)).isTrue()
    // Senza hash e senza `qh1` non si puo' dire che la sorgente sia la versione: niente.
    assertThat(kit.catalog.isSafe(noQuick.mediaId)).isFalse()
    assertThat(report.skipped).isEqualTo(1)
    assertClean(before)
  }

  /** E-D12: la copia lato server rifiutata; la versione e' ancora sul telefono: i suoi byte, poi la verifica completa. */
  @Test
  fun copiaLatoServerRifiutataRipiegaSuiByteDelTelefono() = runTest {
    val start = bench.commonOnly("IMG_4.jpg", SafetyBench.bytes(70_000, 5), onPhone = true)
    kit.nas.serverCopyMode = ServerCopyMode.NOT_SUPPORTED
    val before = kit.snapshot()
    val phoneRead = kit.phone.bytesRead

    val report = bench.guardian().ensure(GuardScope.Media(setOf(start.mediaId)), 1.hours)

    assertThat(report.created).isEqualTo(1)
    assertThat(kit.phone.bytesRead - phoneRead).isEqualTo(70_000L)
    assertThat(kit.nas.backupBytesWritten.get()).isEqualTo(70_000L)
    assertThat(kit.nas.bytes(kit.share, "${bench.guardDir()}/IMG_4.jpg")!!.toList()).isEqualTo(start.data.toList())
    assertThat(kit.catalog.isSafe(start.mediaId)).isTrue()
    assertClean(before)
  }

  /** E-D12 senza il telefono: la sorgente letta dal NAS e scritta dall'app nel temporaneo, verificata, pubblicata. */
  @Test
  fun copiaLatoServerRifiutataSenzaTelefonoCopiaLaSorgente() = runTest {
    val start = bench.commonOnly("IMG_5.jpg", SafetyBench.bytes(70_000, 6))
    kit.nas.serverCopyMode = ServerCopyMode.ACCESS_DENIED
    val before = kit.snapshot()

    val report = bench.guardian().ensure(GuardScope.All, 1.hours)

    assertThat(report.created).isEqualTo(1)
    assertThat(kit.nas.backupBytesWritten.get()).isEqualTo(70_000L)
    assertThat(kit.catalog.isSafe(start.mediaId)).isTrue()
    assertClean(before)
  }

  /** E-D12 con i byte del telefono che non sono la versione (riga riscritta): non si usano, si copia la sorgente. */
  @Test
  fun iByteDelTelefonoSbagliatiNonSiUsano() = runTest {
    val start = bench.commonOnly("IMG_6.jpg", SafetyBench.bytes(40_000, 7), onPhone = true)
    kit.phone.rewrite(start.phoneId!!, SafetyBench.bytes(40_000, 70).content(), modifiedMs = 5_000)
    kit.nas.serverCopyMode = ServerCopyMode.NOT_SUPPORTED
    val before = kit.snapshot()

    bench.guardian().ensure(GuardScope.Media(setOf(start.mediaId)), 1.hours)

    assertThat(kit.nas.bytes(kit.share, "${bench.guardDir()}/IMG_6.jpg")!!.toList()).isEqualTo(start.data.toList())
    assertThat(kit.catalog.isSafe(start.mediaId)).isTrue()
    assertClean(before)
  }

  /** E-D13: la copia comune modificata dal PC dopo la scansione (timbro diverso): niente si copia ne' si pubblica. */
  @Test
  fun sorgenteCambiataPrimaDellaCopiaNientePubblicato() = runTest {
    val start = bench.commonOnly("IMG_7.jpg", SafetyBench.bytes(30_000, 8))
    kit.nas.modify(kit.commonShare, start.commonPath!!.value, SafetyBench.bytes(30_000, 80).content(), modifiedAt = 77)
    val before = kit.snapshot()

    val report = bench.guardian().ensure(GuardScope.All, 1.hours)

    assertThat(report.created).isEqualTo(0)
    assertThat(bench.guardItems().single().state).isEqualTo(UploadState.GONE)
    assertThat(bench.visible(kit.share)).isEmpty()
    assertThat(kit.catalog.isSafe(start.mediaId)).isFalse()
    assertClean(before)
  }

  /**
   * E-D13: la sorgente cambia **durante** la copia lato server (il temporaneo ha byte misti o nuovi): la verifica completa non torna,
   * il nuovo tentativo vede il timbro cambiato e si ferma. Niente pubblicato, nessun temporaneo rimasto.
   */
  @Test
  fun sorgenteCambiataDuranteLaCopiaNientePubblicato() = runTest {
    val start = bench.commonOnly("IMG_8.jpg", SafetyBench.bytes(30_000, 9))
    val before = kit.snapshot()
    val ops = SourceChangingOps(kit.ops) {
      kit.nas.modify(kit.commonShare, start.commonPath!!.value, SafetyBench.bytes(30_000, 90).content(), modifiedAt = 88)
    }

    val report = bench.guardian(ops = ops).ensure(GuardScope.All, 1.hours)

    assertThat(ops.changed).isTrue()
    assertThat(report.created).isEqualTo(0)
    val item = bench.guardItems().single()
    assertThat(item.state).isEqualTo(UploadState.GONE)
    assertThat(item.verifyAttempts).isEqualTo(1)
    assertThat(bench.visible(kit.share)).isEmpty()
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
    assertThat(kit.catalog.isSafe(start.mediaId)).isFalse()
    assertClean(before.withSourceChangedTo(kit.commonShare, start.commonPath!!.value, SafetyBench.bytes(30_000, 90)))
  }

  /** E-D13 senza hash della versione: la sorgente cambia mentre la si rilegge per il riferimento: niente si copia. */
  @Test
  fun sorgenteCambiataDuranteLaLetturaDiRiferimento() = runTest {
    val start = bench.commonOnly("IMG_9.jpg", SafetyBench.bytes(30_000, 10), hashKnown = false)
    val path = start.commonPath!!.value
    var changed = false
    kit.nas.fault = FakeNasFault { op, share, p ->
      if (!changed && op == FakeNasOp.READ_ALL && share == kit.commonShare && p?.value == path) {
        changed = true
        kit.nas.modify(kit.commonShare, path, start.data.content(), modifiedAt = 99)
      }
      null
    }
    val before = kit.snapshot()

    bench.guardian().ensure(GuardScope.All, 1.hours)

    assertThat(changed).isTrue()
    assertThat(bench.guardItems().single().state).isEqualTo(UploadState.GONE)
    assertThat(kit.catalog.media(start.mediaId)!!.fullHash).isNull()
    assertThat(kit.nas.serverCopies.get()).isEqualTo(0)
    assertThat(bench.visible(kit.share)).isEmpty()
    assertClean(before)
  }

  /** E-D13 nel ripiego dalla sorgente: cambia mentre la si copia dall'app: il temporaneo si toglie, niente pubblicato. */
  @Test
  fun sorgenteCambiataDuranteIlRipiego() = runTest {
    val start = bench.commonOnly("IMG_10.jpg", SafetyBench.bytes(60_000, 11))
    val path = start.commonPath!!.value
    kit.nas.serverCopyMode = ServerCopyMode.NOT_SUPPORTED
    var reads = 0
    kit.nas.fault = FakeNasFault { op, share, p ->
      if (op == FakeNasOp.READ && share == kit.commonShare && p?.value == path && ++reads == 2) {
        kit.nas.modify(kit.commonShare, path, SafetyBench.bytes(60_000, 110).content(), modifiedAt = 111)
      }
      null
    }
    val before = kit.snapshot()

    bench.guardian().ensure(GuardScope.All, 1.hours)

    assertThat(reads).isAtLeast(2)
    assertThat(bench.guardItems().single().state).isEqualTo(UploadState.GONE)
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
    assertClean(before.withSourceChangedTo(kit.commonShare, path, SafetyBench.bytes(60_000, 110)))
  }

  /** E-B3: il nome e' di un altro file → "(2)"; il file che c'era resta byte per byte. */
  @Test
  fun nomeOccupatoDaUnAltroFile() = runTest {
    val start = bench.commonOnly("IMG_11.jpg", SafetyBench.bytes(20_000, 12))
    bench.putFile(kit.share, "${bench.guardDir()}/IMG_11.jpg", SafetyBench.bytes(21_000, 13))
    val before = kit.snapshot()

    bench.guardian().ensure(GuardScope.All, 1.hours)

    assertThat(kit.nas.bytes(kit.share, "${bench.guardDir()}/IMG_11 (2).jpg")!!.toList()).isEqualTo(start.data.toList())
    assertThat(kit.nas.bytes(kit.share, "${bench.guardDir()}/IMG_11.jpg")!!.toList()).isEqualTo(SafetyBench.bytes(21_000, 13).toList())
    assertClean(before)
  }

  /** D8 per il guardiano: una copia identica gia' nell'area privata (vecchio backup) si collega, nessuna copia nuova. */
  @Test
  fun unaCopiaIdenticaGiaNellAreaPrivataSiCollega() = runTest {
    val start = bench.commonOnly("IMG_12.jpg", SafetyBench.bytes(25_000, 14))
    kit.putNasFile(kit.share, "Backup telefono 2023/IMG_12.jpg", start.data.content())
    val before = kit.snapshot()

    val report = bench.guardian().ensure(GuardScope.All, 1.hours)

    assertThat(report.linked).isEqualTo(1)
    assertThat(bench.guardItems().single().state).isEqualTo(UploadState.LINKED_EXISTING)
    assertThat(kit.nas.serverCopies.get()).isEqualTo(0)
    assertThat(kit.catalog.isSafe(start.mediaId)).isTrue()
    assertClean(before)
  }

  /**
   * F4-D8: l'hash della versione viene dall'indice e non e' quello dei byte della copia comune. La verifica completa non torna mai:
   * dopo 3 tentativi `FAILED` con `VERIFY_FAILED`, niente pubblicato, mai "al sicuro".
   */
  @Test
  fun unHashDellIndiceSbagliatoNonFaMaiAlSicuro() = runTest {
    val real = SafetyBench.bytes(30_000, 15)
    val start = bench.commonOnly("IMG_13.jpg", real, claimedHash = Sha256.of(SafetyBench.oneByteChanged(real)))
    val before = kit.snapshot()

    bench.guardUntilQuiet()

    val item = bench.guardItems().single()
    assertThat(item.state).isEqualTo(UploadState.FAILED)
    assertThat(item.verifyAttempts).isEqualTo(3)
    assertThat(kit.catalog.problemOf(start.mediaId)).isEqualTo(BackupProblemCode.VERIFY_FAILED)
    assertThat(kit.catalog.isSafe(start.mediaId)).isFalse()
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
    assertThat(kit.snapshot().files.keys).isEqualTo(before.files.keys)
  }

  /**
   * Legge solo la sorgente e scrive solo nell'area privata: l'altra area privata non si apre mai, l'area comune solo per la sorgente
   * (aperture e copie), e nessuna scrittura fuori dall'area privata propria.
   */
  @Test
  fun leggeSoloLaSorgenteEScriveSoloNellAreaPrivata() = runTest {
    kit.putNasFile(SafetyBench.OTHER_PRIVATE, "Backup/Tablet/IMG_14.jpg", SafetyBench.bytes(20_000, 16).content(), knownToCatalog = false)
    bench.commonOnly("IMG_14.jpg", SafetyBench.bytes(20_000, 16))
    bench.commonOnly("IMG_15.jpg", SafetyBench.bytes(20_000, 17), hashKnown = false)
    kit.nas.serverCopyMode = ServerCopyMode.NOT_SUPPORTED
    bench.recordShares()

    bench.guardian().ensure(GuardScope.All, 1.hours)

    val writes = setOf(FakeNasOp.OPEN_TEMP, FakeNasOp.WRITE_TEMP, FakeNasOp.PUBLISH_TEMP, FakeNasOp.DELETE, FakeNasOp.MKDIRS, FakeNasOp.REOPEN_TEMP, FakeNasOp.RENAME)
    val touched = synchronized(bench.touched) { bench.touched.toList() }
    assertThat(touched.map { it.second }.toSet()).doesNotContain(SafetyBench.OTHER_PRIVATE)
    assertThat(touched.filter { it.first in writes }.map { it.second }.toSet()).containsExactly(kit.share.lowercase())
  }

  /** Tetto per giro (D9), in piccolo: un elemento si comincia solo se ci sta; i giri successivi continuano. */
  @Test
  fun ilTettoPerGiroSiRispetta() = runTest {
    val starts = (1..7).map { bench.commonOnly("IMG_C$it.jpg", SafetyBench.bytes(20_000, 40 + it)) }
    val before = kit.snapshot()
    val cap = 50_000L
    val rounds = ArrayList<Long>()
    for (round in 1..10) {
      val report = bench.guardian(capBytes = cap).ensure(GuardScope.All, 1.hours)
      rounds += report.bytesCopied
      if (report.stoppedBy == RunStop.COMPLETE) break
    }
    assertThat(rounds).containsExactly(40_000L, 40_000L, 40_000L, 20_000L).inOrder()
    assertThat(rounds.all { it <= cap }).isTrue()
    assertThat(starts.all { kit.catalog.isSafe(it.mediaId) }).isTrue()
    assertClean(before)
  }

  /** Un solo media piu' grande del tetto parte da solo, come primo del giro (altrimenti non partirebbe mai); il resto aspetta. */
  @Test
  fun unMediaPiuGrandeDelTettoParteDaSolo() = runTest {
    val big = bench.commonOnly("VID_BIG.mp4", SafetyBench.bytes(60_000, 50))
    val small = bench.commonOnly("IMG_S.jpg", SafetyBench.bytes(10_000, 51))
    val first = bench.guardian(capBytes = 50_000).ensure(GuardScope.All, 1.hours)
    assertThat(first.created).isEqualTo(1)
    assertThat(first.stoppedBy).isEqualTo(RunStop.BUDGET)
    assertThat(kit.catalog.isSafe(big.mediaId)).isTrue()
    assertThat(kit.catalog.isSafe(small.mediaId)).isFalse()
    bench.guardian(capBytes = 50_000).ensure(GuardScope.All, 1.hours)
    assertThat(kit.catalog.isSafe(small.mediaId)).isTrue()
  }

  /**
   * Il tetto vero, 20 GB per giro, con 25 video "da 1 GB" virtuali nell'area comune (la copia lato server del NAS finto condivide il
   * contenuto, e la verifica qui risponde l'hash atteso per dimensione: si prova il tetto, non SHA-256 su 25 GB).
   */
  @Test
  fun ilTettoDi20GbPerGiro() = runTest {
    assertThat(BackupPrivateCopyGuardian.DEFAULT_CAP_BYTES).isEqualTo(20_000_000_000L)
    val shas = HashMap<Long, Sha256>()
    val ids = (0 until 25).map { i ->
      val size = 1_000_000_000L + i
      val sha = Sha256.of(byteArrayOf(i.toByte()))
      shas[size] = sha
      val m = InMemoryBackupCatalog.Media(
        mediaId = MediaId.random(), versionId = VersionId.random(), kind = MediaKind.VIDEO, size = size,
        quickHash = QuickHashAlgorithm.ofContent(byteArrayOf(i.toByte())), phone = null, phoneStamp = null, source = BackupSource.Camera,
        localDay = 20261012, fileName = "VID_$i.mp4", sortTime = i.toLong(), fullHash = sha,
      )
      kit.catalog.put(m)
      val path = SharePath.parse("Video/VID_$i.mp4")
      val fileId = kit.nas.putFile(kit.commonShare, path.value, NasContent.virtual(size, seed = i.toLong()), modifiedAt = 1_000)
      kit.catalog.putCopy(m.mediaId, m.versionId, kit.commonShare, path, NasStamp(size, 1_000, fileId))
      m.mediaId
    }
    val reader = HashBySizeReader(kit.nas.reader, shas)

    val first = bench.guardian(reader = reader).ensure(GuardScope.All, 1.hours)
    assertThat(first.stoppedBy).isEqualTo(RunStop.BUDGET)
    assertThat(first.created).isEqualTo(19)
    assertThat(first.bytesCopied).isAtMost(20_000_000_000L)
    val second = bench.guardian(reader = reader).ensure(GuardScope.All, 1.hours)
    assertThat(second.created).isEqualTo(6)
    assertThat(second.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(ids.all { kit.catalog.isSafe(it) }).isTrue()
  }

  /** D32, D12: congelamento, scritture ferme, "Backup in prova", area in sola lettura: il guardiano non scrive niente. */
  @Test
  fun ilCancelloChiusoFermaIlGuardiano() = runTest {
    bench.commonOnly("IMG_16.jpg", SafetyBench.bytes(20_000, 18))
    val before = kit.snapshot()
    for (state in BackupGateState.entries.filter { !it.open }) {
      kit.gate.default = state
      val report = bench.guardian().ensure(GuardScope.All, 1.hours)
      assertWithMessage(state.code).that(report.stoppedBy).isEqualTo(RunStop.GATE)
    }
    assertThat(kit.snapshot().files.keys).isEqualTo(before.files.keys)
    assertThat(kit.store.all()).isEmpty()
  }

  /** D13: nella build debug con gli account di prova le destinazioni stanno sotto la radice recintata (`mcg-campo/`). */
  @Test
  fun conLaRecinzioneDiProvaSiScriveSottoLaRadice() = runTest {
    val start = bench.commonOnly("IMG_17.jpg", SafetyBench.bytes(20_000, 19))
    bench.guardian(root = SharePath.of("mcg-campo")).ensure(GuardScope.All, 1.hours)
    assertThat(kit.nas.bytes(kit.share, "mcg-campo/Backup/Area comune/2026/10/IMG_17.jpg")!!.toList()).isEqualTo(start.data.toList())
  }

  /** E-B17: data assente, prima del 1990 o oltre domani: `Senza data/`. */
  @Test
  fun dateAssenteOAssurdaVaInSenzaData() = runTest {
    bench.commonOnly("IMG_A.jpg", SafetyBench.bytes(10_000, 20), localDay = null)
    bench.commonOnly("IMG_B.jpg", SafetyBench.bytes(10_001, 21), localDay = 19790101)
    bench.commonOnly("IMG_C.jpg", SafetyBench.bytes(10_002, 22), localDay = 20991231)
    bench.commonOnly("IMG_D.jpg", SafetyBench.bytes(10_003, 23), localDay = 20240229)
    bench.guardian().ensure(GuardScope.All, 1.hours)
    assertThat(bench.visible(kit.share)).containsExactly(
      "Backup/Area comune/Senza data/IMG_A.jpg",
      "Backup/Area comune/Senza data/IMG_B.jpg",
      "Backup/Area comune/Senza data/IMG_C.jpg",
      "Backup/Area comune/2024/02/IMG_D.jpg",
    )
  }

  /** Un elemento del caricamento gia' aperto per la stessa versione la protegge gia': il guardiano non ne crea un secondo. */
  @Test
  fun unaltroElementoApertoPerLaVersioneBasta() = runTest {
    val start = bench.commonOnly("IMG_18.jpg", SafetyBench.bytes(10_000, 24))
    kit.store.create(
      listOf(
        NewBackupItem(
          kind = UploadKind.NEW_COPY, mediaId = start.mediaId, versionId = start.versionId, size = 10_000, phone = null, expected = null,
          share = kit.share, planned = SharePath.parse("Backup/x/IMG_18.jpg"),
        ),
      ),
    )
    val report = bench.guardian().ensure(GuardScope.Media(setOf(start.mediaId)), 1.hours)
    assertThat(report.skipped).isEqualTo(1)
    assertThat(bench.guardItems()).isEmpty()
  }

  /**
   * La sorgente sparita (un familiare l'ha cancellata) prima della copia, senza la copia del telefono ne' una privata (L44): `GONE`,
   * niente copiato, e il media porta `COMMON_COPY_LOST` ("Recuperalo dal cestino del NAS").
   */
  @Test
  fun sorgenteSparitaPrimaDellaCopia() = runTest {
    val start = bench.commonOnly("IMG_19.jpg", SafetyBench.bytes(10_000, 25))
    kit.nas.delete(kit.commonShare, start.commonPath!!.value)
    bench.guardian().ensure(GuardScope.All, 1.hours)
    val item = bench.guardItems().single()
    assertThat(item.state).isEqualTo(UploadState.GONE)
    assertThat(item.problem).isEqualTo(BackupProblemCode.COMMON_COPY_LOST)
    assertThat(kit.catalog.problemOf(start.mediaId)).isEqualTo(BackupProblemCode.COMMON_COPY_LOST)
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
  }

  /** L44 anche quando l'hash della versione non e' noto e la sorgente sparisce prima della lettura di riferimento. */
  @Test
  fun sorgenteSparitaPrimaDellaLetturaDiRiferimento() = runTest {
    val start = bench.commonOnly("IMG_20.jpg", SafetyBench.bytes(10_000, 26), hashKnown = false)
    kit.nas.delete(kit.commonShare, start.commonPath!!.value)
    bench.guardian().ensure(GuardScope.All, 1.hours)
    assertThat(bench.guardItems().single().problem).isEqualTo(BackupProblemCode.COMMON_COPY_LOST)
    assertThat(kit.catalog.problemOf(start.mediaId)).isEqualTo(BackupProblemCode.COMMON_COPY_LOST)
  }

  /** La sorgente sparita ma la versione e' ancora sul telefono (ambito dei media chiesti): non e' persa, niente si segna. */
  @Test
  fun sorgenteSparitaMaIlTelefonoCeLHa() = runTest {
    val start = bench.commonOnly("IMG_21.jpg", SafetyBench.bytes(10_000, 27), onPhone = true)
    kit.nas.delete(kit.commonShare, start.commonPath!!.value)
    bench.guardian().ensure(GuardScope.Media(setOf(start.mediaId)), 1.hours)
    val item = bench.guardItems().single()
    assertThat(item.state).isEqualTo(UploadState.GONE)
    assertThat(item.problem).isNull()
    assertThat(kit.catalog.problemOf(start.mediaId)).isNull()
  }

  /** La sorgente sparita con i permessi del telefono ritirati: non si sa se il telefono ha ancora la versione, niente si segna. */
  @Test
  fun sorgenteSparitaConIPermessiRitiratiNonSegnaNiente() = runTest {
    val start = bench.commonOnly("IMG_22.jpg", SafetyBench.bytes(10_000, 28), onPhone = true)
    kit.nas.delete(kit.commonShare, start.commonPath!!.value)
    kit.phone.denied = dev.pampa.mycloudgallery.core.backup.contract.PhoneDenied.MEDIA_PERMISSION
    bench.guardian().ensure(GuardScope.Media(setOf(start.mediaId)), 1.hours)
    assertThat(bench.guardItems().single().problem).isNull()
    assertThat(kit.catalog.problemOf(start.mediaId)).isNull()
  }

  // --- Doppi ---------------------------------------------------------------------------------------------------------------------

  /** La copia lato server che "inizia" prima che il PC cambi la sorgente e finisce dopo: il temporaneo ha i byte nuovi. */
  private class SourceChangingOps(private val inner: NasBackupOps, private val change: () -> Unit) : NasBackupOps by inner {
    var changed = false

    override suspend fun copyToTemp(
      fromShare: String,
      from: SharePath,
      toShare: String,
      temp: SharePath,
      expect: NasStamp?,
      purpose: Purpose,
    ): NasResult<CopyOutcome> {
      if (!changed) {
        changed = true
        change()
        return inner.copyToTemp(fromShare, from, toShare, temp, null, purpose)
      }
      return inner.copyToTemp(fromShare, from, toShare, temp, expect, purpose)
    }
  }

  /** Il lettore che, per i file "da GB" della prova del tetto, risponde l'hash atteso per dimensione senza leggere. */
  private class HashBySizeReader(private val inner: NasMediaReader, private val shas: Map<Long, Sha256>) : NasMediaReader by inner {
    override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> =
      shas[file.size]?.let { NasResult.Ok(it) } ?: inner.readAll(file, sink, progress)
  }
}
