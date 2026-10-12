package dev.pampa.mycloudgallery.core.trust

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.EcdsaDer
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.P256
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryCode
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKit
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.AcquireResult
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadLocation
import dev.pampa.mycloudgallery.core.trust.data.HeadVerifier
import dev.pampa.mycloudgallery.core.trust.data.LeaseManager
import dev.pampa.mycloudgallery.core.trust.data.LocalTrashEntry
import dev.pampa.mycloudgallery.core.trust.data.PurgeDecision
import dev.pampa.mycloudgallery.core.trust.data.PurgeRound
import dev.pampa.mycloudgallery.core.trust.data.Segments
import dev.pampa.mycloudgallery.core.trust.data.TrashItemInfo
import dev.pampa.mycloudgallery.core.trust.data.TrashPurgePolicy
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.CommitResult
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.FamilyRegistry
import dev.pampa.mycloudgallery.core.trust.registry.LookAlike
import dev.pampa.mycloudgallery.core.trust.registry.PendingRequests
import dev.pampa.mycloudgallery.core.trust.registry.RegistrySync
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import dev.pampa.mycloudgallery.core.trust.testing.newSigner
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.security.Signature

/**
 * Test di regressione della revisione di sicurezza 1.4 (`docs/review/1.4-core-trust.md`). Ogni test
 * riproduce lo scenario d'attacco della voce indicata nel nome e fallirebbe sul codice di `e9eb3b9`.
 */
class ReviewFixesTest {

  private val family = TestFamily()

  private fun signed(registry: FamilyRegistry, key: FamilyKey = family.familyKey): ByteArray =
    Envelope.seal(DocType.FAMILY_REGISTRY, registry.toJson(), key)

  /** La stessa busta con la firma (r, s) sostituita da (r, n − s): valida per JCA, fatta senza chiave. */
  private fun malleate(file: ByteArray): ByteArray {
    val at = Envelope.HEADER_BYTES + ByteBuffer.wrap(file, 97, 4).int
    val (r, s) = EcdsaDer.parse(file.copyOfRange(at + 1, file.size))!!
    val other = EcdsaDer.encode(r, P256.N.subtract(s))
    return file.copyOfRange(0, at) + byteArrayOf(other.size.toByte()) + other
  }

  private fun jcaAccepts(file: ByteArray): Boolean {
    val at = Envelope.HEADER_BYTES + ByteBuffer.wrap(file, 97, 4).int
    val key = EcPublicKey.fromSpki(file.copyOfRange(6, 97))
    return Signature.getInstance("SHA256withECDSA").run {
      initVerify(key.toJca())
      update("mycloudgallery/signed".toByteArray() + byteArrayOf(0) + file.copyOfRange(0, at))
      verify(file.copyOfRange(at + 1, file.size))
    }
  }

  // --- A1: regressione del registro ---------------------------------------------------------------------

  @Test
  fun a1_unRegistroPiuNuovoCheRiapprovaUnRevocatoERifiutato() {
    val tablet = family.approveNewDevice()
    family.changeStatus(tablet, DeviceStatus.REVOKED)
    val beforeRevocation = (RegistryVerifier.restore(family.history[1], family.familyKey.publicKey.fingerprint) as Verdict.Accepted).value.registry
    val forged = signed(beforeRevocation.copy(version = 4, issuedAt = family.clock.now))

    val verdict = RegistryVerifier.accept(forged, family.state, family.anchor())

    assertThat(verdict.rejectionOrNull()).isEqualTo(RejectReason.REGRESSION)
    assertThat(family.state.canWrite(tablet.deviceId)).isFalse()
  }

  @Test
  fun a1_ogniFormaDiRegressioneERifiutata() {
    val tablet = family.approveNewDevice()
    val head = DeviceHead(family.familyId, tablet.deviceId, "Public", HeadKind.INDEX, 1, 1, family.clock.now, emptyList()).sign(tablet)
    val headHash = Envelope.open(head, DocType.DEVICE_HEAD).contentHash
    family.changeStatus(tablet, DeviceStatus.RETIRED, frozenHeads = listOf(headHash))
    val current = family.state.registry
    val next = current.copy(version = current.version + 1)
    val tabletEntry = current.device(tablet.deviceId)!!
    val otherHash = Segments.wrap(byteArrayOf(1)).second.hash

    val cases = listOf(
      next.copy(devices = current.devices.filter { it.id != tablet.deviceId }), // sparito
      next.copy(devices = current.devices.map { if (it.id == tablet.deviceId) it.copy(nasUser = "babbo") else it }), // altro account
      next.copy(devices = current.devices.map { if (it.id == tablet.deviceId) it.copy(status = DeviceStatus.APPROVED, frozenHeads = emptyList()) else it }),
      next.copy(devices = current.devices.map { if (it.id == tablet.deviceId) it.copy(frozenHeads = (tabletEntry.frozenHeads + otherHash).sorted()) else it }),
      next.copy(issuedAt = current.issuedAt - 1), // emesso "prima"
    )
    for (case in cases) {
      assertThat(RegistryVerifier.accept(signed(case), family.state, family.anchor()).rejectionOrNull()).isEqualTo(RejectReason.REGRESSION)
    }
    // Cio' che e' ammesso: stato piu' severo, teste congelate in meno.
    val stricter = next.copy(devices = current.devices.map { if (it.id == tablet.deviceId) it.copy(frozenHeads = emptyList()) else it })
    assertThat(RegistryVerifier.accept(signed(stricter), family.state, family.anchor()).acceptedOrNull()).isNotNull()
  }

  @Test
  fun a1_scenarioCompleto_secondoAdminConBaseVecchia_laRevocaNonSiPerdeEIRamiSiRiuniscono() {
    val tablet = family.approveNewDevice(name = "Tablet", nasUser = "mamma") // v2
    val staleBase = family.state // il secondo admin (profilo sul tablet di Admin) si ferma qui
    val stale = FamilyAuthority(family.familyKey, family.clock)
    family.changeStatus(tablet, DeviceStatus.REVOKED) // v3: il telefono dell'admin revoca il tablet compromesso
    val phoneState = family.state

    // Il malware cancella la v3; il secondo admin, fermo alla v2, approva un dispositivo e pubblica una v3'.
    family.common.attackerDelete(NasLayout.registryFile(3))
    val x = newSigner()
    val xRequest = family.verified(family.request(x, "Telefono di Babbo", "babbo"))
    val staleV3 = (stale.commit(family.common, stale.approve(staleBase, xRequest, family.typed(xRequest), family.clock.now)) as CommitResult.Published).state

    // Il telefono vede l'equivocazione al posto della sua v3 (allarme), e resta alla sua v3.
    val first = RegistrySync(family.common).refresh(phoneState, family.anchor())
    assertThat(first.state).isSameInstanceAs(phoneState)
    assertThat(first.tamperingSuspected).isTrue()
    assertThat(first.forks).hasSize(1)

    // La mossa successiva del secondo admin (v4, tablet ancora approvato) e' una regressione per il telefono.
    stale.commit(family.common, stale.updateConfig(staleV3, family.config.copy(nasHost = "192.168.178.20")))
    val second = RegistrySync(family.common).refresh(phoneState, family.anchor())
    assertThat(second.state!!.canWrite(tablet.deviceId)).isFalse()
    assertThat(second.rejected.count(RejectReason.REGRESSION)).isEqualTo(1)

    // L'admin prova a pubblicare: gli si mostra il ramo; lo riconosce e lo unisce.
    val attempt = family.authority.commit(family.common, family.authority.updateConfig(phoneState, family.config))
    assertThat(attempt).isInstanceOf(CommitResult.Fork::class.java)
    val fork = (attempt as CommitResult.Fork).fork
    assertThat(fork.devicesOnlyHere(phoneState.registry).map { it.id }).containsExactly(x.deviceId)
    val merged = (family.authority.commit(family.common, family.authority.mergeFork(phoneState, fork)) as CommitResult.Published).state

    // Entrambi i rami accettano l'unione: il tablet resta revocato, il dispositivo nuovo resta approvato.
    assertThat(merged.statusOf(tablet.deviceId)).isEqualTo(DeviceStatus.REVOKED)
    assertThat(merged.canWrite(x.deviceId)).isTrue()
    val staleView = RegistrySync(family.common).refresh(
      (RegistryVerifier.accept(family.common.bytesAt(NasLayout.registryFile(4))!!, staleV3, family.anchor()) as Verdict.Accepted).value,
      family.anchor(),
    )
    assertThat(staleView.state!!.registry.version).isEqualTo(merged.registry.version)
    assertThat(staleView.state!!.canWrite(tablet.deviceId)).isFalse()
  }

  // --- A2: nome della versione successiva occupato ------------------------------------------------------

  @Test
  fun a2_nomeSuccessivoOccupatoDaSpazzatura_laRevocaSiPubblicaComunque() {
    val tablet = family.approveNewDevice()
    val observer = family.state
    family.common.attackerWrite(NasLayout.registryFile(3).value, "spazzatura".toByteArray())

    val result = family.authority.commit(family.common, family.authority.changeStatus(family.state, tablet.deviceId, DeviceStatus.REVOKED))

    assertThat(result).isInstanceOf(CommitResult.Published::class.java)
    result as CommitResult.Published
    assertThat(result.state.registry.version).isEqualTo(4)
    assertThat(result.skipped).containsExactly(NasLayout.registryFile(3))
    val seen = RegistrySync(family.common).refresh(observer, family.anchor())
    assertThat(seen.state!!.canWrite(tablet.deviceId)).isFalse()
  }

  @Test
  fun a2_troppaSpazzaturaDiFila_bloccoConLElencoEsattoDaCancellare() {
    val tablet = family.approveNewDevice()
    val junk = (3L..3L + TrustLimits.MAX_JUNK_RUN).map { NasLayout.registryFile(it) }
    junk.forEach { family.common.attackerWrite(it.value, ByteArray(10)) }
    val draft = family.authority.changeStatus(family.state, tablet.deviceId, DeviceStatus.REVOKED)

    val blocked = family.authority.commit(family.common, draft)
    assertThat(blocked).isInstanceOf(CommitResult.Blocked::class.java)
    assertThat((blocked as CommitResult.Blocked).junk).containsExactlyElementsIn(junk)

    // L'utente cancella quei file dal PC: la revoca esce.
    junk.forEach { family.common.attackerDelete(it) }
    assertThat(family.authority.commit(family.common, draft)).isInstanceOf(CommitResult.Published::class.java)
  }

  // --- A3: malleabilita' di ECDSA -----------------------------------------------------------------------

  @Test
  fun a3_laFirmaMalleataEValidaPerJcaMaIlFormatoLaRifiuta() {
    val original = family.history[0]
    val malleated = malleate(original)

    assertThat(jcaAccepts(malleated)).isTrue() // l'attacco e' reale: nessuna chiave serve
    assertThat(assertThrows(TrustViolation::class.java) { Envelope.open(malleated, DocType.FAMILY_REGISTRY) }.reason)
      .isEqualTo(RejectReason.BAD_SIGNATURE)
  }

  @Test
  fun a3_copieMalleateNonFabbricanoEquivocazioni() {
    val v1 = family.state
    family.common.attackerWrite(NasLayout.registryFile(2).value, malleate(family.history[0]))
    family.common.attackerWrite(NasLayout.registryFile(1).value, malleate(family.history[0]))

    val sync = RegistrySync(family.common).refresh(v1, family.anchor())

    assertThat(sync.rejected.count(RejectReason.EQUIVOCATION)).isEqualTo(0)
    assertThat(sync.tamperingSuspected).isFalse()
    assertThat(sync.state).isSameInstanceAs(v1)
  }

  @Test
  fun a3_lIdentitaDiUnDatoNonDipendeDaiByteDellaFirma() {
    // Lo stesso contenuto firmato di nuovo (altro nonce, altra firma valida) e' la stessa versione.
    val resigned = signed(family.state.registry)
    assertThat(resigned).isNotEqualTo(family.history[0])
    assertThat(RegistryVerifier.accept(resigned, family.state, family.anchor()).acceptedOrNull()).isSameInstanceAs(family.state)

    // Teste: niente equivocazione con una nuova firma dello stesso contenuto, e le teste congelate restano valide.
    val tablet = family.approveNewDevice()
    val head = DeviceHead(family.familyId, tablet.deviceId, "Public", HeadKind.INDEX, 1, family.state.registry.version, family.clock.now, emptyList())
    val where = HeadLocation("Public", tablet.deviceId, HeadKind.INDEX, 1)
    val mark = HeadVerifier.accept(head.sign(tablet), family.state, where, null, "babbo").acceptedOrNull()!!.mark
    assertThat(HeadVerifier.accept(head.sign(tablet), family.state, where, mark, "babbo").acceptedOrNull()).isNotNull()
    family.changeStatus(tablet, DeviceStatus.RETIRED, frozenHeads = listOf(mark.contentHash))
    assertThat(HeadVerifier.accept(head.sign(tablet), family.state, where, null, "babbo").acceptedOrNull()).isNotNull()
  }

  @Test
  fun a3_firmeSempreCanoniche_eForme_nonCanonicheRifiutate() {
    val signer = newSigner()
    repeat(200) {
      val sealed = Envelope.seal(DocType.LEASE, dev.pampa.mycloudgallery.core.trust.codec.jsonObject(), signer)
      val at = Envelope.HEADER_BYTES + ByteBuffer.wrap(sealed, 97, 4).int
      assertThat(EcdsaDer.isCanonical(sealed.copyOfRange(at + 1, sealed.size))).isTrue()
    }
    val message = "x".toByteArray()
    val (r, s) = EcdsaDer.parse(signer.sign(message))!!
    val highS = EcdsaDer.encode(r, P256.N.subtract(s))
    val paddedR = byteArrayOf(0x02, (r.toByteArray().size + 1).toByte(), 0) + r.toByteArray()
    val sBytes = s.toByteArray()
    val nonMinimal = byteArrayOf(0x30, (paddedR.size + 2 + sBytes.size).toByte()) + paddedR + byteArrayOf(0x02, sBytes.size.toByte()) + sBytes

    assertThat(signer.publicKey.verify(message, EcdsaDer.encode(r, s))).isTrue()
    assertThat(signer.publicKey.verify(message, highS)).isFalse()
    assertThat(signer.publicKey.verify(message, nonMinimal)).isFalse()
  }

  // --- M1: elenco troncato --------------------------------------------------------------------------------

  @Test
  fun m1_unaCartellaInondataNonNascondeLaRevoca() {
    val tablet = family.approveNewDevice()
    val observer = family.state
    repeat(20_000) { family.common.attackerWrite(NasLayout.REGISTRY_DIR.value + "/rumore-$it", ByteArray(1)) }
    family.changeStatus(tablet, DeviceStatus.REVOKED)

    val sync = RegistrySync(family.common).refresh(observer, family.anchor())

    assertThat(sync.listingTruncated).isTrue()
    assertThat(sync.state!!.canWrite(tablet.deviceId)).isFalse()
  }

  // --- M2: approvazione ---------------------------------------------------------------------------------

  @Test
  fun m2_codiceA80BitDaDigitare_eFinestraDiQuindiciMinuti() {
    val device = newSigner()
    val request = family.verified(family.request(device, "Tablet profilo Babbo", "babbo"))
    val now = family.clock.now

    assertThat(request.approvalCode.replace("-", "")).hasLength(16)
    val typedLoosely = request.approvalCode.lowercase().replace("-", " ")
    assertThat(ApprovalProof.TypedCode(typedLoosely).proves(request)).isTrue()
    val wrongLast = if (request.approvalCode.last() == 'A') 'B' else 'A'
    assertThat(ApprovalProof.TypedCode(request.approvalCode.dropLast(1) + wrongLast).proves(request)).isFalse()
    assertThat(ApprovalProof.ScannedKey(device.publicKey).proves(request)).isTrue()
    assertThat(ApprovalProof.ScannedKey(newSigner().publicKey).proves(request)).isFalse()

    assertThrows(IllegalArgumentException::class.java) {
      family.authority.approve(family.state, request, ApprovalProof.TypedCode("AAAA-AAAA-AAAA-AAAA"), now)
    }
    assertThrows(IllegalArgumentException::class.java) {
      family.authority.approve(family.state, request, family.typed(request), now - TrustLimits.MAX_REQUEST_AGE_MILLIS - 1)
    }
    family.authority.approve(family.state, request, family.typed(request), now - TrustLimits.MAX_REQUEST_AGE_MILLIS)
  }

  @Test
  fun m2_richiesteSosiaSegnalate_eRichiestaNascostaLettaPerId() {
    val real = newSigner()
    val impostor = newSigner()
    val zwj = Char(0x200D)
    family.common.publishExclusive(NasLayout.requestFile(real.deviceId), family.request(real, "Tablet Mamma", "mamma"))
    family.common.publishExclusive(NasLayout.requestFile(impostor.deviceId), family.request(impostor, "Tablet$zwj Mamma", "mamma"))
    val copycat = newSigner()
    family.common.publishExclusive(NasLayout.requestFile(copycat.deviceId), family.request(copycat, "TELEFONO di admin", "admin"))

    val pending = PendingRequests.read(family.common, family.state)
    assertThat(pending.lookAlikes).containsExactly(real.deviceId, impostor.deviceId, copycat.deviceId)
    assertThat(LookAlike.key("Tablet$zwj Mamma")).isEqualTo(LookAlike.key("tablet  mamma"))

    // Con la cartella inondata la richiesta vera non compare nell'elenco, ma si legge per id.
    repeat(200) { family.common.attackerWrite(NasLayout.REQUESTS_DIR.value + "/rumore-$it", ByteArray(1)) }
    assertThat(PendingRequests.read(family.common, family.state).listingTruncated).isTrue()
    assertThat(PendingRequests.readOne(family.common, family.state, real.deviceId)?.acceptedOrNull()?.deviceId).isEqualTo(real.deviceId)
  }

  // --- M4: kit dopo la rotazione ------------------------------------------------------------------------

  @Test
  fun m4_dopoLaRotazioneIlKitNuovoHaUnPostoSuoEUnCodiceNuovo() {
    val oldCode = RecoveryCode.generate()
    val oldKit = RecoveryKit.seal(family.familyKey, family.familyId, oldCode, family.clock.now)
    val oldPath = NasLayout.recoveryKitFile(family.familyId, family.familyKey.publicKey.fingerprint)
    assertThat(family.common.publishExclusive(oldPath, oldKit)).isEqualTo(PublishResult.CREATED)

    val next = family.rotate()
    val newCode = RecoveryCode.generate()
    val newPath = NasLayout.recoveryKitFile(family.familyId, next.publicKey.fingerprint)

    assertThat(newPath).isNotEqualTo(oldPath)
    assertThat(family.common.publishExclusive(newPath, RecoveryKit.seal(next, family.familyId, newCode, family.clock.now))).isEqualTo(PublishResult.CREATED)
    val newKit = family.common.bytesAt(newPath)!!
    assertThat(RecoveryKit.open(newKit, oldCode, family.familyId, next.publicKey.fingerprint).rejectionOrNull()).isEqualTo(RejectReason.BAD_SIGNATURE)
  }

  // --- M6: prenotazioni ---------------------------------------------------------------------------------

  @Test
  fun m6_unaRisorsaPrenotataMigliaiaDiVolteRestaPrenotabile() {
    val phone = family.approveNewDevice(name = "Telefono", nasUser = "babbo")
    val leases = LeaseManager(family.common, phone, family.clock)
    val resource = "index:Public:tutto"

    repeat(700) { round ->
      val result = leases.tryAcquire(family.state, resource, 60_000)
      assertThat(result).isInstanceOf(AcquireResult.Acquired::class.java)
      assertThat((result as AcquireResult.Acquired).generation).isEqualTo(2L * round + 1)
      leases.release(family.state, resource)
    }
  }

  // --- M7: recupero dell'admin --------------------------------------------------------------------------

  @Test
  fun m7_unKitConUnAltraChiaveNonPassa() {
    val code = RecoveryCode.generate()
    val attackerKit = RecoveryKit.seal(FamilyKey.generate(), family.familyId, code, family.clock.now)
    val sheetFingerprint = family.familyKey.publicKey.fingerprint

    assertThat(RecoveryKit.open(attackerKit, code, family.familyId, sheetFingerprint).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_NOT_APPROVED)
  }

  @Test
  fun m7_conVersioniRecentiCancellate_ilRecuperoNonPartedaUnRegistroVecchio() {
    val tablet = family.approveNewDevice()
    family.changeStatus(tablet, DeviceStatus.LOST) // v3
    val stateQr = family.state.toAnchor() // mostrato da un dispositivo approvato
    family.common.attackerDelete(NasLayout.registryFile(3))

    val tooEarly = RegistrySync(family.common).refresh(null, stateQr)
    assertThat(tooEarly.state).isNull() // la v2 (tablet approvato) non viene presa per buona

    RegistrySync(family.common).refresh(family.state, family.anchor()) // un dispositivo che l'aveva vista la ripubblica
    val recovered = RegistrySync(family.common).refresh(null, stateQr)
    assertThat(recovered.state!!.statusOf(tablet.deviceId)).isEqualTo(DeviceStatus.LOST)
  }

  // --- Bassi e informativi --------------------------------------------------------------------------------

  @Test
  fun b1_laCartellaDellAppScrittaConUnPuntoALarghezzaPienaERifiutata() {
    val fullWidthDot = Char(0xFF0E)
    assertThrows(TrustViolation::class.java) { SharePath.parseMediaPath("${fullWidthDot}mycloudgallery/family/registry/00000000000000000001") }
    assertThrows(TrustViolation::class.java) { SharePath.parseMediaPath("${fullWidthDot}MyCloudGallery/x") }
  }

  @Test
  fun b2_caratteriCheSembranoVuotiRifiutatiNeiNomi() {
    for (cp in listOf(0x3164, 0x115F, 0x1160, 0xFFA0, 0x034F, 0x17B4, 0x180B, 0x2800, 0xFE00)) {
      assertThrows("U+%04X".format(cp), TrustViolation::class.java) { TextRules.displayText("Telefono" + String(Character.toChars(cp)), 64) }
    }
    assertThrows(TrustViolation::class.java) { TextRules.displayText(String(Character.toChars(0xE0100)) + "a", 64) }
    // Le emoji composte restano ammesse.
    assertThat(TextRules.displayText("Tablet " + String(Character.toChars(0x1F468)) + Char(0x200D) + String(Character.toChars(0x1F469)), 64)).isNotEmpty()
  }

  @Test
  fun b3_puntiFuoriDallaCurvaOCoordinateOltrePRifiutati() {
    val valid = newSigner().publicKey.encoded
    val prefix = valid.copyOf(27)
    fun coord(v: java.math.BigInteger) = v.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
    fun spki(x: java.math.BigInteger, y: java.math.BigInteger): ByteArray = prefix + coord(x) + coord(y)
    val x = java.math.BigInteger(1, valid.copyOfRange(27, 59))
    val y = java.math.BigInteger(1, valid.copyOfRange(59, 91))

    assertThrows(TrustViolation::class.java) { EcPublicKey.fromSpki(spki(java.math.BigInteger.ONE, java.math.BigInteger.ONE)) }
    assertThrows(TrustViolation::class.java) { EcPublicKey.fromSpki(spki(P256.P, y)) } // coordinata = p
    assertThrows(TrustViolation::class.java) { EcPublicKey.fromSpki(spki(x, y.add(java.math.BigInteger.ONE))) }
    assertThat(EcPublicKey.fromSpki(spki(x, y))).isEqualTo(EcPublicKey.fromSpki(valid))
  }

  @Test
  fun b4_ilQrMostratoSubitoDopoLaRotazioneFaPartireUnDispositivoNuovo() {
    family.rotate()
    val qr = family.state.toAnchor()

    val sync = RegistrySync(family.common).refresh(null, qr)

    assertThat(sync.state!!.registry.version).isEqualTo(family.state.registry.version)
    assertThat(sync.state!!.trustedFamilyKey).isEqualTo(family.familyKey.publicKey)
  }

  @Test
  fun b5_laSpazzaturaInCimaCostaUnNumeroLimitatoDiLetture() {
    val observer = family.state
    for (v in 2L..400L) family.common.attackerWrite(NasLayout.registryFile(v).value, ByteArray(1000))
    val before = family.common.reads

    val sync = RegistrySync(family.common).refresh(observer, family.anchor())

    assertThat(sync.incomplete).isTrue()
    assertThat(family.common.reads - before).isAtMost(TrustLimits.MAX_JUNK_RUN + 3)
  }

  @Test
  fun b7_unFileOltreIlTettoDelGiroSiEliminaDaSolo() {
    val phone = family.approveNewDevice(name = "Telefono", nasUser = "babbo")
    val policy = TrashPurgePolicy(phone.deviceId)
    val huge = TrustLimits.MAX_PURGE_BYTES_PER_ROUND + 5L * 1024 * 1024 * 1024
    val later = family.clock.now + TrustLimits.TRASH_RETENTION_MILLIS
    val round = PurgeRound()

    val first = policy.decide(LocalTrashEntry("Public", TrustIds.random(), huge, family.clock.now), TrashItemInfo(huge), later, round)
    val second = policy.decide(LocalTrashEntry("Public", TrustIds.random(), 1, family.clock.now), TrashItemInfo(1), later, round)

    assertThat(first).isInstanceOf(PurgeDecision.Purge::class.java)
    assertThat(second).isEqualTo(PurgeDecision.Defer)
  }

  @Test
  fun i7_nomeDelNasStretto() {
    for (ok in listOf("mycloudex2ultra.fritz.box", "192.168.178.20", "[fd00::1]", "nas")) assertThat(TextRules.hostName(ok)).isEqualTo(ok)
    for (bad in listOf("a]b", "[::1", "a..b", "-a.b", "a:b", "a_b", "[zz::1]", ".")) {
      assertThrows(bad, TrustViolation::class.java) { TextRules.hostName(bad) }
    }
  }

  @Test
  fun i9_versioniOltreIlTettoRifiutate() {
    val absurd = signed(family.state.registry.copy(version = 2)).let { bytes ->
      // Contenuto con version oltre MAX_VERSION: lo si scrive a mano perche' il modello non lo costruisce.
      val json = String(bytes.copyOfRange(Envelope.HEADER_BYTES, Envelope.HEADER_BYTES + ByteBuffer.wrap(bytes, 97, 4).int))
      json.replace("\"version\":2", "\"version\":${TrustLimits.MAX_VERSION + 1}")
    }
    val raw = dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson.parse(absurd.toByteArray()) as dev.pampa.mycloudgallery.core.trust.codec.JsonObject
    val bytes = Envelope.seal(DocType.FAMILY_REGISTRY, raw, family.familyKey)

    assertThat(RegistryVerifier.accept(bytes, family.state, family.anchor()).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(NasLayout.parseVersionName((TrustLimits.MAX_VERSION + 1).toString().padStart(20, '0'))).isNull()
  }
}
