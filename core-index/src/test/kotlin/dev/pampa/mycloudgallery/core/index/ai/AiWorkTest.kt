package dev.pampa.mycloudgallery.core.index.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.UnitLease
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnitKey
import dev.pampa.mycloudgallery.core.index.p3.TestFile
import dev.pampa.mycloudgallery.core.index.testing.FakeUnitPriority
import dev.pampa.mycloudgallery.core.index.work.LeasedWorkUnits
import dev.pampa.mycloudgallery.core.index.work.TrustWorkLeases
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Il catalogo per le unita' del calcolo AI, in memoria: i media della share per mese e cio' che l'indice locale ha gia'. */
class MapAiWorkCatalog(private val media: Map<String, List<TestFile>>) : AiWorkCatalog {
  val settled = HashMap<ContentKey, MutableSet<AiClass>>()

  override suspend fun months(share: String): List<String> = media.keys.sortedDescending()

  override suspend fun monthsOf(share: String, visible: Set<Long>): List<String> =
    media.filterValues { files -> files.any { it.contentKey.short in visible } }.keys.sortedDescending()

  override suspend fun monthMedia(share: String, month: String): List<WorkItem> = media[month].orEmpty().map {
    WorkItem(it.contentKey, it.path, it.stamp, MediaKind.PHOTO, "image/jpeg", it.mediaId, it.versionId, missing = emptySet())
  }

  override suspend fun settled(share: String, keys: Collection<ContentKey>): Map<ContentKey, Set<AiClass>> =
    keys.mapNotNull { k -> settled[k]?.let { k to it.toSet() } }.toMap()
}

/**
 * Il lavoro coordinato del calcolo AI (D9; formato-nas.md §5.6 "Unita' del calcolo AI"): `WorkPass.AI`, `AiWorkSource` e
 * `AiPublishedClasses` dentro le unita' con prenotazione della Fase 4. Un contenuto e' fatto per classe quando una testa `ai`
 * attestata di qualunque dispositivo approvato ha la voce della ricetta corrente.
 */
class AiWorkTest {

  @get:Rule val tmp = TemporaryFolder()

  private val share = "test-comune"

  private class Setup(val ai: AiWorld, val files: List<TestFile>, val catalog: MapAiWorkCatalog)

  private suspend fun setup(count: Int = 10): Setup {
    val ai = AiWorld(tmp.newFolder())
    val files = (1..count).map { ai.file(share, "2024/IMG_$it.jpg") }
    ai.device("Tablet").prepare(files)
    return Setup(ai, files, MapAiWorkCatalog(mapOf("2024-03" to files)))
  }

  private fun units(device: AiDevice, catalog: AiWorkCatalog, wanted: Set<AiClass>, recipe: Int = 1): LeasedWorkUnits {
    val remote = device.remote()
    val published = AiPublishedClasses(device.shareSync(remote), device.aiStore) { recipe }
    return LeasedWorkUnits(
      identity = device.identity,
      source = AiWorkSource(catalog) { wanted },
      published = published,
      leases = TrustWorkLeases({ device.ai.world.nas.share(it) }, device.base.signer, device.ai.clock, { device.ai.world.trust.state }, Dispatchers.Unconfined),
      rules = device.rules(),
    )
  }

  @Test
  fun laChiaveDellUnitaEWork1Ai() {
    assertThat(WorkUnitKey(share, WorkPass.AI, "2024-03", 2).resource).isEqualTo("work/1/ai/2024-03/2")
    assertThat(WorkUnitKey(share, WorkPass.AI, WorkUnitKey.NO_DATE, 0).resource).isEqualTo("work/1/ai/0000-00/0")
  }

  @Test
  fun aiPublishedClasses_riconosceIlLavoroDiUnAltroIndicizzatore() = runTest {
    val s = setup()
    val other = s.ai.device("Altro indicizzatore")
    publish(other, s.files.take(6).map { other.vector(it) } + s.files.take(3).map { other.text(it) })
    val me = s.ai.device("Telefono")
    me.sync() // i record `meta` della share (l'attestazione)

    val claim = units(me, s.catalog, wanted = setOf(AiClass.VEC, AiClass.OCR)).next(share, WorkPass.AI, FakeUnitPriority())!!

    assertThat(claim.key.resource).isEqualTo("work/1/ai/2024-03/0")
    assertThat(claim.lease).isInstanceOf(UnitLease.Held::class.java)
    val missing = claim.items.associate { it.contentKey to it.missing }
    // I primi 3: fatti del tutto da altri, fuori dall'unita'. Dal 4 al 6: manca solo il testo. Gli altri: tutto.
    assertThat(missing.keys).containsExactlyElementsIn(s.files.drop(3).map { it.contentKey })
    for (f in s.files.subList(3, 6)) assertThat(missing[f.contentKey]).containsExactly(SegmentClass.OCR)
    for (f in s.files.drop(6)) assertThat(missing[f.contentKey]).containsExactly(SegmentClass.VEC, SegmentClass.OCR)
  }

  @Test
  fun conLaLetturaDelTestoSpenta_unOcrMancanteNonELavoro() = runTest {
    val s = setup()
    val other = s.ai.device("Altro indicizzatore")
    publish(other, s.files.take(6).map { other.vector(it) })
    val me = s.ai.device("Telefono")
    me.sync()
    val claim = units(me, s.catalog, wanted = setOf(AiClass.VEC)).next(share, WorkPass.AI, FakeUnitPriority())!!
    assertThat(claim.items.map { it.contentKey }).containsExactlyElementsIn(s.files.drop(6).map { it.contentKey })
    assertThat(claim.items.flatMap { it.missing }.toSet()).containsExactly(SegmentClass.VEC)
    // Fatto anche in locale: niente lavoro.
    s.files.forEach { s.catalog.settled.getOrPut(it.contentKey) { HashSet() } += AiClass.VEC }
    assertThat(units(me, s.catalog, wanted = setOf(AiClass.VEC)).next(share, WorkPass.AI, FakeUnitPriority())).isNull()
    assertThat(units(me, s.catalog, wanted = emptySet()).next(share, WorkPass.AI, FakeUnitPriority())).isNull()
  }

  @Test
  fun unaRicettaPiuVecchiaNonBasta_unaVoceNonAttestataNemmeno() = runTest {
    val s = setup(4)
    val other = s.ai.device("Altro indicizzatore")
    val stray = s.ai.file(share, "2024/IMG_extra.jpg") // nessun record `meta` nella share: la voce non e' attestata
    publish(other, s.files.map { other.vector(it) } + other.vector(stray))
    val me = s.ai.device("Telefono")
    me.sync()
    val catalog = MapAiWorkCatalog(mapOf("2024-03" to s.files + stray))

    val current = units(me, catalog, setOf(AiClass.VEC), recipe = 1).next(share, WorkPass.AI, FakeUnitPriority())!!
    assertThat(current.items.map { it.contentKey }).containsExactly(stray.contentKey)
    val newer = units(me, catalog, setOf(AiClass.VEC), recipe = 2).next(share, WorkPass.AI, FakeUnitPriority())!!
    assertThat(newer.items).hasSize(5)
  }

  @Test
  fun alRinnovoSiRileggonoLeTesteAi_eUnUnitaFattaDaAltriSiLascia() = runTest {
    val s = setup(4)
    val me = s.ai.device("Telefono")
    me.sync()
    val units = units(me, s.catalog, setOf(AiClass.VEC))
    val claim = units.next(share, WorkPass.AI, FakeUnitPriority())!!
    assertThat(claim.items).hasSize(4)
    // Un altro indicizzatore (prenotazione scaduta, E-I2) finisce tutto nel frattempo.
    val other = s.ai.device("Altro indicizzatore")
    publish(other, s.files.map { other.vector(it) })
    s.ai.clock.advance(TrustLimits.LEASE_RENEW_MILLIS)
    assertThat(units.keepAlive(claim)).isEqualTo(KeepAliveResult.Lost)
    units.finish(claim, UnitOutcome.Completed(0, 0))
  }

  @Test
  fun doneDiAiDoneClasses_dallaReplicaSenzaRete() = runTest {
    val s = setup(3)
    val other = s.ai.device("Altro indicizzatore")
    publish(other, listOf(other.vector(s.files[0]), other.text(s.files[0]), other.vector(s.files[1], recipe = 3)))
    val me = s.ai.device("Telefono")
    me.sync()
    val published = AiPublishedClasses(me.shareSync(), me.aiStore) { 1 }
    val done = published.done(share, s.files.map { it.contentKey }, recipe = 1)
    assertThat(done[s.files[0].contentKey]).containsExactly(AiClass.VEC, AiClass.OCR)
    // R16: la ricetta 3 non e' quella chiesta (ne' una che l'app conosce): non conta come fatta.
    assertThat(done).doesNotContainKey(s.files[1].contentKey)
    assertThat(done).doesNotContainKey(s.files[2].contentKey)
    assertThat(published.done(share, s.files.map { it.contentKey }, recipe = 3).keys).containsExactly(s.files[1].contentKey)
  }

  @Test
  fun aiWorkSource_soloIlPassaggioAi() = runTest {
    val s = setup(2)
    val source = AiWorkSource(s.catalog) { setOf(AiClass.VEC) }
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { source.months(share, WorkPass.PREP) } }
    assertThat(source.monthsOf(share, WorkPass.AI, setOf(s.files[0].contentKey.short))).containsExactly("2024-03")
  }

  private suspend fun publish(device: AiDevice, entries: List<AiEntry>) {
    val publisher = device.publisher()
    publisher.stage(share, entries)
    publisher.flush(share, FlushReason.UNIT_FINISHED)
  }
}
