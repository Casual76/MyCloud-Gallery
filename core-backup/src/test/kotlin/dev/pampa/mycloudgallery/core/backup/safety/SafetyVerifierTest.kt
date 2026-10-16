package dev.pampa.mycloudgallery.core.backup.safety

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.CopyToVerify
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.NasCopyRef
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * D14, §2.3, F4-D21, Q10, E-B8, E-L3, L28: il [BackupSafetyVerifier] con tutti gli ambiti. "Al sicuro" nasce solo da una rilettura
 * completa fatta qui; una copia che non torna lo perde.
 */
class SafetyVerifierTest {

  private val bench = SafetyBench()
  private val kit = bench.kit
  private val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"
  private val day = 24L * 60 * 60 * 1000

  /** Rovina in silenzio un byte di una copia (stesso timbro: come un disco che sbaglia, che il RAID 1 copia). */
  private suspend fun corruptSilently(share: String, path: SharePath, data: ByteArray) {
    val stamp = bench.stampOf(share, path.value)!!
    kit.nas.modify(share, path.value, SafetyBench.oneByteChanged(data).content(), modifiedAt = stamp.modifiedAtMillis)
    assertThat(bench.stampOf(share, path.value)).isEqualTo(stamp)
  }

  /**
   * `Unverified` (§2.11, vecchi backup trovati dalla scansione): la copia buona diventa "al sicuro"; quelle rovinate no. Con la versione
   * ancora sul telefono nessun segno (il backup la ricarica: provato col motore di riferimento, con un nome nuovo, mai una
   * sostituzione); senza, `VERIFY_FAILED` (E-B8).
   */
  @Test
  fun leCopieNonVerificateSiRileggonoEQuelleRovinateNonDiventanoAlSicuro() = runTest {
    val good = bench.withPrivateCopy("IMG_A.jpg", SafetyBench.bytes(30_000, 1), verifiedAt = null)
    val badOnPhone = bench.withPrivateCopy("IMG_B.jpg", SafetyBench.bytes(31_000, 2), verifiedAt = null)
    val badLost = bench.withPrivateCopy("IMG_C.jpg", SafetyBench.bytes(32_000, 3), verifiedAt = null, onPhone = false)
    corruptSilently(kit.share, badOnPhone.commonPath!!, badOnPhone.data)
    corruptSilently(kit.share, badLost.commonPath!!, badLost.data)
    val before = kit.snapshot()

    val report = bench.verifier().verify(VerifyScope.Unverified, 1.hours)

    assertThat(report.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(report.checked).isEqualTo(3)
    assertThat(report.verified).isEqualTo(1)
    assertThat(report.mismatched).isEqualTo(2)
    assertThat(report.bytesRead).isEqualTo(30_000L + 31_000 + 32_000)
    assertThat(kit.catalog.isSafe(good.mediaId)).isTrue()
    assertThat(kit.catalog.isSafe(badOnPhone.mediaId)).isFalse()
    assertThat(kit.catalog.problemOf(badOnPhone.mediaId)).isNull()
    assertThat(kit.catalog.isSafe(badLost.mediaId)).isFalse()
    assertThat(kit.catalog.problemOf(badLost.mediaId)).isEqualTo(BackupProblemCode.VERIFY_FAILED)
    // Solo letture: niente e' cambiato sul NAS.
    assertThat(kit.snapshot().files.keys).isEqualTo(before.files.keys)

    // E-B8: il telefono ce l'ha ancora: il backup (qui il motore di riferimento) la ricarica, accanto alla copia rovinata.
    kit.resumeUntilQuiet()
    assertThat(kit.catalog.isSafe(badOnPhone.mediaId)).isTrue()
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_B (2).jpg")!!.toList()).isEqualTo(badOnPhone.data.toList())
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = true)).isEmpty()
  }

  /** La rotazione (Q10) trova un byte cambiato e toglie "al sicuro"; senza la copia del telefono, il segno `PROBLEM` (E-B8). */
  @Test
  fun laRotazioneTrovaUnByteCambiatoEToglieAlSicuro() = runTest {
    val now = kit.clock.nasNow!!
    val kept = bench.withPrivateCopy("IMG_1.jpg", SafetyBench.bytes(40_000, 4), verifiedAt = now - 200 * day)
    val lost = bench.withPrivateCopy("IMG_2.jpg", SafetyBench.bytes(41_000, 5), verifiedAt = now - 150 * day, onPhone = false)
    assertThat(kit.catalog.isSafe(kept.mediaId)).isTrue()
    assertThat(kit.catalog.isSafe(lost.mediaId)).isTrue()
    corruptSilently(kit.share, kept.commonPath!!, kept.data)
    corruptSilently(kit.share, lost.commonPath!!, lost.data)

    val report = bench.verifier().verify(VerifyScope.Rotation, 1.hours)

    assertThat(report.mismatched).isEqualTo(2)
    assertThat(kit.catalog.isSafe(kept.mediaId)).isFalse()
    assertThat(kit.catalog.isSafe(lost.mediaId)).isFalse()
    assertThat(kit.catalog.problemOf(kept.mediaId)).isNull()
    assertThat(kit.catalog.problemOf(lost.mediaId)).isEqualTo(BackupProblemCode.VERIFY_FAILED)
    assertThat(kit.catalog.mediaSafety(lost.mediaId)!!.status).isEqualTo(SafetyStatus.PRIVATE_COPY_UNVERIFIED)
  }

  /**
   * La rotazione parte dalla verifica piu' vecchia, legge almeno il minimo per giro e oltre solo le copie scadute (verificate da piu' di
   * un anno); i giri successivi continuano dove ci si era fermati. In un anno di giri ogni copia e' riletta.
   */
  @Test
  fun laRotazioneParteDallaPiuVecchiaERispettaIlBudget() = runTest {
    val now = kit.clock.nasNow!!
    val ages = listOf(30, 400, 100, 380, 200) // giorni dall'ultima verifica
    val starts = ages.mapIndexed { i, age -> bench.withPrivateCopy("IMG_$i.jpg", SafetyBench.bytes(10_000, 30 + i), verifiedAt = now - age * day) }
    val policy = RotationPolicy(minBytesPerRun = 5_000)

    val first = bench.verifier(rotation = policy).verify(VerifyScope.Rotation, 1.hours)
    // 400 e 380 giorni: scadute, si leggono comunque; poi il minimo (5 KB) e' gia' superato e la prossima (200) non e' scaduta.
    assertThat(first.checked).isEqualTo(2)
    assertThat(first.verified).isEqualTo(2)
    val reverified = starts.filter { kit.catalog.mediaSafety(it.mediaId)!!.verifiedAtMillis == now }.map { ages[starts.indexOf(it)] }
    assertThat(reverified).containsExactly(400, 380)

    val second = bench.verifier(rotation = policy).verify(VerifyScope.Rotation, 1.hours)
    assertThat(second.checked).isEqualTo(1)
    val third = bench.verifier(rotation = policy).verify(VerifyScope.Rotation, 1.hours)
    assertThat(third.checked).isEqualTo(1)
    val againReverified = starts.filter { kit.catalog.mediaSafety(it.mediaId)!!.verifiedAtMillis == now }.map { ages[starts.indexOf(it)] }
    assertThat(againReverified).containsExactly(400, 380, 200, 100)
  }

  @Test
  fun ilBudgetDellaRotazioneSegueIByteProtetti() {
    val policy = RotationPolicy()
    assertThat(policy.bytesPerRun(null)).isEqualTo(100_000_000L)
    assertThat(policy.bytesPerRun(10_000_000_000L)).isEqualTo(100_000_000L)
    // ~140 MB a notte con 50 GB (§0.1 D14).
    assertThat(policy.bytesPerRun(50_000_000_000L)).isEqualTo(136_986_302L)
  }

  /** E-L3: due copie private dello stesso media, una rovinata: il media resta al sicuro con l'altra, nessun segno. */
  @Test
  fun unaCopiaRovinataFraDueNonToglieIlMediaDaAlSicuro() = runTest {
    val now = kit.clock.nasNow!!
    val start = bench.withPrivateCopy("IMG_L3.jpg", SafetyBench.bytes(20_000, 6), verifiedAt = now - 400 * day, onPhone = false)
    val second = SharePath.parse("Vecchio backup/IMG_L3.jpg")
    val stamp = bench.putFile(kit.share, second.value, start.data)
    kit.catalog.putCopy(start.mediaId, start.versionId, kit.share, second, stamp, verifiedAtMillis = now - 10 * day)
    corruptSilently(kit.share, start.commonPath!!, start.data)

    val report = bench.verifier().verify(VerifyScope.Rotation, 1.hours)

    assertThat(report.mismatched).isEqualTo(1)
    assertThat(kit.catalog.isSafe(start.mediaId)).isTrue()
    assertThat(kit.catalog.problemOf(start.mediaId)).isNull()
  }

  /**
   * F4-D21: la fase `FULL_HASH` risolve i gruppi `PROBABLE_DUPLICATE`. Un video del telefono e la sua copia probabile (stessa dimensione,
   * stesso `qh1`) nell'area comune: l'hash calcolato qui li unisce. Un altro con i byte diversi solo nel mezzo: si separano, e la copia
   * (privata) diventa un media a se', verificato. La coda la mette il catalogo; l'hash che entra e' sempre quello letto qui.
   */
  @Test
  fun fullHashRisolveIGruppiProbableDuplicate() = runTest {
    val grouping = GroupingCatalog(kit.catalog)
    val same = SafetyBench.bytes(400_000, 7)
    val phoneSame = grouping.phoneVideo("VID_1.mp4", same, 101)
    val nasSame = grouping.nasVideo(kit.commonShare, "Video/VID_1.mp4", same, bench)
    grouping.group(phoneSame, nasSame)

    val base = SafetyBench.bytes(400_000, 8)
    val middle = SafetyBench.oneByteChanged(base)
    assertThat(QuickHashAlgorithm.ofContent(middle)).isEqualTo(QuickHashAlgorithm.ofContent(base))
    val phoneOther = grouping.phoneVideo("VID_2.mp4", base, 102)
    val nasOther = grouping.nasVideo(kit.share, "Vecchio/VID_2.mp4", middle, bench)
    grouping.group(phoneOther, nasOther)

    val report = bench.verifier(catalog = grouping).verify(VerifyScope.FullHash, 1.hours)

    assertThat(report.checked).isEqualTo(2)
    assertThat(grouping.groups).isEmpty()
    assertThat(grouping.merged).containsExactly(phoneSame to nasSame)
    assertThat(grouping.separated).containsExactly(nasOther)
    assertThat(grouping.learned).containsExactly(Sha256.of(same), Sha256.of(middle))
    // La copia privata del media separato e' riletta qui: verificata; quella dell'area comune non e' mai "al sicuro".
    assertThat(kit.catalog.isSafe(nasOther)).isTrue()
    assertThat(kit.catalog.isSafe(nasSame)).isFalse()
    assertThat(kit.catalog.isSafe(phoneOther)).isFalse()
  }

  /** F4-D8 nella fase `FULL_HASH`: un hash "dall'indice" nella coda non entra mai; entra quello calcolato qui. */
  @Test
  fun unHashDellIndiceNellaCodaNonEntraMai() = runTest {
    val grouping = GroupingCatalog(kit.catalog)
    val phoneBytes = SafetyBench.bytes(300_000, 9)
    val nasBytes = SafetyBench.oneByteChanged(phoneBytes)
    val phoneId = grouping.phoneVideo("VID_3.mp4", phoneBytes, 103)
    val nasId = grouping.nasVideo(kit.commonShare, "Video/VID_3.mp4", nasBytes, bench)
    grouping.group(phoneId, nasId)
    grouping.claimed[nasId] = Sha256.of(phoneBytes) // l'indice dice "stessi byte del telefono"

    bench.verifier(catalog = grouping).verify(VerifyScope.FullHash, 1.hours)

    assertThat(grouping.learned).containsExactly(Sha256.of(nasBytes))
    assertThat(grouping.merged).isEmpty()
    assertThat(grouping.separated).containsExactly(nasId)
  }

  /**
   * F4-D8 e L28 per le verifiche: il catalogo riceve **lo SHA-256 letto**, mai quello atteso. Un hash atteso "dall'indice" uguale a
   * quello del telefono, con byte veri diversi, non fa mai "al sicuro".
   */
  @Test
  fun alCatalogoArrivaSoloLoShaLettoQui() = runTest {
    val honest = bench.withPrivateCopy("IMG_H.jpg", SafetyBench.bytes(25_000, 10), verifiedAt = null)
    val forged = bench.withPrivateCopy("IMG_F.jpg", SafetyBench.bytes(26_000, 11), verifiedAt = null)
    corruptSilently(kit.share, forged.commonPath!!, forged.data)
    val spy = SpyCatalog(kit.catalog)

    bench.verifier(catalog = spy).verify(VerifyScope.Unverified, 1.hours)

    assertThat(spy.readBacks).hasSize(2)
    for ((location, sha) in spy.readBacks) {
      val copy = kit.catalog.allCopies().single { it.locationId == location }
      assertThat(sha).isEqualTo(Sha256.of(kit.nas.bytes(copy.share, copy.path.value)!!))
    }
    assertThat(kit.catalog.isSafe(honest.mediaId)).isTrue()
    assertThat(kit.catalog.isSafe(forged.mediaId)).isFalse()
  }

  /** `OnTheSpot` (5B, D21): solo le copie private dei media chiesti. */
  @Test
  fun laVerificaSulMomentoRileggeSoloIMediaChiesti() = runTest {
    val asked = bench.withPrivateCopy("IMG_S1.jpg", SafetyBench.bytes(20_000, 12), verifiedAt = kit.clock.nasNow!! - 3 * day)
    val other = bench.withPrivateCopy("IMG_S2.jpg", SafetyBench.bytes(20_500, 13), verifiedAt = kit.clock.nasNow!! - 3 * day)
    kit.clock.advance(1_000)

    val report = bench.verifier().verify(VerifyScope.OnTheSpot(setOf(asked.mediaId)), 1.hours)

    assertThat(report.checked).isEqualTo(1)
    assertThat(kit.catalog.mediaSafety(asked.mediaId)!!.verifiedAtMillis).isEqualTo(kit.clock.nasNow)
    assertThat(kit.catalog.mediaSafety(other.mediaId)!!.verifiedAtMillis).isLessThan(kit.clock.nasNow!!)
  }

  /** Una copia sparita o aperta dal PC: "illeggibile" questa volta, niente cambia; un errore del NAS ferma il giro. */
  @Test
  fun copieIllegibiliEdErroriDelNas() = runTest {
    val missing = bench.withPrivateCopy("IMG_M.jpg", SafetyBench.bytes(10_000, 14), verifiedAt = kit.clock.nasNow!! - 400 * day)
    val busy = bench.withPrivateCopy("IMG_N.jpg", SafetyBench.bytes(10_500, 15), verifiedAt = kit.clock.nasNow!! - 390 * day)
    kit.nas.delete(kit.share, missing.commonPath!!.value)
    kit.nas.lock(kit.share, busy.commonPath!!.value)

    val report = bench.verifier().verify(VerifyScope.Rotation, 1.hours)
    assertThat(report.unreadable).isEqualTo(2)
    assertThat(report.mismatched).isEqualTo(0)

    kit.nas.unlock(kit.share, busy.commonPath.value)
    kit.nas.failWith = NasError.Timeout
    val down = bench.verifier().verify(VerifyScope.Rotation, 1.hours)
    assertThat(down.stoppedBy).isEqualTo(RunStop.NAS)
    assertThat(kit.catalog.isSafe(busy.mediaId)).isTrue()
  }

  /** Il cancello: "Backup in prova" (D12) o famiglia non pronta = niente si legge; congelamento o scritture ferme = si rilegge. */
  @Test
  fun ilCancelloFermaLeVerificheSoloSeIlBackupNonCe() = runTest {
    bench.withPrivateCopy("IMG_G.jpg", SafetyBench.bytes(10_000, 16), verifiedAt = null)
    kit.gate.default = BackupGateState.TEST_ONLY
    assertThat(bench.verifier().verify(VerifyScope.Unverified, 1.hours).stoppedBy).isEqualTo(RunStop.GATE)
    kit.gate.default = BackupGateState.NOT_READY
    assertThat(bench.verifier().verify(VerifyScope.Unverified, 1.hours).stoppedBy).isEqualTo(RunStop.GATE)
    assertThat(kit.nas.opens.get()).isEqualTo(0)
    kit.gate.default = BackupGateState.FROZEN
    val frozen = bench.verifier().verify(VerifyScope.Unverified, 1.hours)
    assertThat(frozen.verified).isEqualTo(1)
  }

  /** Un "copia non integra" rimasto da prima si toglie quando il media torna al sicuro. */
  @Test
  fun unSegnoVecchioSiToglieQuandoIlMediaTornaAlSicuro() = runTest {
    val start = bench.withPrivateCopy("IMG_P.jpg", SafetyBench.bytes(10_000, 17), verifiedAt = null, onPhone = false)
    kit.catalog.markProblem(start.mediaId, BackupProblemCode.VERIFY_FAILED)
    bench.verifier().verify(VerifyScope.Unverified, 1.hours)
    assertThat(kit.catalog.isSafe(start.mediaId)).isTrue()
    assertThat(kit.catalog.problemOf(start.mediaId)).isNull()
  }

  /** Senza tempo, niente: il giro si ferma per il budget. */
  @Test
  fun senzaBudgetNonSiLeggeNiente() = runTest {
    bench.withPrivateCopy("IMG_Z.jpg", SafetyBench.bytes(10_000, 18), verifiedAt = null)
    val report = bench.verifier().verify(VerifyScope.Unverified, SafetyBench.NOTHING)
    assertThat(report.stoppedBy).isEqualTo(RunStop.BUDGET)
    assertThat(report.checked).isEqualTo(0)
  }

  // --- Doppi del catalogo ------------------------------------------------------------------------------------------------------

  /** Registra le riletture che arrivano al catalogo. */
  private class SpyCatalog(private val inner: BackupCatalog) : BackupCatalog by inner {
    val readBacks = ArrayList<Pair<LocationId, Sha256>>()

    override suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean {
      readBacks += location to readBack
      return inner.markVerified(location, readBack, atMillis)
    }
  }

  /**
   * I gruppi `PROBABLE_DUPLICATE` del catalogo (F4-D21, `modello-dati.md` §11.4) quanto basta a questo test: la coda `FULL_HASH` sono le
   * copie sul NAS (in qualunque area) dei membri senza hash completo; quando l'hash si impara il gruppo si risolve: stessi byte = unione,
   * byte diversi = separazione. [claimed] e' un hash "dall'indice" messo nella coda come atteso.
   */
  private class GroupingCatalog(private val inner: InMemoryBackupCatalog) : BackupCatalog by inner {
    val groups = ArrayList<MutableSet<MediaId>>()
    val merged = ArrayList<Pair<MediaId, MediaId>>()
    val separated = ArrayList<MediaId>()
    val learned = ArrayList<Sha256>()
    val claimed = HashMap<MediaId, Sha256>()
    private val queue = ArrayList<NasCopyRef>()

    fun phoneVideo(name: String, data: ByteArray, id: Long): MediaId {
      val m = InMemoryBackupCatalog.Media(
        mediaId = MediaId.random(), versionId = VersionId.random(), kind = MediaKind.VIDEO, size = data.size.toLong(),
        quickHash = QuickHashAlgorithm.ofContent(data), phone = null, phoneStamp = null, source = BackupSource.Camera, localDay = 20261012,
        fileName = name, sortTime = id, fullHash = Sha256.of(data),
      )
      inner.put(m)
      return m.mediaId
    }

    fun nasVideo(share: String, path: String, data: ByteArray, bench: SafetyBench): MediaId {
      val m = InMemoryBackupCatalog.Media(
        mediaId = MediaId.random(), versionId = VersionId.random(), kind = MediaKind.VIDEO, size = data.size.toLong(),
        quickHash = QuickHashAlgorithm.ofContent(data), phone = null, phoneStamp = null, source = BackupSource.Camera, localDay = 20261012,
        fileName = path.substringAfterLast('/'), sortTime = 0, fullHash = null,
      )
      inner.put(m)
      val stamp = bench.putFile(share, path, data)
      val copy = inner.putCopy(m.mediaId, m.versionId, share, SharePath.parse(path), stamp)
      queue += NasCopyRef(copy.locationId, m.mediaId, m.versionId, share, copy.path, stamp)
      return m.mediaId
    }

    fun group(a: MediaId, b: MediaId) {
      groups += linkedSetOf(a, b)
    }

    override suspend fun verifyCandidates(scope: VerifyScope, limit: Int): List<CopyToVerify> {
      if (scope != VerifyScope.FullHash) return inner.verifyCandidates(scope, limit)
      return queue.filter { inner.media(it.mediaId)?.fullHash == null }.take(limit).map {
        CopyToVerify(it, it.stamp!!.size, claimed[it.mediaId], null)
      }
    }

    override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord {
      val r = inner.recordFullHash(mediaId, versionId, sha256)
      if (r == HashRecord.RECORDED) {
        learned += sha256
        val g = groups.firstOrNull { mediaId in it } ?: return r
        if (g.all { inner.media(it)?.fullHash != null }) {
          val anchor = g.first()
          for (other in g.drop(1)) {
            if (inner.media(other)!!.fullHash == inner.media(anchor)!!.fullHash) merged += anchor to other else separated += other
          }
          groups.remove(g)
        }
      }
      return r
    }
  }
}
