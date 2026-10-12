package dev.pampa.mycloudgallery.core.trust.registry

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.FakeNasFileSystem
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import dev.pampa.mycloudgallery.core.trust.testing.newSigner
import org.junit.Assert.assertThrows
import org.junit.Test

class RegistryTest {

  private val family = TestFamily()

  @Test
  fun famigliaNuova_ilFondatoreEAdminApprovato() {
    val registry = family.state.registry

    assertThat(registry.version).isEqualTo(1)
    assertThat(registry.devices).hasSize(1)
    assertThat(registry.devices[0].admin).isTrue()
    assertThat(family.state.canWrite(family.adminDevice.deviceId)).isTrue()
  }

  @Test
  fun unDispositivoNuovoAccettaIlRegistroDelQrELeVersioniSuccessive() {
    val anchor = family.anchor()
    val tablet = family.approveNewDevice()

    val sync = RegistrySync(family.common).refresh(current = null, anchor = anchor)

    assertThat(sync.state!!.registry.version).isEqualTo(2)
    assertThat(sync.state!!.canWrite(tablet.deviceId)).isTrue()
    assertThat(sync.newerUnverified).isFalse()
  }

  @Test
  fun registroNonFirmatoOSpazzaturaRifiutato() {
    val garbage = "{\"family\":\"x\"}".toByteArray()

    assertThat(RegistryVerifier.accept(garbage, family.state, family.anchor()).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun registroFirmatoDaUnDispositivoInvecheDallaChiaveDiFamigliaRifiutato() {
    val forged = Envelope.seal(DocType.FAMILY_REGISTRY, family.state.registry.copy(version = 99).toJson(), family.adminDevice)

    assertThat(RegistryVerifier.accept(forged, family.state, family.anchor()).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_NOT_APPROVED)
  }

  @Test
  fun registroDiUnAltraFamigliaRifiutato() {
    val other = TestFamily()
    // Stessa chiave di famiglia attesa? No: l'altra famiglia ha un'altra chiave.
    assertThat(RegistryVerifier.accept(other.history[0].bytes, null, family.anchor()).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_NOT_APPROVED)

    // Anche firmato con la NOSTRA chiave ma con un altro id di famiglia: rifiutato.
    val wrongFamily = Envelope.seal(DocType.FAMILY_REGISTRY, family.state.registry.copy(familyId = other.familyId, version = 5).toJson(), family.familyKey)
    assertThat(RegistryVerifier.accept(wrongFamily, family.state, family.anchor()).rejectionOrNull()).isEqualTo(RejectReason.WRONG_FAMILY)
  }

  @Test
  fun rollback_unRegistroPiuVecchioNonSostituisceQuelloVisto() {
    val tablet = family.approveNewDevice()
    family.changeStatus(tablet, DeviceStatus.REVOKED)
    val revokedState = family.state
    val beforeRevocation = family.history[1].bytes // versione 2: tablet ancora approvato

    val verdict = RegistryVerifier.accept(beforeRevocation, revokedState, family.anchor())

    assertThat(verdict.rejectionOrNull()).isEqualTo(RejectReason.ROLLBACK)
  }

  @Test
  fun rollback_unDispositivoNuovoNonScendeSottoLaVersioneDelQr() {
    val tablet = family.approveNewDevice()
    family.changeStatus(tablet, DeviceStatus.REVOKED)
    val anchorAfterRevocation = family.anchor() // versione 3

    // Chi ha accesso SMB cancella le versioni nuove e lascia la 2.
    family.common.attackerDelete(NasLayout.registryFile(3))
    val sync = RegistrySync(family.common).refresh(current = null, anchor = anchorAfterRevocation)

    assertThat(sync.state).isNull()
    assertThat(RegistryVerifier.accept(family.history[1].bytes, null, anchorAfterRevocation).rejectionOrNull()).isEqualTo(RejectReason.ROLLBACK)
  }

  @Test
  fun rollbackSulNas_loStatoLocaleResta() {
    val tablet = family.approveNewDevice()
    family.changeStatus(tablet, DeviceStatus.REVOKED)
    val seen = family.state

    family.common.attackerDelete(NasLayout.registryFile(3))
    val sync = RegistrySync(family.common).refresh(current = seen, anchor = family.anchor())

    assertThat(sync.state).isSameInstanceAs(seen)
    assertThat(sync.state!!.statusOf(tablet.deviceId)).isEqualTo(DeviceStatus.REVOKED)
  }

  @Test
  fun equivocazione_dueContenutiConLaStessaVersione() {
    val fork = Envelope.seal(DocType.FAMILY_REGISTRY, family.state.registry.copy(issuedAt = family.state.registry.issuedAt + 1).toJson(), family.familyKey)

    assertThat(RegistryVerifier.accept(fork, family.state, family.anchor()).rejectionOrNull()).isEqualTo(RejectReason.EQUIVOCATION)
    assertThat(RegistryVerifier.accept(family.history[0].bytes, family.state, family.anchor()).acceptedOrNull()).isSameInstanceAs(family.state)
  }

  @Test
  fun ilNomeDelFileDeveCoincidereConLaVersione() {
    val version2 = FamilyAuthority(family.familyKey, family.clock).updateConfig(family.state, family.config.copy(nasHost = "192.168.178.20"))
    family.common.attackerWrite(NasLayout.registryFile(7).value, version2.bytes)

    val sync = RegistrySync(family.common).refresh(current = family.state, anchor = family.anchor())

    assertThat(sync.changed).isFalse()
    assertThat(sync.rejected.count(RejectReason.WRONG_LOCATION)).isEqualTo(1)
  }

  @Test
  fun spazzaturaNellaCartellaDelRegistro_ignorataESegnalata() {
    family.approveNewDevice()
    family.common.attackerWrite(NasLayout.registryFile(50).value, ByteArray(10_000_000))
    family.common.attackerWrite(NasLayout.registryFile(51).value, "ciao".toByteArray())
    family.common.attackerWrite(NasLayout.REGISTRY_DIR.value + "/../../evil", ByteArray(1))
    family.common.attackerWrite(NasLayout.REGISTRY_DIR.value + "/CON", ByteArray(1))

    val sync = RegistrySync(family.common).refresh(current = null, anchor = family.manualAnchor())

    assertThat(sync.state!!.registry.version).isEqualTo(2)
    assertThat(sync.newerUnverified).isTrue()
    assertThat(sync.rejected.count(RejectReason.TOO_LARGE)).isEqualTo(1)
    assertThat(sync.rejected.count(RejectReason.MALFORMED)).isEqualTo(1)
  }

  @Test
  fun rotazioneDellaChiave_ilVecchioSmetteDiValere() {
    val freshAnchor = family.anchor() // QR stampato prima della rotazione
    val oldKey = family.familyKey
    family.rotate() // versione 2 firmata dalla vecchia chiave con il successore
    family.approveNewDevice() // versione 3 firmata dalla nuova

    // Un dispositivo che aveva visto la versione 1 risale la catena.
    val sync = RegistrySync(family.common).refresh(current = null, anchor = freshAnchor)
    assertThat(sync.state!!.registry.version).isEqualTo(3)
    assertThat(sync.state!!.trustedFamilyKey).isEqualTo(family.familyKey.publicKey)

    // La vecchia chiave non firma piu' nulla che venga accettato.
    val byOldKey = Envelope.seal(DocType.FAMILY_REGISTRY, sync.state!!.registry.copy(version = 4).toJson(), oldKey)
    assertThat(RegistryVerifier.accept(byOldKey, sync.state, freshAnchor).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_NOT_APPROVED)

    // E l'admin con la vecchia chiave non riesce nemmeno a produrla.
    assertThrows(IllegalStateException::class.java) { FamilyAuthority(oldKey, family.clock).updateConfig(sync.state!!, family.config) }
  }

  @Test
  fun rotazione_saltareLAnnuncioNonBasta() {
    val freshAnchor = family.anchor()
    family.rotate()
    family.approveNewDevice()
    // Chi ha accesso SMB cancella l'annuncio: le versioni nuove non sono verificabili, si resta alla 1.
    family.common.attackerDelete(NasLayout.registryFile(2))

    val sync = RegistrySync(family.common).refresh(current = null, anchor = freshAnchor)

    assertThat(sync.state!!.registry.version).isEqualTo(1)
    assertThat(sync.newerUnverified).isTrue()
  }

  @Test
  fun dueAdminSullaStessaVersione_vinceIlPrimo() {
    val second = FamilyAuthority(family.familyKey, family.clock).approve(family.state, family.verified(family.request(newSigner(), "Telefono B", "babbo")))
    val third = FamilyAuthority(family.familyKey, family.clock).approve(family.state, family.verified(family.request(newSigner(), "Telefono C", "babbo")))

    assertThat(FamilyAuthority.publish(family.common, second)).isEqualTo(PublishResult.CREATED)
    assertThat(FamilyAuthority.publish(family.common, third)).isEqualTo(PublishResult.ALREADY_EXISTS)
  }

  @Test
  fun statiSoloInAvanti_niente_riapprovazione() {
    val tablet = family.approveNewDevice()
    family.changeStatus(tablet, DeviceStatus.RETIRED)
    family.changeStatus(tablet, DeviceStatus.LOST)

    assertThat(family.state.mustSelfWipe(tablet.deviceId)).isTrue()
    assertThat(family.state.canWrite(tablet.deviceId)).isFalse()
    for (next in DeviceStatus.entries) {
      assertThrows(IllegalArgumentException::class.java) { family.changeStatus(tablet, next) }
    }
    val again = family.verified(family.request(tablet, "Tablet ritrovato", "mamma"))
    assertThrows(IllegalArgumentException::class.java) { FamilyAuthority(family.familyKey, family.clock).approve(family.state, again) }
  }

  @Test
  fun voceDelRegistroIncoerenteRifiutata() {
    val tablet = family.approveNewDevice()
    val entries = family.state.registry.devices
    // Un registro firmato dalla chiave giusta ma con id che non corrisponde alla chiave: il parser lo rifiuta.
    val tampered = family.state.registry.toJson().let { json ->
      val devices = (json.members["devices"] as dev.pampa.mycloudgallery.core.trust.codec.JsonArray).items.map { item ->
        val obj = item as dev.pampa.mycloudgallery.core.trust.codec.JsonObject
        if ((obj.members["id"] as dev.pampa.mycloudgallery.core.trust.codec.JsonString).value == tablet.deviceId) {
          dev.pampa.mycloudgallery.core.trust.codec.JsonObject(obj.members + ("key" to entries.first { it.id != tablet.deviceId }.key.toText().json()))
        } else {
          obj
        }
      }
      dev.pampa.mycloudgallery.core.trust.codec.JsonObject(json.members + ("devices" to devices.json()) + ("version" to 9.json()))
    }
    val bytes = Envelope.seal(DocType.FAMILY_REGISTRY, tampered, family.familyKey)

    assertThat(RegistryVerifier.accept(bytes, family.state, family.anchor()).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun campoSconosciutoNellaStessaVersioneRifiutato() {
    val extra = dev.pampa.mycloudgallery.core.trust.codec.JsonObject(
      family.state.registry.copy(version = 2).toJson().members + ("nuovoCampo" to true.json()),
    )
    val bytes = Envelope.seal(DocType.FAMILY_REGISTRY, extra, family.familyKey)

    assertThat(RegistryVerifier.accept(bytes, family.state, family.anchor()).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun statoLocaleRicaricato() {
    family.approveNewDevice()
    val saved = family.state.signedBytes
    val fingerprint = family.state.signerKey.fingerprint

    val restored = RegistryVerifier.restore(saved, fingerprint)
    val swapped = RegistryVerifier.restore(TestFamily().state.signedBytes, fingerprint)

    assertThat(restored.acceptedOrNull()!!.registry).isEqualTo(family.state.registry)
    assertThat(swapped.rejectionOrNull()).isEqualTo(RejectReason.SIGNER_NOT_APPROVED)
  }

  @Test
  fun ancoraDalQrTolleranteMaStretta() {
    val fp = family.familyKey.publicKey.fingerprint
    assertThat(TrustAnchor.create(family.familyId.uppercase(), fp.uppercase().chunked(4).joinToString("-"), 3)).isNotNull()
    assertThat(TrustAnchor.create(family.familyId, fp.dropLast(1), 3)).isNull()
    assertThat(TrustAnchor.create("non-un-id", fp, 3)).isNull()
    assertThat(TrustAnchor.create(family.familyId, fp, -1)).isNull()
  }

  @Test
  fun richiesteInAttesa_soloQuelleValideENonGiaApprovate() {
    val pending = newSigner()
    family.common.publishExclusive(NasLayout.requestFile(pending.deviceId), family.request(pending, "Telefono di Babbo", "babbo"))
    // Richiesta "iniettata" da chi ha SMB: nome rassicurante ma firmata da un'altra chiave.
    val impostor = newSigner()
    family.common.attackerWrite(NasLayout.requestFile(newSigner().deviceId).value, family.request(impostor, "Telefono di Babbo", "babbo"))
    // Richiesta di un'altra famiglia.
    val foreign = newSigner()
    family.common.publishExclusive(
      NasLayout.requestFile(foreign.deviceId),
      RegistrationRequest.sign(foreign, TestFamily().familyId, "Estraneo", "X", "estraneo", family.clock.nowMillis()),
    )
    family.common.attackerWrite(NasLayout.REQUESTS_DIR.value + "/nome-strano", ByteArray(3))

    val result = PendingRequests.read(family.common, family.state)

    assertThat(result.requests.map { it.deviceId }).containsExactly(pending.deviceId)
    assertThat(result.requests[0].approvalCode).matches("[0-9A-Z]{5}-[0-9A-Z]{5}")
    assertThat(result.rejected.count(RejectReason.WRONG_LOCATION)).isEqualTo(2) // impostore + nome strano
    assertThat(result.rejected.count(RejectReason.WRONG_FAMILY)).isEqualTo(1)
  }

  @Test
  fun richiestaConChiaveNelPayloadDiversaDaChiFirma() {
    val device = newSigner()
    val other = newSigner()
    val payload = jsonObject(
      "family" to family.familyId.json(),
      "device" to other.deviceId.json(),
      "key" to other.publicKey.toText().json(),
      "name" to "Finto".json(),
      "model" to "X".json(),
      "nasUser" to "babbo".json(),
      "requestedAt" to family.clock.nowMillis().json(),
    )
    val bytes = Envelope.seal(DocType.REGISTRATION_REQUEST, payload, device)

    assertThat(RegistrationRequest.verify(bytes, family.familyId, other.deviceId).rejectionOrNull()).isEqualTo(RejectReason.BAD_SIGNATURE)
  }

  @Test
  fun codiceDiApprovazioneStabileEDiversoPerChiave() {
    val a = newSigner()
    val request = family.verified(family.request(a, "A", "babbo"))

    assertThat(request.approvalCode).isEqualTo(ApprovalCodes.of(a.publicKey))
    assertThat(ApprovalCodes.of(newSigner().publicKey)).isNotEqualTo(request.approvalCode)
  }

  @Test
  fun registroDiUnAppPiuNuova_siChiedeLAggiornamentoESiTieneQuelloVisto() {
    val next = FamilyAuthority(family.familyKey, family.clock).updateConfig(family.state, family.config)
    val newerFormat = next.bytes.also { it[4] = 2 }
    family.common.attackerWrite(NasLayout.registryFile(2).value, newerFormat)

    val sync = RegistrySync(family.common).refresh(current = family.state, anchor = family.anchor())

    assertThat(sync.state).isSameInstanceAs(family.state)
    assertThat(sync.needsAppUpdate).isTrue()
    // E un admin con l'app vecchia non puo' comunque scrivere sopra quella versione.
    assertThat(FamilyAuthority.publish(family.common, next)).isEqualTo(PublishResult.ALREADY_EXISTS)
  }

  @Test
  fun famigliaVuotaOSenzaRegistro_nessunoStato() {
    val empty = FakeNasFileSystem()
    val sync = RegistrySync(empty).refresh(null, family.anchor())

    assertThat(sync.state).isNull()
    assertThat(sync.changed).isFalse()
  }

  @Test
  fun firmaDiUnaChiaveDiFamigliaNuovaSenzaAnnuncioRifiutata() {
    // Il "reset" fatto da un estraneo: chiave nuova, versione alta. Chi conosce la vecchia non lo accetta.
    val attackerKey = FamilyKey.generate()
    val bytes = Envelope.seal(DocType.FAMILY_REGISTRY, family.state.registry.copy(version = 100).toJson(), attackerKey)

    val verdict = RegistryVerifier.accept(bytes, family.state, family.anchor())

    assertThat(verdict).isEqualTo(Verdict.Rejected(RejectReason.SIGNER_NOT_APPROVED))
  }
}
