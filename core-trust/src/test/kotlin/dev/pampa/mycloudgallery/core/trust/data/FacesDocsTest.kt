package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il codec dei dati dei volti, la parte che deve funzionare (Fase 8, P3; `docs/fase8-progetto.md` §2.4, D12, D21): ogni forma di corpo, voce
 * e testa si rilegge uguale; l'impronta di un corpo sono le prime 8 byte dello SHA-256 dei suoi byte (`FaceRef.bodyDigest`, il punto aperto
 * di P0); i firmatari ammessi (approvato, dismesso con la testa congelata) e quelli no; anti-rollback ed equivocazione. I casi ostili per
 * campo stanno in [FacesHostileTest].
 */
class FacesDocsTest {

  private val fx = FacesFx()

  @Test
  fun ogniCorpoSiRilegge_eLImprontaSonoLePrime8ByteDelloSha256() {
    val bodies = listOf(
      fx.body(),
      fx.body(emptyList()),
      fx.videoBody(),
      fx.body((0 until 64).map { fx.face(it % 60) }.mapIndexed { i, f -> FaceRecord(f.frame, f.box, f.landmarks, i % 256, f.tier, 0, f.sidePx, 90 - i, f.sharpness, f.vector) }, recipe = 255),
    )
    for (body in bodies) {
      val bytes = FacesDocs.encodeBody(body)
      assertThat(FacesDocs.decodeBody(bytes).acceptedOrNull()).isEqualTo(body)
      val expected = ByteBuffer.wrap(Hashes.sha256(bytes)).long
      assertThat(FacesDocs.bodyDigest(bytes)).isEqualTo(expected)
      assertThat(FacesDocs.digestOf(body)).isEqualTo(expected)
    }
    // Il corpo piu' grande possibile: 6 + 64 volti CORE da 291 byte = 18.630 byte, sotto il tetto di 20 KiB.
    assertThat(FacesDocs.encodeBody(bodies[3]).size).isEqualTo(FacesLimits.MAX_BODY_BYTES)
    assertThat(FacesLimits.MAX_BODY_BYTES).isEqualTo(18_630)
    assertThat(FacesLimits.MAX_BODY_BYTES).isAtMost(TrustLimits.MAX_FACES_BODY_BYTES)
    // Un corpo diverso ha un'impronta diversa (un volto in piu', una ricetta diversa).
    assertThat(FacesDocs.digestOf(fx.body(recipe = 2))).isNotEqualTo(FacesDocs.digestOf(fx.body()))
  }

  @Test
  fun iSegmentiDelleTreClassiSiRileggono() {
    val items = (1..20).map { fx.key(it) to fx.body(recipe = 1 + it % 3) }
    val bodies = FacesDocs.bodiesSegment(items)
    val read = FacesDocs.decodeSegment(bodies.bytes, bodies.ref, SegmentClass.FACES).acceptedOrNull() as FacesSegmentContent.Bodies
    assertThat(read.entries.map { it.contentKey }).containsExactlyElementsIn(items.map { it.first })
    for (entry in read.entries) {
      val body = items.first { it.first == entry.contentKey }.second
      assertThat(entry.body).isEqualTo(body)
      assertThat(entry.digest).isEqualTo(FacesDocs.digestOf(body))
    }
    val cluster = FacesDocs.clusterSegment(fx.snapshotEntries())
    val entries = (FacesDocs.decodeSegment(cluster.bytes, cluster.ref, SegmentClass.CLUSTER).acceptedOrNull() as FacesSegmentContent.Cluster).entries
    assertThat(entries).containsExactlyElementsIn(fx.snapshotEntries())
    val people = FacesDocs.peopleSegment(fx.everyPeople())
    val docs = (FacesDocs.decodeSegment(people.bytes, people.ref, SegmentClass.PEOPLE).acceptedOrNull() as FacesSegmentContent.People).entries
    assertThat(docs).containsExactlyElementsIn(fx.everyPeople())
    // Lo stesso contenuto in un altro ordine e' lo stesso segmento (stessa impronta): la pubblicazione e' stabile.
    assertThat(FacesDocs.peopleSegment(fx.everyPeople().reversed()).ref).isEqualTo(people.ref)
    // Chiavi ripetute o nessuna voce: errore di chi chiama, mai un segmento che gli altri rifiuterebbero.
    assertThrows(IllegalArgumentException::class.java) { FacesDocs.bodiesSegment(listOf(fx.key(1) to fx.body(), fx.key(1) to fx.body(recipe = 2))) }
    assertThrows(IllegalArgumentException::class.java) { FacesDocs.peopleSegment(emptyList()) }
  }

  @Test
  fun lIstantaneaCoerenteSiAccetta() {
    val doc = FacesDocs.checkSnapshot(SnapshotMark(3, 2), fx.snapshotEntries().shuffled()).acceptedOrNull()!!
    assertThat(doc.persons.map { it.id }).isEqualTo(doc.persons.map { it.id }.sorted())
    assertThat(doc.assignments).hasSize(5)
    assertThat(doc.mark).isEqualTo(SnapshotMark(3, 2))
  }

  @Test
  fun laTestaSiRilegge_conESenzaIstantanea_eLAccountInMinuscolo() {
    val people = FacesDocs.peopleSegment(fx.everyPeople())
    val cluster = FacesDocs.clusterSegment(fx.snapshotEntries())
    val segments = listOf(people.classed, cluster.classed).sortedBy { it.ref.hash }
    val encoded = fx.head(segments, snapshot = SnapshotMark(3, 2))
    val accepted = fx.accept(encoded.bytes).acceptedOrNull()!!
    assertThat(accepted.head.segments).isEqualTo(segments)
    assertThat(accepted.head.snapshot).isEqualTo(SnapshotMark(3, 2))
    assertThat(accepted.mark).isEqualTo(encoded.mark)
    assertThat(accepted.signer.id).isEqualTo(fx.phone.deviceId)
    // Il tablet ha `Test` nel registro: la testa dice `test`, e chi legge come `test` (o `TEST`) la accetta.
    val tablet = fx.head(signer = fx.tablet)
    assertThat(fx.accept(tablet.bytes, signer = fx.tablet).acceptedOrNull()!!.head.account).isEqualTo("test")
    assertThat(fx.accept(tablet.bytes, signer = fx.tablet, reader = "TEST").acceptedOrNull()).isNotNull()
    // Una testa senza segmenti (un dispositivo che non ha ancora niente) e' valida.
    assertThat(fx.accept(fx.head().bytes).acceptedOrNull()!!.head.segments).isEmpty()
  }

  /** Dismesso: valgono solo le teste congelate. Revocato, perso o sconosciuto: mai. */
  @Test
  fun firmatariAmmessiENo() {
    val frozen = fx.head(signer = fx.tablet)
    val later = fx.head(version = 2, signer = fx.tablet)
    fx.family.changeStatus(fx.tablet, DeviceStatus.RETIRED, listOf(frozen.mark.contentHash))
    assertThat(fx.accept(frozen.bytes, signer = fx.tablet).acceptedOrNull()).isNotNull()
    assertThat(fx.accept(later.bytes, version = 2, signer = fx.tablet).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_RETIRED)
    val phoneHead = fx.head()
    fx.family.changeStatus(fx.phone, DeviceStatus.REVOKED)
    assertThat(fx.accept(phoneHead.bytes).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_REVOKED)
    val otherHead = fx.head(signer = fx.other)
    fx.family.changeStatus(fx.other, DeviceStatus.LOST)
    assertThat(fx.accept(otherHead.bytes, signer = fx.other, reader = "test2").rejectionOrNull()).isEqualTo(RejectReason.SIGNER_REVOKED)
    val stranger = dev.pampa.mycloudgallery.core.trust.testing.newSigner()
    val strangerHead = FacesDocs.encodeHead(
      FacesHead(fx.family.familyId, "test", fx.generation, stranger.deviceId, 1, 1, fx.now, null, emptyList()),
      stranger,
    )
    assertThat(fx.accept(strangerHead.bytes, signer = stranger).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_NOT_APPROVED)
  }

  /** D11, C37: una testa di un altro account non si legge, nemmeno messa nella mia area. */
  @Test
  fun leTesteDiUnAltroAccountNonSiLeggono() {
    val foreign = fx.head(signer = fx.other)
    assertThat(fx.accept(foreign.bytes, signer = fx.other, reader = "test").rejectionOrNull()).isEqualTo(RejectReason.NOT_OWNER)
    assertThat(fx.accept(foreign.bytes, signer = fx.other, reader = "test2").acceptedOrNull()).isNotNull()
  }

  @Test
  fun antiRollbackEdEquivocazione() {
    val v2 = fx.head(version = 2)
    val mark = fx.accept(v2.bytes, version = 2).acceptedOrNull()!!.mark
    assertThat(fx.accept(fx.head(version = 1).bytes, version = 1, previous = mark).rejectionOrNull()).isEqualTo(RejectReason.ROLLBACK)
    val other = fx.head(version = 2, createdAt = fx.now - 1_000)
    assertThat(fx.accept(other.bytes, version = 2, previous = mark).rejectionOrNull()).isEqualTo(RejectReason.EQUIVOCATION)
    // La stessa testa riletta va bene; una piu' nuova pure.
    assertThat(fx.accept(v2.bytes, version = 2, previous = mark).acceptedOrNull()).isNotNull()
    assertThat(fx.accept(fx.head(version = 3).bytes, version = 3, previous = mark).acceptedOrNull()).isNotNull()
  }

  @Test
  fun leRegoleDelLivelloSonoQuelleDelFormato() {
    // CORE senza motivi, WEAK e NONE con o senza; i motivi del fotogramma solo nei video.
    assertThat(fx.face(0, FaceTier.WEAK, reasons = 0).reasons).isEqualTo(0)
    assertThat(fx.face(0, FaceTier.NONE, reasons = FaceReasons.SMALL or FaceReasons.PROFILE).tier).isEqualTo(FaceTier.NONE)
    assertThat(FacesDocs.decodeBody(FacesDocs.encodeBody(fx.videoBody()))).isInstanceOf(Verdict.Accepted::class.java)
    for (entry in fx.everyPeople()) assertWithMessage(entry.field).that(PeopleForms.fits(entry.subject, entry.field, entry.value)).isTrue()
  }
}
