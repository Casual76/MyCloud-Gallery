package dev.pampa.mycloudgallery.core.backup.safety

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyResult
import dev.pampa.mycloudgallery.core.backup.contract.NasCopyRef
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * D8 (deduplica byte per byte), §2.11 punti 1-3, E-B5, E-B16, E-B23, E-L3, F4-D8: la porta [dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyFinder]
 * che il motore del caricamento (P2) chiama dopo aver letto i byte del telefono e prima di aprire il temporaneo.
 */
class PrivateCopyFinderTest {

  private val bench = SafetyBench()
  private val kit = bench.kit
  private val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"

  /** Un media del telefono "appena letto": il candidato come lo vede il motore, con lo SHA-256 dei byte del telefono. */
  private fun phoneCandidate(name: String, data: ByteArray, quick: Boolean = true): Pair<BackupCandidate, Sha256> {
    val id = 9_000L + data.size
    val ref = kit.phone.add(id, data.content())
    val m = InMemoryBackupCatalog.Media(
      mediaId = MediaId.random(), versionId = VersionId.random(), kind = MediaKind.PHOTO, size = data.size.toLong(),
      quickHash = if (quick) QuickHashAlgorithm.ofContent(data) else null, phone = ref, phoneStamp = kit.phone.stamp(id),
      source = BackupSource.Camera, localDay = 20261012, fileName = name, sortTime = 1, fullHash = Sha256.of(data),
    )
    kit.catalog.put(m)
    val candidate = BackupCandidate(
      mediaId = m.mediaId, versionId = m.versionId, phone = ref, stamp = kit.phone.stamp(id)!!, size = m.size, kind = m.kind,
      source = m.source, localDay = m.localDay, dateEstimated = false, fileName = name, sortTime = m.sortTime, quickHash = m.quickHash,
    )
    return candidate to Sha256.of(data)
  }

  /** Il motore collega la copia trovata (cio' che fara' P2 con `LINKED_EXISTING`). */
  private suspend fun link(result: ExistingCopyResult): Boolean {
    if (result !is ExistingCopyResult.Found) return false
    kit.catalog.attachVerifiedCopies(listOf(result.copy))
    return true
  }

  /** E-B5, §2.11 punto 2: una copia identica in un vecchio backup (qualunque cartella dell'area privata) si trova dopo la rilettura. */
  @Test
  fun unaCopiaIdenticaInUnVecchioBackupSiTrovaDopoLaRilettura() = runTest {
    val data = SafetyBench.bytes(200_000, 1)
    kit.putNasFile(kit.share, "Backup telefono 2023/IMG_1.jpg", data.content())
    val (candidate, sha) = phoneCandidate("IMG_1.jpg", data)
    val before = kit.nas.bytesRead.get()
    val result = bench.finder().find(candidate, sha, kit.share)
    assertThat(result).isInstanceOf(ExistingCopyResult.Found::class.java)
    val found = (result as ExistingCopyResult.Found).copy
    assertThat(found.path.value).isEqualTo("Backup telefono 2023/IMG_1.jpg")
    assertThat(found.verifiedAtMillis).isEqualTo(kit.clock.nasNow)
    // Riletta per intero: testa e coda per `qh1`, poi tutto il file.
    assertThat(kit.nas.bytesRead.get() - before).isAtLeast(data.size.toLong())
    assertThat(link(result)).isTrue()
    assertThat(kit.catalog.isSafe(candidate.mediaId)).isTrue()
    assertThat(kit.nas.backupBytesWritten.get()).isEqualTo(0L)
  }

  /** "Nel dubbio si carica": stessa dimensione, byte diversi nel mezzo (stesso `qh1`) = nessuna copia. */
  @Test
  fun stessaDimensioneEStessoQh1MaByteDiversiNelMezzoSiCarica() = runTest {
    val data = SafetyBench.bytes(300_000, 2)
    val other = SafetyBench.oneByteChanged(data)
    assertThat(QuickHashAlgorithm.ofContent(other)).isEqualTo(QuickHashAlgorithm.ofContent(data))
    kit.putNasFile(kit.share, "$dir/IMG_2.jpg", other.content())
    val (candidate, sha) = phoneCandidate("IMG_2.jpg", data)
    val result = bench.finder().find(candidate, sha, kit.share)
    assertThat(result).isEqualTo(ExistingCopyResult.None)
    assertThat(kit.catalog.isSafe(candidate.mediaId)).isFalse()
  }

  /** `qh1` letto qui diverso: il candidato si scarta leggendo solo testa e coda (un video da GB non si rilegge per niente). */
  @Test
  fun unQh1DiversoScartaIlCandidatoSenzaLeggereTutto() = runTest {
    val data = SafetyBench.bytes(2_000_000, 3)
    val other = SafetyBench.oneByteChanged(data, at = 10)
    kit.putNasFile(kit.share, "$dir/VID_3.mp4", other.content())
    val (candidate, sha) = phoneCandidate("VID_3.mp4", data)
    val before = kit.nas.bytesRead.get()
    assertThat(bench.finder().find(candidate, sha, kit.share)).isEqualTo(ExistingCopyResult.None)
    assertThat(kit.nas.bytesRead.get() - before).isAtMost(2L * QuickHashAlgorithm.CHUNK_BYTES)
  }

  /** E-L3: piu' copie candidate; la prima diversa, la seconda identica: vince quella riletta con i byte giusti; nessuna si tocca. */
  @Test
  fun fraPiuCandidateVinceQuellaConIByteGiusti() = runTest {
    val data = SafetyBench.bytes(50_000, 4)
    kit.putNasFile(kit.share, "Vecchio/IMG_4.jpg", SafetyBench.oneByteChanged(data).content())
    kit.putNasFile(kit.share, "Ancora piu vecchio/IMG_4.jpg", data.content())
    val (candidate, sha) = phoneCandidate("IMG_4.jpg", data, quick = false)
    val before = kit.snapshot()
    val result = bench.finder().find(candidate, sha, kit.share) as ExistingCopyResult.Found
    assertThat(result.copy.path.value).isEqualTo("Ancora piu vecchio/IMG_4.jpg")
    assertThat(kit.snapshot().files.keys).isEqualTo(before.files.keys)
  }

  /** Una copia sparita, aperta dal PC o cambiata durante la lettura non vale: si passa oltre, e senza altre si carica. */
  @Test
  fun copiaSparitaOccupataOCambiataNonVale() = runTest {
    val data = SafetyBench.bytes(40_000, 5)
    kit.putNasFile(kit.share, "A/IMG_5.jpg", data.content())
    kit.putNasFile(kit.share, "B/IMG_5.jpg", data.content())
    kit.putNasFile(kit.share, "C/IMG_5.jpg", data.content())
    val (candidate, sha) = phoneCandidate("IMG_5.jpg", data)
    kit.nas.delete(kit.share, "A/IMG_5.jpg")
    kit.nas.lock(kit.share, "B/IMG_5.jpg")
    // C cambia durante la lettura: la scansione l'aveva vista, il PC la riscrive con la stessa dimensione e un'altra data.
    var touched = false
    kit.nas.fault = dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault { op, _, path ->
      if (op == FakeNasOp.READ_ALL && path?.value == "C/IMG_5.jpg" && !touched) {
        touched = true
        kit.nas.modify(kit.share, "C/IMG_5.jpg", data.content(), modifiedAt = 42)
      }
      null
    }
    assertThat(bench.finder().find(candidate, sha, kit.share)).isEqualTo(ExistingCopyResult.None)
    assertThat(touched).isTrue()
  }

  /** Un errore del NAS durante la rilettura non e' "nessuna copia": il motore ferma il giro (§2.2 "Rete"). */
  @Test
  fun unErroreDelNasInterrompe() = runTest {
    val data = SafetyBench.bytes(40_000, 6)
    kit.putNasFile(kit.share, "$dir/IMG_6.jpg", data.content())
    val (candidate, sha) = phoneCandidate("IMG_6.jpg", data)
    kit.nas.failWith = NasError.Timeout
    val result = bench.finder().find(candidate, sha, kit.share)
    assertThat(result).isInstanceOf(ExistingCopyResult.Interrupted::class.java)
    assertThat((result as ExistingCopyResult.Interrupted).problem).isEqualTo(BackupProblemCode.NAS_UNREACHABLE)
  }

  /** Senza l'ora del NAS una copia non si segna "verificata adesso": il giro si ferma, niente doppione caricato. */
  @Test
  fun senzaOraDelNasNonSiCollegaEIlGiroSiFerma() = runTest {
    val data = SafetyBench.bytes(40_000, 7)
    kit.putNasFile(kit.share, "$dir/IMG_7.jpg", data.content())
    val (candidate, sha) = phoneCandidate("IMG_7.jpg", data)
    kit.clock.nasNow = null
    val result = bench.finder().find(candidate, sha, kit.share)
    assertThat(result).isInstanceOf(ExistingCopyResult.Interrupted::class.java)
  }

  /**
   * "Mai leggere un'altra area privata o l'area comune per deduplicare": un'altra area privata e l'area comune piene di file identici,
   * e un catalogo che (sbagliando) li propone come candidati. Il cercatore non li apre: le sole share toccate sono quella privata
   * propria; il media si carica.
   */
  @Test
  fun unAltraAreaPrivataELAreaComunePieneDiIdenticiNonSiLeggono() = runTest {
    val data = SafetyBench.bytes(60_000, 8)
    for (i in 1..5) {
      kit.putNasFile(SafetyBench.OTHER_PRIVATE, "Backup/Tablet/2026/10/IMG_8 ($i).jpg", data.content(), knownToCatalog = false)
      kit.putNasFile(kit.commonShare, "Foto/IMG_8 ($i).jpg", data.content())
    }
    val (candidate, sha) = phoneCandidate("IMG_8.jpg", data)
    val leaky = LeakyCatalog(kit.catalog, extra = (1..5).flatMap { i ->
      listOf(
        NasCopyRef(LocationId(900L + i), MediaId.random(), VersionId.random(), SafetyBench.OTHER_PRIVATE, SharePath.parse("Backup/Tablet/2026/10/IMG_8 ($i).jpg"), null),
        NasCopyRef(LocationId(950L + i), MediaId.random(), VersionId.random(), kit.commonShare, SharePath.parse("Foto/IMG_8 ($i).jpg"), null),
      )
    })
    bench.recordShares()
    val result = bench.finder(catalog = leaky).find(candidate, sha, kit.share)
    assertThat(result).isEqualTo(ExistingCopyResult.None)
    assertThat(bench.touchedShares()).doesNotContain(SafetyBench.OTHER_PRIVATE)
    assertThat(bench.touchedShares()).doesNotContain(kit.commonShare.lowercase())
    assertThat(kit.nas.opens.get()).isEqualTo(0)
  }

  /**
   * F4-D8: un hash dell'indice non fa mai un collegamento. Il catalogo dice che la copia privata ha i byte del telefono (impronte
   * "dall'indice" uguali, dimensione uguale), ma i byte veri sono altri: la rilettura lo scopre, nessun collegamento.
   */
  @Test
  fun unHashDellIndiceNonFaUnCollegamento() = runTest {
    val data = SafetyBench.bytes(70_000, 9)
    val forged = SafetyBench.oneByteChanged(data)
    val (candidate, sha) = phoneCandidate("IMG_9.jpg", data)
    // La "copia" e' di un media che il catalogo crede identico: stesso hash completo (dall'indice) e stessa impronta rapida.
    val ghost = InMemoryBackupCatalog.Media(
      mediaId = MediaId.random(), versionId = VersionId.random(), kind = MediaKind.PHOTO, size = data.size.toLong(),
      quickHash = QuickHashAlgorithm.ofContent(data), phone = null, phoneStamp = null, source = BackupSource.Camera, localDay = null,
      fileName = "IMG_9.jpg", sortTime = 0, fullHash = sha,
    )
    kit.catalog.put(ghost)
    val stamp = bench.putFile(kit.share, "$dir/IMG_9.jpg", forged)
    kit.catalog.putCopy(ghost.mediaId, ghost.versionId, kit.share, SharePath.parse("$dir/IMG_9.jpg"), stamp, verifiedAtMillis = 1)
    assertThat(bench.finder().find(candidate, sha, kit.share)).isEqualTo(ExistingCopyResult.None)
    assertThat(kit.catalog.isSafe(candidate.mediaId)).isFalse()
  }

  /** E-B16 (reinstallazione, telefono nuovo): le copie nella cartella del dispositivo si ritrovano e si collegano senza caricare. */
  @Test
  fun dopoUnaReinstallazioneLeCopieSiRitrovanoSenzaCaricare() = runTest {
    val photos = (1..6).map { SafetyBench.bytes(30_000 + it, 20 + it) }
    photos.forEachIndexed { i, data -> kit.putNasFile(kit.share, "$dir/IMG_${i + 1}.jpg", data.content()) }
    for ((i, data) in photos.withIndex()) {
      val (candidate, sha) = phoneCandidate("IMG_${i + 1}.jpg", data)
      assertThat(link(bench.finder().find(candidate, sha, kit.share))).isTrue()
      assertThat(kit.catalog.isSafe(candidate.mediaId)).isTrue()
    }
    assertThat(kit.nas.backupBytesWritten.get()).isEqualTo(0L)
    assertThat(bench.visible(kit.share)).hasSize(photos.size)
  }

  /** Un catalogo che sbaglia: aggiunge ai candidati della deduplica copie fuori dall'area privata del proprietario. */
  private class LeakyCatalog(private val inner: BackupCatalog, private val extra: List<NasCopyRef>) : BackupCatalog by inner {
    override suspend fun privateCopies(share: String, size: Long, quickHash: QuickHash?, limit: Int): List<NasCopyRef> =
      extra + inner.privateCopies(share, size, quickHash, limit)
  }
}
