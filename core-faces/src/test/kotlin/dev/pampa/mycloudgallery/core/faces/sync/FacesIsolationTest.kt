package dev.pampa.mycloudgallery.core.faces.sync

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.EncodedSegment
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesHead
import dev.pampa.mycloudgallery.core.trust.data.PeopleDoc
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Isolamento degli errori (Fase 8, P3, "Fatto quando": un segmento ostile o enorme non ferma la sincronizzazione degli altri dati, con tetti
 * per dispositivo; E-F41; come la revisione A della Fase 6). Le teste ostili sono firmate davvero da un dispositivo dell'account (un
 * dispositivo compromesso, L99): la firma da sola non basta a far passare un dato.
 */
class FacesIsolationTest {

  private val a = FacesSyncWorld.person(1)
  private val b = FacesSyncWorld.person(2)

  private fun nameOf(device: SyncDevice, person: PersonId): String? = kotlinx.coroutines.runBlocking {
    (device.archive.people()[PeopleKey(person.base32(), "name")]?.edit as? PeopleEdit.Name)?.name
  }

  /** Una testa firmata da [signer] con i segmenti [segments], scritta a mano nella sua cartella (come farebbe un dispositivo compromesso). */
  private fun plantHead(world: FacesSyncWorld, device: SyncDevice, version: Long, segments: List<Pair<SegmentClass, ByteArray>>, declared: Map<Int, Long> = emptyMap()) {
    val classed = segments.mapIndexed { i, (cls, bytes) ->
      ClassedSegment(cls, SegmentRef(Hashes.sha256Name(bytes), declared[i] ?: bytes.size.toLong()))
    }.sortedBy { it.ref.hash }
    for ((cls, bytes) in segments) device.share.plant(NasLayout.facesSegment(world.generation, device.id, Hashes.sha256Name(bytes)), bytes)
    val head = FacesHead(world.family.familyId, "test", world.generation, device.id, version, world.family.state.registry.version, world.clock.nasNow!!, null, classed)
    device.share.plant(NasLayout.facesHead(world.generation, device.id, version), FacesDocs.encodeHead(head, device.signer).bytes)
  }

  private fun people(vararg docs: PeopleDoc): EncodedSegment = FacesDocs.peopleSegment(docs.toList())

  @Test
  fun unSegmentoOstileNonFermaGliAltri() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono", "test")
    val tablet = world.device("Tablet", "test")
    val laptop = world.device("Portatile", "test")
    val stranger = world.device("Telefono di Test2", "test2")
    laptop.compute(7)
    laptop.edit(PeopleEdit.Name(b, "Dal portatile"))
    laptop.publisher.flush(FlushReason.UNIT_FINISHED)
    val now = world.clock.nasNow!!
    val good = people(
      PeopleDoc(a.base32(), "name", "Dal tablet", now, tablet.id),
      PeopleDoc(a.base32(), "hidden", "true", now, stranger.id),
      PeopleDoc(b.base32(), "hidden", "true", now + 10 * 60_000, tablet.id),
    )
    val garbage = ByteArray(300) { (it * 7).toByte() }
    val missing = people(PeopleDoc(b.base32(), "forget", "true", now, tablet.id))
    plantHead(world, tablet, 1, listOf(SegmentClass.PEOPLE to good.bytes, SegmentClass.FACES to garbage, SegmentClass.PEOPLE to missing.bytes))
    tablet.share.delete(NasLayout.facesSegment(world.generation, tablet.id, missing.ref.hash))
    val report = phone.round()
    // Il segmento spazzatura e' rifiutato; quello che manca si riprova; le voci giuste passano, quella di un altro account no,
    // quella "dal futuro" aspetta. Il portatile arriva lo stesso.
    assertThat(report.segmentsRejected[RejectReason.MALFORMED.name]).isEqualTo(1)
    assertThat(report.segmentsRejected["MISSING"]).isEqualTo(1)
    assertThat(report.segmentsRejected[PeopleVerdict.AUTHOR_NOT_ALLOWED.code]).isEqualTo(1)
    assertThat(report.segmentsRejected[PeopleVerdict.FROM_FUTURE.code]).isEqualTo(1)
    assertThat(nameOf(phone, a)).isEqualTo("Dal tablet")
    assertThat(nameOf(phone, b)).isEqualTo("Dal portatile")
    assertThat(phone.archive.people()[PeopleKey(a.base32(), "hidden")]).isNull()
    assertThat(phone.archive.winners(listOf(FacesSyncWorld.key(7))).keys).containsExactly(FacesSyncWorld.key(7))
    // Il segmento rifiutato non si riscarica; quello "dal futuro" nemmeno prima della sua ora; quello che manca torna e passa.
    tablet.share.plant(NasLayout.facesSegment(world.generation, tablet.id, missing.ref.hash), missing.bytes)
    val reads = phone.share.reads.size
    phone.round()
    val again = phone.share.reads.drop(reads).filter { it.isWithin(NasLayout.facesSegmentDir(world.generation, tablet.id)) }.map { it.name }
    assertThat(again).containsExactly(missing.ref.hash)
    assertThat(phone.archive.people()[PeopleKey(b.base32(), "forget")]).isNotNull()
    world.clock.advanceMillis(10 * 60_000)
    phone.round()
    assertThat(phone.archive.people()[PeopleKey(b.base32(), "hidden")]).isNotNull()
  }


  /** I tetti per dispositivo e per giro: un dispositivo con tanto da scaricare avanza a rate, gli altri arrivano subito (a rotazione). */
  @Test
  fun unDispositivoEnormeAvanzaARateEGliAltriArrivano() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono", "test")
    val tablet = world.device("Tablet", "test")
    val laptop = world.device("Portatile", "test")
    laptop.edit(PeopleEdit.Name(b, "Dal portatile"))
    laptop.publisher.flush(FlushReason.UNIT_FINISHED)
    val now = world.clock.nasNow!!
    val segments = (1..8).map { n ->
      val hidden = (0 until 10).map { k -> PeopleDoc(FacesSyncWorld.person(1_000 + n * 10 + k).base32(), "hidden", "true", now, tablet.id) }
      people(*(hidden + PeopleDoc(FacesSyncWorld.person(100 + n).base32(), "name", "Persona $n", now, tablet.id)).toTypedArray())
    }
    plantHead(world, tablet, 1, segments.map { SegmentClass.PEOPLE to it.bytes })
    val size = segments.maxOf { it.bytes.size }.toLong()
    val limits = FacesSyncLimits(maxBytesPerRound = 3 * size + 2_000, maxBytesPerDevice = 2 * size + 10)
    val sync = NasFacesSync(phone.identity, phone.switch, phone.remote, phone.archives, phone.replicaStore, phone.gate, world.clock, limits = limits)
    sync.sync(1.hours)
    assertThat(nameOf(phone, b)).isEqualTo("Dal portatile")
    val applied = { (101..108).count { nameOf(phone, FacesSyncWorld.person(it)) != null } }
    assertThat(applied()).isEqualTo(2)
    repeat(3) { sync.sync(1.hours) }
    assertThat(applied()).isEqualTo(8)
  }

  /**
   * Tetti per dispositivo: troppe voci in un dispositivo = segmento rifiutato (`TOO_LARGE`); segmenti che si dichiarano da 12 MiB e non lo
   * sono costano il loro peso dichiarato e si rifiutano (`HASH_MISMATCH`), due per giro, senza fermare gli altri dispositivi.
   */
  @Test
  fun tettiPerDispositivo() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono", "test")
    val tablet = world.device("Tablet", "test")
    val laptop = world.device("Portatile", "test")
    val other = world.device("Secondo tablet", "test")
    val now = world.clock.nasNow!!
    val many = people(*(1..5).map { PeopleDoc(FacesSyncWorld.person(200 + it).base32(), "hidden", "true", now, tablet.id) }.toTypedArray())
    plantHead(world, tablet, 1, listOf(SegmentClass.PEOPLE to many.bytes))
    val fake = (0 until 6).map { SegmentClass.FACES to ByteArray(10) { n -> (n + it * 16).toByte() } }
    plantHead(world, laptop, 1, fake, declared = (0 until 6).associateWith { TrustLimits.MAX_FACES_SEGMENT_BYTES.toLong() })
    other.edit(PeopleEdit.Name(b, "Dal secondo tablet"))
    other.publisher.flush(FlushReason.UNIT_FINISHED)
    val sync = NasFacesSync(phone.identity, phone.switch, phone.remote, phone.archives, phone.replicaStore, phone.gate, world.clock, limits = FacesSyncLimits(maxEntriesPerDevice = 3))
    val report = sync.sync(1.hours)
    assertThat(report.segmentsRejected[RejectReason.TOO_LARGE.name]).isEqualTo(1)
    assertThat(report.segmentsRejected[RejectReason.HASH_MISMATCH.name]).isEqualTo(2)
    assertThat(nameOf(phone, b)).isEqualTo("Dal secondo tablet")
    assertThat(phone.archive.people().keys.none { it.field == "hidden" }).isTrue()
    val second = sync.sync(1.hours)
    val third = sync.sync(1.hours)
    assertThat(second.segmentsRejected[RejectReason.HASH_MISMATCH.name]).isEqualTo(2)
    assertThat(third.segmentsRejected[RejectReason.HASH_MISMATCH.name]).isEqualTo(2)
    assertThat(sync.sync(1.hours).segmentsRejected).isEmpty()
  }
}
