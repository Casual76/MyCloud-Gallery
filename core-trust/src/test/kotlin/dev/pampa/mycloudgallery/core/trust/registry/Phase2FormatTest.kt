package dev.pampa.mycloudgallery.core.trust.registry

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryCode
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKit
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.FakeNasFileSystem
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import dev.pampa.mycloudgallery.core.trust.testing.newSigner
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * L'estensione del formato v1 della Fase 2 (`docs/formato-nas.md` §5.3, §5.9, §5.10, §4.10): configurazione
 * di famiglia completa, segnalazione "perso" (tipo 7), stato del dispositivo (tipo 8), rifondazione dopo un reset.
 */
class Phase2FormatTest {

  private val family = TestFamily()

  private val fullConfig = FamilyConfig(
    nasHost = "192.168.178.58",
    commonShare = "test-comune",
    nasName = "MyCloudEX2Ultra.fritz.box",
    nasGuid = "0f8fad5b-d9cb-469f-a165-70867728950e",
    homeUla = listOf("fd12:3456:789a::/48", "fdab:0000:0001::/48"),
    nasPower = "08:00-22:00",
    wolMac = "00:90:a9:12:34:56",
    commonExclusions = listOf("Film", "Musica/Copertine"),
    defaults = ConfigDefaults(preset = "night", quality = "high"),
  )

  // --- Configurazione ------------------------------------------------------------------------------------

  @Test
  fun laConfigurazioneCompletaFaAndataERitornoNelRegistroFirmato() {
    val next = family.commit(family.authority.updateConfig(family.state, fullConfig))

    val reread = RegistrySync(family.common).refresh(null, family.anchor()).state!!

    assertThat(reread.registry.config).isEqualTo(fullConfig)
    assertThat(next.registry.config).isEqualTo(fullConfig)
  }

  @Test
  fun iValoriDiDefaultSiOmettonoECheckDiForma() {
    val minimal = FamilyConfig(nasHost = "192.168.178.58", commonShare = "Public")
    assertThat(minimal.toJson().members.keys).containsExactly("nasHost", "commonShare", "nasPower", "defaults")

    fun rejects(block: () -> Unit) = assertThrows(TrustViolation::class.java) { block() }.reason
    assertThat(rejects { minimal.copy(nasHost = "mycloudex2ultra.fritz.box") }).isEqualTo(RejectReason.INVALID_FIELD) // E-R7: solo IP
    assertThat(rejects { minimal.copy(nasHost = "192.168.178.058") }).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejects { minimal.copy(nasGuid = "0F8FAD5B-D9CB-469F-A165-70867728950E") }).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejects { minimal.copy(homeUla = listOf("FD12:3456:789A::/48")) }).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejects { minimal.copy(homeUla = listOf("fdab:0000:0001::/48", "fd12:3456:789a::/48")) }).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejects { minimal.copy(homeUla = List(5) { "fd00:0000:000$it::/48" }) }).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(rejects { minimal.copy(nasPower = "08:00-08:00") }).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejects { minimal.copy(nasPower = "24:00-07:00") }).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejects { minimal.copy(wolMac = "00-90-A9-12-34-56") }).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejects { minimal.copy(commonExclusions = listOf(".mycloudgallery/family")) }).isEqualTo(RejectReason.HOSTILE_PATH)
    assertThat(rejects { minimal.copy(commonExclusions = listOf("../Film")) }).isEqualTo(RejectReason.HOSTILE_PATH)
    assertThat(rejects { minimal.copy(commonExclusions = listOf("b", "a")) }).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejects { minimal.copy(defaults = ConfigDefaults("Night", "high")) }).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun unaListaVuotaScrittaNelRegistroNonECanonica() {
    val payload = family.state.registry.toJson().members.toMutableMap()
    val config = family.config.toJson().members + ("homeUla" to emptyList<dev.pampa.mycloudgallery.core.trust.codec.JsonValue>().json())
    payload["config"] = dev.pampa.mycloudgallery.core.trust.codec.JsonObject(config)
    payload["version"] = 2L.json()
    family.common.publishExclusive(NasLayout.registryFile(2), Envelope.seal(DocType.FAMILY_REGISTRY, dev.pampa.mycloudgallery.core.trust.codec.JsonObject(payload), family.familyKey))

    val sync = RegistrySync(family.common).refresh(family.state, family.anchor())

    assertThat(sync.state!!.registry.version).isEqualTo(1)
    assertThat(sync.rejected.count(RejectReason.NOT_CANONICAL)).isEqualTo(1)
  }

  @Test
  fun ilPrefissoUlaSiPortaNellaFormaDelFormato() {
    assertThat(UlaPrefix.canonical("fd12:3456:789a::/48")).isEqualTo("fd12:3456:789a::/48")
    assertThat(UlaPrefix.canonical("FD00:12:0::/48")).isEqualTo("fd00:0012:0000::/48")
    assertThat(UlaPrefix.canonical("fd12::/48")).isEqualTo("fd12:0000:0000::/48")
    assertThat(UlaPrefix.canonical(" fdab:0:1:0:0:0:0:0/48 ")).isEqualTo("fdab:0000:0001::/48")
    assertThat(UlaPrefix.canonical("fd12:3456:789a::/64")).isNull()
    assertThat(UlaPrefix.canonical("fd12:3456:789a:1::/48")).isNull() // bit oltre il /48
    assertThat(UlaPrefix.canonical("2001:db8:1::/48")).isNull() // non e' un ULA
    assertThat(UlaPrefix.canonical("fc00:1:2::/48")).isNull() // fc00::/8 non e' assegnato localmente
    assertThat(UlaPrefix.canonical("fd12:::/48")).isNull()
    assertThat(UlaPrefix.canonical("fd12:3456:789a:1:2:3:4:5:6/48")).isNull()
  }

  // --- Segnalazione "perso" (tipo 7) ---------------------------------------------------------------------

  private val now get() = family.clock.now

  private fun report(reporter: dev.pampa.mycloudgallery.core.trust.crypto.Signer, target: String, account: String = "mamma") =
    LostReport(family.familyId, reporter.deviceId, target, account, now).sign(reporter)

  @Test
  fun unaSegnalazioneDiUnDispositivoDelloStessoAccountEValida() {
    val tablet = family.approveNewDevice(name = "Tablet", nasUser = "mamma")
    val phone = family.approveNewDevice(name = "Telefono di Mamma", nasUser = "Mamma") // stesso account, maiuscole diverse

    val verdict = LostReports.verify(report(phone, tablet.deviceId), family.state, tablet.deviceId, now)

    assertThat(verdict.acceptedOrNull()!!.reporter.id).isEqualTo(phone.deviceId)
  }

  @Test
  fun unaSegnalazioneSenzaPoteriNonValeMai() {
    val tablet = family.approveNewDevice(name = "Tablet", nasUser = "mamma")
    val babbo = family.approveNewDevice(name = "Telefono di Babbo", nasUser = "babbo")
    val pending = newSigner() // ha chiesto di entrare ma non e' approvato (E-S14)
    val revoked = family.approveNewDevice(name = "Vecchio", nasUser = "mamma")
    family.changeStatus(revoked, DeviceStatus.REVOKED)
    val state = family.state

    assertThat(LostReports.verify(report(babbo, tablet.deviceId), state, tablet.deviceId, now).rejectionOrNull()).isEqualTo(RejectReason.NOT_OWNER)
    assertThat(LostReports.verify(report(babbo, tablet.deviceId, "babbo"), state, tablet.deviceId, now).rejectionOrNull()).isEqualTo(RejectReason.NOT_OWNER)
    assertThat(LostReports.verify(report(pending, tablet.deviceId), state, tablet.deviceId, now).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_NOT_APPROVED)
    assertThat(LostReports.verify(report(revoked, tablet.deviceId), state, tablet.deviceId, now).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_REVOKED)
    // Copiata nella cartella di un altro dispositivo.
    val other = family.approveNewDevice(name = "Altro", nasUser = "mamma")
    val phone = family.approveNewDevice(name = "Telefono di Mamma", nasUser = "mamma")
    assertThat(LostReports.verify(report(phone, tablet.deviceId), family.state, other.deviceId, now).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
    // Dal futuro oltre lo scarto ammesso.
    val future = LostReport(family.familyId, phone.deviceId, tablet.deviceId, "mamma", now + TrustLimits.MAX_FUTURE_SKEW_MILLIS + 1).sign(phone)
    assertThat(LostReports.verify(future, family.state, tablet.deviceId, now).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    // Un dispositivo non segnala se stesso.
    assertThrows(TrustViolation::class.java) { report(phone, phone.deviceId) }
  }

  @Test
  fun laSerieSiLeggePerNomeScavalcandoLaSpazzaturaENonDallElenco() {
    val tablet = family.approveNewDevice(name = "Tablet", nasUser = "mamma")
    val phone = family.approveNewDevice(name = "Telefono di Mamma", nasUser = "mamma")
    val share = FakeNasFileSystem("mamma")
    share.attackerWrite(NasLayout.reportFile(tablet.deviceId, 1).value, "spazzatura".toByteArray())
    share.attackerFlood(NasLayout.reportDir(tablet.deviceId), 200)
    val signed = report(phone, tablet.deviceId)

    val path = LostReports.publish(share, tablet.deviceId, signed)!!
    assertThat(path).isEqualTo(NasLayout.reportFile(tablet.deviceId, 2))
    assertThat(LostReports.publish(share, tablet.deviceId, signed)).isEqualTo(path) // idempotente

    val scan = LostReports.find(share, family.state, tablet.deviceId, now)
    assertThat(scan.found!!.reporter.id).isEqualTo(phone.deviceId)
    assertThat(scan.rejected.total).isEqualTo(1)
    assertThat(LostReports.find(share, family.state, phone.deviceId, now).found).isNull()
  }

  // --- Stato del dispositivo (tipo 8) --------------------------------------------------------------------

  private fun status(device: dev.pampa.mycloudgallery.core.trust.crypto.Signer, version: Long, seen: Long = now, appVersion: String = "2.0.0") =
    dev.pampa.mycloudgallery.core.trust.data.DeviceStatusReport(
      familyId = family.familyId,
      deviceId = device.deviceId,
      version = version,
      appVersion = appVersion,
      roles = setOf(dev.pampa.mycloudgallery.core.trust.data.DeviceRole.PREPARER, dev.pampa.mycloudgallery.core.trust.data.DeviceRole.INDEXER),
      lastSeenAt = seen,
      registryVersion = family.state.registry.version,
    ).sign(device)

  @Test
  fun loStatoDiUnDispositivoApprovatoSiAccettaConAntiRollback() {
    val tablet = family.approveNewDevice()
    val verifier = dev.pampa.mycloudgallery.core.trust.data.DeviceStatusVerifier
    val v2 = verifier.accept(status(tablet, 2), family.state, tablet.deviceId, 2, previous = null, nasNow = now).acceptedOrNull()!!
    assertThat(v2.report.roles).hasSize(2)

    assertThat(verifier.accept(status(tablet, 1), family.state, tablet.deviceId, 1, v2.mark, now).rejectionOrNull()).isEqualTo(RejectReason.ROLLBACK)
    assertThat(verifier.accept(status(tablet, 2, appVersion = "2.0.1"), family.state, tablet.deviceId, 2, v2.mark, now).rejectionOrNull())
      .isEqualTo(RejectReason.EQUIVOCATION)
    assertThat(verifier.accept(status(tablet, 3), family.state, tablet.deviceId, 4, v2.mark, now).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
    assertThat(verifier.accept(status(tablet, 3, seen = now + 10 * 60_000), family.state, tablet.deviceId, 3, v2.mark, now).rejectionOrNull())
      .isEqualTo(RejectReason.INVALID_FIELD)
    // La stessa testa riletta (stesso contenuto) va bene.
    assertThat(verifier.accept(status(tablet, 2), family.state, tablet.deviceId, 2, v2.mark, now).acceptedOrNull()).isNotNull()
  }

  @Test
  fun e_s14_loStatoDiUnDispositivoInAttesaNonVale() {
    val pending = newSigner()
    val verdict = dev.pampa.mycloudgallery.core.trust.data.DeviceStatusVerifier.accept(status(pending, 1), family.state, pending.deviceId, 1, null, now)
    assertThat(verdict.rejectionOrNull()).isEqualTo(RejectReason.SIGNER_NOT_APPROVED)
  }

  @Test
  fun laSerieDegliStatiSiPercorreConPocheLetture() {
    val tablet = family.approveNewDevice()
    val series = dev.pampa.mycloudgallery.core.trust.data.DeviceStatusSeries
    var last = 0L
    repeat(400) { last = series.publish(family.common, tablet.deviceId, last) { v -> status(tablet, v) }!! }
    assertThat(last).isEqualTo(400)
    val before = family.common.reads

    val scan = series.readLatest(family.common, family.state, tablet.deviceId, previous = null, nasNow = now)

    assertThat(scan.latest!!.report.version).isEqualTo(400)
    assertThat(family.common.reads - before).isLessThan(40)
    // Dall'ultimo segno: nulla di nuovo, la stessa versione si riconosce.
    assertThat(series.readLatest(family.common, family.state, tablet.deviceId, scan.latest!!.mark, now).latest!!.report.version).isEqualTo(400)
  }

  @Test
  fun laSpazzaturaInCimaAllaSerieNonNascondeLUltimoStatoValido() {
    val tablet = family.approveNewDevice()
    val series = dev.pampa.mycloudgallery.core.trust.data.DeviceStatusSeries
    series.publish(family.common, tablet.deviceId, 0) { v -> status(tablet, v) }
    family.common.attackerWrite(NasLayout.statusFile(tablet.deviceId, 2).value, "x".toByteArray())

    val scan = series.readLatest(family.common, family.state, tablet.deviceId, null, now)

    assertThat(scan.latest!!.report.version).isEqualTo(1)
    assertThat(scan.rejected.total).isEqualTo(1)
    assertThat(series.publish(family.common, tablet.deviceId, 1) { v -> status(tablet, v) }).isEqualTo(3)
  }

  // --- Reset: rifondazione -------------------------------------------------------------------------------

  @Test
  fun dopoUnResetLaFamigliaNuovaPartePiuAvantiEQuellaVecchiaLaIgnora() {
    repeat(TrustLimits.MAX_JUNK_RUN + 8) { family.approveNewDevice(name = "Dispositivo $it", nasUser = "mamma") } // v2…v41
    val oldState = family.state
    val newKey = FamilyKey.generate()
    val newFamilyId = TrustIds.random()
    val admin = newSigner()
    val founder = (RegistrationRequest.verify(
      RegistrationRequest.sign(admin, newFamilyId, "Telefono di Admin", "SM-S931B", "admin2", now),
      newFamilyId,
      admin.deviceId,
    ) as Verdict.Accepted).value

    val signed = FamilyAuthority(newKey, family.clock).refound(family.common, newFamilyId, family.config, founder, knownVersion = 0)!!

    assertThat(signed.registry.version).isEqualTo(oldState.registry.version + 1)
    val anchor = TrustAnchor.create(newFamilyId, newKey.publicKey.fingerprint, signed.registry.version)!!
    val fresh = RegistrySync(family.common).refresh(null, anchor)
    assertThat(fresh.state!!.registry.familyId).isEqualTo(newFamilyId)
    assertThat(fresh.state!!.canWrite(admin.deviceId)).isTrue()
    // La famiglia vecchia non vede la nuova (firmata da un'altra chiave) e non se ne fa ingannare.
    val old = RegistrySync(family.common).refresh(oldState, family.anchor())
    assertThat(old.state!!.registry.familyId).isEqualTo(family.familyId)
    assertThat(old.state!!.registry.version).isEqualTo(oldState.registry.version)
    assertThat(old.rejected.count(RejectReason.SIGNER_NOT_APPROVED)).isEqualTo(1)
  }

  @Test
  fun laRifondazioneScavalcaINomiOccupatiELasciaSpazioDopo() {
    family.common.attackerWrite(NasLayout.registryFile(5).value, "spazzatura".toByteArray())
    val founder = newSigner()
    val newFamilyId = TrustIds.random()
    val request = (RegistrationRequest.verify(
      RegistrationRequest.sign(founder, newFamilyId, "Telefono", "SM-S931B", "admin", now), newFamilyId, founder.deviceId,
    ) as Verdict.Accepted).value

    val signed = FamilyAuthority(FamilyKey.generate(), family.clock).refound(family.common, newFamilyId, family.config, request, knownVersion = 1)!!

    assertThat(signed.registry.version).isEqualTo(6) // 2…4 liberi ma seguiti dalla spazzatura in 5
  }

  // --- Kit: intestazione ------------------------------------------------------------------------------

  @Test
  fun lIntestazioneDelKitSiLeggeSenzaCodice() {
    val blob = RecoveryKit.seal(family.familyKey, family.familyId, RecoveryCode.generate(), now)

    val header = RecoveryKit.inspect(blob).acceptedOrNull()!!

    assertThat(header.familyId).isEqualTo(family.familyId)
    assertThat(header.keyFingerprint).isEqualTo(family.familyKey.publicKey.fingerprint)
    assertThat(header.createdAt).isEqualTo(now)
    assertThat(RecoveryKit.inspect(blob.copyOf(20)).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun iTipiNuoviHannoIlLoroCodiceETetto() {
    assertThat(DocType.fromCode(7)).isEqualTo(DocType.LOST_REPORT)
    assertThat(DocType.fromCode(8)).isEqualTo(DocType.DEVICE_STATUS)
    assertThat(DocType.DEVICE_STATUS.maxPayload).isEqualTo(2 * 1024)
    // Uno stato non si spaccia per una segnalazione.
    val tablet = family.approveNewDevice()
    val bytes = status(tablet, 1)
    assertThat(LostReports.verify(bytes, family.state, tablet.deviceId, now).rejectionOrNull()).isEqualTo(RejectReason.WRONG_TYPE)
    assertThat(jsonObject("a" to 1L.json()).members).hasSize(1)
  }
}
