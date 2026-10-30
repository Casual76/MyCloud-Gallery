package dev.pampa.mycloudgallery.core.faces.sync

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesRead
import dev.pampa.mycloudgallery.core.faces.contract.FacesRemote
import dev.pampa.mycloudgallery.core.faces.contract.FacesSegmentCodes
import dev.pampa.mycloudgallery.core.faces.contract.FacesSyncReport
import dev.pampa.mycloudgallery.core.faces.contract.FacesSyncStop
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.data.PeopleDoc
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Correzioni della Fase 8, pacchetto E (C232, C234): i segmenti **in attesa** e l'**archivio pieno** non finiscono piu' nel conto dei segmenti rifiutati: ognuno ha il
 * suo numero (`FACES_SEGMENT_WAITING`, `FACES_ARCHIVE_FULL`), e `FACES_SEGMENT_REJECTED` conta solo i veri rifiuti. Solo numeri e codici. Ogni prova fallisce senza la
 * correzione (il conto dei rifiutati contava tutto: `segmentsRefused` valeva 1 dove ora vale 0).
 */
class FacesCountsCorrectionsETest {

  private val a = FacesSyncWorld.person(1)
  private val b = FacesSyncWorld.person(2)

  private fun report(rejected: Map<String, Int>) = FacesSyncReport(0, 0, emptyMap(), rejected, 0, 0, null, FacesSyncStop.COMPLETE)

  @Test
  fun iCodiciDelContoSonoQuelliCheScriveLaReplica() {
    // I valori sono costanti stabili: se la replica ne cambia uno senza cambiare il contratto, la Diagnostica conterebbe di nuovo tutto fra i rifiutati.
    assertThat(FacesSegmentCodes.FROM_FUTURE).isEqualTo(PeopleVerdict.FROM_FUTURE.code)
    assertThat(FacesSegmentCodes.WAITING).containsExactly("MISSING", "UNREADABLE", PeopleVerdict.FROM_FUTURE.code)
    assertThat(FacesSegmentCodes.ARCHIVE_FULL_CODES).containsExactly("ARCHIVE_FULL", "PEOPLE_FULL")
    // I tetti dell'autore e le impronte sbagliate restano veri rifiuti.
    assertThat(FacesSegmentCodes.AUTHOR_FULL).isNotIn(FacesSegmentCodes.WAITING + FacesSegmentCodes.ARCHIVE_FULL_CODES)
    assertThat(RejectReason.HASH_MISMATCH.name).isNotIn(FacesSegmentCodes.WAITING + FacesSegmentCodes.ARCHIVE_FULL_CODES)
  }

  @Test
  fun ilResocontoDividePerMotivoSenzaPerdereNiente() {
    val r = report(
      mapOf(
        "MISSING" to 2, "UNREADABLE" to 1, PeopleVerdict.FROM_FUTURE.code to 4, "ARCHIVE_FULL" to 3, "PEOPLE_FULL" to 5,
        "AUTHOR_FULL" to 7, RejectReason.HASH_MISMATCH.name to 11, RejectReason.TOO_LARGE.name to 13,
      ),
    )
    assertThat(r.segmentsWaiting).isEqualTo(2 + 1 + 4)
    assertThat(r.segmentsArchiveFull).isEqualTo(3 + 5)
    assertThat(r.segmentsRefused).isEqualTo(7 + 11 + 13)
    assertThat(r.segmentsWaiting + r.segmentsArchiveFull + r.segmentsRefused).isEqualTo(r.segmentsRejected.values.sum())
    assertThat(report(emptyMap()).segmentsRefused).isEqualTo(0)
  }

  @Test
  fun c234_unSegmentoIllegibileEUnAttesaNonUnRifiuto() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono")
    val evil = HostileFacesPublisher(world, world.device("Aaaa-compromesso"))
    val locked = evil.people(PeopleDoc(a.base32(), "name", "Chiuso", world.clock.nasNow!!, evil.deviceId))
    evil.head(listOf(SegmentClass.PEOPLE to locked.bytes))
    val remote = object : FacesRemote by phone.remote {
      override suspend fun readSegment(generation: FaceGeneration, deviceId: String, hash: String, maxBytes: Int): RemoteResult<FacesRead> =
        if (hash == locked.ref.hash) RemoteResult.Failed(RemoteFailure.UNREACHABLE) else phone.remote.readSegment(generation, deviceId, hash, maxBytes)
    }
    val sync = NasFacesSync(phone.identity, phone.switch, remote, phone.archives, phone.replicaStore, phone.gate, world.clock)

    val report = sync.sync(1.hours)

    assertThat(report.stop).isEqualTo(FacesSyncStop.COMPLETE)
    assertThat(report.segmentsWaiting).isEqualTo(1)
    // Senza la correzione tutto questo finiva nel conto dei rifiutati.
    assertThat(report.segmentsRefused).isEqualTo(0)
    assertThat(report.segmentsArchiveFull).isEqualTo(0)
  }

  @Test
  fun c232_conLArchivioPienoIlSegmentoENelSuoContoENonFraIRifiutati() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono")
    val tablet = world.device("Tablet")
    (1..3).forEach { tablet.compute(it) }
    tablet.publisher.flush(FlushReason.UNIT_FINISHED)
    val tight = NasFacesSync(
      phone.identity, phone.switch, phone.remote, phone.archives, phone.replicaStore, phone.gate, world.clock, limits = FacesSyncLimits(maxBodiesTotal = 2),
    )

    val report = tight.sync(1.hours)

    assertThat(report.segmentsArchiveFull).isEqualTo(1)
    assertThat(report.segmentsRefused).isEqualTo(0)
    assertThat(report.segmentsWaiting).isEqualTo(0)
  }

  @Test
  fun unVeroRifiutoRestaFraIRifiutati() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono")
    val tablet = world.device("Tablet")
    tablet.edit(PeopleEdit.Name(b, "Anna"))
    tablet.publisher.flush(FlushReason.UNIT_FINISHED)
    val path = tablet.share.paths().first { it.isWithin(NasLayout.facesSegmentDir(world.generation, tablet.id)) }
    val good = tablet.share.bytes(path)!!
    // Dal PC il file e' sostituito con altri byte della stessa lunghezza: l'impronta non torna. Si riprova (e si dice), ma e' un rifiuto, non un'attesa.
    tablet.share.plant(path, ByteArray(good.size) { 7 })

    val report = phone.round()

    assertThat(report.segmentsRejected[RejectReason.HASH_MISMATCH.name]).isEqualTo(1)
    assertThat(report.segmentsRefused).isEqualTo(1)
    assertThat(report.segmentsWaiting).isEqualTo(0)
  }
}
