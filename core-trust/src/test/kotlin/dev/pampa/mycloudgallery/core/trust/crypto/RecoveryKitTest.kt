package dev.pampa.mycloudgallery.core.trust.crypto

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.CommitResult
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.RegistrySync
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.NAS_NOON
import dev.pampa.mycloudgallery.core.trust.testing.SoftwareKeyProvider
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import org.junit.Test

class RecoveryKitTest {

  private val familyKey = FamilyKey.generate()
  private val familyId = TrustIds.random()
  private val code = RecoveryCode.generate()
  private val kit = RecoveryKit.seal(familyKey, familyId, code, NAS_NOON)
  private val fingerprint = familyKey.publicKey.fingerprint

  @Test
  fun kitAndataERitorno_laChiaveRecuperataFirmaComeLOriginale() {
    val opened = RecoveryKit.open(kit, code, familyId, fingerprint).acceptedOrNull()!!

    assertThat(opened.familyId).isEqualTo(familyId)
    assertThat(opened.createdAt).isEqualTo(NAS_NOON)
    assertThat(opened.familyKey.publicKey).isEqualTo(familyKey.publicKey)
    val message = "registro".toByteArray()
    assertThat(familyKey.publicKey.verify(message, opened.familyKey.sign(message))).isTrue()
  }

  @Test
  fun recuperoDellAdmin_daZero_conLAncoraDelFoglioEDelQrDiStato() {
    val family = TestFamily()
    val tablet = family.approveNewDevice()
    val kitOfFamily = RecoveryKit.seal(family.familyKey, family.familyId, code, family.clock.now)
    family.common.publishExclusive(NasLayout.recoveryKitFile(family.familyId, family.familyKey.publicKey.fingerprint), kitOfFamily)
    // Il QR "stato della famiglia" mostrato dal tablet: id, impronta, versione corrente.
    val stateQr = family.state.toAnchor()!!

    // Il telefono dell'admin e' perso. Il dispositivo nuovo NON ha lo stato del vecchio: solo il foglio
    // (codice + impronta) e il QR di stato. Apre il kit, si sincronizza dal NAS, si approva da se'.
    val sheetFingerprint = family.familyKey.publicKey.fingerprint
    val blob = family.common.bytesAt(NasLayout.recoveryKitFile(family.familyId, sheetFingerprint))!!
    val recovered = RecoveryKit.open(blob, RecoveryCode.parse(code.toDisplayString())!!, family.familyId, sheetFingerprint).acceptedOrNull()!!
    val synced = RegistrySync(family.common).refresh(current = null, anchor = stateQr).state!!
    val newAdmin = SoftwareKeyProvider().deviceSigner()
    val authority = FamilyAuthority(recovered.familyKey, family.clock)
    val request = family.verified(family.request(newAdmin, "Telefono nuovo di Admin", "admin"))
    val result = authority.commit(family.common, authority.approve(synced, request, ApprovalProof.ScannedKey(newAdmin.publicKey), family.clock.now, admin = true))

    assertThat(result).isInstanceOf(CommitResult.Published::class.java)
    assertThat((result as CommitResult.Published).state.canWrite(tablet.deviceId)).isTrue()
  }

  @Test
  fun codiceSbagliatoOKitAlteratoRifiutati() {
    assertThat(RecoveryKit.open(kit, RecoveryCode.generate(), familyId, fingerprint).rejectionOrNull()).isEqualTo(RejectReason.BAD_SIGNATURE)

    for (index in listOf(5, 30, 60, 70, 80, kit.size - 1)) {
      val tampered = kit.copyOf().also { it[index] = (it[index].toInt() xor 0x40).toByte() }
      assertThat(RecoveryKit.open(tampered, code, familyId, fingerprint).rejectionOrNull()).isIn(listOf(RejectReason.BAD_SIGNATURE, RejectReason.WRONG_FAMILY, RejectReason.SIGNER_NOT_APPROVED))
    }
  }

  @Test
  fun kitMalformatoOAltraFamiglia() {
    assertThat(RecoveryKit.open(ByteArray(0), code, familyId, fingerprint).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
    assertThat(RecoveryKit.open(ByteArray(5000), code, familyId, fingerprint).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
    assertThat(RecoveryKit.open(kit.copyOf().also { it[0] = 'X'.code.toByte() }, code, familyId, fingerprint).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
    assertThat(RecoveryKit.open(kit.copyOf().also { it[4] = 9 }, code, familyId, fingerprint).rejectionOrNull()).isEqualTo(RejectReason.NEWER_FORMAT)
    assertThat(RecoveryKit.open(kit, code, TrustIds.random(), fingerprint).rejectionOrNull()).isEqualTo(RejectReason.WRONG_FAMILY)
  }

  @Test
  fun codiceDiRecupero_altaEntropiaFormatoStampabileErroriDiBattituraScoperti() {
    val display = code.toDisplayString()

    assertThat(display).matches("([0-9A-Z]{4}-){6}[0-9A-Z]{4}")
    assertThat(code.toString()).doesNotContain(display.take(4))
    assertThat(RecoveryCode.parse(display.lowercase().replace("-", " "))).isNotNull()

    // Errori di battitura: su 1000 codici (generatore con seme, test deterministico), con un carattere
    // sbagliato in una posizione qualunque, il controllo ne lascia passare circa 1 su 1000.
    val seeded = java.security.SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
    var typos = 0
    var undetected = 0
    repeat(1000) { round ->
      val sample = RecoveryCode.generate(seeded).toDisplayString()
      val positions = sample.indices.filter { sample[it] != '-' }
      val position = positions[round % positions.size]
      val wrong = if (sample[position] == 'Q') 'R' else 'Q'
      val typo = sample.substring(0, position) + wrong + sample.substring(position + 1)
      typos++
      if (RecoveryCode.parse(typo) != null) undetected++
    }
    assertThat(typos).isEqualTo(1000)
    assertThat(undetected).isAtMost(5)
    assertThat(RecoveryCode.parse(display.dropLast(1))).isNull()
    assertThat(RecoveryCode.parse("")).isNull()

    // 128 bit casuali: codici diversi a ogni generazione.
    assertThat((1..50).map { RecoveryCode.generate().toDisplayString() }.toSet()).hasSize(50)
  }

  @Test
  fun aliasDiCrockfordAccettatiNelCodiceScrittoAMano() {
    val display = code.toDisplayString()
    val withAliases = display.replace('0', 'O').replace('1', 'I')

    assertThat(RecoveryCode.parse(withAliases)?.toDisplayString()).isEqualTo(display)
  }

  @Test
  fun chiaveDiFamigliaRestituitaSoloSeCoerente() {
    val other = FamilyKey.generate()
    val pkcs8 = familyKey.exportPkcs8()

    assertThat(runCatching { FamilyKey.restore(pkcs8, other.publicKey) }.isFailure).isTrue()
    assertThat(FamilyKey.restore(pkcs8, familyKey.publicKey).publicKey).isEqualTo(familyKey.publicKey)
  }

  @Test
  fun hkdfVettoreRfc5869() {
    // RFC 5869, caso di prova 1.
    val ikm = ByteArray(22) { 0x0b }
    val salt = ByteArray(13) { it.toByte() }
    val info = ByteArray(10) { (0xf0 + it).toByte() }
    val okm = Hkdf.sha256(ikm, salt, info, 42)

    assertThat(okm.joinToString("") { "%02x".format(it) })
      .isEqualTo("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
  }

  @Test
  fun chiaveDelDispositivoSoftwareDiProva() {
    val provider = SoftwareKeyProvider()
    assertThat(provider.hasDeviceKey()).isFalse()
    val signer = provider.deviceSigner()
    assertThat(provider.deviceSigner()).isSameInstanceAs(signer)
    provider.destroyDeviceKey()
    assertThat(provider.hasDeviceKey()).isFalse()
  }
}
