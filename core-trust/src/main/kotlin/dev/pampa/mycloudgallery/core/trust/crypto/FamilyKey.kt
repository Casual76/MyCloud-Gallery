package dev.pampa.mycloudgallery.core.trust.crypto

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.violation
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * La chiave di famiglia: la radice di fiducia (`docs/formato-nas.md` §4.1). Firma soltanto il registro
 * di famiglia.
 *
 * A differenza delle chiavi dei dispositivi e' ESTRAIBILE per costruzione: deve poter stare nel kit di
 * recupero ([RecoveryKit]) e su un secondo dispositivo admin. A riposo sul dispositivo admin e' cifrata da
 * una chiave AES non estraibile del Keystore che si usa solo dopo l'impronta o il PIN del telefono, a ogni
 * uso ([AndroidKeystoreFamilyKeyVault], revisione 1.4 M3, decisione 2(a)); in chiaro esiste solo in memoria,
 * durante un'operazione dell'admin.
 */
class FamilyKey private constructor(
  private val privateKey: PrivateKey,
  override val publicKey: EcPublicKey,
) : Signer {

  override fun sign(message: ByteArray): ByteArray =
    EcdsaDer.normalize(
      Signature.getInstance(TrustAlgorithms.SIGNATURE).run {
        initSign(privateKey)
        update(message)
        sign()
      },
    )

  /** PKCS#8 della chiave privata: SOLO per cifrarla subito (kit, cassaforte). Va azzerato dal chiamante. */
  internal fun exportPkcs8(): ByteArray = privateKey.encoded ?: error("Chiave privata non esportabile.")

  override fun toString(): String = "FamilyKey(${publicKey.fingerprint})"

  companion object {
    fun generate(random: SecureRandom = SecureRandom()): FamilyKey {
      val generator = KeyPairGenerator.getInstance(TrustAlgorithms.KEY_ALGORITHM)
      generator.initialize(ECGenParameterSpec(TrustAlgorithms.CURVE), random)
      val pair = generator.generateKeyPair()
      return FamilyKey(pair.private, EcPublicKey.fromJca(pair.public))
    }

    /**
     * Ricostruisce la chiave da PKCS#8 + chiave pubblica attesa, e controlla che le due metà stiano
     * insieme firmando e verificando un messaggio di prova.
     */
    internal fun restore(pkcs8: ByteArray, publicKey: EcPublicKey): FamilyKey {
      val privateKey = try {
        KeyFactory.getInstance(TrustAlgorithms.KEY_ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(pkcs8))
      } catch (e: java.security.GeneralSecurityException) {
        violation(RejectReason.MALFORMED, "chiave di famiglia illeggibile")
      }
      val key = FamilyKey(privateKey, publicKey)
      val probe = "mycloudgallery/family-key-probe".toByteArray()
      if (!publicKey.verify(probe, key.sign(probe))) violation(RejectReason.HASH_MISMATCH, "chiave di famiglia incoerente")
      return key
    }
  }
}
