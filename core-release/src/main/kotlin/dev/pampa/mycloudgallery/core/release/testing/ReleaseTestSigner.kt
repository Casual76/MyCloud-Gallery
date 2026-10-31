package dev.pampa.mycloudgallery.core.release.testing

import dev.pampa.mycloudgallery.core.release.codec.EcdsaP256
import dev.pampa.mycloudgallery.core.release.codec.ManifestCodec
import dev.pampa.mycloudgallery.core.release.contract.PinnedKey
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.keys.ReleaseTrust
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Chi firma manifest **veri** nelle prove (P1, §5.2): una coppia P-256 **generata al momento** per ogni `keyId` chiesto (nessuna chiave nel
 * repository, nessuna chiave vera), la busta `MCGR1` del codec di produzione e la firma normalizzata. [trust] da' l'insieme fidato da passare a
 * `SignedManifestVerifier` o alla fabbrica, cosi' le prove di "K1" e "K2" usano chiavi del test negli stessi posti delle vere.
 */
class ReleaseTestSigner : ReleaseSealer {
  private val pairs = HashMap<Int, KeyPair>()

  @Synchronized
  fun pair(keyId: Int): KeyPair = pairs.getOrPut(keyId) {
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  }

  /** La chiave pubblica di [keyId] come chiave fissata. */
  fun pinned(keyId: Int): PinnedKey {
    val spki = pair(keyId).public.encoded
    return PinnedKey(keyId, spki, EcdsaP256.sha256Hex(spki))
  }

  /** L'insieme fidato con le chiavi del test di [keyIds]. */
  fun trust(vararg keyIds: Int): ReleaseTrust = ReleaseTestKeys.trustOf(keyIds.map(::pinned))

  /** Le chiavi del test negli id di K1 e K2. */
  fun trust(): ReleaseTrust = trust(ReleaseKeyIds.K1, ReleaseKeyIds.K2)

  override fun seal(manifest: ReleaseManifest, keyId: Int): ByteArray = sealJson(ManifestCodec.canonical(manifest), keyId)

  /** Una busta firmata intorno a un JSON qualsiasi (per le prove dei campi `critical`, dello schema, delle forme non canoniche). */
  fun sealJson(json: ByteArray, keyId: Int = ReleaseKeyIds.K1): ByteArray = sealWith(pair(keyId).private, json, keyId)

  /** La stessa busta con un byte del JSON cambiato: la forma resta, la firma non torna (`SIGNATURE`). */
  override fun tamper(sealed: ByteArray): ByteArray {
    val copy = sealed.copyOf()
    val at = ManifestCodec.HEADER_BYTES + 1
    copy[at] = (copy[at].toInt() xor 0x01).toByte()
    return copy
  }

  /** La stessa busta con la **seconda** firma valida `(r, n - s)`, che si ottiene senza chiave: deve essere rifiutata (forma canonica). */
  fun highS(sealed: ByteArray): ByteArray {
    val length = ((sealed[5].toInt() and 0xff) shl 24) or ((sealed[6].toInt() and 0xff) shl 16) or
      ((sealed[7].toInt() and 0xff) shl 8) or (sealed[8].toInt() and 0xff)
    val signedEnd = ManifestCodec.HEADER_BYTES + length + 1
    val (r, s) = requireNotNull(EcdsaP256.parse(sealed.copyOfRange(signedEnd, sealed.size)))
    return sealed.copyOfRange(0, signedEnd) + EcdsaP256.encode(r, EcdsaP256.N.subtract(s))
  }

  companion object {
    /** Firma [json] con [key] come `keyId` [keyId]: busta `MCGR1`, SHA256withECDSA, firma in forma canonica. */
    fun sealWith(key: PrivateKey, json: ByteArray, keyId: Int): ByteArray {
      val unsigned = ManifestCodec.unsigned(json, keyId)
      val signature = Signature.getInstance(EcdsaP256.SIGNATURE).run {
        initSign(key)
        update(unsigned)
        sign()
      }
      return ManifestCodec.seal(unsigned, EcdsaP256.normalize(signature))
    }
  }
}
