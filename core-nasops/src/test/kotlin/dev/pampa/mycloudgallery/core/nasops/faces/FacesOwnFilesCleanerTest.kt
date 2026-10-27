package dev.pampa.mycloudgallery.core.nasops.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasFileSystem
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nasops.contract.FacesCurrent
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.p12.SigningFamily
import dev.pampa.mycloudgallery.core.nasops.p12.deviceId
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesHead
import dev.pampa.mycloudgallery.core.trust.data.LeaseManager
import dev.pampa.mycloudgallery.core.trust.data.LeaseRoot
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Fase 8, P8 (formato §2.3, A8, M6): la pulizia dei **propri** file dei volti sul simulatore SMB con le primitive vere. Si toglie solo cio' che
 * e' proprio per firma **e** per posto, superato, vecchio e nel diario del pubblicatore, **solo nella generazione corrente**; il resto (le teste
 * recenti, i segmenti nuovi o non nel diario, i file degli altri dispositivi, le teste false nella mia cartella, i file strani, le altre
 * generazioni) resta byte per byte. Con il cancello chiuso o senza la generazione corrente non si tocca niente.
 */
class FacesOwnFilesCleanerTest {

  private val now = InMemorySmbBackend().nasTime()
  private val day = 24L * 60 * 60 * 1000
  private val family = SigningFamily(now)
  private val me: Signer = family.approveDevice("Telefono", "test")
  private val other: Signer = family.approveDevice("Tablet", "test")
  private val w = FacesSmbWorld(deviceId = me.deviceId())
  private val share = "test"
  private val g1 = FaceGeneration.fromBytes(ByteArray(16) { 11 })
  private val g2 = FaceGeneration.fromBytes(ByteArray(16) { 12 })
  private val diary = HashSet<String>()

  @After
  fun close() = w.close()

  private val cleaner = FacesOwnFilesCleaner(
    deviceId = me.deviceId(),
    accountId = "test",
    fileOps = w.fileOps,
    reader = w.reader,
    facts = w.facts,
    registry = { family.state },
    publishedSegments = { g -> if (g == g1) diary else emptySet() },
    privateShare = { share },
    commonShare = { "test-comune" },
    gate = { w.blocked },
    clocks = w.clocks,
  )

  /** Un segmento dei volti di [device] in [g] scritto [ageDays] giorni fa; restituisce l'impronta. */
  private fun segment(g: FaceGeneration, device: Signer, name: String, ageDays: Long): String {
    val bytes = "segmento $name".toByteArray()
    val hash = Hashes.sha256Name(bytes)
    w.put(share, NasLayout.facesSegment(g, device.deviceId(), hash), bytes, lastWriteMs = now - ageDays * day)
    return hash
  }

  /** Una testa dei volti firmata da [signer] messa al posto della testa [version] di [owner] in [g]. */
  private fun head(g: FaceGeneration, signer: Signer, version: Long, segments: List<String>, owner: Signer = signer): SharePath {
    val head = FacesHead(
      familyId = family.familyId,
      account = "test",
      generation = g,
      deviceId = signer.deviceId(),
      version = version,
      registryVersion = family.state.registry.version,
      createdAt = now - day,
      snapshot = null,
      segments = segments.sorted().map { ClassedSegment(SegmentClass.FACES, SegmentRef(it, 20)) },
    )
    val path = NasLayout.facesHead(g, owner.deviceId(), version)
    w.put(share, path, FacesDocs.encodeHead(head, signer).bytes)
    return path
  }

  /** Le prenotazioni del custode: la mia presa e rilasciata (numeri 1 e 2), poi quella dell'altro (3). */
  private fun leases(g: FaceGeneration): List<SharePath> {
    val fs = InMemoryNasFileSystem(share)
    val resource = NasLayout.facesClusterResource(g)
    val mine = LeaseManager(fs, me, NasClock { now }, LeaseRoot.Faces(g))
    mine.tryAcquire(family.state, resource, 60_000)
    mine.release(family.state, resource)
    LeaseManager(fs, other, NasClock { now }, LeaseRoot.Faces(g)).tryAcquire(family.state, resource, 60_000)
    return (1L..3L).map { n ->
      val path = NasLayout.facesLeaseFile(g, resource, n)
      w.put(share, path, (fs.read(path, 64 * 1024) as NasRead.Data).bytes)
      path
    }
  }

  @Test
  fun toglieSoloCioCheEMioSuperatoEVecchioNellaGenerazioneCorrente() = runTest {
    w.facts.current = FacesCurrent.Known(g1)
    val listed = (1..5).map { segment(g1, me, "elencato-$it", ageDays = 10) }
    val heads = (1L..5L).map { v -> head(g1, me, v, listOf(listed[(v - 1).toInt()])) }
    val old = segment(g1, me, "vecchio", ageDays = 10)
    val fresh = segment(g1, me, "nuovo", ageDays = 1)
    val notInDiary = segment(g1, me, "fuori-diario", ageDays = 10)
    diary += listed + old + fresh
    // Una testa falsa nella mia cartella, firmata da un altro: non e' mia.
    val forged = head(g1, other, 6, listOf(listed[0]), owner = me)
    val othersFiles = (1L..4L).map { v -> head(g1, other, v, listOf(segment(g1, other, "altro-$v", 10))) }
    val junk = NasLayout.facesHeadDir(g1, me.deviceId()).child("strano").also { w.put(share, it) }
    val leases = leases(g1)
    val otherGeneration = (1L..4L).map { v -> head(g2, me, v, listOf(segment(g2, me, "g2-$v", 30))) }

    val report = cleaner.clean(listOf(share, "test-comune"), 5.minutes)

    assertThat(report.stoppedBy).isEqualTo(PurgeStop.COMPLETE)
    val removed = w.removed().map { it.second }
    val segmentsOf = { hashes: List<String> -> hashes.map { NasLayout.facesSegment(g1, me.deviceId(), it) } }
    assertThat(removed).containsExactlyElementsIn(heads.take(2) + segmentsOf(listed.take(2) + old) + leases.take(2))
    assertThat(report.removed).isEqualTo(7)
    val left = w.files(share)
    assertThat(left).containsAtLeastElementsIn(heads.drop(2) + segmentsOf(listed.drop(2) + fresh + notInDiary) + forged + junk + leases[2])
    assertThat(left).containsAtLeastElementsIn(othersFiles + otherGeneration)
  }

  @Test
  fun senzaGenerazioneCorrenteOConIlCancelloChiusoNonSiToccaNiente() = runTest {
    (1L..5L).forEach { v -> head(g1, me, v, listOf(segment(g1, me, "s$v", 10))) }
    for (current in listOf(FacesCurrent.Unknown, FacesCurrent.Known(null))) {
      w.facts.current = current
      assertThat(cleaner.clean(listOf(share), 5.minutes).removed).isEqualTo(0)
    }
    w.facts.current = FacesCurrent.Known(g1)
    w.blocked = FacesEraseStop.FROZEN
    assertThat(cleaner.clean(listOf(share), 5.minutes).stoppedBy).isEqualTo(PurgeStop.BLOCKED)
    w.blocked = null
    // Una share che non e' l'area privata: niente.
    assertThat(cleaner.clean(listOf("test-comune"), 5.minutes).removed).isEqualTo(0)
    assertThat(w.changes).isEmpty()
    assertThat(cleaner.clean(listOf(share), 5.minutes).removed).isEqualTo(2)
  }
}
