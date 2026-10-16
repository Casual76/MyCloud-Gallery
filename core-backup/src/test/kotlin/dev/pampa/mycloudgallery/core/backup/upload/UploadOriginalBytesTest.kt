package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneFile
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.backup.testing.BackupInvariants
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.backup.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.index.testing.FakeIndexPublisher
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.prepare.testing.FakePreparer
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E-B15 e L38 (decisione dell'orchestratore del 16/10): un temporaneo **completo** non si scarta quando il file del telefono cambia dopo
 * la lettura. Se la rilettura dal NAS da' lo SHA-256 dei byte letti **prima** del cambiamento, sono i byte originali (che sul telefono non
 * ci sono piu': Samsung "Salva" sovrascrive) e si pubblicano come copia di **quella** versione; la versione nuova arriva dopo, come
 * elemento a se', con "nome (2)". Byte misti (il file cambia a meta' caricamento) = la verifica non torna = si scarta come prima. Una foto
 * tenuta in memoria per la preparazione (D10) si carica dalla memoria, anche se il telefono l'ha riscritta o cancellata. Tutto con guasti
 * a ogni chiamata e la morte del processo a ogni scrittura del diario.
 */
class UploadOriginalBytesTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  private val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"

  /** La lettura del telefono fa nascere una versione nuova (ContentChanged): nel catalogo finto, il media passa alla versione nuova. */
  private fun BackupTestKit.newVersionFromPhone(mediaId: MediaId, phoneId: Long) {
    val old = catalog.media(mediaId)!!
    catalog.put(
      InMemoryBackupCatalog.Media(
        mediaId = old.mediaId, versionId = VersionId.random(), kind = old.kind, size = phone.stamp(phoneId)!!.size, quickHash = null,
        phone = phone.ref(phoneId), phoneStamp = phone.stamp(phoneId), source = old.source, localDay = old.localDay, fileName = old.fileName,
        sortTime = old.sortTime, fullHash = null,
      ),
    )
  }

  /** Il record di base dal catalogo finto (per la preparazione dai byte in memoria). */
  private class KitRecords(private val catalog: InMemoryBackupCatalog) : UploadRecords {
    override suspend fun record(mediaId: MediaId, versionId: VersionId, stamp: NasStamp, quickHash: QuickHash, fullHash: Sha256): MetaFile? {
      val m = catalog.media(mediaId)?.takeIf { it.versionId == versionId } ?: return null
      return MetaFile(stamp = stamp, quickHash = quickHash, mediaId = mediaId, versionId = versionId, kind = m.kind, source = MediaSource.OTHER)
    }
  }

  private fun RealEngineKit.withMemory(): RealEngineKit = apply {
    indexing = PreparingUploadIndexing(FakePreparer(), FakeIndexPublisher(), KitRecords(kit.catalog))
    restart()
  }

  /** Il file cambia dopo il caricamento e la verifica, prima della pubblicazione: si pubblica l'originale, poi la versione nuova a parte. */
  @Test
  fun ilFileCambiaDopoIlCaricamentoPrimaDellaPubblicazione() = runTest {
    val h = RealEngineKit()
    val kit = h.kit
    val original = bytes(40_000, 1)
    val edited = bytes(41_000, 2)
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(original))
    val phoneId = kit.phoneIdOf(media)
    val oldVersion = kit.catalog.media(media)!!.versionId
    val before = kit.snapshot()
    // La pubblicazione non arriva (guasto prima): il temporaneo e' completo e verificato.
    kit.nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection, op = SimOp.RENAME)
    h.runAll()
    kit.nas.opsFaults = null
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.PUBLISHING)
    // Samsung "Salva": il file del telefono e' riscritto. L'originale esiste ormai solo nel temporaneo.
    kit.phone.rewrite(phoneId, NasContent.of(edited), modifiedMs = 9_000)
    h.restart()
    h.resumeUntilQuiet()
    val first = kit.store.all().single()
    assertThat(first.state).isEqualTo(UploadState.DONE)
    assertThat(first.versionId).isEqualTo(oldVersion)
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_1.jpg")!!.toList()).isEqualTo(original.toList())
    assertThat(kit.catalog.copiesOf(media).single { it.versionId == oldVersion }.verifiedAtMillis).isNotNull()
    // La lettura del telefono vede la versione nuova: e' un elemento a se', con "nome (2)" perche' il nome e' gia' dell'originale.
    kit.newVersionFromPhone(media, phoneId)
    h.resumeUntilQuiet()
    val second = kit.store.all().single { it.versionId != oldVersion }
    assertThat(second.state).isEqualTo(UploadState.DONE)
    assertThat(second.finalPath!!.value).isEqualTo("$dir/IMG_1 (2).jpg")
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_1 (2).jpg")!!.toList()).isEqualTo(edited.toList())
    assertThat(kit.catalog.isSafe(media)).isTrue()
    assertWithMessage("invarianti").that(kit.violations(BackupInvariants.Snapshot(before.files, before.phoneShas + kit.phone.allSha256()), final = true))
      .isEmpty()
  }

  /** Il file cambia dopo la scrittura completa del temporaneo, prima della verifica: la rilettura torna con i byte di prima, si salva. */
  @Test
  fun ilFileCambiaDopoLaScritturaPrimaDellaVerifica() = runTest {
    val h = RealEngineKit()
    val kit = h.kit
    val original = bytes(50_000, 3)
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(original))
    val before = kit.snapshot()
    kit.nas.readFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection, op = SimOp.OPEN_READ)
    h.runAll()
    kit.nas.readFaults = null
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.UPLOADED)
    kit.phone.rewrite(kit.phoneIdOf(media), NasContent.of(bytes(50_000, 4)), modifiedMs = 9_000)
    h.restart()
    h.resumeUntilQuiet()
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_1.jpg")!!.toList()).isEqualTo(original.toList())
    assertThat(kit.violations(BackupInvariants.Snapshot(before.files, before.phoneShas + kit.phone.allSha256()), final = true)).isEmpty()
  }

  /** Il file cambia a meta' caricamento: byte misti, la verifica non torna, il temporaneo si toglie e l'elemento chiude con `GONE`. */
  @Test
  fun ilFileCambiaAMetaCaricamentoEIByteMistiSiScartano() = runTest {
    val h = RealEngineKit()
    val kit = h.kit
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(60_000, 5)))
    val before = kit.snapshot()
    // Il primo giro si ferma prima del temporaneo; poi la riga si riscrive dopo 20.000 byte della lettura del caricamento.
    kit.nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection, op = SimOp.CREATE)
    h.runAll()
    kit.nas.opsFaults = null
    kit.phone.duringRead(kit.phoneIdOf(media), afterBytes = 20_000, event = FakePhoneBytes.MidRead.Rewritten(NasContent.of(bytes(60_000, 6)), 9_000))
    h.restart()
    h.resumeUntilQuiet()
    val item = kit.store.all().single()
    assertThat(kit.phone.firedEvents()).isEqualTo(1)
    assertThat(item.state).isEqualTo(UploadState.GONE)
    assertThat(item.problem).isNull()
    assertThat(item.verifyAttempts).isEqualTo(1)
    assertThat(kit.catalog.isSafe(media)).isFalse()
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
    assertThat(kit.violations(BackupInvariants.Snapshot(before.files, before.phoneShas + kit.phone.allSha256()), final = true)).isEmpty()
  }

  /** D10, E-B15, E-B4: una foto tenuta in memoria si carica dalla memoria anche se il telefono l'ha riscritta o cancellata dopo l'hash. */
  @Test
  fun unaFotoInMemoriaSiCaricaDallaMemoria() = runTest {
    val h = RealEngineKit().withMemory()
    val kit = h.kit
    val rewritten = bytes(30_000, 7)
    val deleted = bytes(25_000, 8)
    val a = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(rewritten))
    val b = kit.addPhoneMedia("IMG_2.jpg", NasContent.of(deleted))
    val before = kit.snapshot()
    // Entrambe lette (hash e preparazione), poi il giro si ferma prima dei temporanei.
    kit.nas.fault = dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault { op, _, _ ->
      if (op == FakeNasOp.OPEN_TEMP) dev.pampa.mycloudgallery.core.nas.contract.NasError.Timeout else null
    }
    var rounds = 0
    while (kit.store.all().size < 2 || kit.store.all().any { it.state != UploadState.UPLOADING }) {
      check(++rounds <= 10) { "le foto non arrivano al caricamento" }
      h.runAll()
    }
    kit.nas.fault = null
    assertThat(kit.store.all().map { it.state }.toSet()).containsExactly(UploadState.UPLOADING)
    kit.phone.rewrite(kit.phoneIdOf(a), NasContent.of(bytes(30_000, 9)), modifiedMs = 9_000)
    kit.phone.delete(kit.phoneIdOf(b))
    val phoneBytes = kit.phone.bytesRead
    // Lo stesso processo (la memoria c'e' ancora): si caricano i byte letti per l'hash, il telefono non si legge piu'.
    h.runAll()
    assertThat(kit.phone.bytesRead).isEqualTo(phoneBytes)
    assertThat(kit.store.all().map { it.state }.toSet()).containsExactly(UploadState.DONE)
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_1.jpg")!!.toList()).isEqualTo(rewritten.toList())
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_2.jpg")!!.toList()).isEqualTo(deleted.toList())
    assertThat(kit.catalog.problemOf(b)).isNull()
    assertThat(kit.violations(BackupInvariants.Snapshot(before.files, before.phoneShas + kit.phone.allSha256()), final = true)).isEmpty()
  }

  /** Dopo una morte del processo la memoria non c'e' piu': con il telefono riscritto e il temporaneo incompleto si chiude con `GONE`. */
  @Test
  fun dopoUnaMorteLaMemoriaNonCEPiu() = runTest {
    val h = RealEngineKit().withMemory()
    val kit = h.kit
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(30_000, 10)))
    val before = kit.snapshot()
    kit.nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection, op = SimOp.CREATE)
    h.runAll()
    kit.nas.opsFaults = null
    kit.phone.rewrite(kit.phoneIdOf(media), NasContent.of(bytes(30_000, 11)), modifiedMs = 9_000)
    h.restart()
    h.resumeUntilQuiet()
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.GONE)
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
    assertThat(kit.violations(BackupInvariants.Snapshot(before.files, before.phoneShas + kit.phone.allSha256()), final = true)).isEmpty()
  }

  // --- Guasti a ogni chiamata e morte a ogni scrittura sul ramo E-B15 ----------------------------------------------------------------

  /**
   * Il telefono che riscrive la riga [phoneId] alla chiusura della sua apertura numero [opening] (1 = la lettura dell'hash, dopo il
   * controllo del timbro; 2 = la lettura del caricamento, completa o interrotta). Il conteggio e' del "telefono": sopravvive ai riavvii.
   */
  private class RewriteOnClose(private val fake: FakePhoneBytes, private val phoneId: Long, private val opening: Int, private val content: NasContent) {
    var opens = 0
    var fired = false

    fun wrap(inner: PhoneBytes): PhoneBytes = object : PhoneBytes by inner {
      override suspend fun open(ref: PhoneRef): PhoneOpen {
        val opened = inner.open(ref)
        if (opened !is PhoneOpen.Opened || ref.mediaStoreId != phoneId) return opened
        val n = ++opens
        val file = opened.file
        return PhoneOpen.Opened(
          PhoneFile(file.bytes, file.stamp) {
            file.close()
            if (n == opening && !fired) {
              fired = true
              fake.rewrite(phoneId, content, modifiedMs = 9_999)
            }
          },
        )
      }
    }
  }

  private class Branch(val name: String, val memory: Boolean, val opening: Int)

  private suspend fun everyInterruption(branch: Branch) {
    val original = bytes(70_000, 12)
    fun setup(h: RealEngineKit): RewriteOnClose {
      if (branch.memory) h.withMemory()
      val media = h.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(original))
      val hook = RewriteOnClose(h.kit.phone, h.kit.phoneIdOf(media), branch.opening, NasContent.of(bytes(70_000, 13)))
      h.phone = { inner -> hook.wrap(inner) }
      h.restart()
      return hook
    }
    suspend fun finalCheck(h: RealEngineKit, before: BackupInvariants.Snapshot, label: String) {
      h.restart()
      h.resumeUntilQuiet()
      // Dopo un GONE il catalogo finto ripropone la versione (la lettura del telefono non e' passata): altri GONE, mai altro.
      val items = h.kit.store.all()
      for (item in items) assertWithMessage("$label: esito").that(item.state).isAnyOf(UploadState.DONE, UploadState.GONE)
      assertWithMessage("$label: al piu' un DONE").that(items.count { it.state == UploadState.DONE }).isAtMost(1)
      if (items.any { it.state == UploadState.DONE }) {
        assertWithMessage("$label: l'originale").that(h.kit.nas.bytes(h.kit.share, "$dir/IMG_1.jpg")?.toList()).isEqualTo(original.toList())
      } else {
        assertWithMessage("$label: niente di visibile").that(h.kit.nas.filePaths(h.kit.share)).isEmpty()
      }
      val known = BackupInvariants.Snapshot(before.files, before.phoneShas + h.kit.phone.allSha256())
      assertWithMessage("$label: invarianti alla fine").that(h.kit.violations(known, final = true)).isEmpty()
    }

    // Il giro pulito: l'originale si salva (il cambiamento arriva a temporaneo completo, o il caricamento usa la memoria).
    val clean = RealEngineKit()
    val cleanHook = setup(clean)
    val counter = FaultPlan()
    clean.kit.nas.opsFaults = counter
    clean.kit.nas.readFaults = counter
    val cleanBefore = clean.kit.snapshot()
    clean.runAll()
    clean.kit.nas.opsFaults = null
    clean.kit.nas.readFaults = null
    assertWithMessage("${branch.name}: il telefono e' cambiato").that(cleanHook.fired).isTrue()
    assertWithMessage("${branch.name}: pulito").that(clean.kit.store.all().single().state).isEqualTo(UploadState.DONE)
    finalCheck(clean, cleanBefore, "${branch.name}, pulito")
    val calls = counter.operations
    var done = 0
    for (n in 1..calls) {
      for (phase in FaultPhase.entries) {
        val h = RealEngineKit()
        setup(h)
        val before = h.kit.snapshot()
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        h.kit.nas.opsFaults = fault
        h.kit.nas.readFaults = fault
        h.runAll()
        h.kit.nas.opsFaults = null
        h.kit.nas.readFaults = null
        val label = "${branch.name}, guasto alla chiamata $n/$calls $phase"
        assertWithMessage("$label: scattato").that(fault.fired).hasSize(1)
        val mid = BackupInvariants.Snapshot(before.files, before.phoneShas + h.kit.phone.allSha256())
        assertWithMessage("$label: a meta'").that(h.kit.violations(mid, final = false)).isEmpty()
        finalCheck(h, before, label)
        if (h.kit.store.all().any { it.state == UploadState.DONE }) done++
      }
    }
    val countDeaths = RealEngineKit()
    setup(countDeaths)
    val counting = BackupDeath()
    countDeaths.runAll(countDeaths.dyingEngine(counting))
    val writes = counting.steps
    for (n in 1..writes) {
      for (after in listOf(false, true)) {
        val h = RealEngineKit()
        setup(h)
        val before = h.kit.snapshot()
        val death = BackupDeath(n, after)
        try {
          h.runAll(h.dyingEngine(death))
        } catch (_: ProcessDeath) {
        }
        val label = "${branch.name}, morte alla scrittura $n/$writes ${if (after) "dopo" else "prima"}"
        assertWithMessage("$label: morto").that(death.died).isTrue()
        val mid = BackupInvariants.Snapshot(before.files, before.phoneShas + h.kit.phone.allSha256())
        assertWithMessage("$label: a meta'").that(h.kit.violations(mid, final = false)).isEmpty()
        finalCheck(h, before, label)
        if (h.kit.store.all().any { it.state == UploadState.DONE }) done++
      }
    }
    println("P2 E-B15 ${branch.name}: $calls chiamate x 2 fasi, $writes scritture x 2; originale salvato in $done casi su ${2 * (calls + writes)}")
  }

  @Test
  fun ilTelefonoCambiaDopoLaLetturaDelCaricamentoConGuastiEMorti() = runTest {
    everyInterruption(Branch("cambio dopo il caricamento", memory = false, opening = 2))
  }

  @Test
  fun ilTelefonoCambiaDopoLHashConLaFotoInMemoriaConGuastiEMorti() = runTest {
    everyInterruption(Branch("cambio dopo l'hash, foto in memoria", memory = true, opening = 1))
  }
}
