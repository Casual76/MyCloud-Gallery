package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La pulizia dei propri file dei volti (`FacesOwnFiles`, formato §2.3, A8) e la radice delle prenotazioni sotto la generazione (`LeaseRoot`,
 * A5, D16): Fase 8, P3.
 */
class FacesOwnFilesAndLeasesTest {

  private val me = TrustIds.random()
  private val other = TrustIds.random()
  private val g = FaceGeneration.fromBytes(ByteArray(16) { 5 })
  private val old = FaceGeneration.fromBytes(ByteArray(16) { 6 })
  private val hash = Hashes.sha256Name("x".toByteArray())
  private val day = 24L * 60 * 60 * 1000
  private val now = 1_800_000_000_000L
  private val policy = FacesOwnFilesPolicy(me, g)

  @Test
  fun ilPostoSiRiconosceSoloNellaFormaEsatta() {
    assertThat(FacesOwnFile.classify(NasLayout.facesHead(g, me, 3))).isEqualTo(FacesOwnFile.Head(NasLayout.facesHead(g, me, 3), g, me, 3))
    assertThat(FacesOwnFile.classify(NasLayout.facesSegment(g, me, hash))).isInstanceOf(FacesOwnFile.Segment::class.java)
    val resource = NasLayout.facesClusterResource(g)
    assertThat(FacesOwnFile.classify(NasLayout.facesLeaseFile(g, resource, 300))).isInstanceOf(FacesOwnFile.LeaseGeneration::class.java)
    // Il gruppo sbagliato per il numero, nomi strani, cartelle di altri, file fuori da faces/.
    val wrongShard = NasLayout.facesLeaseDir(g, resource).child(NasLayout.versionName(1)).child(NasLayout.versionName(300))
    for (path in listOf(
      wrongShard,
      NasLayout.facesHeadDir(g, me).child("spazzatura"),
      NasLayout.facesDeviceDir(g, me).child("altro").child(hash),
      NasLayout.facesGenerationDir(g).child("non-un-id").child("head").child(NasLayout.versionName(1)),
      NasLayout.FACES_DIR.child("NON-UNA-GENERAZIONE").child(me).child("head").child(NasLayout.versionName(1)),
      NasLayout.headFile(me, "personal", 1),
      SharePath.parseMediaPath("Foto/IMG_1.jpg"),
    )) {
      assertThat(FacesOwnFile.classify(path)).isNull()
    }
  }

  @Test
  fun leRegoleDiM6SoloNellaGenerazioneCorrente() {
    fun head(v: Long, gen: FaceGeneration = g, device: String = me) = FacesOwnFile.Head(NasLayout.facesHead(gen, device, v), gen, device, v)
    val versions = setOf(1L, 2L, 3L, 4L)
    assertThat(policy.decide(FacesOwnFileFacts.HeadFacts(head(1), true, versions), now)).isEqualTo(OwnFileDecision.Remove)
    assertThat(policy.decide(FacesOwnFileFacts.HeadFacts(head(2), true, versions), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.RECENT_HEAD))
    assertThat(policy.decide(FacesOwnFileFacts.HeadFacts(head(1), false, versions), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_SIGNED_BY_ME))
    assertThat(policy.decide(FacesOwnFileFacts.HeadFacts(head(1, device = other), true, versions), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_MINE))
    // Una generazione non corrente non si pulisce file per file: si toglie intera con la regola di D31.
    assertThat(policy.decide(FacesOwnFileFacts.HeadFacts(head(1, gen = old), true, versions), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_MINE))

    val segment = FacesOwnFile.Segment(NasLayout.facesSegment(g, me, hash), g, me, hash)
    assertThat(policy.decide(FacesOwnFileFacts.SegmentFacts(segment, false, true, now - 8 * day), now)).isEqualTo(OwnFileDecision.Remove)
    assertThat(policy.decide(FacesOwnFileFacts.SegmentFacts(segment, true, true, now - 8 * day), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.LISTED))
    assertThat(policy.decide(FacesOwnFileFacts.SegmentFacts(segment, false, false, now - 8 * day), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_IN_DIARY))
    assertThat(policy.decide(FacesOwnFileFacts.SegmentFacts(segment, false, true, now - 6 * day), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.TOO_RECENT))
    assertThat(policy.decide(FacesOwnFileFacts.SegmentFacts(segment, false, true, null), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.AGE_UNKNOWN))
    assertThat(policy.decide(FacesOwnFileFacts.SegmentFacts(segment, false, true, now + day), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.TIME_IN_FUTURE))

    val resource = NasLayout.facesClusterResource(g)
    fun lease(n: Long) = FacesOwnFile.LeaseGeneration(NasLayout.facesLeaseFile(g, resource, n), g, NasLayout.resourceName(resource), n)
    assertThat(policy.decide(FacesOwnFileFacts.LeaseFacts(lease(3), true, 4), now)).isEqualTo(OwnFileDecision.Remove)
    assertThat(policy.decide(FacesOwnFileFacts.LeaseFacts(lease(4), true, 4), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.HIGHEST_GENERATION))
    assertThat(policy.decide(FacesOwnFileFacts.LeaseFacts(lease(3), false, 4), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_SIGNED_BY_ME))
    // Un fatto con un percorso che non torna con il tipo dichiarato non rende il file mio.
    val forged = FacesOwnFile.Head(NasLayout.facesHead(g, me, 9), g, me, 1)
    assertThat(policy.decide(FacesOwnFileFacts.HeadFacts(forged, true, versions), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_MINE))
    // Sospesa: niente si toglie; al piu' MAX_OWN_FILES_PER_ROUND per giro.
    val facts = (1L..TrustLimits.MAX_OWN_FILES_PER_ROUND + 10L).map { FacesOwnFileFacts.HeadFacts(head(it), true, (1L..1_000L).toSet()) }
    assertThat(policy.plan(facts, now, suspended = true).remove).isEmpty()
    assertThat(policy.plan(facts, now, suspended = false).remove).hasSize(TrustLimits.MAX_OWN_FILES_PER_ROUND)
  }

  /** A5, D16: le prenotazioni dei volti stanno sotto `faces/<G>/leases/`, con le stesse regole di quelle della share. */
  @Test
  fun lePrenotazioniDeiVoltiStannoSottoLaGenerazione() {
    val family = TestFamily()
    val phone = family.approveNewDevice(name = "Telefono", nasUser = "test")
    val tablet = family.approveNewDevice(name = "Tablet", nasUser = "test")
    val resource = NasLayout.facesWorkResource(g, NasLayout.FACES_AREA_PRIVATE, "2024-03", 2)
    val mine = LeaseManager(family.common, phone, family.clock, LeaseRoot.Faces(g))
    val theirs = LeaseManager(family.common, tablet, family.clock, LeaseRoot.Faces(g))
    val acquired = mine.tryAcquire(family.state, resource, 60 * 60 * 1000L)
    assertThat(acquired).isInstanceOf(AcquireResult.Acquired::class.java)
    assertThat(theirs.tryAcquire(family.state, resource, 60 * 60 * 1000L)).isEqualTo(AcquireResult.Busy(phone.deviceId, (acquired as AcquireResult.Acquired).until))
    val written = family.common.paths().filter { it.contains("/faces/") }
    assertThat(written).containsExactly(NasLayout.facesLeaseFile(g, resource, 1).value)
    assertThat(family.common.paths().none { it.startsWith(".mycloudgallery/leases/") }).isTrue()
    // Il rinnovo (una generazione nuova) e il rilascio restano sotto la generazione.
    assertThat(mine.tryAcquire(family.state, resource, 60 * 60 * 1000L)).isInstanceOf(AcquireResult.Acquired::class.java)
    mine.release(family.state, resource)
    assertThat(theirs.tryAcquire(family.state, resource, 60 * 60 * 1000L)).isInstanceOf(AcquireResult.Acquired::class.java)
    assertThat(family.common.paths().filter { it.contains("/faces/") }.all { it.startsWith(NasLayout.facesLeaseRoot(g).value + "/") }).isTrue()
    // Una risorsa di un'altra generazione, o della share, non si prenota sotto questa radice (e il contrario).
    assertThrows(IllegalArgumentException::class.java) { mine.tryAcquire(family.state, NasLayout.facesClusterResource(old), 1_000) }
    assertThrows(IllegalArgumentException::class.java) { mine.tryAcquire(family.state, "work/1/ai/2024-03/2", 1_000) }
    assertThrows(IllegalArgumentException::class.java) {
      LeaseManager(family.common, phone, family.clock).tryAcquire(family.state, NasLayout.facesClusterResource(g), 1_000)
    }
  }
}
