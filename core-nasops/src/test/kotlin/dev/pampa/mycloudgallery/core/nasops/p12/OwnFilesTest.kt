package dev.pampa.mycloudgallery.core.nasops.p12

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.compact.CompactOutcome
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nasops.clean.IndexMaintenance
import dev.pampa.mycloudgallery.core.nasops.clean.OwnFilesCleaner
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpTemps
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.testing.FakeOpClocks
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.LeaseManager
import dev.pampa.mycloudgallery.core.trust.data.OwnKeepReason
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La pulizia dei propri file (formato §2.3, M6) e la manutenzione dell'indice che la accende con la compattazione (P12, riga del §4.4:
 * "compattazione e pulizia dei propri file accese"). Si toglie solo cio' che e' **proprio per firma e per posto**, superato, vecchio, non
 * citato; il resto resta byte per byte. Con congelamento, salto dell'ora, emergenza o senza ora del NAS non si tocca niente.
 */
class OwnFilesTest {

  private val family = SigningFamily(FakeOpClocks.DEFAULT_NAS_NOW)
  private val device = family.approveDevice()
  private val other = family.approveDevice(name = "Tablet")
  private val kit = NasOpsTestKit(deviceId = device.deviceId(), engine = KitEngine.PRODUCTION, signing = family.identity(device))
  private val me = kit.deviceId
  private val day = 24L * 60 * 60 * 1000
  private val now get() = kit.clocks.nasNow!!

  private val diary = HashSet<String>()
  private var registry: RegistryState? = family.state

  private fun cleaner() = OwnFilesCleaner(
    deviceId = me,
    accountId = "test",
    fileOps = kit.fileOps,
    reader = kit.nas.reader,
    store = kit.store,
    clocks = kit.clocks,
    gate = kit.gate,
    lock = NasOpsLock(),
    registry = { registry },
    publishedSegments = { diary },
  )

  private fun segment(name: String, ageDays: Long): String {
    val bytes = "segmento $name".toByteArray()
    val hash = Hashes.sha256Name(bytes)
    kit.nas.putFile("test", NasLayout.segmentFile(me, "index", hash).value, NasContent.of(bytes), modifiedAt = now - ageDays * day)
    return hash
  }

  private fun head(signer: Signer, version: Long, segments: List<String>, owner: String = me) {
    val head = DeviceHead(
      familyId = family.familyId,
      deviceId = signer.deviceId(),
      share = "test",
      kind = HeadKind.INDEX,
      version = version,
      registryVersion = family.state.registry.version,
      createdAt = now,
      segments = segments.sorted().map { ClassedSegment(SegmentClass.META, SegmentRef(it, 20)) },
    )
    kit.putFile("test", NasLayout.headFile(owner, "index", version).value, head.sign(signer), modifiedAt = now - 10 * day)
  }

  private fun snapshot(): Map<String, List<Byte>> = kit.filesIn("test").associateWith { kit.bytes("test", it)!!.toList() }

  private class World(val removable: Set<String>, val kept: Set<String>)

  private suspend fun world(): World {
    val a = segment("A", 9)
    val b = segment("B", 9)
    val c = segment("C", 9)
    val d = segment("D", 9)
    val e = segment("E", 30)
    val f = segment("F", 30)
    val g = segment("G", 1)
    diary += listOf(a, b, c, d, e, g)
    head(device, 1, listOf(a, b))
    head(device, 2, listOf(b))
    head(device, 3, listOf(b))
    head(device, 4, listOf(c))
    head(device, 5, listOf(c, d))
    // Una testa piantata nella mia cartella, firmata da un altro, e un file illeggibile con il nome di una testa.
    head(other, 6, listOf(a), owner = me)
    kit.putFile("test", NasLayout.headFile(me, "index", 7).value, byteArrayOf(1, 2, 3), modifiedAt = now - 30 * day)
    // Prenotazioni: le mie superate si tolgono, la piu' alta resta sempre, quelle degli altri restano.
    val fs = kit.nas.fileSystem("test")
    val clock = NasClock { now }
    val mine = LeaseManager(fs, device, clock)
    val theirs = LeaseManager(fs, other, clock)
    repeat(3) { mine.tryAcquire(family.state, "work/1/meta/2026-10/1", 60_000) }
    theirs.tryAcquire(family.state, "work/1/meta/2026-09/1", 60_000)
    theirs.release(family.state, "work/1/meta/2026-09/1")
    mine.tryAcquire(family.state, "work/1/meta/2026-09/1", 60_000)
    // Temporanei: un mio vecchio rimasto, uno recente, uno di un altro, uno citato da un'operazione in corso.
    val oldTemp = NasLayout.ROOT.child("tmp").child("$me-" + "ab".repeat(16))
    kit.putFile("test", oldTemp.value, byteArrayOf(5), modifiedAt = now - 3 * day)
    kit.putFile("test", NasLayout.ROOT.child("tmp").child("$me-recente").value, byteArrayOf(6), modifiedAt = now - 60_000)
    kit.putFile("test", NasLayout.ROOT.child("tmp").child("${other.deviceId()}-vecchio").value, byteArrayOf(7), modifiedAt = now - 30 * day)
    kit.putFile("test", "V/x.jpg", byteArrayOf(8, 8))
    kit.lock("test", "V/x.jpg")
    kit.run(OpRequest.Move(listOf(kit.itemOf("test", "V/x.jpg")), "test-comune", null))
    val op = kit.store.ops(setOf(dev.pampa.mycloudgallery.core.nasops.contract.OpState.PAUSED)).single()
    val cited = OpTemps.tempFor(me, op.id, 0)
    kit.putFile("test", cited.value, byteArrayOf(9), modifiedAt = now - 30 * day)
    val removable = setOf(
      NasLayout.headFile(me, "index", 1).value,
      NasLayout.headFile(me, "index", 2).value,
      NasLayout.segmentFile(me, "index", a).value,
      NasLayout.segmentFile(me, "index", e).value,
      NasLayout.leaseFile("work/1/meta/2026-10/1", 1).value,
      NasLayout.leaseFile("work/1/meta/2026-10/1", 2).value,
      oldTemp.value,
    )
    return World(removable, snapshot().keys - removable)
  }

  @Test
  fun siTolgonoSoloIPropriSuperatiEVecchiIlRestoResta() = runTest {
    val w = world()
    val before = snapshot()
    val report = cleaner().clean(listOf("test"), 1.minutes)
    assertThat(report.stoppedBy).isEqualTo(PurgeStop.COMPLETE)
    val after = snapshot()
    assertThat(before.keys - after.keys).containsExactlyElementsIn(w.removable)
    // Tutto il resto e' com'era, byte per byte (teste recenti, segmenti elencati o non nel diario o recenti, file altrui).
    assertThat(after).isEqualTo(before.filterKeys { it !in w.removable })
    assertThat(report.removed).isEqualTo(w.removable.size)
    assertThat(report.kept[OwnKeepReason.RECENT_HEAD]).isEqualTo(3)
    assertThat(report.kept[OwnKeepReason.NOT_SIGNED_BY_ME]).isAtLeast(2)
    assertThat(report.kept[OwnKeepReason.LISTED]).isEqualTo(3)
    assertThat(report.kept[OwnKeepReason.NOT_IN_DIARY]).isEqualTo(1)
    assertThat(report.kept[OwnKeepReason.TOO_RECENT]).isAtLeast(2)
    assertThat(report.kept[OwnKeepReason.CITED_BY_DIARY]).isEqualTo(1)
    assertThat(report.kept[OwnKeepReason.HIGHEST_GENERATION]).isNull()
    // Un secondo giro non trova piu' niente da togliere.
    assertThat(cleaner().clean(listOf("test"), 1.minutes).removed).isEqualTo(0)
  }

  @Test
  fun conCongelamentoSaltoDellOraEmergenzaOSenzaOraNonSiToccaNiente() = runTest {
    world()
    val before = snapshot()
    for (status in listOf(GateStatus.FROZEN, GateStatus.CLOCK_JUMP, GateStatus.WRITES_STOPPED)) {
      kit.gate.byShare["test"] = status
      assertThat(cleaner().clean(listOf("test"), 1.minutes).stoppedBy).isEqualTo(PurgeStop.BLOCKED)
    }
    kit.gate.byShare.clear()
    val saved = kit.clocks.nasNow
    kit.clocks.nasNow = null
    assertThat(cleaner().clean(listOf("test"), 1.minutes).stoppedBy).isEqualTo(PurgeStop.NO_NAS_TIME)
    kit.clocks.nasNow = saved
    assertThat(snapshot()).isEqualTo(before)
  }

  @Test
  fun senzaRegistroNessunaTestaNeSegmentoNePrenotazioneSoloITemporaneiPropri() = runTest {
    val w = world()
    registry = null
    val before = snapshot()
    cleaner().clean(listOf("test"), 1.minutes)
    val removed = before.keys - snapshot().keys
    assertThat(removed).containsExactly(w.removable.single { it.contains("/tmp/") })
  }

  @Test
  fun senzaNessunaTestaValidaISegmentiRestanoTutti() = runTest {
    val old = segment("vecchio", 60)
    diary += old
    // Le mie teste sono sparite (cancellate dal PC): non si sa cosa e' elencato, quindi niente.
    head(other, 1, listOf(old), owner = me)
    cleaner().clean(listOf("test"), 1.minutes)
    assertThat(kit.exists("test", NasLayout.segmentFile(me, "index", old).value)).isTrue()
  }

  @Test
  fun laManutenzioneAccendeLaCompattazioneEPoiPulisceUnaVoltaAlGiorno() = runTest {
    world()
    val compacted = ArrayList<String>()
    val maintenance = IndexMaintenance(
      compact = { share ->
        compacted += share
        // La compattazione pubblica una testa nuova che non elenca piu' C e D (riscritti in H): dopo il periodo di grazia si tolgono.
        val h = segment("H", 0)
        diary += h
        head(device, 8, listOf(h))
        CompactOutcome.Compacted(8, removed = 2, added = 1)
      },
      cleaner = cleaner(),
      gate = kit.gate,
      clocks = kit.clocks,
    )
    val first = maintenance.run(listOf("test"), 1.minutes)
    assertThat(compacted).containsExactly("test")
    assertThat(first.compacted).isEqualTo(1)
    assertThat(first.clean.removed).isAtLeast(5)
    // Le ultime 3 teste valide sono 4, 5, 8: C e D restano (elencati), A, E e le teste 1-2-3 se ne vanno.
    assertThat(kit.exists("test", NasLayout.headFile(me, "index", 3).value)).isFalse()
    assertThat(kit.exists("test", NasLayout.headFile(me, "index", 4).value)).isTrue()
    // Lo stesso giorno: niente, nemmeno la compattazione.
    val second = maintenance.run(listOf("test"), 1.minutes)
    assertThat(second.skipped).isEqualTo(1)
    assertThat(compacted).hasSize(1)
    // Con la share congelata: saltata.
    kit.advance(day + 1)
    kit.gate.byShare["test"] = GateStatus.FROZEN
    assertThat(maintenance.run(listOf("test"), 1.minutes).skipped).isEqualTo(1)
    assertThat(compacted).hasSize(1)
  }

  @Test
  fun iPercorsiDaTogliereSonoSoloNellAreaDellApp() = runTest {
    val w = world()
    assertThat(w.removable.all { SharePath.parse(it).components.first() == ".mycloudgallery" }).isTrue()
  }
}
