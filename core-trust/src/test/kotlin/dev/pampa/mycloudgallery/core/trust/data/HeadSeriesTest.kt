package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import dev.pampa.mycloudgallery.core.trust.testing.newSigner
import org.junit.Assert.assertThrows
import org.junit.Test

/** Le serie di teste per nome (§5.5 M1, M3): salti doppi, bisezione, spazzatura, buchi, rollback, pubblicazione. */
class HeadSeriesTest {

  private val family = TestFamily()
  private val tablet = family.approveNewDevice(name = "Tablet", nasUser = "mamma")
  private val fs = family.common
  private val segment = IndexSegments.meta(listOf(TestRecords.present("Foto/a.jpg", 1)))

  private fun head(version: Long, signer: Signer = tablet, segments: List<ClassedSegment> = listOf(segment.classed)): ByteArray =
    DeviceHead(family.familyId, signer.deviceId, "Public", HeadKind.INDEX, version, family.state.registry.version, family.clock.now, segments)
      .sign(signer)

  private fun path(version: Long, device: String = tablet.deviceId) = NasLayout.headFile(device, "index", version)

  private fun publishUpTo(last: Long, from: Long = 1) = (from..last).forEach { fs.publishExclusive(path(it), head(it)) }

  private fun read(previous: HeadMark?, hint: Boolean = previous == null, budget: HeadReadBudget = HeadReadBudget()) =
    HeadSeries.readLatest(fs, family.state, tablet.deviceId, HeadKind.INDEX, previous, "babbo", budget, hint)

  private fun accepted(scan: HeadScan): AcceptedHead = (scan.result as HeadRead.Accepted).head

  @Test
  fun nessunaNovita_unaSolaLettura() {
    publishUpTo(3)
    val mark = accepted(read(null)).mark

    val scan = read(mark)

    assertThat(scan.result).isEqualTo(HeadRead.NoChange)
    assertThat(scan.reads).isEqualTo(1)
    assertThat(read(null, hint = false).let { accepted(it).head.version }).isEqualTo(3)
  }

  @Test
  fun ritardoLungo_saltiDoppiEBisezioneConPocheLetture() {
    publishUpTo(5)
    val mark5 = accepted(read(null)).mark
    publishUpTo(300, from = 6)

    val fromStart = read(null, hint = false)
    val fromFive = read(mark5)

    assertThat(accepted(fromStart).head.version).isEqualTo(300)
    assertThat(fromStart.reads).isAtMost(25)
    assertThat(accepted(fromFive).head.version).isEqualTo(300)
    assertThat(fromFive.reads).isAtMost(25)
  }

  @Test
  fun spazzaturaInCima_siTornaIndietroFinoAllUltimaValida() {
    publishUpTo(3)
    fs.attackerWrite(path(4).value, "spazzatura".toByteArray())
    fs.attackerWrite(path(5).value, head(5, signer = newSigner()))

    val scan = read(HeadMark(1, "x"), hint = false)

    assertThat(accepted(scan).head.version).isEqualTo(3)
    assertThat(scan.rejected.count(RejectReason.MALFORMED)).isEqualTo(1)
    assertThat(scan.rejected.count(RejectReason.SIGNER_NOT_APPROVED)).isEqualTo(1)
  }

  @Test
  fun troppaSpazzaturaDiFila_lettoreFermoEScrittoreConLElenco() {
    publishUpTo(1)
    val mark1 = accepted(read(null)).mark
    (2L..40L).forEach { fs.attackerWrite(path(it).value, "x$it".toByteArray()) }

    val scan = read(mark1)
    val published = HeadSeries.publish(fs, family.state, tablet, HeadKind.INDEX, after = 1, readerAccount = "mamma") { head(it) }

    assertThat(scan.result).isEqualTo(HeadRead.Rejected(RejectReason.MALFORMED))
    assertThat(scan.rejected.total).isEqualTo(TrustLimits.MAX_JUNK_RUN + 1)
    assertThat((published as HeadPublish.Blocked).junk).hasSize(TrustLimits.MAX_JUNK_RUN + 1)
  }

  @Test
  fun unBuco_lIndizioDellElencoLoScavalca() {
    publishUpTo(3)
    val mark3 = accepted(read(null)).mark
    publishUpTo(6, from = 4)
    fs.attackerDelete(path(4))

    assertThat(read(mark3, hint = false).result).isEqualTo(HeadRead.NoChange)
    assertThat(accepted(read(mark3, hint = true)).head.version).isEqualTo(6)
  }

  @Test
  fun testaGiaAccettataSparita_rollback() {
    publishUpTo(3)
    val mark3 = accepted(read(null)).mark
    fs.attackerDelete(path(3))
    fs.attackerDelete(path(2))

    assertThat(read(mark3, hint = true).result).isEqualTo(HeadRead.Rejected(RejectReason.ROLLBACK))
    assertThat(read(mark3, hint = false).result).isEqualTo(HeadRead.NoChange)
  }

  @Test
  fun testeCopiateRevocateOFuoriBudget() {
    val phone = family.approveNewDevice(name = "Telefono", nasUser = "babbo")
    fs.attackerWrite(path(1).value, head(1, signer = phone))
    assertThat(read(null).result).isEqualTo(HeadRead.Rejected(RejectReason.WRONG_LOCATION))

    fs.attackerDelete(path(1))
    publishUpTo(50)
    assertThat(read(null, budget = HeadReadBudget(3)).result).isEqualTo(HeadRead.OutOfBudget)

    family.changeStatus(tablet, DeviceStatus.REVOKED)
    assertThat(read(null).result).isEqualTo(HeadRead.Rejected(RejectReason.SIGNER_REVOKED))
  }

  @Test
  fun pubblicazione_primoNomeLiberoEPropriaTestaGiaPresente() {
    fs.attackerWrite(path(1).value, "spazzatura".toByteArray())
    fs.attackerWrite(path(2).value, head(2, signer = newSigner()))

    val first = HeadSeries.publish(fs, family.state, tablet, HeadKind.INDEX, after = 0, readerAccount = "mamma") { head(it) }
    assertThat(first).isInstanceOf(HeadPublish.Published::class.java)
    first as HeadPublish.Published
    assertThat(first.version).isEqualTo(3)
    assertThat(first.skipped).hasSize(2)
    assertThat(accepted(read(null)).mark).isEqualTo(first.mark)

    // Un crash dopo la pubblicazione: lo stato locale dice ancora "2", sul NAS c'e' gia' la mia 3.
    val again = HeadSeries.publish(fs, family.state, tablet, HeadKind.INDEX, after = 2, readerAccount = "mamma") { head(it) }
    assertThat((again as HeadPublish.ExistingOwn).version).isEqualTo(3)
    assertThat(again.head.head.refs).containsExactly(segment.ref)
  }

  @Test
  fun classiNelleTeste_M1() {
    val thumbs = IndexSegments.blobs(SegmentClass.THUMB_S, listOf(TestRecords.key(1) to TestImages.webp(200, 150)))
    val both = listOf(segment.classed, thumbs.classed).sortedBy { it.ref.hash }
    fs.publishExclusive(path(1), head(1, segments = both))
    assertThat(accepted(read(null)).head.segments).containsExactlyElementsIn(both).inOrder()

    // Una testa index non elenca segmenti personal (e viceversa): rifiutata gia' da chi la costruisce.
    assertThrows(Exception::class.java) { head(2, segments = listOf(ClassedSegment(SegmentClass.PERSONAL, segment.ref))) }

    fun signed(entry: Map<String, String>): ByteArray = Envelope.seal(
      DocType.DEVICE_HEAD,
      jsonObject(
        "family" to family.familyId.json(),
        "device" to tablet.deviceId.json(),
        "share" to "Public".json(),
        "kind" to "index".json(),
        "version" to 7.json(),
        "registryVersion" to family.state.registry.version.json(),
        "createdAt" to family.clock.now.json(),
        "segments" to listOf(jsonObject(*(entry.map { it.key to it.value.json() } + ("size" to segment.ref.size.json())).toTypedArray())).json(),
      ),
      tablet,
    )
    val location = HeadLocation("Public", tablet.deviceId, HeadKind.INDEX, 7)
    fun verdict(entry: Map<String, String>) = HeadVerifier.accept(signed(entry), family.state, location, null, "babbo")

    assertThat(verdict(mapOf("class" to "meta", "hash" to segment.ref.hash)).acceptedOrNull()).isNotNull()
    assertThat(verdict(mapOf("class" to "vec", "hash" to segment.ref.hash)).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(verdict(mapOf("class" to "ocr", "hash" to segment.ref.hash)).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(verdict(mapOf("class" to "personal", "hash" to segment.ref.hash)).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(verdict(mapOf("hash" to segment.ref.hash)).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun dispositiviDellaShare_soloQuelliDelRegistro_ancheConLaCartellaInondata() {
    val phone = family.approveNewDevice(name = "Telefono", nasUser = "babbo")
    val revoked = family.approveNewDevice(name = "Vecchio", nasUser = "babbo")
    val stranger = newSigner()
    for (signer in listOf(tablet, phone, revoked, stranger)) fs.publishExclusive(path(1, signer.deviceId), head(1, signer))
    fs.attackerWrite(NasLayout.ROOT.child("devices").child("non-un-id").child("x").value, ByteArray(1))
    family.changeStatus(revoked, DeviceStatus.REVOKED)

    val listed = HeadSeries.devices(fs, family.state)
    assertThat(listed.ids.toSet()).isEqualTo(setOf(tablet.deviceId, phone.deviceId))
    assertThat(listed.ignored).isEqualTo(3)

    fs.attackerFlood(NasLayout.ROOT.child("devices"), TrustLimits.MAX_INDEX_DEVICES_PER_SHARE + 10)
    val flooded = HeadSeries.devices(fs, family.state)
    assertThat(flooded.listingTruncated).isTrue()
    assertThat(flooded.ids).containsAtLeast(tablet.deviceId, phone.deviceId)
    assertThat(flooded.ids).doesNotContain(revoked.deviceId)
  }
}
