package dev.pampa.mycloudgallery.core.faces.sync

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesRead
import dev.pampa.mycloudgallery.core.faces.contract.FacesRemote
import dev.pampa.mycloudgallery.core.faces.contract.FacesSyncStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.data.ClusterEntry
import dev.pampa.mycloudgallery.core.trust.data.ClusterPersonState
import dev.pampa.mycloudgallery.core.trust.data.FacesLimits
import dev.pampa.mycloudgallery.core.trust.data.PeopleDoc
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le correzioni della Fase 8 sulla replica e sul pubblicatore dei volti (pacchetto "formato e replica dei volti": R3, R4, R5, R6, R7, R8, I5),
 * con un **pubblicatore ostile** ([HostileFacesPublisher]: un dispositivo compromesso dell'account che firma davvero) sul NAS finto. Ogni prova
 * fallisce senza la sua correzione. Il mondo e' in memoria; `RoomFacesFormatCorrectionsTest` ripete le prove sul database dei volti.
 */
open class FacesFormatCorrectionsTest {

  protected open fun newWorld(): FacesSyncWorld = FacesSyncWorld()

  private val a = FacesSyncWorld.person(1)
  private val b = FacesSyncWorld.person(2)
  private val c = FacesSyncWorld.person(3)

  private suspend fun FacesArchive.value(person: PersonId, field: String): String? = people()[PeopleKey(person.base32(), field)]?.edit?.value

  // --- R3: provenienza --------------------------------------------------------------------------------------------------------------

  @Test
  fun r3_leVociPortateDaUnRevocatoSpariscanoOvunqueAncheSeDiconoUnAltroAutore() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono")
    val tablet = world.device("Tablet")
    val laptop = world.device("Portatile")
    val evilDevice = world.device("Compromesso")
    val evil = HostileFacesPublisher(world, evilDevice)
    tablet.edit(PeopleEdit.Name(a, "Anna"))
    tablet.publisher.flush(FlushReason.UNIT_FINISHED)
    val later = world.clock.nasNow!! + 1_000
    world.clock.advanceMillis(2_000)
    // Il dispositivo compromesso scrive a nome del tablet: un nome offensivo, una persona nascosta, una "Dimentica".
    evil.head(
      listOf(
        SegmentClass.PEOPLE to evil.people(
          PeopleDoc(a.base32(), "name", "Offesa", later, tablet.id),
          PeopleDoc(b.base32(), "hidden", "true", later, tablet.id),
          PeopleDoc(c.base32(), "forget", "true", later, tablet.id),
        ).bytes,
      ),
    )
    phone.round()
    assertThat(phone.archive.value(a, "name")).isEqualTo("Offesa")
    // Il telefono ripubblica lo stato unito: le voci portate dal compromesso non ci sono (la loro provenienza resta di un passo).
    world.clock.advanceMillis(6 * 60_000)
    phone.publisher.flush(FlushReason.UNIT_FINISHED)
    laptop.round()
    assertThat(laptop.archive.value(b, "hidden")).isEqualTo("true")
    // La revoca: tutto cio' che il compromesso ha portato sparisce, su ogni dispositivo; il nome vero torna dal tablet.
    world.family.changeStatus(evilDevice.signer, DeviceStatus.REVOKED)
    phone.round()
    laptop.round()
    for (device in listOf(phone, laptop)) {
      assertThat(device.archive.value(a, "name")).isEqualTo("Anna")
      assertThat(device.archive.value(b, "hidden")).isNull()
      assertThat(device.archive.value(c, "forget")).isNull()
    }
  }

  @Test
  fun r3_unaVoceAMioNomeFirmataDaUnAltroNonSostituisceLaMia() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono")
    val evil = HostileFacesPublisher(world, world.device("Compromesso"))
    phone.edit(PeopleEdit.Name(a, "Vero"))
    world.clock.advanceMillis(2_000)
    evil.head(listOf(SegmentClass.PEOPLE to evil.people(PeopleDoc(a.base32(), "name", "Falso", world.clock.nasNow!!, phone.id)).bytes))
    phone.round()
    assertThat(phone.archive.value(a, "name")).isEqualTo("Vero")
  }

  // --- R4: la seq dell'istantanea ---------------------------------------------------------------------------------------------------

  @Test
  fun r4_unIstantaneaConLaSeqOltreIlTettoNonSiAccettaESiConta() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono")
    val evil = HostileFacesPublisher(world, world.device("Compromesso"))
    val person = ClusterEntry.Person(a, ClusterPersonState.ACTIVE, null, 1, 1, 0, ShortArray(FacesLimits.DIMENSION), emptyList())
    evil.head(listOf(SegmentClass.CLUSTER to evil.cluster(listOf(person)).bytes), snapshot = SnapshotMark(1_000_000_000L, 999_999_999L))
    val report = phone.round()
    assertThat(report.segmentsRejected["SNAPSHOT_${RejectReason.TOO_LARGE.name}"]).isEqualTo(1)
    assertThat(phone.sync.winningSnapshot()).isNull()
  }

  // --- R5: un file illeggibile, segmenti assenti, tetto delle richieste ---------------------------------------------------------------

  /** La porta del telefono vista con un file che non si legge (un'ACL dal PC): ogni lettura di [bad] e' `UNREACHABLE`, il resto risponde. */
  private fun unreadable(remote: FacesRemote, bad: Set<String>): FacesRemote = object : FacesRemote by remote {
    override suspend fun readSegment(generation: FaceGeneration, deviceId: String, hash: String, maxBytes: Int): RemoteResult<FacesRead> =
      if (hash in bad) RemoteResult.Failed(RemoteFailure.UNREACHABLE) else remote.readSegment(generation, deviceId, hash, maxBytes)
  }

  @Test
  fun r5_unSegmentoIllegibileNonFermaIlGiroEAspetta() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono")
    val evil = HostileFacesPublisher(world, world.device("Aaaa-compromesso"))
    val laptop = world.device("Portatile")
    laptop.edit(PeopleEdit.Name(b, "Dal portatile"))
    laptop.publisher.flush(FlushReason.UNIT_FINISHED)
    val locked = evil.people(PeopleDoc(a.base32(), "name", "Chiuso", world.clock.nasNow!!, evil.deviceId))
    evil.head(listOf(SegmentClass.PEOPLE to locked.bytes))
    val remote = unreadable(phone.remote, setOf(locked.ref.hash))
    var reads = 0
    val counting = object : FacesRemote by remote {
      override suspend fun readSegment(generation: FaceGeneration, deviceId: String, hash: String, maxBytes: Int): RemoteResult<FacesRead> {
        if (hash == locked.ref.hash) reads++
        return remote.readSegment(generation, deviceId, hash, maxBytes)
      }
    }
    val sync = NasFacesSync(phone.identity, phone.switch, counting, phone.archives, phone.replicaStore, phone.gate, world.clock)
    val first = sync.sync(1.hours)
    assertThat(first.stop).isEqualTo(FacesSyncStop.COMPLETE)
    assertThat(first.segmentsRejected["UNREADABLE"]).isEqualTo(1)
    assertThat(phone.archive.value(b, "name")).isEqualTo("Dal portatile")
    // Il giro dopo non lo richiede (aspetta 15 minuti); dopo, si'.
    sync.sync(1.hours)
    assertThat(reads).isEqualTo(1)
    world.clock.advanceMillis(SegmentWait.FIRST_WAIT_MS)
    sync.sync(1.hours)
    assertThat(reads).isEqualTo(2)
  }

  @Test
  fun r5_iSegmentiElencatiEAssentiAspettanoEHannoUnTetto() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono")
    val evilDevice = world.device("Compromesso")
    val evil = HostileFacesPublisher(world, evilDevice)
    evil.head(emptyList(), absent = evil.absent(600))
    val dir = NasLayout.facesSegmentDir(world.generation, evilDevice.id)
    val before = phone.share.reads.size
    phone.round()
    val first = phone.share.reads.drop(before).count { it.isWithin(dir) }
    // Al piu' 256 richieste per dispositivo e per giro.
    assertThat(first).isEqualTo(256)
    val again = phone.share.reads.size
    phone.round()
    val second = phone.share.reads.drop(again).count { it.isWithin(dir) }
    // Le 256 gia' chieste aspettano: il giro dopo chiede le altre, mai di nuovo le stesse.
    assertThat(second).isEqualTo(256)
    val third = phone.share.reads.size
    phone.round()
    phone.round()
    assertThat(phone.share.reads.drop(third).count { it.isWithin(dir) }).isEqualTo(600 - 512)
  }

  // --- R6: tetti dei corpi per autore e in tutto ---------------------------------------------------------------------------------------

  @Test
  fun r6_unAutoreCheCambiaChiaviAOgniTestaSiFermaAlSuoTetto() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono")
    val evilDevice = world.device("Compromesso")
    val evil = HostileFacesPublisher(world, evilDevice)
    val limits = FacesSyncLimits(maxBodiesPerAuthor = 5)
    val sync = NasFacesSync(phone.identity, phone.switch, phone.remote, phone.archives, phone.replicaStore, phone.gate, world.clock, limits = limits)
    for (round in 0 until 4) {
      evil.head(listOf(SegmentClass.FACES to evil.bodies((0 until 3).map { FacesSyncWorld.key(1_000 + round * 10 + it) }).bytes))
      sync.sync(1.hours)
    }
    // Teste successive con chiavi inventate non si sommano oltre il tetto per autore.
    assertThat(phone.archive.bodyTotals(evilDevice.id).bodies).isAtMost(5)
  }

  @Test
  fun r6_conLArchivioPienoIlSegmentoAspettaENonSiPerde() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono")
    val tablet = world.device("Tablet")
    (1..3).forEach { tablet.compute(it) }
    tablet.publisher.flush(FlushReason.UNIT_FINISHED)
    val tight = NasFacesSync(
      phone.identity, phone.switch, phone.remote, phone.archives, phone.replicaStore, phone.gate, world.clock, limits = FacesSyncLimits(maxBodiesTotal = 2),
    )
    val report = tight.sync(1.hours)
    assertThat(report.segmentsRejected["ARCHIVE_FULL"]).isEqualTo(1)
    assertThat(phone.archive.bodyTotals(null).bodies).isEqualTo(0)
    // Con posto (un tetto piu' largo) e dopo l'attesa, il segmento arriva: non e' rifiutato per sempre.
    world.clock.advanceMillis(SegmentWait.FIRST_WAIT_MS)
    phone.round()
    assertThat(phone.archive.bodyTotals(tablet.id).bodies).isEqualTo(3)
  }

  // --- R7: file tolti o letti male dal PC -------------------------------------------------------------------------------------------

  @Test
  fun r7_unMioSegmentoToltoDalPcSiRimette() = runTest {
    val world = newWorld()
    val tablet = world.device("Tablet")
    (1..2).forEach { tablet.compute(it) }
    tablet.edit(PeopleEdit.Name(a, "Anna"))
    assertThat(tablet.publisher.flush(FlushReason.UNIT_FINISHED)).isInstanceOf(FacesPublishOutcome.Published::class.java)
    val dir = NasLayout.facesSegmentDir(world.generation, tablet.id)
    val removed = tablet.share.paths().first { it.isWithin(dir) }
    tablet.share.delete(removed)
    // Il giorno dopo il pubblicatore guarda, trova il buco e lo rimette (nessuna testa nuova: quella di prima lo elenca gia').
    world.clock.advanceMillis(25 * 60 * 60 * 1000L)
    tablet.publisher.flush(FlushReason.SYNC_ROUND)
    assertThat(tablet.share.bytes(removed)).isNotNull()
    assertThat(tablet.publisher.restored).isEqualTo(1)
    val phone = world.device("Telefono")
    phone.round()
    assertThat(phone.archive.value(a, "name")).isEqualTo("Anna")
  }

  @Test
  fun r7_unSegmentoLettoMaleSiRiprovaPrimaDelRifiuto() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono")
    val tablet = world.device("Tablet")
    tablet.edit(PeopleEdit.Name(a, "Anna"))
    tablet.publisher.flush(FlushReason.UNIT_FINISHED)
    val path = tablet.share.paths().first { it.isWithin(NasLayout.facesSegmentDir(world.generation, tablet.id)) }
    val good = tablet.share.bytes(path)!!
    // Dal PC il file viene sostituito con altri byte della stessa lunghezza, poi rimesso com'era.
    tablet.share.plant(path, ByteArray(good.size) { 7 })
    assertThat(phone.round().segmentsRejected[RejectReason.HASH_MISMATCH.name]).isEqualTo(1)
    tablet.share.plant(path, good)
    world.clock.advanceMillis(SegmentWait.FIRST_WAIT_MS)
    phone.round()
    assertThat(phone.archive.value(a, "name")).isEqualTo("Anna")
  }

  // --- R8: il cancello D35 a ogni scrittura --------------------------------------------------------------------------------------------

  @Test
  fun r8_unCongelamentoAMetaPubblicazioneFermaISegmentiRimastiELaTesta() = runTest {
    val world = newWorld()
    val tablet = world.device("Tablet")
    (1..2).forEach { tablet.compute(it) }
    tablet.edit(PeopleEdit.Name(a, "Anna"))
    var asked = 0
    val freezing = FacesWriteGate { if (++asked > 2) FacesWriteBlock.FROZEN else null }
    val publisher = NasFacesPublisher(tablet.identity, tablet.switch, tablet.remote, tablet.archives, tablet.publishStore, tablet.attestation, freezing, world.clock)
    val outcome = publisher.flush(FlushReason.UNIT_FINISHED)
    assertThat(outcome).isEqualTo(FacesPublishOutcome.Blocked(FacesWriteBlock.FROZEN))
    val heads = tablet.share.paths().filter { it.isWithin(NasLayout.facesHeadDir(world.generation, tablet.id)) }
    assertThat(heads).isEmpty()
    // Un solo segmento e' uscito (il cancello era aperto all'inizio e prima del primo segmento).
    assertThat(tablet.share.paths().count { it.isWithin(NasLayout.facesSegmentDir(world.generation, tablet.id)) }).isEqualTo(1)
  }

  // --- I5: errori del database fuori dal `try` ----------------------------------------------------------------------------------------

  @Test
  fun i5_unErroreDelDatabaseNonEsceDalleIstantaneeNeDallaPubblicazione() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono")
    val broken = FacesArchives { throw java.io.IOException("database") }
    val sync = NasFacesSync(phone.identity, phone.switch, phone.remote, broken, phone.replicaStore, phone.gate, world.clock)
    assertThat(sync.latest(world.generation)).isEmpty()
    assertThat(sync.winningSnapshot()).isNull()
    val publisher = NasFacesPublisher(phone.identity, phone.switch, phone.remote, broken, phone.publishStore, phone.attestation, phone.gate, world.clock)
    assertThat(publisher.flush(FlushReason.UNIT_FINISHED)).isEqualTo(FacesPublishOutcome.Failed)
  }
}
