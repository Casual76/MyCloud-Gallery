package dev.pampa.mycloudgallery.core.release.codec

import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestVerifier
import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import dev.pampa.mycloudgallery.core.release.contract.Verified
import dev.pampa.mycloudgallery.core.release.keys.ReleaseTrust
import java.security.PublicKey

/**
 * Il verificatore vero del manifest (D29, C440; P1): la busta `MCGR1` con le chiavi di [trust] (K1 e K2; in debug anche KT). In ordine: tetto,
 * forma della busta, chiave conosciuta, firma ECDSA P-256 in forma canonica sui byte prima di lei, e **solo dopo** il JSON (schema, campi
 * `critical`, app, campi e tetti). Tutto cio' che non e' intatto e firmato da una chiave di [trust] e' un `Rejected` con il motivo; non lancia
 * mai (un errore inatteso vale `MALFORMED`).
 */
class SignedManifestVerifier(trust: ReleaseTrust) : ManifestVerifier {
  private val keys: Map<Int, PublicKey> = trust.keys.associate { it.id to EcdsaP256.publicKey(it.spki) }

  /** Gli id fidati (per i test e la Diagnostica). */
  val keyIds: Set<Int> = keys.keys.toSortedSet()

  override fun verify(bytes: ByteArray, app: String): Verified {
    if (bytes.size > ManifestLimits.MAX_BYTES) return Verified.Rejected(RejectReason.TOO_LARGE)
    return try {
      val opened = ManifestCodec.open(bytes)
      val key = keys[opened.keyId] ?: return Verified.Rejected(RejectReason.UNKNOWN_KEY)
      if (!EcdsaP256.verify(key, opened.signed, opened.signature)) return Verified.Rejected(RejectReason.SIGNATURE)
      Verified.Ok(ManifestCodec.decode(opened.json, app), opened.keyId)
    } catch (e: CodecException) {
      Verified.Rejected(e.reason)
    } catch (e: RuntimeException) {
      Verified.Rejected(RejectReason.MALFORMED)
    }
  }

  override fun toString(): String = "SignedManifestVerifier$keyIds"
}
