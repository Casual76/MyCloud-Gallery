package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import dev.pampa.mycloudgallery.core.trust.testing.newSigner
import org.junit.Assert.assertThrows
import org.junit.Test

class LeaseTest {

  private val family = TestFamily()
  private val phone = family.approveNewDevice(name = "Telefono", nasUser = "babbo")
  private val tablet = family.approveNewDevice(name = "Tablet", nasUser = "mamma")
  private val phoneLeases = LeaseManager(family.common, phone, family.clock)
  private val tabletLeases = LeaseManager(family.common, tablet, family.clock)
  private val resource = "index:Public:batch-0042"
  private val quarterHour = 15L * 60 * 1000

  @Test
  fun dueIndicizzatoriSullaStessaRisorsa_unoSoloLaPrende() {
    val first = phoneLeases.tryAcquire(family.state, resource, quarterHour)
    val second = tabletLeases.tryAcquire(family.state, resource, quarterHour)

    assertThat(first).isEqualTo(AcquireResult.Acquired(1, family.clock.now + quarterHour))
    assertThat(second).isEqualTo(AcquireResult.Busy(phone.deviceId, family.clock.now + quarterHour))
  }

  @Test
  fun scadenzaSullOraDelNas_ilDispositivoMortoLiberaLaRisorsa() {
    phoneLeases.tryAcquire(family.state, resource, quarterHour)

    family.clock.advance(quarterHour - 1)
    assertThat(tabletLeases.tryAcquire(family.state, resource, quarterHour)).isInstanceOf(AcquireResult.Busy::class.java)

    family.clock.advance(1)
    assertThat(tabletLeases.tryAcquire(family.state, resource, quarterHour)).isEqualTo(AcquireResult.Acquired(2, family.clock.now + quarterHour))
  }

  @Test
  fun rilascio_laRisorsaTornaLibera() {
    phoneLeases.tryAcquire(family.state, resource, quarterHour)
    phoneLeases.release(family.state, resource)

    assertThat(tabletLeases.tryAcquire(family.state, resource, quarterHour)).isEqualTo(AcquireResult.Acquired(3, family.clock.now + quarterHour))
  }

  @Test
  fun garaSullaStessaGenerazione_vinceChiPubblicaPrima() {
    // Il tablet ha letto "libera, generazione 0" e nel frattempo il telefono ha pubblicato la 1.
    phoneLeases.tryAcquire(family.state, resource, quarterHour)
    val lateLease = Lease(family.familyId, tablet.deviceId, "Public", resource, 1, family.clock.now, quarterHour)

    val result = family.common.publishExclusive(NasLayout.leaseFile(resource, 1), lateLease.sign(tablet))

    assertThat(result).isEqualTo(dev.pampa.mycloudgallery.core.trust.nas.PublishResult.ALREADY_EXISTS)
  }

  @Test
  fun prenotazioniConScadenzaAssurdaIgnorate() {
    val now = family.clock.now
    fun policy(acquiredAt: Long, duration: Long) =
      LeasePolicy.evaluate(Lease(family.familyId, phone.deviceId, "Public", resource, 1, acquiredAt, duration), now, tablet.deviceId)

    assertThat(policy(now, TrustLimits.MAX_LEASE_MILLIS)).isInstanceOf(LeaseStatus.HeldByOther::class.java)
    assertThat(policy(now, TrustLimits.MAX_LEASE_MILLIS + 1)).isEqualTo(LeaseStatus.Free) // oltre il tetto: ignorata
    assertThat(policy(now, Long.MAX_VALUE)).isEqualTo(LeaseStatus.Free)
    assertThat(policy(now + 365L * 24 * 3600 * 1000, quarterHour)).isEqualTo(LeaseStatus.Free) // inizia fra un anno
    assertThat(policy(now + TrustLimits.MAX_FUTURE_SKEW_MILLIS, quarterHour)).isInstanceOf(LeaseStatus.HeldByOther::class.java)
    assertThat(policy(now, 0)).isEqualTo(LeaseStatus.Free) // rilasciata
  }

  @Test
  fun unaPrenotazioneAssurdaScrittaSulNasNonBloccaNessuno() {
    val absurd = Lease(family.familyId, phone.deviceId, "Public", resource, 1, family.clock.now, 30L * 24 * 3600 * 1000)
    family.common.publishExclusive(NasLayout.leaseFile(resource, 1), absurd.sign(phone))

    assertThat(tabletLeases.tryAcquire(family.state, resource, quarterHour)).isEqualTo(AcquireResult.Acquired(2, family.clock.now + quarterHour))
  }

  @Test
  fun lOrologioDelDispositivoNonConta() {
    // Il telefono ha l'orologio un anno avanti (E-P9), ma firma con l'ora del NAS: non c'e' altro orologio.
    phoneLeases.tryAcquire(family.state, resource, quarterHour)
    family.clock.advance(quarterHour / 2)

    assertThat(tabletLeases.inspect(family.state, resource)!!.first).isInstanceOf(LeaseStatus.HeldByOther::class.java)
  }

  @Test
  fun prenotazioniDiDispositiviRevocatiNonFirmateOCopiateIgnorate() {
    phoneLeases.tryAcquire(family.state, resource, quarterHour)
    family.changeStatus(phone, DeviceStatus.REVOKED)
    val bytes = family.common.bytesAt(NasLayout.leaseFile(resource, 1))!!

    assertThat(Leases.verify(bytes, family.state, "Public", resource, 1).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_REVOKED)
    assertThat(tabletLeases.tryAcquire(family.state, resource, quarterHour)).isInstanceOf(AcquireResult.Acquired::class.java)

    // Spazzatura e copie in altre posizioni.
    val other = "index:Public:batch-0043"
    family.common.attackerWrite(NasLayout.leaseFile(other, 1).value, "spazzatura".toByteArray())
    assertThat(tabletLeases.tryAcquire(family.state, other, quarterHour)).isEqualTo(AcquireResult.Acquired(2, family.clock.now + quarterHour))

    val third = "index:Public:batch-0044"
    family.common.attackerWrite(NasLayout.leaseFile(third, 1).value, family.common.bytesAt(NasLayout.leaseFile(resource, 2))!!)
    assertThat(Leases.verify(family.common.bytesAt(NasLayout.leaseFile(third, 1))!!, family.state, "Public", third, 1).rejectionOrNull())
      .isEqualTo(RejectReason.WRONG_LOCATION)
  }

  @Test
  fun dispositivoNonApprovatoNonPrenota() {
    val stranger = LeaseManager(family.common, newSigner(), family.clock)

    assertThrows(IllegalStateException::class.java) { stranger.tryAcquire(family.state, resource, quarterHour) }
    assertThrows(IllegalArgumentException::class.java) { phoneLeases.tryAcquire(family.state, resource, TrustLimits.MAX_LEASE_MILLIS + 1) }
  }

  @Test
  fun cartellaDellePrenotazioniInondata_siLavoraSenza() {
    repeat(TrustLimits.MAX_LIST_LEASE_GENERATIONS + 1) {
      family.common.attackerWrite(NasLayout.leaseFile(resource, (it + 1).toLong()).value, ByteArray(1))
    }

    assertThat(phoneLeases.tryAcquire(family.state, resource, quarterHour)).isEqualTo(AcquireResult.Contended)
  }

  @Test
  fun generazioneAlLimite_siLavoraSenza() {
    family.common.attackerWrite(NasLayout.leaseFile(resource, 999_999_999L).value, ByteArray(1))

    assertThat(phoneLeases.tryAcquire(family.state, resource, quarterHour)).isEqualTo(AcquireResult.Contended)
  }
}
